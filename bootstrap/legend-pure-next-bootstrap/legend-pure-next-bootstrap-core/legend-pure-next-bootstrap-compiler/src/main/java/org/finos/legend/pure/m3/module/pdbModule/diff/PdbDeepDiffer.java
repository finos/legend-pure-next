// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.m3.module.pdbModule.diff;

import meta.pure.metamodel.PackageableElement;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ElementIndex;
import org.finos.legend.pure.m3.module.pdbModule.fbs.FunctionIndex;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Structural diff for two PDB archives.
 *
 * <p>Loads both PDBs into {@link PDBModule}s (which lazily deserializes
 * each element on access), walks every shared element via reflection on
 * the generated {@code _xxxx()} accessors, and reports per-property
 * differences. Unlike {@link PdbDiffer}'s byte-level mode, this ignores
 * encoding drift (FlatBuffer field-write order, FBW vs Impl, PointerRef
 * vs AncestorRef) and only flags semantic differences:</p>
 * <ul>
 *   <li><b>Pointer references</b> (PackageableElement): compared by path.</li>
 *   <li><b>Sequences</b>: compared element-wise; size mismatch is itself
 *       a diff.</li>
 *   <li><b>Primitives / strings</b>: compared with {@code equals}.</li>
 *   <li><b>Nested objects</b> (e.g. {@code GenericType}, {@code Multiplicity}):
 *       recurse, with cycle detection via an identity-keyed visited set.</li>
 *   <li><b>Self-referential</b> values (FBW returns {@code this} from
 *       {@code _classifierGenericType}): treated as equal when both sides
 *       do the same.</li>
 * </ul>
 *
 * <p>Output is a per-property diff path like
 * {@code meta::pure::compiler::CompilerContext.properties[0].genericType}
 * with the two values printed compactly.</p>
 */
public final class PdbDeepDiffer
{
    /**
     * Maximum recursion depth before a property diff is reported as
     * {@code <too deep>}. Keeps pathological cycles from blowing the stack
     * even when the visited-set check fails for value-equal objects.
     */
    private static final int MAX_DEPTH = 120;

    /**
     * Accessors whose returned sequences are semantically order-insensitive —
     * elements are matched by {@code _name()} rather than positionally.
     * Adding to this set when a property's specification doesn't mandate
     * iteration order (e.g. binding maps surfaced as lists in the PDB).
     */
    private static final java.util.Set<String> ORDER_INSENSITIVE_ACCESSORS = java.util.Set.of(
            "_resolvedTypeParameters",
            "_resolvedMultiplicityParameters",
            "_typeParameters",
            "_multiplicityParameters",
            "_stereotypes",
            "_taggedValues",
            "_children",
            "_properties",
            "_qualifiedProperties"
    );

    private PdbDeepDiffer() {}

    public static Result diff(Path a, Path b, PrintStream out) throws Exception
    {
        return diff(a, b, null, out);
    }

    public static Result diff(Path a, Path b, Path corePdb, PrintStream out) throws Exception
    {
        // Construct each side as a PureModel so loader.extensions/resolver
        // wire via setPureModel. With corePdb passed in, both sides bundle
        // it as a dependency so PointerRefs into core (e.g. canonical UDPGT
        // anchors) actually resolve — without it, FBW returns null for any
        // cross-module reference, masking real diffs as "A: null".
        List<String> deps = corePdb != null ? List.of("core") : List.of();
        PDBModule modA = new PDBModule(a, PDBModule.Mode.EXECUTION, "a", "*", deps);
        PDBModule modB = new PDBModule(b, PDBModule.Mode.EXECUTION, "b", "*", deps);
        if (corePdb != null)
        {
            wireWithCore(modA, corePdb);
            wireWithCore(modB, corePdb);
        }
        else
        {
            wireModule(modA);
            wireModule(modB);
        }
        return doDiff(modA, modB, a, b, out);
    }

    private static void wireModule(PDBModule module)
    {
        MutableList<Module> modules = Lists.mutable.with(module);
        MutableList<LanguageExtension> extensions = Lists.mutable.with(new PureLanguageExtension());
        PureModel.withModules(modules).withExtensions(extensions).build().compile();
    }

