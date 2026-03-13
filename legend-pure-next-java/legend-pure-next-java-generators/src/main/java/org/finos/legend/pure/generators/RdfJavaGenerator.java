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

package org.finos.legend.pure.generators;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.factory.SortedSets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.set.sorted.MutableSortedSet;
import org.finos.legend.pure.specification.generation.model.ClassInfo;
import org.finos.legend.pure.specification.generation.model.EnumInfo;
import org.finos.legend.pure.specification.generation.model.M3MetamodelReader;
import org.finos.legend.pure.specification.generation.model.M3Model;
import org.finos.legend.pure.specification.generation.model.PropertyInfo;
import org.finos.legend.pure.specification.generation.model.TaggedValueEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.finos.legend.pure.specification.generation.model.ModelUtils.*;
import static org.finos.legend.pure.generators.JavaGeneratorUtils.*;

/**
 * Generates Java classes from the Pure M3 metamodel.
 *
 * <p>Uses {@link M3MetamodelReader} to parse the RDF model into an {@link M3Model},
 * then produces Java interfaces and classes that mirror the metamodel structure.
 * This generator has no direct dependency on the RDF model.</p>
 *
 * Generated artifacts include:
 * - Interfaces for each Class with property accessors
 * - Impl classes for each leaf Class
 * - Enum types for each Enumeration
 * - Annotation types for stereotypes and tagged values
 */
public class RdfJavaGenerator
{
    private static final String DEFAULT_OUTPUT_PACKAGE = "org.finos.legend.pure.m3.generated";

    private final M3Model m3Model;
    private final String outputPackage;

    public RdfJavaGenerator(String ttlPath)
    {
        this(new M3MetamodelReader(ttlPath).read(), DEFAULT_OUTPUT_PACKAGE);
    }

    public RdfJavaGenerator(String ttlPath, String outputPackage)
    {
        this(new M3MetamodelReader(ttlPath).read(), outputPackage);
    }

    public RdfJavaGenerator(M3Model m3Model, String outputPackage)
    {
        this.m3Model = m3Model;
        this.outputPackage = outputPackage;
    }

    /**
     * Generate Java source files to the specified output directory.
     *
     * @param outputDir the output directory path as a string
     * @throws IOException if an I/O error occurs
     */
    public void generate(String outputDir) throws IOException
    {
        generate(Path.of(outputDir));
    }

    /**
     * Generate Java source files to the specified output directory.
     * Classes are organized into subdirectories based on their Pure package paths.
     *
     * @param outputDir the output directory path
     * @throws IOException if an I/O error occurs
     */
    public void generate(Path outputDir) throws IOException
    {
        System.out.println("Found " + m3Model.classInfoMap().size() + " classes, " + m3Model.enumInfoMap().size() + " enumerations");
        System.out.println("Found " + m3Model.classesWithSubtypes().size() + " classes with subtypes");

        Files.createDirectories(outputDir);

        generateAnnotations(outputDir);
        generateClassInterfaces(outputDir);
        generateClassImplementations(outputDir);
        generateEnums(outputDir);

        System.out.println("\nGeneration complete. Output: " + outputDir);
    }

    // =========================================================================
    // Package Mapping
    // =========================================================================

    private String toJavaPackage(String purePackagePath)
    {
        return JavaGeneratorUtils.toJavaPackage(purePackagePath, outputPackage);
    }

    // =========================================================================
    // Code Generation - Class Interfaces
    // =========================================================================

    private void generateClassInterfaces(Path outputDir) throws IOException
    {
        for (ClassInfo classInfo : m3Model.classInfoMap().valuesView())
        {
            String javaPackage = toJavaPackage(classInfo.packagePath);
            Path packageDir = outputDir.resolve(javaPackage.replace('.', '/'));
            Files.createDirectories(packageDir);

            String javaCode = generateClassInterfaceCode(classInfo);
            Path filePath = packageDir.resolve(classInfo.name + ".java");
            Files.write(filePath, javaCode.getBytes(StandardCharsets.UTF_8));
            System.out.println("  Generated: " + classInfo.name + ".java");
        }
    }

