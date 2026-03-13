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
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.finos.legend.pure.specification.generation.model.ClassInfo;
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
        Files.createDirectories(outputDir);

        StringBuilder sb = new StringBuilder();
        sb.append("// AUTO-GENERATED from m3.ttl - DO NOT EDIT\n\n");
        sb.append("namespace org.finos.legend.pure.m3.module.pdbModule.fbs;\n\n");

        // Forward-declare all tables
        m3Model.classInfoMap().keysView().toSortedList().forEach(name ->
                sb.append("// forward: table ").append(name).append("Def\n"));
        sb.append("\n");

        // Pointer reference table for union pointer fields
        sb.append("table PointerRef {\n");
        sb.append("    path: string;\n");
        sb.append("}\n\n");

        // Generate union types for pointer properties with nonPointerSubtypes
        MutableSet<String> generatedUnions = Sets.mutable.empty();
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
                if (nps.notEmpty())
                {
                    String fbsField = toFbsFieldName(prop.name);
                    String uName = unionTypeName(fbsField);
                    if (generatedUnions.add(uName))
                    {
                        sb.append("union ").append(uName).append(" { PointerRef");
                        nps.forEach(subtype -> sb.append(", ").append(subtype).append("Def"));
                        sb.append(" }\n\n");
                    }
                }
            });
        });

        // Generate union types for mainTaxonomy classes (polymorphic hierarchies)
        // Maps className -> unionTypeName for use in property type resolution
        MutableMap<String, String> mainTaxonomyUnions = Maps.mutable.empty();
        m3Model.classInfoMap().valuesView().toSortedListBy(ci -> ci.name).forEach(classInfo ->
        {
            if (isMainTaxonomy(classInfo))
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
                    sb.append(", ").append(classInfo.name).append("Def");
                    sb.append(" }\n\n");
                }
            }
        });

        // AtomicValue.value is Any[1] — needs a union for primitive (string) vs LambdaFunction
        sb.append("table PrimitiveValueDef { val: string; }\n");
        sb.append("union AtomicValueContentUnion { PrimitiveValueDef, LambdaFunctionDef }\n\n");

        // Generate tables
        m3Model.classInfoMap().valuesView().toSortedListBy(ci -> ci.name).forEach(classInfo ->
        {
            MutableList<PropertyInfo> allProps = collectAllProperties(m3Model, classInfo);

            sb.append("table ").append(classInfo.name).append("Def {\n");

            allProps.forEach(prop ->
            {
                if (hasStereotype(prop.stereotypes, "excluded"))
                {
                    return;
                }
                MutableList<String> nps = getNonPointerSubtypes(m3Model, prop);
                if (nps.notEmpty())
                {
                    String fbsField = toFbsFieldName(prop.name);
                    String uName = unionTypeName(fbsField);
                    sb.append("    ").append(fbsField).append(": ").append(uName).append(";\n");
                }
                else if ("AtomicValue".equals(classInfo.name) && "value".equals(prop.name))
                {
                    // Use union for Any-typed value
                    sb.append("    value: AtomicValueContentUnion;\n");
                }
                else
                {
                    String fbsType = mapToFbsType(prop.typeName, prop.isMany, hasStereotype(prop.stereotypes, "pointer"), mainTaxonomyUnions);
                    String fbsFieldName = toFbsFieldName(prop.name);
                    sb.append("    ").append(fbsFieldName).append(": ").append(fbsType).append(";\n");
                }
            });

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
        sb.append("    path: string;\n");
        sb.append("    element_type: string;\n");

        m3Model.classInfoMap().keysView().toSortedList().forEach(name ->
        {
            String fbsField = toFbsFieldName(name);
            sb.append("    ").append(fbsField).append("_val: ").append(name).append("Def;\n");
        });

        sb.append("}\n\n");
        sb.append("root_type ElementEntry;\n");

        Path schemaPath = outputDir.resolve("m3.fbs");
        Files.write(schemaPath, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("  Generated: m3.fbs (" + m3Model.classInfoMap().size() + " tables)");
    }

    // =========================================================================
    // FBS Type Mapping
    // =========================================================================

    private String mapToFbsType(String m3Type, boolean isMany, boolean isPointer, MutableMap<String, String> mainTaxonomyUnions)
    {
        if (isPointer)
        {
            return isMany ? "[string]" : "string";
        }

        // For many-valued properties of mainTaxonomy types, use the union
        if (isMany && mainTaxonomyUnions.containsKey(m3Type))
        {
            return "[" + mainTaxonomyUnions.get(m3Type) + "]";
        }

        String baseType = switch (m3Type)
        {
            case null -> "string";
            case "String" -> "string";
            case "Boolean" -> "bool";
            case "Integer" -> "long";
            case "Float" -> "double";
            case "Decimal" -> "string";
            case "Date", "DateTime", "StrictDate" -> "string";
            case "Number" -> "double";
            case "Byte" -> "ubyte";
            case "Any" -> "string";
            default ->
            {
                if (m3Model.classInfoMap().containsKey(m3Type))
                {
                    yield m3Type + "Def";
                }
                yield "string";
            }
        };

        return isMany ? "[" + baseType + "]" : baseType;
    }

    // =========================================================================
    // Main Entry Point
    // =========================================================================

    /**
     * Usage: {@code RdfFbsSchemaGenerator <input.ttl> <fbsOutputDir>}
     */
    public static void main(String[] args)
    {
        try
        {
            if (args.length < 2)
            {
                System.out.println("Usage: RdfFbsSchemaGenerator <input.ttl> <fbsOutputDir>");
                System.exit(1);
            }

            System.out.println("M3 FlatBuffer Schema Generator");
            System.out.println("================================");
            System.out.println("Input:  " + args[0]);
            System.out.println("Output: " + args[1]);
            System.out.println();

            List<String> additionalFbs = args.length > 2
                    ? List.of(java.util.Arrays.copyOfRange(args, 2, args.length))
                    : List.of();
            new RdfFbsSchemaGenerator(args[0]).generate(Paths.get(args[1]), additionalFbs);
            System.out.println("\nFBS schema generation complete.");
        }
        catch (Exception e)
        {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
