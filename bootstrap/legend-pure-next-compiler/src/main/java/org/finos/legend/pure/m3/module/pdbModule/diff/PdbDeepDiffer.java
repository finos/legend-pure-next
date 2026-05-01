// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.m3.module.pdbModule.diff;

import meta.pure.metamodel.PackageableElement;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
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
    private static final int MAX_DEPTH = 30;

    private PdbDeepDiffer() {}

    public static Result diff(Path a, Path b, PrintStream out) throws Exception
    {
        PDBModule modA = new PDBModule(a, PDBModule.Mode.EXECUTION);
        PDBModule modB = new PDBModule(b, PDBModule.Mode.EXECUTION);
        return doDiff(modA, modB, a, b, out);
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
            walker.compare(path, elA, elB, 0);
            if (walker.diffs.isEmpty())
            {
                identical++;
            }
            else
            {
                diffs.addAll(walker.diffs);
            }
        }

        onlyInA.sort(Comparator.naturalOrder());
        onlyInB.sort(Comparator.naturalOrder());
        diffs.sort(Comparator.comparing((PathDiff d) -> d.path));

        Result result = new Result(pathsA.size(), pathsB.size(), shared.size(),
                identical, onlyInA, onlyInB, diffs);
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
                    compare(path + "." + propName, vA, vB, depth + 1);
                }
            }
            finally
            {
                visitedA.remove(a);
                visitedB.remove(b);
            }
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

        Result(int aTotal, int bTotal, int shared, int identical,
               List<String> onlyInA, List<String> onlyInB, List<PathDiff> diffs)
        {
            this.aTotal = aTotal;
            this.bTotal = bTotal;
            this.shared = shared;
            this.identical = identical;
            this.onlyInA = onlyInA;
            this.onlyInB = onlyInB;
            this.diffs = diffs;
        }

        public boolean isClean()
        {
            return onlyInA.isEmpty() && onlyInB.isEmpty() && diffs.isEmpty();
        }
    }

    public record PathDiff(String path, String valueA, String valueB) {}
}
