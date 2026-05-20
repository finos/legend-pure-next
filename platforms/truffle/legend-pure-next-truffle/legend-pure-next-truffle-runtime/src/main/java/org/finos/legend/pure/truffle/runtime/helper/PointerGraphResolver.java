// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime.helper;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry;
import org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Walks the {@code Map<String, PackageableElement>} (CompilerContext.elementMap)
 * produced by a Pure-on-Truffle compile and resolves every
 * {@code TempCompilerPointer} found in any element's graph to its live target.
 *
 * <p>Compile-pure intentionally embeds path-only pointers across element
 * boundaries (Class.package, Class.generalizations[].general.type, etc.) so
 * cross-element refs stay identity-stable across the compile's three passes.
 * Once the compile has finished, those pointers are dead weight — they have
 * to be resolved before any consumer (Truffle AST execution, PDB write,
 * subsequent compile) reads through them.</p>
 *
 * <p>The resolution flow:
 * <ol>
 *   <li>For each value in the map, walk the PDO/Sequence graph.</li>
 *   <li>For each pointer encountered, look up the live target — first in this
 *       map (in-module pointers), then in the cross-module resolver
 *       (references to elements in already-loaded modules like
 *       {@code core.pdb}).</li>
 *   <li>Mutate the parent slot in place to swap the pointer for the live
 *       element. The mutation is idempotent (pointer → live; subsequent
 *       visits see the live element and skip).</li>
 * </ol>
 *
 * <p>The walk visits each PDO at most once via an identity-keyed visited set,
 * so cycles (e.g. {@code Class.properties[i].owner == thatClass}) terminate
 * cleanly.</p>
 *
 * <p><strong>Why Java, not Pure?</strong> Per project decision, compile-pure
 * does not resolve its own pointers — that would couple the compiler's
 * internal representation to its consumers. The runtime, which is the
 * consumer, does the resolution at module-construction time.</p>
 */
public final class PointerGraphResolver
{
    private PointerGraphResolver() {}

    /**
     * Resolve every {@code TempCompilerPointer} in the values of
     * {@code elementMap} (or in the values' reachable graph) to its live
     * target. Mutates element PDOs in place.
     *
     * @param elementMap  the Pure-side {@code Map<String, PackageableElement>}
     *                    from {@code CompilationResult.context.elementMap}.
     *                    May be {@code null}, in which case this is a no-op.
     * @param resolver    the cross-module resolver, used to look up pointers
     *                    whose target lives in another module (e.g. references
     *                    to {@code core.pdb} elements like {@code Integer}).
     */
    @TruffleBoundary
    public static void resolveAll(Map<String, Object> byPath, TruffleMetadataAccess resolver)
    {
        if (byPath == null || byPath.isEmpty()) return;
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        for (Object element : byPath.values())
        {
            walk(element, byPath, resolver, visited);
        }
    }

    private static void walk(Object value, Map<String, Object> byPath, TruffleMetadataAccess resolver,
                             IdentityHashMap<Object, Boolean> visited)
    {
        if (value == null) return;
        if (visited.put(value, Boolean.TRUE) != null) return;
        if (value instanceof PureDynamicObject pdo)
        {
            // Sweep every slot; for each slot value, deref if pointer,
            // recurse otherwise.
            String[] names = pdo.classInfo.nameBySlot();
            for (int i = 0; i < names.length; i++)
            {
                if (names[i] == null) continue;
                Object slotVal = pdo.readSlot(i);
                if (slotVal == null) continue;
                Object resolved = resolveOrRecurse(slotVal, byPath, resolver, visited);
                if (resolved != slotVal)
                {
                    // Direct slot write — bypass writeProperty's coercion,
                    // since pointer→live is structurally compatible
                    // (pointer extends target class).
                    pdo.writeSlot(pdo.classInfo.slotIndex(names[i]), resolved);
                }
            }
        }
        else if (value instanceof ObjectSequence seq)
        {
            for (int i = 0; i < seq.size(); i++)
            {
                walk(seq.getBoxed(i), byPath, resolver, visited);
            }
        }
    }