    private String generateClassInterfaceCode(ClassInfo classInfo)
    {
        StringBuilder sb = new StringBuilder();
        String thisPackage = toJavaPackage(classInfo.packagePath);

        // Package declaration
        sb.append("// AUTO-GENERATED from m3.ttl - DO NOT EDIT\n");
        sb.append("package ").append(thisPackage).append(";\n\n");

        // Collect imports from generalizations and properties
        MutableSortedSet<String> imports = SortedSets.mutable.empty();
        imports.add("org.eclipse.collections.api.list.MutableList");

        // Add Pure annotation imports if needed
        classInfo.stereotypes.forEach(stereo -> imports.add("pure.annotations." + toAnnotationClassName(bareName(stereo))));
        classInfo.taggedValues.forEach(tv -> imports.add("pure.annotations." + toAnnotationClassName(bareName(tv.tag))));

        // Check if any own properties have stereotypes or tagged values
        MutableList<PropertyInfo> properties = m3Model.propertiesByOwner().getIfAbsentValue(classInfo.name, Lists.mutable.empty());
        properties.forEach(prop ->
        {
            prop.stereotypes.forEach(stereo -> imports.add("pure.annotations." + toAnnotationClassName(bareName(stereo))));
            prop.taggedValues.forEach(tv -> imports.add("pure.annotations." + toAnnotationClassName(bareName(tv.tag))));
        });

        // Check if this class has subtypes (is a taxonomy interface)
        boolean hasSubtypes = m3Model.classesWithSubtypes().contains(classInfo.name);

        // Add imports for generalization types in different packages
        classInfo.generalizations.forEach(parent ->
        {
            ClassInfo parentInfo = m3Model.classInfoMap().get(parent);
            if (parentInfo != null)
            {
                String parentPackage = toJavaPackage(parentInfo.packagePath);
                if (!parentPackage.equals(thisPackage))
                {
                    imports.add(parentPackage + "." + parent);
                }
            }
        });

        // Add imports for property types in different packages
        properties.forEach(prop -> addTypeImport(imports, prop.typeName, thisPackage));

        // Compute extends list early so we can add Any import if needed
        MutableList<String> validExtends = classInfo.generalizations.select(m3Model.classInfoMap()::containsKey);

        // If no generalizations, extend Any (unless this IS Any)
        if (validExtends.isEmpty() && !"Any".equals(classInfo.name))
        {
            validExtends = Lists.mutable.with("Any");
            // Add import for Any if in different package
            ClassInfo anyInfo = m3Model.classInfoMap().get("Any");
            if (anyInfo != null)
            {
                String anyPackage = toJavaPackage(anyInfo.packagePath);
                if (!anyPackage.equals(thisPackage))
                {
                    imports.add(anyPackage + ".Any");
                }
            }
        }

        // Add JsonTypeInfo import for interfaces that have subtypes and non-base parents
        if (hasSubtypes && needsTypeAnnotations(classInfo))
        {
            imports.add("com.fasterxml.jackson.annotation.JsonTypeInfo");
        }

        // Write imports
        imports.forEach(imp -> sb.append("import ").append(imp).append(";\n"));
        sb.append("\n");

        // JavaDoc
        sb.append("/**\n");
        sb.append(" * Generated interface for M3 class: ").append(classInfo.name).append("\n");
        if (classInfo.packagePath != null)
        {
            sb.append(" * Pure package: ").append(classInfo.packagePath).append("\n");
        }
        sb.append(" */\n");

        // Add @JsonTypeInfo to interfaces for polymorphic serialization
        if (hasSubtypes && needsTypeAnnotations(classInfo))
        {
            sb.append("@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, ")
                .append("property = \"_type\")\n");
        }

        // Add Pure stereotype/tagged value annotations
        appendPureAnnotations(sb, classInfo.stereotypes, classInfo.taggedValues, "");

        // Interface declaration with extends
        sb.append("public interface ").append(classInfo.name);

        if (!validExtends.isEmpty())
        {
            sb.append(" extends ").append(validExtends.makeString(", "));
        }

        sb.append("\n{\n");

        // Generate property accessors (getters and fluent setters)
        properties.forEach(prop ->
        {
            String javaType = mapToJavaType(prop.typeName, prop.isMany);
            String getterName = "_" + prop.name;

            sb.append("    /**\n");
            sb.append("     * @return the ").append(prop.name).append(" property\n");
            sb.append("     */\n");
            appendPureAnnotations(sb, prop.stereotypes, prop.taggedValues, "    ");
            sb.append("    ").append(javaType).append(" ").append(getterName).append("();\n\n");

            sb.append("    /**\n");
            sb.append("     * @param value the ").append(prop.name).append(" property value\n");
            sb.append("     * @return this instance for fluent chaining\n");
            sb.append("     */\n");
            sb.append("    ").append(classInfo.name).append(" _").append(prop.name).append("(").append(javaType);
            sb.append(" value);\n\n");
        });

        sb.append("}\n");

        return sb.toString();
    }