    private static void wireWithCore(PDBModule diffModule, Path corePdb) throws java.io.IOException
    {
        PDBModule core = new PDBModule(corePdb, PDBModule.Mode.EXECUTION, "core", "*", List.of());
        MutableList<Module> modules = Lists.mutable.with(core, diffModule);
        MutableList<LanguageExtension> extensions = Lists.mutable.with(new PureLanguageExtension());
        PureModel.withModules(modules).withExtensions(extensions).build().compile();
    }

    private static Result doDiff(PDBModule modA, PDBModule modB,
                                 Path pathA, Path pathB,
                                 PrintStream out)
    {
        Set<String> pathsA = modA.elementPaths();
        Set<String> pathsB = modB.elementPaths();

        List<String> onlyInA = new ArrayList<>();
        List<String> onlyInB = new ArrayList<>();
        for (String p : pathsA) if (!pathsB.contains(p)) onlyInA.add(p);
        for (String p : pathsB) if (!pathsA.contains(p)) onlyInB.add(p);

        Set<String> shared = new TreeSet<>(pathsA);
        shared.retainAll(pathsB);

        List<PathDiff> diffs = new ArrayList<>();
        int identical = 0;

        for (String path : shared)
        {
            PackageableElement elA = (PackageableElement) modA.getElement(path);
            PackageableElement elB = (PackageableElement) modB.getElement(path);
            DiffWalker walker = new DiffWalker();
            walker.compareTopLevel(path, elA, elB);
            if (walker.diffs.isEmpty())
            {
                identical++;
            }
            else
            {
                diffs.addAll(walker.diffs);
            }
        }

        // Archive-level sections (e.g. functionIndex, elementIndex). Each PDB
        // can carry runtime-relevant indexes alongside the element entries; an
        // index present on one side and absent on the other is a real semantic
        // divergence (e.g. function-name resolution falls back to a linear
        // scan when the index is missing) — the per-element walk above can't
        // see it because it isn't a PackageableElement. For sections present
        // on both sides we compare bytes — the index payload is FlatBuffer
        // serialized but built from a deterministic walk of compiled elements,
        // so byte equality is a reasonable proxy for semantic equality and
        // any drift is worth flagging.
        Set<String> sectionsA = modA.archive().sectionNames();
        Set<String> sectionsB = modB.archive().sectionNames();
        List<String> sectionsOnlyInA = new ArrayList<>();
        List<String> sectionsOnlyInB = new ArrayList<>();
        List<SectionDiff> sectionDiffs = new ArrayList<>();
        for (String s : sectionsA) if (!sectionsB.contains(s)) sectionsOnlyInA.add(s);
        for (String s : sectionsB) if (!sectionsA.contains(s)) sectionsOnlyInB.add(s);
        for (String s : sectionsA)
        {
            if (sectionsB.contains(s))
            {
                byte[] bytesA = modA.archive().readSection(s);
                byte[] bytesB = modB.archive().readSection(s);
                if (!java.util.Arrays.equals(bytesA, bytesB))
                {
                    SectionDiff diff = compareSection(s, bytesA, bytesB);
                    // Drop ORDER and ENCODING diffs entirely: they describe
                    // byte-level divergence with no semantic difference (same
                    // entries, different traversal order or FlatBuffer layout)
                    // and just add noise to reports.
                    if (diff.kind != SectionDiff.Kind.ORDER && diff.kind != SectionDiff.Kind.ENCODING)
                    {
                        sectionDiffs.add(diff);
                    }
                }
            }
        }
        sectionsOnlyInA.sort(Comparator.naturalOrder());
        sectionsOnlyInB.sort(Comparator.naturalOrder());
        sectionDiffs.sort(Comparator.comparing(d -> d.name));

        onlyInA.sort(Comparator.naturalOrder());
        onlyInB.sort(Comparator.naturalOrder());
        diffs.sort(Comparator.comparing((PathDiff d) -> d.path));

        Result result = new Result(pathsA.size(), pathsB.size(), shared.size(),
                identical, onlyInA, onlyInB, diffs,
                sectionsOnlyInA, sectionsOnlyInB, sectionDiffs);
        writeReport(out, pathA, pathB, result);
        return result;
    }

