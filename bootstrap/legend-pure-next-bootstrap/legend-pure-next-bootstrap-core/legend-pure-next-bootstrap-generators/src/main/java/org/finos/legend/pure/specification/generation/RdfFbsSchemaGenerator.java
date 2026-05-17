// Copyright 2024 Goldman Sachs
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

import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.specification.generation.model.M3MetamodelReader;
import org.finos.legend.pure.specification.generation.model.M3Model;
import org.finos.legend.pure.specification.generation.model.PropertyInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.finos.legend.pure.specification.generation.model.ModelUtils.*;

/**
 * Generates the FlatBuffer schema (.fbs) from the M3 metamodel.
 *
 * <p>Produces {@code m3.fbs} with one table per M3 class,
 * union types for pointer properties with nonPointerSubtypes,
 * and a root {@code ElementEntry} table.</p>
 */
public class RdfFbsSchemaGenerator
{
    private final M3Model m3Model;

    public RdfFbsSchemaGenerator(String ttlPath)
    {
        this(new M3MetamodelReader(ttlPath).read());
    }

    public RdfFbsSchemaGenerator(M3Model m3Model)
    {
        this.m3Model = m3Model;
    }

    /**
     * Generate the FlatBuffer schema file to the specified output directory.
     *
     * @param outputDir          the output directory for the .fbs file
     * @param additionalFbsPaths paths to additional .fbs files to merge into the output
     */
    public void generate(Path outputDir, List<String> additionalFbsPaths) throws IOException
    {
        generate(outputDir, additionalFbsPaths, null);
    }