    // =========================================================================
    // Code Generation - Class Implementations
    // =========================================================================

    private void generateClassImplementations(Path outputDir) throws IOException
    {
        for (ClassInfo classInfo : m3Model.classInfoMap().valuesView())
        {
            // Skip non-leaf types - only leaf classes are instantiable
            if (m3Model.classesWithSubtypes().contains(classInfo.name))
            {
                continue;
            }

            String javaPackage = toJavaPackage(classInfo.packagePath);
            Path packageDir = outputDir.resolve(javaPackage.replace('.', '/'));
            Files.createDirectories(packageDir);

            String javaCode = generateClassImplementationCode(classInfo);
            Path filePath = packageDir.resolve(classInfo.name + "Impl.java");
            Files.write(filePath, javaCode.getBytes(StandardCharsets.UTF_8));
            System.out.println("  Generated: " + classInfo.name + "Impl.java");
        }
    }

    private String generateClassImplementationCode(ClassInfo classInfo)
    {
        StringBuilder sb = new StringBuilder();
        String thisPackage = toJavaPackage(classInfo.packagePath);

        // Package declaration
        sb.append("// AUTO-GENERATED from m3.ttl - DO NOT EDIT\n");
        sb.append("package ").append(thisPackage).append(";\n\n");

        // Collect all properties (own + inherited)
        MutableList<PropertyInfo> allProperties = collectAllProperties(m3Model, classInfo);

        // Check if this class needs type annotations
        boolean needsTypeAnnotations = needsTypeAnnotations(classInfo);

        // Collect imports
        MutableSortedSet<String> imports = SortedSets.mutable.empty();
        imports.add("com.fasterxml.jackson.annotation.JsonProperty");
        if (needsTypeAnnotations)
        {
            imports.add("com.fasterxml.jackson.annotation.JsonTypeInfo");
            imports.add("com.fasterxml.jackson.annotation.JsonTypeName");
        }
        imports.add("org.eclipse.collections.api.factory.Lists");
        imports.add("org.eclipse.collections.api.list.MutableList");

        // Add Pure annotation imports if needed
        classInfo.stereotypes.forEach(stereo -> imports.add("pure.annotations." + toAnnotationClassName(bareName(stereo))));
        classInfo.taggedValues.forEach(tv -> imports.add("pure.annotations." + toAnnotationClassName(bareName(tv.tag))));
        allProperties.forEach(prop ->
        {
            prop.stereotypes.forEach(stereo -> imports.add("pure.annotations." + toAnnotationClassName(bareName(stereo))));
            prop.taggedValues.forEach(tv -> imports.add("pure.annotations." + toAnnotationClassName(bareName(tv.tag))));
        });

        // Add imports for property types in different packages
        allProperties.forEach(prop -> addTypeImport(imports, prop.typeName, thisPackage));

        // Write imports
        imports.forEach(imp -> sb.append("import ").append(imp).append(";\n"));
        sb.append("\n");

        // JavaDoc
        sb.append("/**\n");
        sb.append(" * Generated implementation for M3 class: ");
        sb.append(classInfo.name).append("\n");
        if (classInfo.packagePath != null)
        {
            sb.append(" * Pure package: ");
            sb.append(classInfo.packagePath).append("\n");
        }
        sb.append(" */\n");

        // Class declaration with @JsonTypeInfo and @JsonTypeName annotations
        if (needsTypeAnnotations)
        {
            sb.append("@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, ")
                .append("property = \"_type\")\n");
            sb.append("@JsonTypeName(\"")
                .append(classInfo.name).append("\")\n");
        }

        // Add Pure stereotype/tagged value annotations
        appendPureAnnotations(sb, classInfo.stereotypes, classInfo.taggedValues, "");

        sb.append("public class ").append(classInfo.name).append("Impl");
        sb.append(" implements ").append(classInfo.name);
        sb.append("\n{\n");

        // Generate private fields for all properties
        allProperties.forEach(prop ->
        {
            String javaType = mapToJavaType(prop.typeName, prop.isMany);
            String fieldName = escapeFieldName(prop.name);
            sb.append("    private ").append(javaType).append(" ");
            sb.append(fieldName);
            // Initialize list fields
            if (prop.isMany)
            {
                sb.append(" = Lists.mutable.empty()");
            }
            sb.append(";\n");
        });

        if (!allProperties.isEmpty())
        {
            sb.append("\n");
        }

        // Generate getters and fluent setters
        allProperties.forEach(prop ->
        {
            String javaType = mapToJavaType(prop.typeName, prop.isMany);
            String fieldName = escapeFieldName(prop.name);
            String getterName = "_" + prop.name;

            // Getter
            appendPureAnnotations(sb, prop.stereotypes, prop.taggedValues, "    ");
            sb.append("    @JsonProperty(\"")
                .append(prop.name).append("\")\n");
            sb.append("    @Override\n");
            sb.append("    public ").append(javaType).append(" ");
            sb.append(getterName).append("()\n");
            sb.append("    {\n");
            sb.append("        return this.").append(fieldName).append(";\n");
            sb.append("    }\n\n");

            // Fluent setter
            sb.append("    public ").append(classInfo.name).append("Impl _");
            sb.append(prop.name).append("(");
            sb.append(javaType).append(" value)\n");
            sb.append("    {\n");
            sb.append("        this.").append(fieldName).append(" = value;\n");
            sb.append("        return this;\n");
            sb.append("    }\n\n");
        });

        sb.append("}\n");

        return sb.toString();
    }