    private static void writeReport(PrintStream out, Path a, Path b, Result r)
    {
        out.println("PDB deep diff");
        out.println("  A: " + a + " (" + r.aTotal + " elements)");
        out.println("  B: " + b + " (" + r.bTotal + " elements)");
        out.println();
        out.println("Summary:");
        out.println("  shared paths           : " + r.shared);
        out.println("  structurally identical : " + r.identical);
        out.println("  property diffs         : " + r.diffs.size());
        out.println("  only in A              : " + r.onlyInA.size());
        out.println("  only in B              : " + r.onlyInB.size());
        out.println("  archive sections only in A : " + r.sectionsOnlyInA.size());
        out.println("  archive sections only in B : " + r.sectionsOnlyInB.size());
        out.println("  archive section diffs       : " + r.sectionDiffs.size());
        out.println();
        if (!r.onlyInA.isEmpty())
        {
            out.println("Only in A (" + r.onlyInA.size() + "):");
            for (String p : r.onlyInA) out.println("  - " + p);
            out.println();
        }
        if (!r.onlyInB.isEmpty())
        {
            out.println("Only in B (" + r.onlyInB.size() + "):");
            for (String p : r.onlyInB) out.println("  + " + p);
            out.println();
        }
        if (!r.sectionsOnlyInA.isEmpty())
        {
            out.println("Archive sections only in A (" + r.sectionsOnlyInA.size() + "):");
            for (String s : r.sectionsOnlyInA) out.println("  - " + s);
            out.println();
        }
        if (!r.sectionsOnlyInB.isEmpty())
        {
            out.println("Archive sections only in B (" + r.sectionsOnlyInB.size() + "):");
            for (String s : r.sectionsOnlyInB) out.println("  + " + s);
            out.println();
        }
        if (!r.sectionDiffs.isEmpty())
        {
            out.println("Archive section diffs (" + r.sectionDiffs.size() + "):");
            for (SectionDiff d : r.sectionDiffs)
            {
                out.println("  ! " + d.name + " [" + d.kind + "]  " + d.summary);
                for (String line : d.details)
                {
                    out.println("      " + line);
                }
            }
            out.println();
        }
        if (!r.diffs.isEmpty())
        {
            out.println("Property diffs (" + r.diffs.size() + "):");
            for (PathDiff d : r.diffs)
            {
                out.println("  ~ " + d.path);
                out.println("      A: " + d.valueA);
                out.println("      B: " + d.valueB);
            }
        }
    }

    private static final class DiffWalker
    {
        final List<PathDiff> diffs = new ArrayList<>();
        // Identity-keyed sets to avoid infinite recursion when (a) and (b)
        // contain self-references (e.g. `_classifierGenericType` returns
        // `this`). Track each side independently because the same Java
        // identity could appear at different positions on each side.
        final IdentityHashMap<Object, Boolean> visitedA = new IdentityHashMap<>();
        final IdentityHashMap<Object, Boolean> visitedB = new IdentityHashMap<>();

        // Entry point for the outer per-element walk. Unlike {@link #compare},
        // this does NOT short-circuit when a/b are PackageableElements — we
        // *want* to descend into the element's own properties. The PE shortcut
        // exists to avoid double-walking cross-references (e.g. `_package`,
        // `_general`) which are themselves PEs and get diffed on their own
        // pass via the outer loop.
        void compareTopLevel(String path, Object a, Object b)
        {
            if (a == null && b == null) return;
            if (a == null || b == null)
            {
                diffs.add(new PathDiff(path, summarize(a), summarize(b)));
                return;
            }
            walkAccessors(path, a, b, 0);
        }

        void compare(String path, Object a, Object b, int depth)
        {
            if (depth > MAX_DEPTH)
            {
                diffs.add(new PathDiff(path, "<too deep>", "<too deep>"));
                return;
            }
            if (a == null && b == null) return;
            if (a == null || b == null)
            {
                diffs.add(new PathDiff(path, summarize(a), summarize(b)));
                return;
            }
            // Cycle detection: if we've already started walking this object
            // on either side at a shallower depth, treat the recursive arrival
            // as equal — the deeper walker would just hit the same fields.
            if (visitedA.containsKey(a) || visitedB.containsKey(b)) return;

            // Primitives / strings / numbers / booleans: direct equality.
            if (isLeaf(a) || isLeaf(b))
            {
                if (!java.util.Objects.equals(a, b))
                {
                    diffs.add(new PathDiff(path, summarize(a), summarize(b)));
                }
                return;
            }

            // PackageableElement: pointer-equal by path. Avoids descending
            // into cross-references that we'll diff on their own pass.
            if (a instanceof PackageableElement peA && b instanceof PackageableElement peB)
            {
                String pathA = _PackageableElement.path(peA);
                String pathB = _PackageableElement.path(peB);
                if (!java.util.Objects.equals(pathA, pathB))
                {
                    diffs.add(new PathDiff(path,
                            "PackageableElement(" + pathA + ")",
                            "PackageableElement(" + pathB + ")"));
                }
                return;
            }

            // Sequences (Eclipse Collections lists, java.util.List).
            if (a instanceof Iterable<?> ia && b instanceof Iterable<?> ib)
            {
                List<Object> la = toList(ia);
                List<Object> lb = toList(ib);
                if (la.size() != lb.size())
                {
                    diffs.add(new PathDiff(path + ".size",
                            String.valueOf(la.size()),
                            String.valueOf(lb.size())));
                    return;
                }
                for (int i = 0; i < la.size(); i++)
                {
                    compare(path + "[" + i + "]", la.get(i), lb.get(i), depth + 1);
                }
                return;
            }

            // Complex objects: walk all `_xxxx()` accessors common to both.
            walkAccessors(path, a, b, depth);
        }

