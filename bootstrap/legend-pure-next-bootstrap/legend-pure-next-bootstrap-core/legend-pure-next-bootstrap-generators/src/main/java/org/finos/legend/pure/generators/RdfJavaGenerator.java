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

import static org.finos.legend.pure.generators.JavaGeneratorUtils.escapeJavaKeyword;
import static org.finos.legend.pure.generators.JavaGeneratorUtils.mapToJavaType;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.bareName;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.collectAllProperties;

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
    private final boolean isMetamodel;
    /**
     * Names of all classes carrying the {@code pointer} stereotype
     * ({@code TempCompilerPointer} and its descendants). Used by the impl
     * generator to decide which getters need a
     * {@link org.finos.legend.pure.m3.pointer.PointerAccessGuard#checkAccess}
     * preamble — properties inherited from a non-pointer parent on a pointer
     * class shouldn't be read until the pointer is dereferenced.
     *
     * <p>The guard preamble is always emitted; the throw itself is gated by
     * the runtime static-final {@code PointerAccessGuard.STRICT} flag,
     * which defaults false. Production CLI / IDE leaves the flag off and
     * the JIT folds the check away to nothing; surefire sets it true so
     * {@code mvn test} catches pointer misuse.</p>
     */
    private final java.util.Set<String> pointerClassNames;

    public RdfJavaGenerator(String ttlPath)
    {
        this(new M3MetamodelReader(ttlPath).read(), DEFAULT_OUTPUT_PACKAGE, false);
    }

    public RdfJavaGenerator(String ttlPath, String outputPackage)
    {
        this(new M3MetamodelReader(ttlPath).read(), outputPackage, false);
    }

    public RdfJavaGenerator(M3Model m3Model, String outputPackage, boolean isMetamodel)
    {
        this.m3Model = m3Model;
        this.outputPackage = outputPackage;
        this.isMetamodel = isMetamodel;
        this.pointerClassNames = new java.util.HashSet<>();
        m3Model.classInfoMap().valuesView().forEach(ci ->
        {
            if (ci.stereotypes.anySatisfy(s -> bareName(s).equals("pointer")))
            {
                this.pointerClassNames.add(ci.name);
            }
        });
    }

    /**
     * True if {@code className} is a {@code TempCompilerPointer} subtype
     * (i.e. carries the {@code pointer} stereotype).
     */
    private boolean isPointerClass(String className)
    {
        return this.pointerClassNames.contains(className);
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
        System.out.println("  Found " + m3Model.classInfoMap().size() + " classes, " + m3Model.enumInfoMap().size() + " enumerations");
        System.out.println("  Found " + m3Model.classesWithSubtypes().size() + " classes with subtypes");

        Files.createDirectories(outputDir);

        int annotations = generateAnnotations(outputDir);
        int interfaces = generateClassInterfaces(outputDir);
        int impls = generateClassImplementations(outputDir);
        int enums = generateEnumInterfaces(outputDir);

        System.out.println("  Generated " + interfaces + " interfaces, " + impls + " impls, " + enums + " enums, " + annotations + " annotations");
        System.out.println("    Generation complete. Output: " + outputDir);
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

    private int generateClassInterfaces(Path outputDir) throws IOException
    {
        int count = 0;
        for (ClassInfo classInfo : m3Model.classInfoMap().valuesView())
        {
            String javaPackage = toJavaPackage(classInfo.packagePath);
            Path packageDir = outputDir.resolve(javaPackage.replace('.', '/'));
            Files.createDirectories(packageDir);

            String javaCode = generateClassInterfaceCode(classInfo);
            Path filePath = packageDir.resolve(classInfo.name + ".java");
            Files.write(filePath, javaCode.getBytes(StandardCharsets.UTF_8));
            count++;
        }
        return count;
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

        // Add Pure stereotype/tagged value annotations (no imports needed, using FQN)
        // Check if any own properties have stereotypes or tagged values
        MutableList<PropertyInfo> properties = m3Model.propertiesByOwner().getIfAbsentValue(classInfo.name, Lists.mutable.empty());
        properties.forEach(prop ->
        {
        });

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

        // Generate _copy() declaration — only on the root Any interface
        // to avoid incompatible return types in diamond hierarchies
        if ("Any".equals(classInfo.name))
        {
            sb.append("    /**\n");
            sb.append("     * Create a shallow copy of this instance.\n");
            sb.append("     * @return a new instance with the same field values\n");
            sb.append("     */\n");
            sb.append("    ").append(classInfo.name).append(" _copy();\n\n");
        }

        sb.append("}\n");

        return sb.toString();
    }

    // =========================================================================
    // Code Generation - Class Implementations
    // =========================================================================

    private int generateClassImplementations(Path outputDir) throws IOException
    {
        int count = 0;
        for (ClassInfo classInfo : m3Model.classInfoMap().valuesView())
        {
            // Skip abstract types
            boolean isAbstract = classInfo.stereotypes.anySatisfy(stereo -> bareName(stereo).equals("abstract"));
            if (isAbstract)
            {
                continue;
            }

            String javaPackage = toJavaPackage(classInfo.packagePath);
            Path packageDir = outputDir.resolve(javaPackage.replace('.', '/'));
            Files.createDirectories(packageDir);

            String javaCode = generateClassImplementationCode(classInfo);
            Path filePath = packageDir.resolve(classInfo.name + "Impl.java");
            Files.write(filePath, javaCode.getBytes(StandardCharsets.UTF_8));
            count++;
        }
        return count;
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

        // Collect imports
        MutableSortedSet<String> imports = SortedSets.mutable.empty();
        imports.add("org.eclipse.collections.api.factory.Lists");
        imports.add("org.eclipse.collections.api.list.MutableList");

        // Add Pure stereotype/tagged value annotations (no imports needed, using FQN)
        allProperties.forEach(prop ->
        {
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

        // Add Pure stereotype/tagged value annotations
        appendPureAnnotations(sb, classInfo.stereotypes, classInfo.taggedValues, "");

        sb.append("public class ").append(classInfo.name).append("Impl");
        sb.append(" implements ").append(classInfo.name);
        sb.append("\n{\n");

        // Freeze support: once frozen, setters throw.
        sb.append("    private boolean frozen;\n\n");
        sb.append("    public void freeze() { this.frozen = true; }\n\n");
        sb.append("    public boolean isFrozen() { return this.frozen; }\n\n");
        sb.append("    private void checkNotFrozen()\n");
        sb.append("    {\n");
        sb.append("        if (this.frozen) throw new UnsupportedOperationException(\"").append(classInfo.name).append("Impl is frozen (immutable)\");\n");
        sb.append("    }\n\n");

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


        if (isMetamodel)
        {
            // No-arg constructor (for bootstrap/FlatBuffer internals)
            if (classInfo.typeParameters.isEmpty() && classInfo.multiplicityParameters.isEmpty())
            {
                sb.append("    @Deprecated\n");
            }
            sb.append("    public ").append(classInfo.name).append("Impl() {}\n\n");

            if (classInfo.typeParameters.isEmpty() && classInfo.multiplicityParameters.isEmpty())
            {
                // Non-parameterized types: take model, use pre-built PackageableGenericType from bootstrap
                sb.append("    public ").append(classInfo.name).append("Impl(org.finos.legend.pure.m3.module.MetadataAccess model)\n");
                sb.append("    {\n");
                sb.append("        this._classifierGenericType(\n");
                sb.append("            (meta.pure.metamodel.type.generics.GenericTypeValue) model.getElement(\"meta::pure::metamodel::type::generics::optimization::GenericType_").append(classInfo.name).append("\"));\n");
            }
            else
            {
                // Parameterized types: typeArguments/multiplicityArguments are instance-specific,
                // so the caller must provide the fully-built classifierGenericType
                sb.append("    public ").append(classInfo.name).append("Impl(meta.pure.metamodel.type.generics.GenericTypeValue classifierGenericType)\n");
                sb.append("    {\n");
                sb.append("        this._classifierGenericType(classifierGenericType);\n");
            }
            sb.append("    }\n\n");
        }
        else
        {
            sb.append("    public ").append(classInfo.name).append("Impl() {}\n\n");
        }

        // Pointer impls get a strict-mode guard on getters for properties
        // inherited from a non-pointer parent. See {@link PointerAccessGuard}.
        boolean classIsPointer = isPointerClass(classInfo.name);

        // Generate getters and fluent setters
        allProperties.forEach(prop ->
        {
            String javaType = mapToJavaType(prop.typeName, prop.isMany);
            String fieldName = escapeFieldName(prop.name);
            String getterName = "_" + prop.name;
            // Guard inherited (non-pointer-native) properties: e.g. on
            // {@code PropertyPointerImpl}, {@code _name()} (inherited from
            // {@code Property}) trips the guard, but {@code _path()} (from
            // {@code TempCompilerPointer}) and {@code _element()} (from
            // {@code PropertyPointer}) don't. The guard is dead code unless
            // strict mode is on (see {@link #pointerClassNames}).
            boolean needsGuard = classIsPointer && !isPointerClass(prop.ownerName);

            // Getter (returns unmodifiable list view when frozen)
            appendPureAnnotations(sb, prop.stereotypes, prop.taggedValues, "    ");
            sb.append("    @Override\n");
            sb.append("    public ").append(javaType).append(" ");
            sb.append(getterName).append("()\n");
            sb.append("    {\n");
            if (needsGuard)
            {
                sb.append("        org.finos.legend.pure.m3.pointer.PointerAccessGuard.checkAccess(\"")
                  .append(classInfo.name).append("\", \"").append(prop.name).append("\");\n");
            }
            if (prop.isMany)
            {
                sb.append("        return this.frozen && this.").append(fieldName).append(" != null ? this.").append(fieldName).append(".asUnmodifiable() : this.").append(fieldName).append(";\n");
            }
            else
            {
                sb.append("        return this.").append(fieldName).append(";\n");
            }
            sb.append("    }\n\n");

            // Fluent setter (throws if frozen)
            sb.append("    public ").append(classInfo.name).append("Impl _");
            sb.append(prop.name).append("(");
            sb.append(javaType).append(" value)\n");
            sb.append("    {\n");
            sb.append("        checkNotFrozen();\n");
            sb.append("        this.").append(fieldName).append(" = value;\n");
            sb.append("        return this;\n");
            sb.append("    }\n\n");
        });
        // Generate _copy() method — always returns an unfrozen (mutable) copy
        sb.append("    @Override\n");
        sb.append("    public ").append(classInfo.name).append("Impl _copy()\n");
        sb.append("    {\n");
        sb.append("        ").append(classInfo.name).append("Impl copy = new ").append(classInfo.name).append("Impl();\n");
        allProperties.forEach(prop ->
        {
            String fieldName = escapeFieldName(prop.name);
            sb.append("        copy.").append(fieldName).append(" = this.").append(fieldName).append(";\n");
        });
        sb.append("        return copy;\n");
        sb.append("    }\n\n");

        sb.append("}\n");

        return sb.toString();
    }

    // =========================================================================
    // Code Generation - Enums
    // =========================================================================

    private int generateEnumInterfaces(Path outputDir) throws IOException
    {
        int count = 0;
        for (EnumInfo enumInfo : m3Model.enumInfoMap().valuesView())
        {
            String javaPackage = toJavaPackage(enumInfo.packagePath);
            Path packageDir = outputDir.resolve(javaPackage.replace('.', '/'));
            Files.createDirectories(packageDir);

            // Generate interface
            String interfaceCode = generateEnumInterfaceCode(enumInfo);
            Path interfacePath = packageDir.resolve(enumInfo.name + ".java");
            Files.write(interfacePath, interfaceCode.getBytes(StandardCharsets.UTF_8));

            // Generate implementation
            String implCode = generateEnumImplCode(enumInfo);
            Path implPath = packageDir.resolve(enumInfo.name + "Impl.java");
            Files.write(implPath, implCode.getBytes(StandardCharsets.UTF_8));
            count++;
        }
        return count;
    }

    private String generateEnumInterfaceCode(EnumInfo enumInfo)
    {
        StringBuilder sb = new StringBuilder();
        String thisPackage = toJavaPackage(enumInfo.packagePath);

        sb.append("// AUTO-GENERATED from m3.ttl - DO NOT EDIT\n");
        sb.append("package ").append(thisPackage).append(";\n\n");
        sb.append("/**\n");
        sb.append(" * Generated interface for M3 enumeration: ").append(enumInfo.name).append("\n");
        if (enumInfo.packagePath != null)
        {
            sb.append(" * Pure package: ").append(enumInfo.packagePath).append("\n");
        }
        sb.append(" */\n");
        sb.append("public interface ").append(enumInfo.name).append(" extends meta.pure.metamodel.type.Enum\n{\n");
        sb.append("}\n");

        return sb.toString();
    }

    private String generateEnumImplCode(EnumInfo enumInfo)
    {
        StringBuilder sb = new StringBuilder();
        String thisPackage = toJavaPackage(enumInfo.packagePath);

        sb.append("// AUTO-GENERATED from m3.ttl - DO NOT EDIT\n");
        sb.append("package ").append(thisPackage).append(";\n\n");
        sb.append("/**\n");
        sb.append(" * Generated implementation for M3 enumeration: ").append(enumInfo.name).append("\n");
        sb.append(" * <p>Extends EnumImpl and implements the typed interface.</p>\n");
        sb.append(" */\n");
        sb.append("public class ").append(enumInfo.name).append("Impl extends meta.pure.metamodel.type.EnumImpl implements ").append(enumInfo.name).append("\n{\n");
        sb.append("    public ").append(enumInfo.name).append("Impl() {}\n\n");
        sb.append("    public ").append(enumInfo.name).append("Impl(org.finos.legend.pure.m3.module.MetadataAccess model)\n");
        sb.append("    {\n");
        sb.append("        super(model);\n");
        sb.append("    }\n");
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
        stereotypes.forEach(stereo -> sb.append(indent).append("@pure.annotations.").append(toAnnotationClassName(bareName(stereo))).append("\n"));
        taggedValues.forEach(tv -> sb.append(indent).append("@pure.annotations.").append(toAnnotationClassName(bareName(tv.tag)))
                .append("(\"")
                .append(tv.value.replace("\"", "\\\""))
                .append("\")\n"));
    }

    /**
     * Generate one annotation type per unique stereotype and per unique tag.
     */
    private int generateAnnotations(Path outputDir) throws IOException
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
        });

        return allStereotypes.size() + allTags.size();
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
        // Use the original case from the metamodel (e.g., "Composite", "Subset")
        // so that Java enum name() matches the metamodel element name.
        return value;
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
                System.out.println("Usage: RdfJavaGenerator <input.ttl> <output-dir> [package-name] [--metamodel]");
                System.exit(1);
            }

            String inputPath = args[0];
            String outputDir = args[1];
            String packageName = args.length > 2 && !args[2].startsWith("--") ? args[2] : DEFAULT_OUTPUT_PACKAGE;
            boolean metamodel = java.util.Arrays.asList(args).contains("--metamodel");

            System.out.println();
            System.out.println("M3 Java Class Generator (JAVA)");
            System.out.println("==============================");
            System.out.println("  Input:     " + inputPath);
            System.out.println("  Output:    " + outputDir);
            System.out.println("  Package:   " + packageName);
            System.out.println("  Metamodel: " + metamodel);

            // Java codegen targets either the source m3.ttl or the derived
            // m3_protocol.ttl. Skip validation for the derived model — its
            // @pointer/@maybePointer annotations have been consumed by the
            // protocol generator's type swap.
            boolean isDerivedProtocolModel = inputPath != null && inputPath.contains("protocol");
            M3MetamodelReader reader = isDerivedProtocolModel
                    ? M3MetamodelReader.forDerivedModel(inputPath, false)
                    : new M3MetamodelReader(inputPath);
            RdfJavaGenerator generator = new RdfJavaGenerator(
                    reader.read(), packageName, metamodel);
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