    // =========================================================================
    // Code Generation - Enums
    // =========================================================================

    private void generateEnums(Path outputDir) throws IOException
    {
        for (EnumInfo enumInfo : m3Model.enumInfoMap().valuesView())
        {
            String javaPackage = toJavaPackage(enumInfo.packagePath);
            Path packageDir = outputDir.resolve(javaPackage.replace('.', '/'));
            Files.createDirectories(packageDir);

            String javaCode = generateEnumCode(enumInfo);
            Path filePath = packageDir.resolve(enumInfo.name + ".java");
            Files.write(filePath, javaCode.getBytes(StandardCharsets.UTF_8));
            System.out.println("  Generated: " + enumInfo.name + ".java");
        }
    }

    private String generateEnumCode(EnumInfo enumInfo)
    {
        StringBuilder sb = new StringBuilder();
        String thisPackage = toJavaPackage(enumInfo.packagePath);

        // Package declaration
        sb.append("// AUTO-GENERATED from m3.ttl - DO NOT EDIT\n");
        sb.append("package ").append(thisPackage).append(";\n\n");

        // JavaDoc
        sb.append("/**\n");
        sb.append(" * Generated enum for M3 enumeration: ").append(enumInfo.name).append("\n");
        if (enumInfo.packagePath != null)
        {
            sb.append(" * Pure package: ").append(enumInfo.packagePath).append("\n");
        }
        sb.append(" */\n");

        // Enum declaration
        sb.append("public enum ").append(enumInfo.name).append("\n{\n");

        // Enum values
        if (!enumInfo.values.isEmpty())
        {
            for (int i = 0; i < enumInfo.values.size(); i++)
            {
                String value = enumInfo.values.get(i);
                String enumConstant = toEnumConstant(value);
                sb.append("    ").append(enumConstant);
                sb.append(i < enumInfo.values.size() - 1 ? "," : ";");
                sb.append("  // ").append(value).append("\n");
            }
        }

        sb.append("}\n");

        return sb.toString();
    }