        private void walkAccessors(String path, Object a, Object b, int depth)
        {
            visitedA.put(a, Boolean.TRUE);
            visitedB.put(b, Boolean.TRUE);
            try
            {
                List<Method> accessorsA = accessors(a);
                for (Method m : accessorsA)
                {
                    Object vA;
                    Object vB;
                    try
                    {
                        vA = m.invoke(a);
                    }
                    catch (Throwable t)
                    {
                        continue; // unreadable property — skip
                    }
                    Method mB;
                    try
                    {
                        mB = b.getClass().getMethod(m.getName());
                    }
                    catch (NoSuchMethodException e)
                    {
                        continue; // accessor missing on B — skip
                    }
                    try
                    {
                        vB = mB.invoke(b);
                    }
                    catch (Throwable t)
                    {
                        continue;
                    }
                    String propName = m.getName().startsWith("_")
                            ? m.getName().substring(1) : m.getName();
                    if (ORDER_INSENSITIVE_ACCESSORS.contains(m.getName())
                            && vA instanceof Iterable<?> ia && vB instanceof Iterable<?> ib)
                    {
                        compareByName(path + "." + propName, toList(ia), toList(ib), depth + 1);
                    }
                    else
                    {
                        compare(path + "." + propName, vA, vB, depth + 1);
                    }
                }
            }
            finally
            {
                visitedA.remove(a);
                visitedB.remove(b);
            }
        }

        /**
         * Compare two collections of named elements (resolvedTypeParameters,
         * stereotypes, etc.) by matching elements via their {@code _name()}
         * accessor rather than positionally. The collections may differ in
         * order without producing diffs — only missing names or per-element
         * value differences surface.
         */
        private void compareByName(String path, List<Object> la, List<Object> lb, int depth)
        {
            if (la.size() != lb.size())
            {
                diffs.add(new PathDiff(path + ".size",
                        String.valueOf(la.size()),
                        String.valueOf(lb.size())));
                return;
            }
            java.util.Map<String, Object> mapA = indexByName(la);
            java.util.Map<String, Object> mapB = indexByName(lb);
            // Fall back to positional comparison if any element lacks _name(),
            // or there are duplicates (unique-name invariant violated).
            if (mapA == null || mapB == null || mapA.size() != la.size() || mapB.size() != lb.size())
            {
                for (int i = 0; i < la.size(); i++)
                {
                    compare(path + "[" + i + "]", la.get(i), lb.get(i), depth + 1);
                }
                return;
            }
            // Iterate A's order, compare to B-by-name; report missing names.
            for (java.util.Map.Entry<String, Object> entry : mapA.entrySet())
            {
                Object bElem = mapB.get(entry.getKey());
                if (bElem == null)
                {
                    diffs.add(new PathDiff(path + "[name=" + entry.getKey() + "]",
                            summarize(entry.getValue()), "<missing>"));
                    continue;
                }
                compare(path + "[name=" + entry.getKey() + "]", entry.getValue(), bElem, depth + 1);
            }
            for (String nameB : mapB.keySet())
            {
                if (!mapA.containsKey(nameB))
                {
                    diffs.add(new PathDiff(path + "[name=" + nameB + "]",
                            "<missing>", summarize(mapB.get(nameB))));
                }
            }
        }

