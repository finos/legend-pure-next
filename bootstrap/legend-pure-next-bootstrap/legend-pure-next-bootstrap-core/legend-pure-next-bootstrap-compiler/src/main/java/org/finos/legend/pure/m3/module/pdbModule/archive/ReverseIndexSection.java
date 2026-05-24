// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.m3.module.pdbModule.archive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reverse reference index serialization for the PDB archive.
 *
 * <p>The index records every cross-element lookup made during compilation:
 * for each target element path, the set of caller element paths that
 * referenced it. Built as a side-effect by {@code RecordingMetadataAccess}
 * (Java) or its compiler-pure counterpart (Pure) and persisted alongside
 * the elements so consumers (validators, IDE find-references, etc.) can
 * read it without re-walking the metamodel.</p>
 *
 * <p>Wire format: UTF-8 text, one {@code caller -> target} line per edge,
 * sorted lexicographically for stability. The same format the test
 * {@code ###ReverseIndex} section uses, so persistence and the test
 * harness share rendering rules. Compact (a few hundred bytes per
 * thousand edges) and human-readable for debugging.</p>
 */
public final class ReverseIndexSection
{
    /** Archive entry name used by {@link CompressedArchiveWriter} / Reader. */
    public static final String ARCHIVE_SECTION = "reverseReferenceIndex";

    private ReverseIndexSection()
    {
    }

    /**
     * Filter a reverse index to keep only entries whose callers belong to
     * a given set of paths. Used when partitioning a single compile's
     * index across multiple output PDBs (e.g. lean vs tests companion)
     * so each archive carries only the references its own callers made.
     *
     * <p>A target entry survives only if at least one of its callers
     * passes the predicate; surviving callers are filtered to those
     * present in the set. Order-preserving when used with LinkedHash-
     * backed inputs.</p>
     */
    public static Map<String, Set<String>> filter(
            Map<String, Set<String>> index, java.util.function.Predicate<String> callerInScope)
    {
        if (index == null || index.isEmpty())
        {
            return new LinkedHashMap<>();
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        index.forEach((target, callers) ->
        {
            Set<String> kept = new LinkedHashSet<>();
            for (String c : callers)
            {
                if (callerInScope.test(c)) kept.add(c);
            }
            if (!kept.isEmpty())
            {
                result.put(target, kept);
            }
        });
        return result;
    }

    /**
     * Build a {@link PDBArchiveSection} from the in-memory reverse index.
     * Returns {@code null} when the index is empty so callers can skip
     * appending the section.
     *
     * <p>Format mirrors the test {@code ###ReverseIndex} section: targets
     * sorted alphabetically, each followed by its sorted callers indented
     * by two spaces. Reverse-index shape ({@code target → callers}) is
     * visible in the layout.</p>
     */
    public static PDBArchiveSection serialize(Map<String, Set<String>> referencedBy)
    {
        if (referencedBy == null || referencedBy.isEmpty())
        {
            return null;
        }
        List<String> targets = new ArrayList<>(referencedBy.keySet());
        Collections.sort(targets);
        StringBuilder sb = new StringBuilder();
        for (String target : targets)
        {
            sb.append(target).append(':').append('\n');
            List<String> callers = new ArrayList<>(referencedBy.get(target));
            Collections.sort(callers);
            for (String caller : callers)
            {
                sb.append("  ").append(caller).append('\n');
            }
        }
        return new PDBArchiveSection(ARCHIVE_SECTION, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parse the archive bytes back into a {@code target -> callers} map.
     * Tolerant of empty input (returns an empty map) and of trailing
     * whitespace. Reciprocal of {@link #serialize}.
     */
    public static Map<String, Set<String>> deserialize(byte[] bytes)
    {
        if (bytes == null || bytes.length == 0)
        {
            return new LinkedHashMap<>();
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        String currentTarget = null;
        for (String line : text.split("\n"))
        {
            if (line.trim().isEmpty())
            {
                continue;
            }
            if (!Character.isWhitespace(line.charAt(0)))
            {
                String t = line.trim();
                if (t.endsWith(":"))
                {
                    currentTarget = t.substring(0, t.length() - 1);
                    result.computeIfAbsent(currentTarget, k -> new LinkedHashSet<>());
                }
            }
            else if (currentTarget != null)
            {
                result.get(currentTarget).add(line.trim());
            }
        }
        return result;
    }
}
