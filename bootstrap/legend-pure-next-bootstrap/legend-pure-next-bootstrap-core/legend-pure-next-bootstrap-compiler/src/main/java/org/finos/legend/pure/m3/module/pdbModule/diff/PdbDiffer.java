// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.m3.module.pdbModule.diff;

import org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveReader;

import java.io.PrintStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compare two {@code .pdb} archives.
 *
 * <p>Two passes:</p>
 * <ol>
 *   <li><b>Element-set</b>: which paths are in A only, B only, both. Cheap.</li>
 *   <li><b>Content (shallow)</b>: for paths in both, compare the type name and
 *       the SHA-256 of the element's raw FlatBuffer bytes. Catches any byte
 *       difference but doesn't try to interpret the bytes — two semantically
 *       equal elements that the writer encoded differently will look
 *       different. That's a feature for self-host comparison: it surfaces
 *       any encoding drift between two compilers.</li>
 * </ol>
 *
 * <p>A deeper, structural diff (walking generated interface accessors and
 * comparing pointer targets by path) would reduce false positives but
 * isn't needed for the typical use case of "did our two compilers produce
 * the same PDB?".</p>
 */
public final class PdbDiffer
{
    private PdbDiffer()
    {
    }

    /**
     * Diff {@code a} against {@code b}, write a human-readable report to
     * {@code out}, and return a {@link Result} for programmatic use.
     */
    public static Result diff(Path a, Path b, PrintStream out) throws Exception
    {
        CompressedArchiveReader ra = new CompressedArchiveReader(a);
        CompressedArchiveReader rb = new CompressedArchiveReader(b);
        Result result = computeResult(ra, rb);
        writeReport(out, a, b, ra, rb, result);
        return result;
    }

    private static Result computeResult(CompressedArchiveReader ra, CompressedArchiveReader rb)
    {
        Set<String> pathsA = ra.elementPaths();
        Set<String> pathsB = rb.elementPaths();

        List<String> onlyInA = new ArrayList<>();
        List<String> onlyInB = new ArrayList<>();
        for (String p : pathsA) if (!pathsB.contains(p)) onlyInA.add(p);
        for (String p : pathsB) if (!pathsA.contains(p)) onlyInB.add(p);

        Set<String> shared = new LinkedHashSet<>(pathsA);
        shared.retainAll(pathsB);

        List<TypeMismatch> typeMismatches = new ArrayList<>();
        List<ContentMismatch> contentMismatches = new ArrayList<>();
        int identical = 0;

        for (String path : shared)
        {
            String typeA = ra.getElementType(path);
            String typeB = rb.getElementType(path);
            if (!equal(typeA, typeB))
            {
                typeMismatches.add(new TypeMismatch(path, typeA, typeB));
                continue;
            }
            byte[] bytesA = ra.readEntryBytes(path);
            byte[] bytesB = rb.readEntryBytes(path);
            if (java.util.Arrays.equals(bytesA, bytesB))
            {
                identical++;
            }
            else
            {
                contentMismatches.add(new ContentMismatch(path, typeA,
                        bytesA == null ? 0 : bytesA.length,
                        bytesB == null ? 0 : bytesB.length,
                        sha256(bytesA), sha256(bytesB)));
            }
        }

        // Section diff (functionIndex, elementIndex, …). Just report names
        // and hashes; sections are opaque blobs.
        Map<String, String> sectionsA = readSectionHashes(ra);
        Map<String, String> sectionsB = readSectionHashes(rb);
        List<SectionDiff> sectionDiffs = new ArrayList<>();
        Set<String> sectionNames = new TreeSet<>();
        sectionNames.addAll(sectionsA.keySet());
        sectionNames.addAll(sectionsB.keySet());
        for (String name : sectionNames)
        {
            String hashA = sectionsA.get(name);
            String hashB = sectionsB.get(name);
            if (!equal(hashA, hashB))
            {
                sectionDiffs.add(new SectionDiff(name, hashA, hashB));
            }
        }

        sortByPath(onlyInA);
        sortByPath(onlyInB);
        typeMismatches.sort(Comparator.comparing(t -> t.path));
        contentMismatches.sort(Comparator.comparing(c -> c.path));

        return new Result(pathsA.size(), pathsB.size(), shared.size(),
                identical, onlyInA, onlyInB, typeMismatches,
                contentMismatches, sectionDiffs);
    }