        private static java.util.Map<String, Object> indexByName(List<Object> list)
        {
            java.util.Map<String, Object> idx = new java.util.LinkedHashMap<>();
            for (Object o : list)
            {
                if (o == null)
                {
                    return null;
                }
                try
                {
                    Method nameMethod = o.getClass().getMethod("_name");
                    Object name = nameMethod.invoke(o);
                    if (!(name instanceof String s))
                    {
                        return null;
                    }
                    idx.put(s, o);
                }
                catch (Throwable t)
                {
                    return null;
                }
            }
            return idx;
        }

        private static List<Method> accessors(Object obj)
        {
            List<Method> result = new ArrayList<>();
            for (Class<?> iface : obj.getClass().getInterfaces())
            {
                if (!iface.getName().startsWith("meta.pure.")) continue;
                for (Method m : iface.getMethods())
                {
                    if (m.getParameterCount() != 0) continue;
                    if (!m.getName().startsWith("_")) continue;
                    if (m.getReturnType() == void.class) continue;
                    // Skip the FlatBufferWrapper-internal _fbParent helper
                    if ("_fbParent".equals(m.getName())) continue;
                    // Skip _copy: it's a deep-copy constructor, not a property.
                    // Each invocation returns a fresh instance with a new
                    // identity, so identity-keyed cycle detection can't catch
                    // the unbounded recursion through copy.copy.copy....
                    if ("_copy".equals(m.getName())) continue;
                    result.add(m);
                }
            }
            return result;
        }

        private static boolean isLeaf(Object o)
        {
            return o instanceof String || o instanceof Number || o instanceof Boolean
                    || o instanceof Character || (o != null && o.getClass().isEnum());
        }

        private static List<Object> toList(Iterable<?> it)
        {
            if (it instanceof List<?> l) return new ArrayList<>(l);
            if (it instanceof MutableList<?> ml) return new ArrayList<>(ml);
            List<Object> out = new ArrayList<>();
            for (Object o : it) out.add(o);
            return out;
        }

        private static String summarize(Object o)
        {
            if (o == null) return "null";
            if (o instanceof PackageableElement pe) return "PackageableElement(" + _PackageableElement.path(pe) + ")";
            if (isLeaf(o)) return String.valueOf(o);
            String cls = o.getClass().getSimpleName();
            return cls + "@" + Integer.toHexString(System.identityHashCode(o));
        }
    }

    public static final class Result
    {
        public final int aTotal;
        public final int bTotal;
        public final int shared;
        public final int identical;
        public final List<String> onlyInA;
        public final List<String> onlyInB;
        public final List<PathDiff> diffs;
        public final List<String> sectionsOnlyInA;
        public final List<String> sectionsOnlyInB;
        public final List<SectionDiff> sectionDiffs;

        Result(int aTotal, int bTotal, int shared, int identical,
               List<String> onlyInA, List<String> onlyInB, List<PathDiff> diffs,
               List<String> sectionsOnlyInA, List<String> sectionsOnlyInB,
               List<SectionDiff> sectionDiffs)
        {
            this.aTotal = aTotal;
            this.bTotal = bTotal;
            this.shared = shared;
            this.identical = identical;
            this.onlyInA = onlyInA;
            this.onlyInB = onlyInB;
            this.diffs = diffs;
            this.sectionsOnlyInA = sectionsOnlyInA;
            this.sectionsOnlyInB = sectionsOnlyInB;
            this.sectionDiffs = sectionDiffs;
        }

        public boolean isClean()
        {
            return onlyInA.isEmpty() && onlyInB.isEmpty() && diffs.isEmpty()
                    && sectionsOnlyInA.isEmpty() && sectionsOnlyInB.isEmpty()
                    && sectionDiffs.isEmpty();
        }
    }

    public record PathDiff(String path, String valueA, String valueB) {}

    /**
     * Result of comparing one archive section. {@code kind} categorizes the
     * divergence: ENCODING (semantic content equal, byte layout differs),
     * ORDER (content equal, ordering of entries differs), CONTENT (extra
     * or differing entries), or BYTE (unknown section, only byte-level
     * comparison available).
     */
    public record SectionDiff(String name, Kind kind, String summary, List<String> details)
    {
        public enum Kind { ENCODING, ORDER, CONTENT, BYTE }
    }

    // ========================================================================
    // Section comparators — semantic decoders for known archive sections.
    // ========================================================================

