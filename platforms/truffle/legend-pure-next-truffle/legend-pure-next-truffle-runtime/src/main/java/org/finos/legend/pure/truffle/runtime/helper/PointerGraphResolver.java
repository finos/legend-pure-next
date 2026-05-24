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
     * Per-top-level-element callback fired during {@link #resolveAll}. The IDE
     * wires this to forward a structured progress event to the browser so
     * the F9 progress bar fills during the resolve (which is otherwise an
     * opaque multi-second blackout). Tests pass {@link #NOOP}.
     */
    @FunctionalInterface
    public interface ResolveProgress
    {
        ResolveProgress NOOP = (d, t, p) -> {};

        void tick(int done, int total, String path);
    }

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
     * @param progress    fires once per top-level element after its graph has
     *                    been walked. Pass {@link ResolveProgress#NOOP} when
     *                    no caller-side reporting is needed.
     */
    @TruffleBoundary
    public static void resolveAll(Map<String, Object> byPath, TruffleMetadataAccess resolver,
                                  ResolveProgress progress)
    {
        if (byPath == null || byPath.isEmpty()) return;
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        int total = byPath.size();
        int done = 0;
        // Track the slowest elements so we can dump a top-5 at the end. The
        // resolve is a black box from the outside — at 0.04ms/el (memory
        // baseline) the whole pass is invisible, but at 100ms+/el the user
        // is staring at a frozen screen and needs to know which element
        // is eating the budget. Cheap nanoTime() per top-level entry.
        long[] elapsedNs = new long[total];
        String[] paths = new String[total];
        for (Map.Entry<String, Object> e : byPath.entrySet())
        {
            long t0 = System.nanoTime();
            java.util.Deque<String> trail = new java.util.ArrayDeque<>();
            trail.push("<" + e.getKey() + ">");
            walk(e.getValue(), byPath, resolver, visited, trail);
            elapsedNs[done] = System.nanoTime() - t0;
            paths[done] = e.getKey();
            done++;
            progress.tick(done, total, e.getKey());
        }
        logSlowest(elapsedNs, paths);
    }

    private static void logSlowest(long[] elapsedNs, String[] paths)
    {
        // Stderr-only — diagnostic, not data the IDE needs. Pick the 5 most
        // expensive entries by walking the array once and threading a tiny
        // bubble of size 5; sorting the whole array would be O(n log n) for
        // a result we only inspect when something's wrong.
        int k = Math.min(5, elapsedNs.length);
        if (k == 0) return;
        long[] topNs = new long[k];
        String[] topPaths = new String[k];
        for (int i = 0; i < elapsedNs.length; i++)
        {
            long ns = elapsedNs[i];
            int j = k - 1;
            if (ns <= topNs[j]) continue;
            while (j > 0 && ns > topNs[j - 1])
            {
                topNs[j] = topNs[j - 1];
                topPaths[j] = topPaths[j - 1];
                j--;
            }
            topNs[j] = ns;
            topPaths[j] = paths[i];
        }
        long totalNs = 0;
        for (long ns : elapsedNs) totalNs += ns;
        if (totalNs / 1_000_000 < 200) return; // below 200ms total — not worth noisy logs
        System.err.println("[PointerGraphResolver] slowest top-" + k
                + " (total " + (totalNs / 1_000_000) + " ms across " + elapsedNs.length + " elements):");
        for (int i = 0; i < k && topNs[i] > 0; i++)
        {
            System.err.println("  " + (topNs[i] / 1_000_000) + " ms  " + topPaths[i]);
        }
    }

    private static void walk(Object value, Map<String, Object> byPath, TruffleMetadataAccess resolver,
                             IdentityHashMap<Object, Boolean> visited)
    {
        walk(value, byPath, resolver, visited, new java.util.ArrayDeque<>());
    }

    private static void walk(Object value, Map<String, Object> byPath, TruffleMetadataAccess resolver,
                             IdentityHashMap<Object, Boolean> visited, java.util.Deque<String> trail)
    {
        if (value == null) return;
        if (visited.put(value, Boolean.TRUE) != null) return;
        if (value instanceof PureDynamicObject pdo)
        {
            String pureType = PureObj.pureTypeOf(pdo);
            String[] names = pdo.classInfo.nameBySlot();
            for (int i = 0; i < names.length; i++)
            {
                if (names[i] == null) continue;
                Object slotVal = pdo.readSlot(i);
                if (slotVal == null) continue;
                trail.push(pureType + "." + names[i]);
                try
                {
                    Object resolved = resolveOrRecurse(slotVal, byPath, resolver, visited, trail);
                    if (resolved != slotVal)
                    {
                        // Direct slot write — bypass writeProperty's coercion,
                        // since pointer→live is structurally compatible
                        // (pointer extends target class).
                        pdo.writeSlot(pdo.classInfo.slotIndex(names[i]), resolved);
                    }
                }
                finally
                {
                    trail.pop();
                }
            }
        }
        else if (value instanceof ObjectSequence seq)
        {
            for (int i = 0; i < seq.size(); i++)
            {
                trail.push("[" + i + "]");
                try
                {
                    walk(seq.getBoxed(i), byPath, resolver, visited, trail);
                }
                finally
                {
                    trail.pop();
                }
            }
        }
    }

    private static Object resolveOrRecurse(Object value, Map<String, Object> byPath,
                                           TruffleMetadataAccess resolver,
                                           IdentityHashMap<Object, Boolean> visited,
                                           java.util.Deque<String> trail)
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
                trail.push("[" + i + "]");
                Object r;
                try
                {
                    r = resolveOrRecurse(orig, byPath, resolver, visited, trail);
                }
                finally
                {
                    trail.pop();
                }
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
                walk(rebuilt, byPath, resolver, visited, trail);
                return rebuilt;
            }
            walk(seq, byPath, resolver, visited, trail);
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
                        // In-module duplicate guard. byPath is keyed by path
                        // and holds the winner for each path (compile-pure
                        // emits multiple revisions across passes; later
                        // entries win). Compile-pure's contract is that every
                        // cross-element reference goes through a
                        // {@code TempCompilerPointer} — the post-processor
                        // here swaps pointers for live elements. A non-pointer
                        // PDO whose path matches a byPath key but which isn't
                        // the winner means a producer skipped that pointer
                        // wrap and stored a direct PDO ref to a stale
                        // revision. Fail hard so the producing path surfaces.
                        Object winner = byPath.get(pdoPath);
                        if (winner != null && winner != pdo)
                        {
                            java.util.List<String> hops = new java.util.ArrayList<>(trail);
                            java.util.Collections.reverse(hops);
                            throw new IllegalStateException(
                                    "Duplicate PDO for path '" + pdoPath + "'"
                                            + " — winner in byPath identityHashCode="
                                            + System.identityHashCode(winner)
                                            + " (" + PureObj.pureTypeOf(winner) + "),"
                                            + " stale walked identityHashCode="
                                            + System.identityHashCode(pdo)
                                            + " (" + PureObj.pureTypeOf(pdo) + ")."
                                            + " Reached via: " + String.join(" / ", hops)
                                            + ". A producer stored a direct PDO ref instead"
                                            + " of wrapping the cross-element reference as a"
                                            + " TempCompilerPointer.");
                        }
                    }
                }
            }
            walk(pdo, byPath, resolver, visited, trail);
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
        // PackagePointer to the root Package carries {@code path=''} (per
        // {@code toPackagePointer}'s parentless-Package special-case). The
        // registry doesn't index the root at '' — try the alternate '::'
        // key as a fallback so root references resolve to the live core
        // Package. Mirrors {@code PathToElementNode}'s empty-path branch.
        if (live == null && resolver != null && path.isEmpty())
        {
            live = resolver.getElement("::");
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
