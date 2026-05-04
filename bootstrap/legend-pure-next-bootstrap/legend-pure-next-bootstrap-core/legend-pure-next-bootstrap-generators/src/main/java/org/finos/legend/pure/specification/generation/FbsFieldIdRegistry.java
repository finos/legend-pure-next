// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.pure.specification.generation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persistent assignment of FlatBuffer table-field IDs across builds, so the
 * binary wire format stays compatible regardless of how the upstream RDF
 * iteration orders M3 properties.
 *
 * <p>FlatBuffer's {@code (id: N)} attribute pins a field's vtable offset to
 * {@code N} regardless of declaration order. Without persistent IDs, every
 * {@code RdfFbsSchemaGenerator} run could emit {@code m3.fbs} with fields in
 * a different order (Apache Jena's {@code ResIterator} doesn't guarantee
 * iteration order), and the resulting {@code flatc}-generated Java classes
 * would have different vtable offsets — pdbs written with one build couldn't
 * be read by another. Pinning IDs once and reusing them across builds is
 * the wire-stable solution, the same pattern protobuf field numbers use.</p>
 *
 * <h2>File format</h2>
 * <pre>
 *   # comments and blank lines are ignored
 *   ClassDef.classifier_generic_type=0
 *   ClassDef.source_information=1
 *   ClassDef.properties=2
 *   ...
 *   PropertyDef.name=0
 *   ...
 * </pre>
 *
 * <h2>Append-only invariant</h2>
 * <p>Existing entries are never reused or removed. When the schema gains a
 * new property, {@link #idFor} assigns the next-unused-ID for that table
 * (max-existing + 1) and persists it. Removed properties leave their slot
 * intact; the FBS emitter renders them as {@code (deprecated)} so the
 * vtable shape doesn't change. PR review on this file = review on schema
 * evolution: any unexpected addition or removal is visible in the diff.</p>
 *
 * <h2>Concurrency</h2>
 * <p>Single-threaded. The schema generator runs synchronously in the
 * {@code generate-m3-fbs} maven execution, so no synchronisation is
 * needed.</p>
 */
public final class FbsFieldIdRegistry
{
    /**
     * tableName → (fieldName → id). Outer map kept TreeMap for stable
     * file ordering (alphabetical by table); inner kept LinkedHashMap so
     * existing insertion order survives round-trip writes (load preserves
     * the file's order, save replays it).
     */
    private final TreeMap<String, LinkedHashMap<String, Integer>> ids = new TreeMap<>();

    private final Path path;
    private boolean dirty;

    /** Load the registry from {@code path}. Missing file → empty registry. */
    public static FbsFieldIdRegistry load(Path path) throws IOException
    {
        FbsFieldIdRegistry r = new FbsFieldIdRegistry(path);
        if (!Files.isRegularFile(path))
        {
            return r;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
        {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#"))
            {
                continue;
            }
            int eq = trimmed.indexOf('=');
            int dot = trimmed.indexOf('.');
            if (eq < 0 || dot < 0 || dot >= eq)
            {
                throw new IOException("Malformed line in " + path + ": " + line);
            }
            String table = trimmed.substring(0, dot);
            String field = trimmed.substring(dot + 1, eq);
            int id = Integer.parseInt(trimmed.substring(eq + 1).trim());
            r.ids.computeIfAbsent(table, k -> new LinkedHashMap<>()).put(field, id);
        }
        return r;
    }

    private FbsFieldIdRegistry(Path path)
    {
        this.path = path;
    }

    /**
     * Return the persistent ID for {@code (table, field)}, assigning a fresh
     * one on first sight. {@code isUnion} reserves an extra slot for
     * FlatBuffer's implicit {@code <field>_type} byte (which lives at the
     * union's id minus one); without that reservation, flatc rejects the
     * schema with "union must be on second field" because it needs id ≥ 1.
     */
    public int idFor(String table, String field, boolean isUnion)
    {
        LinkedHashMap<String, Integer> tableIds = ids.computeIfAbsent(table, k -> {
            dirty = true;
            return new LinkedHashMap<>();
        });
        Integer existing = tableIds.get(field);
        if (existing != null)
        {
            return existing;
        }
        int max = tableIds.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
        // Scalar: max + 1. Union: max + 2 — slot max+1 is the implicit
        // <field>_type byte; the union itself sits at max+2.
        int next = isUnion ? max + 2 : max + 1;
        tableIds.put(field, next);
        dirty = true;
        return next;
    }

    /**
     * Return the field names previously seen for {@code table} that are NOT
     * in {@code currentFields}. The FBS emitter renders these as
     * {@code (deprecated, id: N)} so the vtable retains its shape after a
     * schema removal — without this, FlatBuffer's contiguous-ID requirement
     * would force an ID renumber.
     */
    public List<String> deprecatedFieldsFor(String table, java.util.Set<String> currentFields)
    {
        LinkedHashMap<String, Integer> tableIds = ids.get(table);
        if (tableIds == null)
        {
            return List.of();
        }
        List<String> deprecated = new java.util.ArrayList<>();
        for (String f : tableIds.keySet())
        {
            if (!currentFields.contains(f))
            {
                deprecated.add(f);
            }
        }
        return deprecated;
    }

    /** Persist the registry. No-op if no changes were made since {@link #load}. */
    public void saveIfDirty() throws IOException
    {
        if (!dirty)
        {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# FlatBuffer field-ID registry — keeps wire format stable across builds.\n");
        sb.append("# Auto-maintained by RdfFbsSchemaGenerator. Append-only.\n");
        sb.append("# Format: TableName.field_name=ID\n");
        sb.append("\n");
        for (Map.Entry<String, LinkedHashMap<String, Integer>> tEntry : ids.entrySet())
        {
            String table = tEntry.getKey();
            // Within a table, sort by ID for stable output (existing IDs first
            // in numeric order, new ones at the end).
            tEntry.getValue().entrySet().stream()
                    .sorted(Comparator.comparingInt(Map.Entry::getValue))
                    .forEach(fEntry ->
                            sb.append(table).append('.').append(fEntry.getKey())
                                    .append('=').append(fEntry.getValue()).append('\n'));
            sb.append('\n');
        }
        if (path.getParent() != null)
        {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        dirty = false;
    }
}