    private static SectionDiff compareSection(String name, byte[] bytesA, byte[] bytesB)
    {
        return switch (name)
        {
            case "functionIndex" -> compareFunctionIndex(bytesA, bytesB);
            case "elementIndex" -> compareElementIndex(bytesA, bytesB);
            default -> new SectionDiff(name, SectionDiff.Kind.BYTE,
                    "byte content differs (no semantic decoder)",
                    List.of("bytes A=" + (bytesA == null ? 0 : bytesA.length)
                            + " B=" + (bytesB == null ? 0 : bytesB.length)));
        };
    }

    /**
     * Decode a {@code functionIndex} section into a list of (path, name,
     * isNative) rows and compare. Categorizes the diff as:
     * - ENCODING: same content, same order, only byte layout differs
     * - ORDER: same content, different order
     * - CONTENT: extra/missing/drifted entries
     */
    private static SectionDiff compareFunctionIndex(byte[] bytesA, byte[] bytesB)
    {
        List<FnIdxRow> rA = decodeFunctionIndex(bytesA);
        List<FnIdxRow> rB = decodeFunctionIndex(bytesB);

        Map<String, FnIdxRow> mapA = new HashMap<>();
        Map<String, FnIdxRow> mapB = new HashMap<>();
        for (FnIdxRow r : rA) mapA.put(r.path, r);
        for (FnIdxRow r : rB) mapB.put(r.path, r);

        List<String> onlyInA = new ArrayList<>();
        List<String> onlyInB = new ArrayList<>();
        for (String p : mapA.keySet()) if (!mapB.containsKey(p)) onlyInA.add(p);
        for (String p : mapB.keySet()) if (!mapA.containsKey(p)) onlyInB.add(p);
        onlyInA.sort(Comparator.naturalOrder());
        onlyInB.sort(Comparator.naturalOrder());

        List<String> contentDrift = new ArrayList<>();
        Set<String> shared = new TreeSet<>(mapA.keySet());
        shared.retainAll(mapB.keySet());
        for (String p : shared)
        {
            FnIdxRow ra = mapA.get(p);
            FnIdxRow rb = mapB.get(p);
            if (!Objects.equals(ra.name, rb.name))
            {
                contentDrift.add(p + ".functionName  A=" + ra.name + "  B=" + rb.name);
            }
            if (ra.isNative != rb.isNative)
            {
                contentDrift.add(p + ".isNative  A=" + ra.isNative + "  B=" + rb.isNative);
            }
        }

        boolean orderDiffers = !pathSequence(rA).equals(pathSequence(rB));

        if (!onlyInA.isEmpty() || !onlyInB.isEmpty() || !contentDrift.isEmpty())
        {
            List<String> details = new ArrayList<>();
            details.add("A=" + rA.size() + " entries, B=" + rB.size() + " entries");
            if (!onlyInA.isEmpty())
            {
                details.add(onlyInA.size() + " only in A:");
                for (String p : onlyInA) details.add("  - " + p);
            }
            if (!onlyInB.isEmpty())
            {
                details.add(onlyInB.size() + " only in B:");
                for (String p : onlyInB) details.add("  + " + p);
            }
            if (!contentDrift.isEmpty())
            {
                details.add(contentDrift.size() + " content drift:");
                for (String d : contentDrift) details.add("  ~ " + d);
            }
            return new SectionDiff("functionIndex", SectionDiff.Kind.CONTENT,
                    "extras/drift across " + rA.size() + " ↔ " + rB.size() + " entries", details);
        }
        if (orderDiffers)
        {
            return new SectionDiff("functionIndex", SectionDiff.Kind.ORDER,
                    rA.size() + " entries match by content; ordering differs",
                    List.of("A first 5: " + pathSequence(rA).stream().limit(5).toList(),
                            "B first 5: " + pathSequence(rB).stream().limit(5).toList()));
        }
        // Bytes differed but path/name/isNative match in same order — must be
        // FunctionType encoding drift below the surface (FlatBuffer field
        // order, default-value omission, vector packing).
        return new SectionDiff("functionIndex", SectionDiff.Kind.ENCODING,
                rA.size() + " entries match by content + order; FlatBuffer encoding differs",
                List.of("bytes A=" + bytesA.length + " B=" + bytesB.length));
    }