    /**
     * Generate with a persistent field-ID registry. When {@code idRegistryPath}
     * is non-null, every emitted field carries an explicit {@code (id: N)}
     * pinned by {@link FbsFieldIdRegistry} — wire format stays stable across
     * builds even though Apache Jena's RDF iteration is order-non-deterministic
     * (which is the underlying reason builds without IDs produced shuffled
     * vtables and incompatible jars).
     */
    public void generate(Path outputDir, List<String> additionalFbsPaths, Path idRegistryPath) throws IOException
    {
        Files.createDirectories(outputDir);

        FbsFieldIdRegistry registry = idRegistryPath == null
                ? null
                : FbsFieldIdRegistry.load(idRegistryPath);

        StringBuilder sb = new StringBuilder();
        sb.append("// AUTO-GENERATED from m3.ttl - DO NOT EDIT\n\n");
        sb.append("namespace org.finos.legend.pure.m3.module.pdbModule.fbs;\n\n");

        // Forward-declare all tables (skip pointer classes — they get no FBS table)
        m3Model.classInfoMap().valuesView().toSortedListBy(ci -> ci.name).forEach(ci ->
        {
            if (isCompilerPointer(ci))
            {
                return;
            }
            sb.append("// forward: table ").append(ci.name).append("Def\n");
        });
        sb.append("\n");

        // Pointer reference table for union pointer fields
        sb.append("enum PointerKind : byte { Element, Property, QualifiedProperty, Stereotype, Tag }\n\n");
        sb.append("table PointerRef {\n");
        appendField(sb, registry, "PointerRef", "kind", "PointerKind");
        appendField(sb, registry, "PointerRef", "path", "[string]");
        sb.append("}\n\n");

        // Ancestor reference table for cycle back-references
        sb.append("table AncestorRef {\n");
        appendField(sb, registry, "AncestorRef", "depth", "int");
        sb.append("}\n\n");

        // Generate union types for pointer properties with nonPointerSubtypes.
        // Properties with identical member sets share a union (so all
        // `classifier_generic_type` slots that have the same inline subtypes
        // collapse to one declaration), but different content produces
        // distinct unions — the previous "dedup by field-name only" approach
        // collided when two unrelated classes had a same-named property of
        // different types (e.g. ResolvedTypeParameter.value vs
        // ResolvedMultiplicityParameter.value).
        MutableMap<String, String> contentToUnionName = Maps.mutable.empty();
        MutableMap<String, String> propertyToUnionName = Maps.mutable.empty();
        m3Model.classInfoMap().valuesView().toSortedListBy(ci -> ci.name).forEach(classInfo ->
        {
            MutableList<PropertyInfo> allProps = collectAllProperties(m3Model, classInfo);
            allProps.forEach(prop ->
            {
                if (hasStereotype(prop.stereotypes, "excluded"))
                {
                    return;
                }
                MutableList<String> nps = getNonPointerSubtypes(m3Model, prop);
                if (nps.isEmpty()) { return; }

                String fbsField = toFbsFieldName(prop.name);
                String defaultName = unionTypeName(fbsField);
                String contentKey = nps.toSortedList().makeString(",");

                String existing = contentToUnionName.get(contentKey);
                String uName;
                if (existing != null)
                {
                    uName = existing;
                }
                else if (!contentToUnionName.valuesView().contains(defaultName))
                {
                    uName = defaultName;
                    contentToUnionName.put(contentKey, uName);
                    sb.append("union ").append(uName).append(" { PointerRef, AncestorRef");
                    nps.forEach(subtype -> sb.append(", ").append(subtype).append("Def"));
                    sb.append(" }\n\n");
                }
                else
                {
                    // Default name is taken by a different content set —
                    // disambiguate with the class name.
                    uName = classInfo.name + defaultName;
                    contentToUnionName.put(contentKey, uName);
                    sb.append("union ").append(uName).append(" { PointerRef, AncestorRef");
                    nps.forEach(subtype -> sb.append(", ").append(subtype).append("Def"));
                    sb.append(" }\n\n");
                }
                propertyToUnionName.put(classInfo.name + "." + fbsField, uName);
            });
        });

        // Generate union types for mainTaxonomy classes (polymorphic hierarchies)
        // Maps className -> unionTypeName for use in property type resolution
        MutableMap<String, String> mainTaxonomyUnions = Maps.mutable.empty();
        m3Model.classInfoMap().valuesView().toSortedListBy(ci -> ci.name).forEach(classInfo ->
        {
            if (isMainTaxonomy(m3Model, classInfo))
            {
                MutableList<String> subtypes = collectAllSubtypes(m3Model, classInfo.name);
                if (subtypes.notEmpty())
                {
                    String uName = classInfo.name + "Union";
                    mainTaxonomyUnions.put(classInfo.name, uName);
                    sb.append("union ").append(uName).append(" { ");
                    // Include all subtypes
                    subtypes.forEachWithIndex((subtype, idx) ->
                    {
                        if (idx > 0) { sb.append(", "); }
                        sb.append(subtype).append("Def");
                    });
                    // Include the base type itself as fallback
                    if (!isAbstract(classInfo))
                    {
                        if (subtypes.notEmpty()) { sb.append(", "); }
                        sb.append(classInfo.name).append("Def");
                    }
                    // Include AncestorRef for cycle back-references
                    if (subtypes.notEmpty() || !isAbstract(classInfo)) { sb.append(", "); }
                    sb.append("AncestorRef");
                    sb.append(" }\n\n");
                }
            }
        });

        // AtomicValue.value is Any[1] — needs a union for primitives vs LambdaFunction
        appendSingleFieldTable(sb, registry, "IntegerValueDef", "val", "long");
        appendSingleFieldTable(sb, registry, "FloatValueDef", "val", "double");
        appendSingleFieldTable(sb, registry, "DecimalValueDef", "val", "string");
        appendSingleFieldTable(sb, registry, "BooleanValueDef", "val", "bool");
        appendSingleFieldTable(sb, registry, "StringValueDef", "val", "string");
        sb.append("union AtomicValueContentUnion { IntegerValueDef, FloatValueDef, BooleanValueDef, StringValueDef, LambdaFunctionDef, PointerRef, DecimalValueDef }\n\n");

        // Generate tables
        m3Model.classInfoMap().valuesView().toSortedListBy(ci -> ci.name).forEach(classInfo ->
        {
            if (isAbstract(classInfo) || isCompilerPointer(classInfo))
            {
                return;
            }
            MutableList<PropertyInfo> allProps = collectAllProperties(m3Model, classInfo);
            String tableName = classInfo.name + "Def";

            sb.append("table ").append(tableName).append(" {\n");

            // Collect (fbsField, fbsType) pairs first; emit them sorted by
            // assigned ID below so the file order is deterministic regardless
            // of how the underlying RDF iteration ordered allProps.
            java.util.LinkedHashMap<String, String> currentFields = new java.util.LinkedHashMap<>();
            allProps.forEach(prop ->
            {
                if (hasStereotype(prop.stereotypes, "excluded"))
                {
                    return;
                }
                MutableList<String> nps = getNonPointerSubtypes(m3Model, prop);
                String fbsField;
                String fbsType;
                if (nps.notEmpty())
                {
                    fbsField = toFbsFieldName(prop.name);
                    String uName = propertyToUnionName.get(classInfo.name + "." + fbsField);
                    if (uName == null)
                    {
                        throw new IllegalStateException("No union name registered for "
                                + classInfo.name + "." + fbsField + " — first-pass union build skipped this property.");
                    }
                    fbsType = prop.isMany ? "[" + uName + "]" : uName;
                }
                else if ("AtomicValue".equals(classInfo.name) && "value".equals(prop.name))
                {
                    fbsField = "value";
                    fbsType = "AtomicValueContentUnion";
                }
                else
                {
                    fbsField = toFbsFieldName(prop.name);
                    fbsType = mapToFbsType(prop.typeName, prop.isMany, hasStereotype(prop.stereotypes, "pointer"), mainTaxonomyUnions);
                }
                currentFields.put(fbsField, fbsType);
            });

            emitTableFields(sb, registry, tableName, currentFields);

            sb.append("}\n\n");
        });

        // Merge additional .fbs files
        if (additionalFbsPaths != null)
        {
            for (String addPath : additionalFbsPaths)
            {
                Path path = Paths.get(addPath);
                if (Files.exists(path))
                {
                    System.out.println("  Merging additional FBS: " + path);
                    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                    sb.append("// --- Merged from ").append(path.getFileName()).append(" ---\n\n");
                    for (String line : lines)
                    {
                        // Skip namespace declarations and comment-only header lines
                        String trimmed = line.trim();
                        if (trimmed.startsWith("namespace ") || trimmed.isEmpty())
                        {
                            continue;
                        }
                        if (trimmed.startsWith("//"))
                        {
                            continue;
                        }
                        sb.append(line).append("\n");
                    }
                    sb.append("\n");
                }
            }
        }

        // Element entry table is the top-level container
        sb.append("table ElementEntry {\n");
        java.util.LinkedHashMap<String, String> entryFields = new java.util.LinkedHashMap<>();
        entryFields.put("path", "string");
        entryFields.put("element_type", "string");
        m3Model.classInfoMap().valuesView().toSortedListBy(ci -> ci.name).forEach(ci ->
        {
            if (isAbstract(ci) || isCompilerPointer(ci))
            {
                return;
            }
            entryFields.put(toFbsFieldName(ci.name) + "_val", ci.name + "Def");
        });
        emitTableFields(sb, registry, "ElementEntry", entryFields);
        sb.append("}\n\n");
        sb.append("root_type ElementEntry;\n");

        Path schemaPath = outputDir.resolve("m3.fbs");
        Files.write(schemaPath, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("  Generated: m3.fbs (" + m3Model.classInfoMap().size() + " tables)");

        if (registry != null)
        {
            registry.saveIfDirty();
        }
    }

    /**
     * Emit a single field, with an explicit {@code (id: N)} when {@code
     * registry} is non-null. Pinning IDs is what keeps vtable offsets
     * stable across builds.
     */
    private static void appendField(StringBuilder sb, FbsFieldIdRegistry registry,
            String table, String field, String type)
    {
        sb.append("    ").append(field).append(": ").append(type);
        if (registry != null)
        {
            sb.append(" (id: ").append(registry.idFor(table, field, isUnionType(type))).append(")");
        }
        sb.append(";\n");
    }

    /**
     * A FlatBuffer field is a "union" (and so consumes two id slots — the
     * implicit type byte plus the value) when its type ends with
     * {@code Union}. Both single ({@code MyUnion}) and vector
     * ({@code [MyUnion]}) flavours qualify.
     */
    private static boolean isUnionType(String fbsType)
    {
        if (fbsType == null)
        {
            return false;
        }
        String inner = (fbsType.startsWith("[") && fbsType.endsWith("]"))
                ? fbsType.substring(1, fbsType.length() - 1)
                : fbsType;
        return inner.endsWith("Union");
    }

    /** Convenience: emit a one-field table with stable ID. */
    private static void appendSingleFieldTable(StringBuilder sb, FbsFieldIdRegistry registry,
            String table, String field, String type)
    {
        sb.append("table ").append(table).append(" {\n");
        appendField(sb, registry, table, field, type);
        sb.append("}\n");
    }

    /**
     * Emit the body of a table given its current fields. Fields keep their
     * assigned IDs across builds; previously-known fields that no longer
     * appear are emitted as {@code (deprecated, id: N)} so the vtable shape
     * is preserved (FlatBuffer requires contiguous IDs from 0; without the
     * deprecated placeholder, removing a field would force a renumber).
     * Output is sorted by ID so the file diffs cleanly.
     */
    private static void emitTableFields(StringBuilder sb, FbsFieldIdRegistry registry,
            String table, java.util.LinkedHashMap<String, String> currentFields)
    {
        if (registry == null)
        {
            // No registry — fall back to declaration order. This mode exists
            // only for the back-compat overload; the maven pipeline always
            // passes a registry path.
            currentFields.forEach((field, type) ->
                    sb.append("    ").append(field).append(": ").append(type).append(";\n"));
            return;
        }
        record Entry(String field, String type, int id, boolean deprecated) {}
        java.util.List<Entry> entries = new java.util.ArrayList<>(currentFields.size());
        for (var e : currentFields.entrySet())
        {
            int id = registry.idFor(table, e.getKey(), isUnionType(e.getValue()));
            entries.add(new Entry(e.getKey(), e.getValue(), id, false));
        }
        // Render previously-seen-but-now-removed fields as deprecated so the
        // vtable retains its slots. Without this, FlatBuffer's "contiguous IDs"
        // requirement would force a renumber on removal.
        for (String dep : registry.deprecatedFieldsFor(table, currentFields.keySet()))
        {
            int id = registry.idFor(table, dep, false);
            // Deprecated fields keep their original type; we don't track the
            // original type so we use a safe placeholder that matches any
            // historical field shape (FlatBuffer doesn't validate the type
            // of a deprecated field). bool is the smallest scalar.
            entries.add(new Entry(dep, "bool", id, true));
        }
        entries.sort(java.util.Comparator.comparingInt(Entry::id));
        for (Entry e : entries)
        {
            sb.append("    ").append(e.field()).append(": ").append(e.type());
            if (e.deprecated())
            {
                sb.append(" (deprecated, id: ").append(e.id()).append(")");
            }
            else
            {
                sb.append(" (id: ").append(e.id()).append(")");
            }
            sb.append(";\n");
        }
    }

    // =========================================================================
    // FBS Type Mapping
    // =========================================================================

    private String mapToFbsType(String m3Type, boolean isMany, boolean isPointer, MutableMap<String, String> mainTaxonomyUnions)
    {
        if (isPointer)
        {
            return isMany ? "[PointerRef]" : "PointerRef";
        }

        // For properties of mainTaxonomy types, use the union
        if (mainTaxonomyUnions.containsKey(m3Type))
        {
            String unionName = mainTaxonomyUnions.get(m3Type);
            return isMany ? "[" + unionName + "]" : unionName;
        }

        // Delegate to formal primitive mapping
        String fbsType = m3Type != null ? PrimitiveFbsTypeMapping.toFbsType(m3Type) : "string";
        String baseType;
        if (fbsType != null)
        {
            baseType = fbsType;
        }
        else if (m3Model.classInfoMap().containsKey(m3Type))
        {
            baseType = m3Type + "Def";
        }
        else
        {
            baseType = "string";
        }

        return isMany ? "[" + baseType + "]" : baseType;
    }

    // =========================================================================
    // Main Entry Point
    // =========================================================================

    /**
     * Usage: {@code RdfFbsSchemaGenerator <input.ttl> <fbsOutputDir>
     *        [--ids <idRegistry>] [<additional.fbs> ...]}
     *
     * <p>{@code --ids <path>} is the persistent field-ID registry — required
     * for wire-format stability across builds. Without it, fields get
     * implicit IDs by declaration order, which depends on Apache Jena's
     * RDF iteration (non-deterministic).</p>
     */
    public static void main(String[] args)
    {
        try
        {
            if (args.length < 2)
            {
                System.out.println("Usage: RdfFbsSchemaGenerator <input.ttl> <fbsOutputDir> [--ids <registry>] [<additional.fbs>...]");
                System.exit(1);
            }

            System.out.println();
            System.out.println("M3 FlatBuffer Schema Generator (FBS)");
            System.out.println("====================================");

            Path idRegistry = null;
            List<String> additionalFbs = new java.util.ArrayList<>();
            for (int i = 2; i < args.length; i++)
            {
                if ("--ids".equals(args[i]) && i + 1 < args.length)
                {
                    idRegistry = Paths.get(args[++i]);
                }
                else
                {
                    additionalFbs.add(args[i]);
                }
            }

            System.out.println("  Input:    " + args[0]);
            System.out.println("  Output:   " + args[1]);
            if (idRegistry != null)
            {
                System.out.println("  Field IDs: " + idRegistry);
            }
            for (String add : additionalFbs)
            {
                System.out.println("  Merge:    " + add);
            }

            new RdfFbsSchemaGenerator(args[0]).generate(Paths.get(args[1]), additionalFbs, idRegistry);
            System.out.println("    FBS schema generation complete.");
        }
        catch (Exception e)
        {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