    // =========================================================================
    // Code Generation - Annotations
    // =========================================================================

    /**
     * Append dedicated stereotype and tagged-value annotations to the StringBuilder.
     * Stereotypes become marker annotations (e.g. @MainTaxonomy).
     * Tags become single-value annotations (e.g. @NonPointerSubtypes("...")).
     */
    private void appendPureAnnotations(StringBuilder sb, MutableList<String> stereotypes, MutableList<TaggedValueEntry> taggedValues, String indent)
    {
        stereotypes.forEach(stereo -> sb.append(indent).append("@").append(toAnnotationClassName(bareName(stereo))).append("\n"));
        taggedValues.forEach(tv -> sb.append(indent).append("@").append(toAnnotationClassName(bareName(tv.tag)))
                .append("(\"")
                .append(tv.value.replace("\"", "\\\""))
                .append("\")\n"));
    }

    /**
     * Generate one annotation type per unique stereotype and per unique tag.
     */
    private void generateAnnotations(Path outputDir) throws IOException
    {
        Path annotationDir = outputDir.resolve("pure/annotations");
        Files.createDirectories(annotationDir);

        // Collect unique stereotype names
        MutableSortedSet<String> allStereotypes = SortedSets.mutable.empty();
        m3Model.classInfoMap().valuesView().forEach(ci -> ci.stereotypes.forEach(s -> allStereotypes.add(bareName(s))));
        m3Model.propertiesByOwner().valuesView().forEach(props -> props.forEach(pi -> pi.stereotypes.forEach(s -> allStereotypes.add(bareName(s)))));

        // Collect unique tag names
        MutableSortedSet<String> allTags = SortedSets.mutable.empty();
        m3Model.classInfoMap().valuesView().forEach(ci -> ci.taggedValues.forEach(tv -> allTags.add(bareName(tv.tag))));
        m3Model.propertiesByOwner().valuesView().forEach(props -> props.forEach(pi -> pi.taggedValues.forEach(tv -> allTags.add(bareName(tv.tag)))));

        // Generate one marker annotation per stereotype
        allStereotypes.forEach(stereo ->
        {
            String className = toAnnotationClassName(stereo);
            String code = """
                    // AUTO-GENERATED from m3.ttl - DO NOT EDIT
                    package pure.annotations;

                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;

                    @Retention(RetentionPolicy.RUNTIME)
                    @Target({ElementType.TYPE, ElementType.METHOD})
                    public @interface %s
                    {
                    }
                    """.formatted(className);
            writeFile(annotationDir.resolve(className + ".java"), code);
            System.out.println("  Generated annotation: " + className + ".java");
        });

        // Generate one value annotation per tag
        allTags.forEach(tag ->
        {
            String className = toAnnotationClassName(tag);
            String code = """
                    // AUTO-GENERATED from m3.ttl - DO NOT EDIT
                    package pure.annotations;

                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;

                    @Retention(RetentionPolicy.RUNTIME)
                    @Target({ElementType.TYPE, ElementType.METHOD})
                    public @interface %s
                    {
                        String value();
                    }
                    """.formatted(className);
            writeFile(annotationDir.resolve(className + ".java"), code);
            System.out.println("  Generated annotation: " + className + ".java");
        });
    }