    private static SectionDiff compareElementIndex(byte[] bytesA, byte[] bytesB)
    {
        List<ElIdxRow> rA = decodeElementIndex(bytesA);
        List<ElIdxRow> rB = decodeElementIndex(bytesB);

        Map<String, String> mapA = new HashMap<>();
        Map<String, String> mapB = new HashMap<>();
        for (ElIdxRow r : rA) mapA.put(r.path, r.type);
        for (ElIdxRow r : rB) mapB.put(r.path, r.type);

        List<String> onlyInA = new ArrayList<>();
        List<String> onlyInB = new ArrayList<>();
        for (String p : mapA.keySet()) if (!mapB.containsKey(p)) onlyInA.add(p);
        for (String p : mapB.keySet()) if (!mapA.containsKey(p)) onlyInB.add(p);
        onlyInA.sort(Comparator.naturalOrder());
        onlyInB.sort(Comparator.naturalOrder());

        List<String> typeDrift = new ArrayList<>();
        Set<String> shared = new TreeSet<>(mapA.keySet());
        shared.retainAll(mapB.keySet());
        for (String p : shared)
        {
            if (!Objects.equals(mapA.get(p), mapB.get(p)))
            {
                typeDrift.add(p + "  A=" + mapA.get(p) + "  B=" + mapB.get(p));
            }
        }

        List<String> seqA = new ArrayList<>();
        List<String> seqB = new ArrayList<>();
        for (ElIdxRow r : rA) seqA.add(r.path);
        for (ElIdxRow r : rB) seqB.add(r.path);
        boolean orderDiffers = !seqA.equals(seqB);

        if (!onlyInA.isEmpty() || !onlyInB.isEmpty() || !typeDrift.isEmpty())
        {
            List<String> details = new ArrayList<>();
            details.add("A=" + rA.size() + " entries, B=" + rB.size() + " entries");
            if (!onlyInA.isEmpty())
            {
                details.add(onlyInA.size() + " only in A: " + onlyInA);
            }
            if (!onlyInB.isEmpty())
            {
                details.add(onlyInB.size() + " only in B: " + onlyInB);
            }
            if (!typeDrift.isEmpty())
            {
                details.add(typeDrift.size() + " type drift:");
                for (String d : typeDrift) details.add("  ~ " + d);
            }
            return new SectionDiff("elementIndex", SectionDiff.Kind.CONTENT,
                    "extras/drift across " + rA.size() + " ↔ " + rB.size() + " entries", details);
        }
        if (orderDiffers)
        {
            return new SectionDiff("elementIndex", SectionDiff.Kind.ORDER,
                    rA.size() + " entries match by content; ordering differs",
                    List.of("A first 5: " + seqA.stream().limit(5).toList(),
                            "B first 5: " + seqB.stream().limit(5).toList()));
        }
        return new SectionDiff("elementIndex", SectionDiff.Kind.ENCODING,
                rA.size() + " entries match; FlatBuffer encoding differs",
                List.of("bytes A=" + bytesA.length + " B=" + bytesB.length));
    }

    private static List<FnIdxRow> decodeFunctionIndex(byte[] bytes)
    {
        List<FnIdxRow> rows = new ArrayList<>();
        if (bytes == null) return rows;
        FunctionIndex idx = FunctionIndex.getRootAsFunctionIndex(ByteBuffer.wrap(bytes));
        for (int i = 0; i < idx.entriesLength(); i++)
        {
            org.finos.legend.pure.m3.module.pdbModule.fbs.FunctionIndexEntry e = idx.entries(i);
            if (e != null) rows.add(new FnIdxRow(e.fullPath(), e.functionName(), e.isNative()));
        }
        return rows;
    }

    private static List<ElIdxRow> decodeElementIndex(byte[] bytes)
    {
        List<ElIdxRow> rows = new ArrayList<>();
        if (bytes == null) return rows;
        ElementIndex idx = ElementIndex.getRootAsElementIndex(ByteBuffer.wrap(bytes));
        for (int i = 0; i < idx.elementsLength(); i++)
        {
            org.finos.legend.pure.m3.module.pdbModule.fbs.ElementIndexEntry e = idx.elements(i);
            if (e != null) rows.add(new ElIdxRow(e.elementPath(), e.elementType()));
        }
        return rows;
    }

    private static List<String> pathSequence(List<FnIdxRow> rows)
    {
        List<String> seq = new ArrayList<>(rows.size());
        for (FnIdxRow r : rows) seq.add(r.path);
        return seq;
    }

    private record FnIdxRow(String path, String name, boolean isNative) {}
    private record ElIdxRow(String path, String type) {}
}
