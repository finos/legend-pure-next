// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime.helper;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.finos.legend.pure.truffle.ast.natives.collection.MapImpl;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry;
import org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable counterpart to {@link PointerGraphResolver}. Walks the same
 * {@code Map<String, PackageableElement>} graph but, instead of mutating
 * element PDOs in place, produces a deep copy whose pointer slots are
 * rewritten to live targets. The input map and its element PDOs are
 * untouched.
 *
 * <p>Used by the {@code resolveAndReturnGraph} native to give compile-pure
 * a way to materialize a resolved graph without mutating the compilation
 * result.</p>
 *
 * <p>Copy rules per visited value:</p>
 * <ul>
 *   <li>{@code null} / primitive (String, Number, Boolean) → return as-is.</li>
 *   <li>{@code TempCompilerPointer} PDO → resolve to live target via byPath
 *       (in-module) then resolver (cross-module); if the target's path is in
 *       the input map, return the target's deep copy, else return the live
 *       external ref unchanged.</li>
 *   <li>Already-copied PDO (seen via {@code copies} IdentityHashMap) → return
 *       the stored copy. Breaks cycles and preserves identity-sharing.</li>
 *   <li>Resolver-indexed PDO (cross-module dep element) → return as-is. These
 *       are JVM-static / dep-PDB elements that must not be cloned (see
 *       enum_singleton_cgt_pollution).</li>
 *   <li>Non-pointer PDO whose canonical path matches a non-self entry in the
 *       input map → return the in-map winner's deep copy (mirrors the
 *       duplicate-PDO contract from PointerGraphResolver).</li>
 *   <li>Other non-pointer PDO → shallow-clone via
 *       {@link PureDynamicObject}'s ctor, register in {@code copies}, then
 *       recursively process every declared slot into the clone.</li>
 *   <li>{@link ObjectSequence} → new {@code ObjectSequence} whose elements are
 *       each processed.</li>
 *   <li>{@link MapImpl} → new {@code MapImpl} whose values are each processed
 *       (keys are usually primitives; processed defensively).</li>
 * </ul>
 *
 * <p>Pointer detection and member-style resolution mirror
 * {@link PointerGraphResolver#resolvePointer}; the small helpers are
 * duplicated here so each resolver evolves independently.</p>
 */
public final class PointerGraphImmutableResolver
{
    private PointerGraphImmutableResolver() {}

    private static final int SLOT_NAME = PureClassRegistry.globalSlot("name");
    private static final int SLOT_ELEMENT = PureClassRegistry.globalSlot("element");

    /**
     * Walk every value of {@code byPath} and return a list of deep-copied,
     * pointer-resolved roots in the same iteration order. The input map and
     * its PDOs are not modified.
     */
    @TruffleBoundary
    public static List<Object> resolveAllImmutable(Map<String, Object> byPath, TruffleMetadataAccess resolver)
    {
        List<Object> out = new ArrayList<>(byPath == null ? 0 : byPath.size());
        if (byPath == null || byPath.isEmpty()) return out;
        Context ctx = new Context(byPath, byPath.keySet(), resolver);
        for (Map.Entry<String, Object> e : byPath.entrySet())
        {
            out.add(process(e.getValue(), ctx));
        }
        return out;
    }

    private static final String PACKAGEABLE_ELEMENT_PATH = "meta::pure::metamodel::PackageableElement";
    private static final String PACKAGE_PATH = "meta::pure::metamodel::Package";

    private static final class Context
    {
        final Map<String, Object> byPath;
        final Set<String> inputPaths;
        final TruffleMetadataAccess resolver;
        /** Resolver's canonical {@code PackageableElement} type, looked up once
         *  per resolve so the per-PDO duplicate-guard test ({@link
         *  #isPackageableElement}) doesn't repeat the lookup. Null when the
         *  resolver doesn't yet know the m3 metamodel (early bootstrap); the
         *  guard then falls back to skipping. */
        final Object packageableElement;
        final IdentityHashMap<Object, Object> copies = new IdentityHashMap<>();

        Context(Map<String, Object> byPath, Set<String> inputPaths, TruffleMetadataAccess resolver)
        {
            this.byPath = byPath;
            this.inputPaths = inputPaths;
            this.resolver = resolver;
            this.packageableElement = resolver != null ? resolver.getElement(PACKAGEABLE_ELEMENT_PATH) : null;
        }
    }

    /**
     * Is {@code pdo}'s Pure class a subtype of {@code PackageableElement}?
     * Asks the type system directly via {@link _Type#subtypeOf} against the
     * resolver-canonical {@code PackageableElement} cached on {@code ctx}.
     * Falls back to {@code false} when either {@code pdo}'s classifier or
     * {@code PackageableElement} isn't (yet) in the resolver — the duplicate
     * guard skips, which is the safe direction (worst case: a stale PE copy
     * survives instead of being unified with the byPath winner; never:
     * swap a value-spec PDO for an unrelated element).
     */
    private static boolean isPackageableElement(PureDynamicObject pdo, Context ctx)
    {
        if (ctx.packageableElement == null || ctx.resolver == null) return false;
        String purePath = pdo.classInfo.purePath;
        if (purePath == null) return false;
        Object classifier = ctx.resolver.getElement(purePath);
        if (classifier == null) return false;
        return _Type.subtypeOf(classifier, ctx.packageableElement, ctx.resolver);
    }

    /**
     * Should {@code pdo} (a PE confirmed via {@link #isPackageableElement}) be
     * replaced with the dep resolver's canonical instance at the same path?
     * Yes when the resolver has a different PDO at this path AND {@code pdo}
     * isn't a {@code Package} (Packages collide and merge at runtime; canonical
     * swap would lose the inmem's contributed children).
     */
    private static boolean canonicalisableToDep(PureDynamicObject pdo, String pdoPath, Context ctx)
    {
        Object canonical = ctx.resolver.getElement(pdoPath);
        if (canonical == null || canonical == pdo) return false;
        return !PureObj.pureTypeIs(pdo, PACKAGE_PATH);
    }

    private static Object process(Object value, Context ctx)
    {
        if (value == null) return null;
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;

        String ptrPath = _PackageableElement.pointerPath(value);
        if (ptrPath != null)
        {
            Object live = resolvePointerToOriginal(value, ptrPath, ctx);
            if (live == null) return value;
            // Resolved target may itself be in the input map → return its
            // copy so the resolved graph is fully detached. External live
            // targets (dep-PDB elements) flow through process and short-
            // circuit to as-is below.
            return process(live, ctx);
        }

        if (value instanceof PureDynamicObject pdo)
        {
            Object existing = ctx.copies.get(pdo);
            if (existing != null) return existing;

            // Resolver-indexed → external live ref, share identity.
            if (ctx.resolver != null && ctx.resolver.pathOf(pdo) != null) return pdo;

            // In-module duplicate guard: a non-pointer PDO whose path matches
            // an input-map key, but isn't the byPath winner, means a
            // producer stored a stale revision instead of a pointer. The
            // winner's copy is the canonical resolved instance.
            //
            // Restrict to {@code PackageableElement} subtypes — only they
            // have a canonical {@code package::name} path to dedupe against.
            // Value-spec PDOs (notably {@code VariableExpression}) carry a
            // {@code name} but no {@code package}, so
            // {@code _PackageableElement.path} degenerates to the bare name
            // and collides with top-level package paths in {@code byPath}
            // (e.g. a lambda param named "x" vs an {@code import x::*}
            // package), incorrectly swapping the VariableExpression for the
            // Package. The subtype test goes through {@link _Type#subtypeOf}'s
            // O(1) ancestors-set cache, so the per-PDO cost is two CHM reads.
            if (!isPackageableElement(pdo, ctx)) return copyAndPopulate(pdo, ctx);

            Object nameObj = PureObj.readBySlot(pdo, SLOT_NAME);
            if (nameObj instanceof String s && !s.isEmpty() && ctx.resolver != null)
            {
                String pdoPath = _PackageableElement.path(pdo, ctx.resolver);
                if (pdoPath != null && !pdoPath.isEmpty())
                {
                    Object winner = ctx.byPath.get(pdoPath);
                    if (winner != null && winner != pdo) return process(winner, ctx);
                    // Canonicalise non-Package PEs to the dep PDB's instance when
                    // it owns the same path. The inmem flow filters compile-pure's
                    // output (test-stereotyped + Package PDOs only); any cross-PE
                    // reference inside a kept Package that points at a non-kept
                    // local PE (e.g. {@code cast.pure} re-emits the native
                    // {@code cast} alongside its test functions; the native isn't
                    // test-stereotyped so it's filtered out) must resolve to the
                    // dep's instance, not the local deep copy. Otherwise the
                    // local and the dep collide at runtime
                    // {@code foldChildrenInto} time.
                    //
                    // Packages are excluded: multiple modules legitimately
                    // contribute children to the same Package path and the
                    // runtime's {@code resolveAndMerge}/{@code foldChildrenInto}
                    // does that merge. Canonicalising a local Package to a dep
                    // Package would collapse the inmem's own contributed
                    // children into the dep instance and lose them.
                    if (canonicalisableToDep(pdo, pdoPath, ctx))
                    {
                        return ctx.resolver.getElement(pdoPath);
                    }
                }
            }

            return copyAndPopulate(pdo, ctx);
        }

        if (value instanceof ObjectSequence seq)
        {
            int n = seq.size();
            Object[] copied = new Object[n];
            for (int i = 0; i < n; i++)
            {
                copied[i] = process(seq.getBoxed(i), ctx);
            }
            return new ObjectSequence(copied);
        }

        if (value instanceof MapImpl m)
        {
            MapImpl out = new MapImpl();
            for (Map.Entry<Object, Object> e : m.getMap().entrySet())
            {
                out.put(process(e.getKey(), ctx), process(e.getValue(), ctx));
            }
            return out;
        }

        // Other PureSequence variants (LongSequence, DoubleSequence) hold
        // primitives only — safe to share.
        if (value instanceof PureSequence) return value;

        return value;
    }

    private static Object copyAndPopulate(PureDynamicObject src, Context ctx)
    {
        PureDynamicObject copy = new PureDynamicObject(src.classInfo, null, null, null);
        ctx.copies.put(src, copy);
        String[] names = src.classInfo.nameBySlot();
        for (int i = 0; i < names.length; i++)
        {
            if (names[i] == null) continue;
            Object slotVal = src.readSlot(i);
            if (slotVal == null) continue;
            Object processed = process(slotVal, ctx);
            copy.writeSlot(src.classInfo.slotIndex(names[i]), processed);
        }
        return copy;
    }

    /**
     * Resolve a {@code TempCompilerPointer} to its live target *original* —
     * not a copy. The caller passes the result back through {@link #process}
     * to obtain the copy (when the target is in the input map) or to share
     * the live ref (when it is external).
     *
     * <p>Mirrors {@link PointerGraphResolver}'s resolvePointer arms:
     * PE-style (path only) vs member-style (path + element).</p>
     */
    private static Object resolvePointerToOriginal(Object ptr, String path, Context ctx)
    {
        Object elementObj = PureObj.readBySlot(ptr, SLOT_ELEMENT);
        if (elementObj instanceof String elementName && !elementName.isEmpty())
        {
            Object owner = ctx.byPath.get(path);
            if (owner == null && ctx.resolver != null) owner = ctx.resolver.getElement(path);
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
        Object live = ctx.byPath.get(path);
        if (live == null && ctx.resolver != null) live = ctx.resolver.getElement(path);
        // PackagePointer to the root carries path='' — fallback to '::'
        // mirrors PointerGraphResolver and PathToElementNode.
        if (live == null && ctx.resolver != null && path.isEmpty())
        {
            live = ctx.resolver.getElement("::");
        }
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