    /**
     * Convert a camelCase stereotype/tag name to a PascalCase annotation class name.
     * e.g. "mainTaxonomy" -> "MainTaxonomy", "excluded" -> "Excluded"
     */
    private String toAnnotationClassName(String name)
    {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    private void addTypeImport(MutableSortedSet<String> imports, String typeName, String thisPackage)
    {
        if (typeName == null)
        {
            return;
        }
        ClassInfo typeInfo = m3Model.classInfoMap().get(typeName);
        if (typeInfo != null)
        {
            String typePackage = toJavaPackage(typeInfo.packagePath);
            if (!typePackage.equals(thisPackage))
            {
                imports.add(typePackage + "." + typeName);
            }
            return;
        }
        EnumInfo enumInfo = m3Model.enumInfoMap().get(typeName);
        if (enumInfo != null)
        {
            String enumPackage = toJavaPackage(enumInfo.packagePath);
            if (!enumPackage.equals(thisPackage))
            {
                imports.add(enumPackage + "." + typeName);
            }
        }
    }

    private String escapeFieldName(String name)
    {
        return escapeJavaKeyword(name);
    }

    private String toEnumConstant(String value)
    {
        // Convert camelCase or spaces to UPPER_SNAKE_CASE
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (Character.isUpperCase(c) && i > 0)
            {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    /**
     * Determines if a class needs @JsonTypeName / @JsonTypeInfo annotations.
     * A class needs them if it is a mainTaxonomy class or a subtype of one.
     */
    private boolean needsTypeAnnotations(ClassInfo classInfo)
    {
        return m3Model.mainTaxonomyClasses().contains(classInfo.name) || isSubtypeOfMainTaxonomy(classInfo.name, Sets.mutable.empty());
    }

    /**
     * Recursively check if a class is a subtype of any mainTaxonomy class.
     */
    private boolean isSubtypeOfMainTaxonomy(String className, MutableSet<String> visited)
    {
        if (visited.contains(className))
        {
            return false;
        }
        visited.add(className);

        ClassInfo info = m3Model.classInfoMap().get(className);
        if (info == null)
        {
            return false;
        }

        return info.generalizations.anySatisfy(parent ->
                m3Model.mainTaxonomyClasses().contains(parent) || isSubtypeOfMainTaxonomy(parent, visited));
    }

    /**
     * Write a string to a file, wrapping IOException as unchecked.
     */
    private void writeFile(Path path, String content)
    {
        try
        {
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    // =========================================================================
    // Main Entry Point
    // =========================================================================

    /**
     * Main method to run the Java generator from command line.
     *
     * <p>Usage: {@code RdfJavaGenerator <input.ttl> <output-dir> [package-name]}
     */
    public static void main(String[] args)
    {
        try
        {
            if (args.length < 2)
            {
                System.out.println("Usage: RdfJavaGenerator <input.ttl> <output-dir> [package-name]");
                System.exit(1);
            }

            String inputPath = args[0];
            String outputDir = args[1];
            String packageName = args.length > 2 ? args[2] : DEFAULT_OUTPUT_PACKAGE;

            System.out.println("M3 Java Class Generator");
            System.out.println("========================");
            System.out.println("Input:   " + inputPath);
            System.out.println("Output:  " + outputDir);
            System.out.println("Package: " + packageName);
            System.out.println();

            RdfJavaGenerator generator = new RdfJavaGenerator(inputPath, packageName);
            generator.generate(outputDir);
        }
        catch (Exception e)
        {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}