    private static Object resolveOrRecurse(Object value, Map<String, Object> byPath,
                                           TruffleMetadataAccess resolver,
                                           IdentityHashMap<Object, Boolean> visited)
    {
        if (value == null) return null;
        String ptrPath = _PackageableElement.pointerPath(value);
        if (ptrPath != null)
        {
            Object live = resolvePointer(value, ptrPath, byPath, resolver);
            return live != null ? live : value;
        }
        if (value instanceof ObjectSequence seq)
        {
            int n = seq.size();
            Object[] resolved = null;
            for (int i = 0; i < n; i++)
            {
                Object orig = seq.getBoxed(i);
                Object r = resolveOrRecurse(orig, byPath, resolver, visited);
                if (r != orig)
                {
                    if (resolved == null)
                    {
                        resolved = new Object[n];
                        for (int j = 0; j < i; j++) resolved[j] = seq.getBoxed(j);
                    }
                    resolved[i] = r;
                }
                else if (resolved != null)
                {
                    resolved[i] = orig;
                }
            }
            if (resolved != null)
            {
                ObjectSequence rebuilt = new ObjectSequence(resolved);
                walk(rebuilt, byPath, resolver, visited);
                return rebuilt;
            }
            walk(seq, byPath, resolver, visited);
            return seq;
        }
        if (value instanceof PureDynamicObject pdo)
        {
            if (resolver != null)
            {
                // Fast-reject: if the resolver already indexes this exact
                // identity, it's a dep-PDB element. Don't walk (already
                // fully-resolved at PDB load, walking would drag the entire
                // metamodel through this pass) and don't canonicalize
                // (already canonical).
                if (resolver.pathOf(pdo) != null) return pdo;
                // Compile-local PDO: if it has a path the resolver knows
                // about (i.e. a duplicate of a dep element), swap to the
                // dep's canonical instance. Skipped when the PDO has no
                // name slot — nameless sub-PDOs (lambda bodies, value
                // specs, generic types) are by definition local and have
                // no canonical to swap to.
                Object nameObj = PureObj.readBySlot(pdo, SLOT_NAME);
                if (nameObj instanceof String s && !s.isEmpty())
                {
                    String pdoPath = _PackageableElement.path(pdo, resolver);
                    if (pdoPath != null && !pdoPath.isEmpty())
                    {
                        Object canonical = resolver.getElement(pdoPath);
                        if (canonical != null && canonical != pdo) return canonical;
                    }
                }
            }
            walk(pdo, byPath, resolver, visited);
            return pdo;
        }
        return value;
    }

    private static final int SLOT_NAME =
            org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");

    private static final int SLOT_ELEMENT = PureClassRegistry.globalSlot("element");

    /**
     * Resolve a {@code TempCompilerPointer} subtype to its live target.
     * Mirrors {@code FbsResolverHelper.resolvePointerRef} (the PDB-load
     * equivalent), but consults the in-memory {@code byPath} first so
     * in-module pointers resolve to the freshly-compiled element.
     *
     * <p>Two arms:
     * <ul>
     *   <li>PE-style (path only — e.g. {@code ClassPointer},
     *       {@code PackageableFunctionPointer}): look up {@code path} in
     *       {@code byPath} (in-module) then {@code resolver} (cross-module).</li>
     *   <li>Member-style ({@code path} + {@code element} — e.g.
     *       {@code PropertyPointer}, {@code QualifiedPropertyPointer},
     *       {@code StereotypePointer}, {@code TagPointer},
     *       {@code EnumPointer}): resolve {@code path} to the owner, then
     *       find the named member inside the owner's list slot
     *       (properties / qualifiedProperties / p_stereotypes / etc.).</li>
     * </ul>
     */
    private static Object resolvePointer(Object ptr, String path,
                                         Map<String, Object> byPath,
                                         TruffleMetadataAccess resolver)
    {
        Object elementObj = PureObj.readBySlot(ptr, SLOT_ELEMENT);
        if (elementObj instanceof String elementName && !elementName.isEmpty())
        {
            Object owner = byPath.get(path);
            if (owner == null && resolver != null) owner = resolver.getElement(path);
            if (owner == null) return null;
            String pointerType = PureObj.pureTypeOf(ptr);
            if (pointerType == null) return owner;
            switch (pointerType)
            {
                case "meta::pure::metamodel::pointer::PropertyPointer":
                {
                    Object hit = findByName(owner, "properties", elementName);
                    return hit != null ? hit : findByName(owner, "propertiesFromAssociations", elementName);
                }
                case "meta::pure::metamodel::pointer::QualifiedPropertyPointer":
                    return findByName(owner, "qualifiedProperties", elementName);
                case "meta::pure::metamodel::pointer::StereotypePointer":
                    return findByValue(owner, "p_stereotypes", elementName);
                case "meta::pure::metamodel::pointer::TagPointer":
                    return findByValue(owner, "p_tags", elementName);
                case "meta::pure::metamodel::pointer::EnumPointer":
                    return findByName(owner, "values", elementName);
                default:
                    return owner;
            }
        }
        Object live = byPath.get(path);
        if (live == null && resolver != null) live = resolver.getElement(path);
        return live;
    }

    private static Object findByName(Object owner, String listName, String memberName)
    {
        Object list = PureObj.read(owner, listName);
        if (list instanceof PureSequence seq)
        {
            for (int i = 0; i < seq.size(); i++)
            {
                Object item = seq.getBoxed(i);
                Object n = PureObj.read(item, "name");
                if (memberName.equals(n)) return item;
            }
        }
        return null;
    }

    private static Object findByValue(Object owner, String listName, String targetValue)
    {
        Object list = PureObj.read(owner, listName);
        if (list instanceof PureSequence seq)
        {
            for (int i = 0; i < seq.size(); i++)
            {
                Object item = seq.getBoxed(i);
                Object v = PureObj.read(item, "value");
                if (targetValue.equals(v)) return item;
            }
        }
        return null;
    }
}