    private static void writeReport(PrintStream out, Path a, Path b,
                                    CompressedArchiveReader ra,
                                    CompressedArchiveReader rb,
                                    Result r)
    {
        out.println("PDB diff");
        out.println("  A: " + a + " (" + r.aTotal + " elements)");
        out.println("  B: " + b + " (" + r.bTotal + " elements)");
        out.println();
        out.println("Summary:");
        out.println("  shared paths           : " + r.shared);
        out.println("  identical bytes        : " + r.identical);
        out.println("  content differs        : " + r.contentMismatches.size());
        out.println("  type mismatched        : " + r.typeMismatches.size());
        out.println("  only in A              : " + r.onlyInA.size());
        out.println("  only in B              : " + r.onlyInB.size());
        out.println("  section diffs          : " + r.sectionDiffs.size());
        out.println();

        if (!r.onlyInA.isEmpty())
        {
            out.println("Only in A (" + r.onlyInA.size() + "):");
            for (String p : r.onlyInA) out.println("  - " + p + " [" + ra.getElementType(p) + "]");
            out.println();
        }
        if (!r.onlyInB.isEmpty())
        {
            out.println("Only in B (" + r.onlyInB.size() + "):");
            for (String p : r.onlyInB) out.println("  + " + p + " [" + rb.getElementType(p) + "]");
            out.println();
        }
        if (!r.typeMismatches.isEmpty())
        {
            out.println("Type mismatches (" + r.typeMismatches.size() + "):");
            for (TypeMismatch t : r.typeMismatches) out.println("  ~ " + t.path + ": A=" + t.typeA + " B=" + t.typeB);
            out.println();
        }
        if (!r.contentMismatches.isEmpty())
        {
            out.println("Content mismatches (" + r.contentMismatches.size() + "):");
            for (ContentMismatch c : r.contentMismatches)
            {
                out.println("  ! " + c.path + " [" + c.typeName + "]"
                        + "  bytes A=" + c.bytesA + " B=" + c.bytesB
                        + "  hash A=" + c.hashA.substring(0, 12)
                        + "… B=" + c.hashB.substring(0, 12) + "…");
            }
            out.println();
        }
        if (!r.sectionDiffs.isEmpty())
        {
            out.println("Section diffs (" + r.sectionDiffs.size() + "):");
            for (SectionDiff s : r.sectionDiffs)
            {
                out.println("  ! " + s.name + ": A=" + (s.hashA == null ? "<absent>" : s.hashA.substring(0, 12) + "…")
                        + " B=" + (s.hashB == null ? "<absent>" : s.hashB.substring(0, 12) + "…"));
            }
        }
    }

    private static Map<String, String> readSectionHashes(CompressedArchiveReader r)
    {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : sectionNames(r))
        {
            byte[] bytes = r.readSection(name);
            out.put(name, sha256(bytes));
        }
        return out;
    }

    /**
     * The reader exposes element paths but not section names. Reflectively
     * peek at the internal `sections` map. If reflection fails we just
     * return an empty set — the rest of the diff is still useful.
     */
    @SuppressWarnings("unchecked")
    private static Set<String> sectionNames(CompressedArchiveReader r)
    {
        try
        {
            java.lang.reflect.Field f = CompressedArchiveReader.class.getDeclaredField("sections");
            f.setAccessible(true);
            return new LinkedHashSet<>(((Map<String, String>) f.get(r)).keySet());
        }
        catch (ReflectiveOperationException e)
        {
            return Set.of();
        }
    }

    private static String sha256(byte[] bytes)
    {
        if (bytes == null) return "<null>";
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        }
        catch (Exception e)
        {
            return "<error>";
        }
    }

    private static boolean equal(Object a, Object b)
    {
        return a == null ? b == null : a.equals(b);
    }

    private static void sortByPath(List<String> list)
    {
        list.sort(Comparator.naturalOrder());
    }

    public static final class Result
    {
        public final int aTotal;
        public final int bTotal;
        public final int shared;
        public final int identical;
        public final List<String> onlyInA;
        public final List<String> onlyInB;
        public final List<TypeMismatch> typeMismatches;
        public final List<ContentMismatch> contentMismatches;
        public final List<SectionDiff> sectionDiffs;

        Result(int aTotal, int bTotal, int shared, int identical,
               List<String> onlyInA, List<String> onlyInB,
               List<TypeMismatch> typeMismatches,
               List<ContentMismatch> contentMismatches,
               List<SectionDiff> sectionDiffs)
        {
            this.aTotal = aTotal;
            this.bTotal = bTotal;
            this.shared = shared;
            this.identical = identical;
            this.onlyInA = onlyInA;
            this.onlyInB = onlyInB;
            this.typeMismatches = typeMismatches;
            this.contentMismatches = contentMismatches;
            this.sectionDiffs = sectionDiffs;
        }

        public boolean isClean()
        {
            return onlyInA.isEmpty() && onlyInB.isEmpty()
                    && typeMismatches.isEmpty() && contentMismatches.isEmpty()
                    && sectionDiffs.isEmpty();
        }
    }

    public record TypeMismatch(String path, String typeA, String typeB) {}

    public record ContentMismatch(String path, String typeName,
                                  int bytesA, int bytesB,
                                  String hashA, String hashB) {}

    public record SectionDiff(String name, String hashA, String hashB) {}
}
