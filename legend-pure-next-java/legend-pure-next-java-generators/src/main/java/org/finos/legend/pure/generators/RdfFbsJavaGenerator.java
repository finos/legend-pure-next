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

import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.sorted.MutableSortedSet;
import org.eclipse.collections.impl.factory.Maps;
import org.eclipse.collections.impl.factory.SortedSets;
import org.finos.legend.pure.specification.generation.model.ClassInfo;
import org.finos.legend.pure.specification.generation.model.M3MetamodelReader;
import org.finos.legend.pure.specification.generation.model.M3Model;
import org.finos.legend.pure.specification.generation.model.PropertyInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.finos.legend.pure.specification.generation.model.ModelUtils.*;
import static org.finos.legend.pure.generators.JavaGeneratorUtils.*;

/**
 * Generates Java FlatBuffer artifacts from the M3 metamodel.
 *
 * <p>Produces:
 * <ul>
 *   <li>*FlatBufferWrapper.java — read-only wrappers implementing M3 interfaces</li>
 *   <li>GeneratedFlatBufferWriter.java — serializes interface objects to FlatBuffer</li>
 * </ul>
 */
public class RdfFbsJavaGenerator
{
    private static final String DEFAULT_OUTPUT_PACKAGE = "org.finos.legend.pure.m3.generated";

    private final M3Model m3Model;
    private final String outputPackage;
    /** Maps mainTaxonomy class name -> sorted list of all subtypes */
    private final MutableMap<String, MutableList<String>> mainTaxonomySubtypes;

    public RdfFbsJavaGenerator(String ttlPath)
    {
        this(new M3MetamodelReader(ttlPath).read(), DEFAULT_OUTPUT_PACKAGE);
    }

    public RdfFbsJavaGenerator(M3Model m3Model, String outputPackage)
    {
        this.m3Model = m3Model;
        this.outputPackage = outputPackage;
        this.mainTaxonomySubtypes = Maps.mutable.empty();
        m3Model.classInfoMap().valuesView().forEach(ci ->
        {
            if (isMainTaxonomy(ci))
            {
                MutableList<String> subtypes = collectAllSubtypes(m3Model, ci.name);
                if (subtypes.notEmpty())
                {
                    mainTaxonomySubtypes.put(ci.name, subtypes);
                }
            }
        });
    }

    /**
     * Check if a property's type is a mainTaxonomy class with known subtypes.
     */
    private boolean isMainTaxonomyType(String typeName)
    {
        return mainTaxonomySubtypes.containsKey(typeName);
    }

    /**
     * Get the sorted subtypes of a mainTaxonomy class.
     */
    private MutableList<String> getMainTaxonomySubtypes(String typeName)
    {
        return mainTaxonomySubtypes.get(typeName);
    }

    /**
     * Generate FlatBuffer wrapper classes and writer class.
     */
    public void generate(Path outputDir) throws IOException
    {
        Files.createDirectories(outputDir);

        generateFlatBufferWrappers(outputDir);
        generateFlatBufferWriter(outputDir);


        System.out.println("\nFlatBuffer Java generation complete.");
    }

    private String toJavaPackage(String purePackagePath)
    {
        return JavaGeneratorUtils.toJavaPackage(purePackagePath, outputPackage);
    }

    /**
     * FBS field name with Java keyword escaping to match flatc's Java target.
     * flatc escapes Java keywords with trailing underscore in generated accessors.
     */
    private static String toJavaFbsFieldName(String name)
    {
        return escapeJavaKeyword(toFbsFieldName(name));
    }

    // =========================================================================
    // Wrapper Generation
    // =========================================================================

    private void generateFlatBufferWrappers(Path outputDir) throws IOException
    {
        for (ClassInfo classInfo : m3Model.classInfoMap().valuesView())
        {
            String javaPackage = toJavaPackage(classInfo.packagePath);
            Path packageDir = outputDir.resolve(javaPackage.replace('.', '/'));
            Files.createDirectories(packageDir);

            String javaCode = generateWrapperCode(classInfo);
            Path filePath = packageDir.resolve(classInfo.name + "FlatBufferWrapper.java");
            Files.write(filePath, javaCode.getBytes(StandardCharsets.UTF_8));
            System.out.println("  Generated: " + classInfo.name + "FlatBufferWrapper.java");
        }
    }

    private String generateWrapperCode(ClassInfo classInfo)
    {
        StringBuilder sb = new StringBuilder();
        String thisPackage = toJavaPackage(classInfo.packagePath);

        MutableList<PropertyInfo> allProps = collectAllProperties(m3Model, classInfo);

        sb.append("// AUTO-GENERATED from m3.ttl - DO NOT EDIT\n");
        sb.append("package ").append(thisPackage).append(";\n\n");

        // Imports
        MutableSortedSet<String> imports = SortedSets.mutable.empty();
        imports.add("org.eclipse.collections.api.factory.Lists");
        imports.add("org.eclipse.collections.api.list.MutableList");
        imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs." + classInfo.name + "Def");
        imports.add("org.finos.legend.pure.m3.module.MetadataAccess");

        allProps.forEach(prop ->
        {
            addTypeImport(imports, prop.typeName, thisPackage);

            if (!hasStereotype(prop.stereotypes, "excluded"))
            {
                boolean isPointer = hasStereotype(prop.stereotypes, "pointer");
                boolean isClassType = m3Model.classInfoMap().containsKey(prop.typeName) && !isPointer && !"Any".equals(prop.typeName);

                // For mainTaxonomy union types, import all subtype Defs and Wrappers
                if (isMainTaxonomyType(prop.typeName) && prop.isMany)
                {
                    MutableList<String> subtypes = getMainTaxonomySubtypes(prop.typeName);
                    subtypes.forEach(subtype ->
                    {
                        imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs." + subtype + "Def");
                        ClassInfo subtypeInfo = m3Model.classInfoMap().get(subtype);
                        if (subtypeInfo != null)
                        {
                            String subtypePkg = toJavaPackage(subtypeInfo.packagePath);
                            if (!subtypePkg.equals(thisPackage))
                            {
                                imports.add(subtypePkg + "." + subtype);
                                imports.add(subtypePkg + "." + subtype + "FlatBufferWrapper");
                            }
                        }
                    });
                    // Also import base type Def and wrapper
                    imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs." + prop.typeName + "Def");
                    ClassInfo baseTypeInfo = m3Model.classInfoMap().get(prop.typeName);
                    if (baseTypeInfo != null)
                    {
                        String basePkg = toJavaPackage(baseTypeInfo.packagePath);
                        if (!basePkg.equals(thisPackage))
                        {
                            imports.add(basePkg + "." + prop.typeName + "FlatBufferWrapper");
                        }
                    }
                }
                else if (isClassType)
                {
                    imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs." + prop.typeName + "Def");
                    ClassInfo propTypeInfo = m3Model.classInfoMap().get(prop.typeName);
                    if (propTypeInfo != null)
                    {
                        String propPkg = toJavaPackage(propTypeInfo.packagePath);
                        if (!propPkg.equals(thisPackage))
                        {
                            imports.add(propPkg + "." + prop.typeName + "FlatBufferWrapper");
                        }
                    }
                }

                MutableList<String> nps = getNonPointerSubtypes(m3Model, prop);
                if (nps.notEmpty())
                {
                    imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs.PointerRef");
                    nps.forEach(subtype ->
                    {
                        imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs." + subtype + "Def");
                        ClassInfo subtypeInfo = m3Model.classInfoMap().get(subtype);
                        if (subtypeInfo != null)
                        {
                            String subtypePkg = toJavaPackage(subtypeInfo.packagePath);
                            if (!subtypePkg.equals(thisPackage))
                            {
                                imports.add(subtypePkg + "." + subtype + "FlatBufferWrapper");
                            }
                        }
                    });
                }
            }
        });

        imports.forEach(imp -> sb.append("import ").append(imp).append(";\n"));
        sb.append("\n");

        // Class definition
        sb.append("/**\n");
        sb.append(" * Read-only FlatBuffer-backed wrapper for ").append(classInfo.name).append(".\n");
        sb.append(" * Implements the generated interface, delegates to FlatBuffer accessors.\n");
        sb.append(" * Pointer properties resolve lazily through MetadataAccess.\n");
        sb.append(" */\n");
        sb.append("public class ").append(classInfo.name).append("FlatBufferWrapper");
        sb.append(" implements ").append(classInfo.name).append("\n{\n");

        // Fields
        sb.append("    private final ").append(classInfo.name).append("Def fb;\n");
        sb.append("    private final MetadataAccess resolver;\n\n");

        // Constructor
        sb.append("    public ").append(classInfo.name).append("FlatBufferWrapper(");
        sb.append(classInfo.name).append("Def fb, MetadataAccess resolver)\n");
        sb.append("    {\n");
        sb.append("        this.fb = fb;\n");
        sb.append("        this.resolver = resolver;\n");
        sb.append("    }\n\n");

        // Generate getters and setters for all properties
        allProps.forEach(prop ->
        {
            if (hasStereotype(prop.stereotypes, "excluded"))
            {
                String javaType = mapToJavaType(prop.typeName, prop.isMany);
                generateExcludedProperty(sb, classInfo, prop, javaType);
                return;
            }

            String javaType = mapToJavaType(prop.typeName, prop.isMany);
            String fbField = toJavaFbsFieldName(prop.name);
            String javaAccessor = toJavaAccessorName(fbField);
            boolean isPointer = hasStereotype(prop.stereotypes, "pointer");
            boolean isClassType = m3Model.classInfoMap().containsKey(prop.typeName) && !isPointer
                    && !"Any".equals(prop.typeName);
            boolean isEnumType = m3Model.enumInfoMap().containsKey(prop.typeName);

            // Getter
            sb.append("    @Override\n");
            sb.append("    public ").append(javaType).append(" _").append(prop.name).append("()\n");
            sb.append("    {\n");

            if (isPointer)
            {
                generatePointerGetter(sb, prop, javaType, javaAccessor);
            }
            else if (isEnumType)
            {
                generateEnumGetter(sb, prop, javaType, javaAccessor);
            }
            else if (isMainTaxonomyType(prop.typeName) && prop.isMany)
            {
                generateMainTaxonomyListGetter(sb, prop, javaType, javaAccessor);
            }
            else if (isClassType && prop.isMany)
            {
                generateOwnedListGetter(sb, prop, javaType, javaAccessor);
            }
            else if (isClassType)
            {
                generateOwnedSingleGetter(sb, prop, javaType, javaAccessor);
            }
            else if ("AtomicValue".equals(classInfo.name) && "value".equals(prop.name))
            {
                // Union getter for AtomicValue.value (PrimitiveValueDef or LambdaFunctionDef)
                sb.append("        byte vType = fb.valueType();\n");
                sb.append("        if (vType == 2)\n");
                sb.append("        {\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.LambdaFunctionDef ld = (org.finos.legend.pure.m3.module.pdbModule.fbs.LambdaFunctionDef) fb.value(new org.finos.legend.pure.m3.module.pdbModule.fbs.LambdaFunctionDef());\n");
                sb.append("            return ld != null ? new meta.pure.metamodel.function.LambdaFunctionFlatBufferWrapper(ld, resolver) : null;\n");
                sb.append("        }\n");
                sb.append("        if (vType == 1)\n");
                sb.append("        {\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.PrimitiveValueDef pv = (org.finos.legend.pure.m3.module.pdbModule.fbs.PrimitiveValueDef) fb.value(new org.finos.legend.pure.m3.module.pdbModule.fbs.PrimitiveValueDef());\n");
                sb.append("            if (pv == null) { return null; }\n");
                sb.append("            String raw = pv.val();\n");
                sb.append("            if (raw == null) { return null; }\n");
                sb.append("            // Use genericType to interpret the primitive value\n");
                sb.append("            meta.pure.metamodel.type.generics.GenericType gt = _genericType();\n");
                sb.append("            if (gt != null && gt._rawType() != null)\n");
                sb.append("            {\n");
                sb.append("                String typeName = (gt._rawType() instanceof meta.pure.metamodel.PackageableElement pe) ? pe._name() : null;\n");
                sb.append("                if (\"Integer\".equals(typeName)) { return Long.parseLong(raw); }\n");
                sb.append("                if (\"Float\".equals(typeName)) { return Double.parseDouble(raw); }\n");
                sb.append("                if (\"Boolean\".equals(typeName)) { return Boolean.parseBoolean(raw); }\n");
                sb.append("                if (typeName != null && !\"String\".equals(typeName) && !\"Date\".equals(typeName) && !\"DateTime\".equals(typeName) && !\"StrictDate\".equals(typeName) && !\"StrictTime\".equals(typeName) && !\"Decimal\".equals(typeName) && !\"Number\".equals(typeName))\n");
                sb.append("                {\n");
                sb.append("                    // Non-primitive type — resolve as element pointer\n");
                sb.append("                    meta.pure.metamodel.PackageableElement resolved = resolver.getElement(raw);\n");
                sb.append("                    if (resolved != null) { return resolved; }\n");
                sb.append("                    // Try enum value: format is enumerationPath.enumValueName\n");
                sb.append("                    int dotIdx = raw.lastIndexOf('.');\n");
                sb.append("                    if (dotIdx > 0)\n");
                sb.append("                    {\n");
                sb.append("                        String enumOwnerPath = raw.substring(0, dotIdx);\n");
                sb.append("                        String enumValueName = raw.substring(dotIdx + 1);\n");
                sb.append("                        meta.pure.metamodel.PackageableElement enumOwner = resolver.getElement(enumOwnerPath);\n");
                sb.append("                        if (enumOwner instanceof meta.pure.metamodel.type.Enumeration enumeration)\n");
                sb.append("                        {\n");
                sb.append("                            // Find the property matching this enum value name\n");
                sb.append("                            for (meta.pure.metamodel.function.property.Property p : enumeration._properties())\n");
                sb.append("                            {\n");
                sb.append("                                if (enumValueName.equals(p._name()))\n");
                sb.append("                                {\n");
                sb.append("                                    // Create a fresh EnumImpl with correct type\n");
                sb.append("                                    return new meta.pure.metamodel.type.EnumImpl()\n");
                sb.append("                                            ._name(enumValueName)\n");
                sb.append("                                            ._classifierGenericType(new meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl()._rawType((meta.pure.metamodel.type.Type) enumeration));\n");
                sb.append("                                }\n");
                sb.append("                            }\n");
                sb.append("                        }\n");
                sb.append("                    }\n");
                sb.append("                    throw new RuntimeException(\"Failed to resolve element pointer '\" + raw + \"' (type: \" + typeName + \")\");\n");
                sb.append("                }\n");
                sb.append("            }\n");
                sb.append("            return raw;\n");
                sb.append("        }\n");
                sb.append("        return null;\n");
            }
            else
            {
                generatePrimitiveGetter(sb, prop, javaType, javaAccessor);
            }

            sb.append("    }\n\n");

            // Setter (throws)
            generateReadOnlySetter(sb, classInfo, prop, javaType);
        });

        sb.append("}\n");
        return sb.toString();
    }

    private void generatePointerGetter(StringBuilder sb, PropertyInfo prop, String javaType, String fbField)
    {
        MutableList<String> nps = getNonPointerSubtypes(m3Model, prop);
        if (nps.notEmpty() && !prop.isMany)
        {
            String fbsField = toJavaFbsFieldName(prop.name);
            String accessor = toJavaAccessorName(fbsField);
            nps.forEachWithIndex((subtype, idx) ->
            {
                String wrapperType = subtype + "FlatBufferWrapper";
                int unionIdx = idx + 2;
                sb.append("        if (fb.").append(accessor).append("Type() == ").append(unionIdx).append(")\n");
                sb.append("        {\n");
                sb.append("            ").append(subtype).append("Def def = (").append(subtype).append("Def) fb.").append(accessor).append("(new ").append(subtype).append("Def());\n");
                sb.append("            return def != null ? new ").append(wrapperType).append("(def, resolver) : null;\n");
                sb.append("        }\n");
            });
            sb.append("        if (fb.").append(accessor).append("Type() == 1)\n");
            sb.append("        {\n");
            sb.append("            PointerRef ref = (PointerRef) fb.").append(accessor).append("(new PointerRef());\n");
            sb.append("            return ref != null && ref.path() != null ? (").append(javaType).append(") resolver.getElement(ref.path()) : null;\n");
            sb.append("        }\n");
            sb.append("        return null;\n");
        }
        else if (prop.isMany)
        {
            String innerType = mapToJavaType(prop.typeName, false);
            sb.append("        int len = fb.").append(fbField).append("Length();\n");
            sb.append("        MutableList<").append(boxType(innerType)).append("> result = Lists.mutable.ofInitialCapacity(len);\n");
            sb.append("        for (int i = 0; i < len; i++)\n");
            sb.append("        {\n");
            sb.append("            String path = fb.").append(fbField).append("(i);\n");

            if ("Stereotype".equals(prop.typeName))
            {
                // Stereotypes use composite paths: "profilePath.stereotypeName"
                sb.append("            if (path != null)\n");
                sb.append("            {\n");
                sb.append("                int dotIdx = path.lastIndexOf('.');\n");
                sb.append("                if (dotIdx > 0)\n");
                sb.append("                {\n");
                sb.append("                    meta.pure.metamodel.extension.Profile p = (meta.pure.metamodel.extension.Profile) resolver.getElement(path.substring(0, dotIdx));\n");
                sb.append("                    if (p != null)\n");
                sb.append("                    {\n");
                sb.append("                        String stName = path.substring(dotIdx + 1);\n");
                sb.append("                        meta.pure.metamodel.extension.Stereotype st = p._p_stereotypes().detect(s -> stName.equals(s._value()));\n");
                sb.append("                        if (st != null) { result.add(st); }\n");
                sb.append("                    }\n");
                sb.append("                }\n");
                sb.append("            }\n");
            }
            else
            {
                sb.append("            if (path != null) { result.add((").append(innerType).append(") resolver.getElement(path)); }\n");
            }

            sb.append("        }\n");
            sb.append("        return result;\n");
        }
        else
        {
            sb.append("        String path = fb.").append(fbField).append("();\n");
            sb.append("        if (path == null) { return null; }\n");
            sb.append("        int dotIdx = path.lastIndexOf('.');\n");
            sb.append("        if (dotIdx > 0 && path.indexOf(\"::\") < dotIdx)\n");
            sb.append("        {\n");
            sb.append("            // Property pointer: ownerPath.propertyName\n");
            sb.append("            String ownerPath = path.substring(0, dotIdx);\n");
            sb.append("            String propName = path.substring(dotIdx + 1);\n");
            sb.append("            meta.pure.metamodel.PackageableElement owner = resolver.getElement(ownerPath);\n");
            sb.append("            if (owner instanceof meta.pure.metamodel.SimplePropertyOwner spo)\n");
            sb.append("            {\n");
            sb.append("                meta.pure.metamodel.function.property.AbstractProperty found = spo._properties().detect(p -> propName.equals(p._name()));\n");
            sb.append("                if (found == null && owner instanceof meta.pure.metamodel.PropertyOwner po) { found = po._qualifiedProperties().detect(p -> propName.equals(p._name())); }\n");
            sb.append("                if (found != null) { return (").append(javaType).append(") found; }\n");
            sb.append("            }\n");
            sb.append("        }\n");
            sb.append("        return (").append(javaType).append(") resolver.getElement(path);\n");
        }
    }

    private void generateOwnedListGetter(StringBuilder sb, PropertyInfo prop, String javaType, String fbField)
    {
        String innerType = mapToJavaType(prop.typeName, false);
        String fbInnerType = prop.typeName + "Def";
        String wrapperType = prop.typeName + "FlatBufferWrapper";

        sb.append("        int len = fb.").append(fbField).append("Length();\n");
        sb.append("        MutableList<").append(boxType(innerType)).append("> result = Lists.mutable.ofInitialCapacity(len);\n");
        sb.append("        for (int i = 0; i < len; i++)\n");
        sb.append("        {\n");
        sb.append("            ").append(fbInnerType).append(" item = fb.").append(fbField).append("(i);\n");
        sb.append("            if (item != null) { result.add(new ").append(wrapperType).append("(item, resolver)); }\n");
        sb.append("        }\n");
        sb.append("        return result;\n");
    }

    /**
     * Generate a getter for a list property whose type is a mainTaxonomy class.
     * Uses the FlatBuffer union vector to dispatch to the correct concrete wrapper.
     */
    private void generateMainTaxonomyListGetter(StringBuilder sb, PropertyInfo prop, String javaType, String fbField)
    {
        String innerType = mapToJavaType(prop.typeName, false);
        MutableList<String> subtypes = getMainTaxonomySubtypes(prop.typeName);

        sb.append("        int len = fb.").append(fbField).append("Length();\n");
        sb.append("        MutableList<").append(boxType(innerType)).append("> result = Lists.mutable.ofInitialCapacity(len);\n");
        sb.append("        for (int i = 0; i < len; i++)\n");
        sb.append("        {\n");
        sb.append("            byte uType = fb.").append(fbField).append("Type(i);\n");
        sb.append("            switch (uType)\n");
        sb.append("            {\n");

        // Generate a case for each subtype — union indices are 1-based, matching sorted order
        subtypes.forEachWithIndex((subtype, idx) ->
        {
            int unionIdx = idx + 1;
            String defType = subtype + "Def";
            String wrapperType = subtype + "FlatBufferWrapper";
            sb.append("                case ").append(unionIdx).append(": { ");
            sb.append(defType).append(" d = (").append(defType).append(") fb.").append(fbField).append("(new ").append(defType).append("(), i); ");
            sb.append("if (d != null) result.add(new ").append(wrapperType).append("(d, resolver)); break; }\n");
        });

        // Fallback for the base type itself (last in union)
        int baseIdx = subtypes.size() + 1;
        sb.append("                case ").append(baseIdx).append(": { ");
        sb.append(prop.typeName).append("Def d = (").append(prop.typeName).append("Def) fb.").append(fbField).append("(new ").append(prop.typeName).append("Def(), i); ");
        sb.append("if (d != null) result.add(new ").append(prop.typeName).append("FlatBufferWrapper(d, resolver)); break; }\n");

        sb.append("                default: break;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return result;\n");
    }

    private void generateOwnedSingleGetter(StringBuilder sb, PropertyInfo prop, String javaType, String fbField)
    {
        String fbInnerType = prop.typeName + "Def";
        String wrapperType = prop.typeName + "FlatBufferWrapper";

        sb.append("        ").append(fbInnerType).append(" inner = fb.").append(fbField).append("();\n");
        sb.append("        return inner != null ? new ").append(wrapperType).append("(inner, resolver) : null;\n");
    }

    private void generatePrimitiveGetter(StringBuilder sb, PropertyInfo prop, String javaType, String fbField)
    {
        if (prop.isMany)
        {
            String innerType = mapPrimitiveType(prop.typeName);
            sb.append("        int len = fb.").append(fbField).append("Length();\n");
            sb.append("        MutableList<").append(boxType(innerType)).append("> result = Lists.mutable.ofInitialCapacity(len);\n");
            sb.append("        for (int i = 0; i < len; i++)\n");
            sb.append("        {\n");
            sb.append("            result.add(fb.").append(fbField).append("(i));\n");
            sb.append("        }\n");
            sb.append("        return result;\n");
        }
        else
        {
            sb.append("        return fb.").append(fbField).append("();\n");
        }
    }

    private void generateEnumGetter(StringBuilder sb, PropertyInfo prop, String javaType, String fbField)
    {
        String enumType = prop.typeName;
        if (prop.isMany)
        {
            sb.append("        int len = fb.").append(fbField).append("Length();\n");
            sb.append("        MutableList<").append(enumType).append("> result = Lists.mutable.ofInitialCapacity(len);\n");
            sb.append("        for (int i = 0; i < len; i++)\n");
            sb.append("        {\n");
            sb.append("            String v = fb.").append(fbField).append("(i);\n");
            sb.append("            if (v != null) { result.add(").append(enumType).append(".valueOf(v)); }\n");
            sb.append("        }\n");
            sb.append("        return result;\n");
        }
        else
        {
            sb.append("        String v = fb.").append(fbField).append("();\n");
            sb.append("        return v != null ? ").append(enumType).append(".valueOf(v) : null;\n");
        }
    }

    private void generateExcludedProperty(StringBuilder sb, ClassInfo classInfo, PropertyInfo prop, String javaType)
    {
        sb.append("    @Override\n");
        sb.append("    public ").append(javaType).append(" _").append(prop.name).append("()\n");
        sb.append("    {\n");
        if (prop.isMany)
        {
            sb.append("        return Lists.mutable.empty();\n");
        }
        else
        {
            sb.append("        return null;\n");
        }
        sb.append("    }\n\n");
        generateReadOnlySetter(sb, classInfo, prop, javaType);
    }

    private void generateReadOnlySetter(StringBuilder sb, ClassInfo classInfo, PropertyInfo prop, String javaType)
    {
        sb.append("    @Override\n");
        sb.append("    public ").append(classInfo.name).append(" _").append(prop.name).append("(");
        sb.append(javaType).append(" value)\n");
        sb.append("    {\n");
        sb.append("        throw new UnsupportedOperationException(\"Read-only FlatBuffer wrapper\");\n");
        sb.append("    }\n\n");
    }

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
        var enumInfo = m3Model.enumInfoMap().get(typeName);
        if (enumInfo != null)
        {
            String enumPackage = toJavaPackage(enumInfo.packagePath);
            if (!enumPackage.equals(thisPackage))
            {
                imports.add(enumPackage + "." + typeName);
            }
        }
    }

    // =========================================================================
    // Writer Generation
    // =========================================================================

    private void generateFlatBufferWriter(Path outputDir) throws IOException
    {
        Path packageDir = outputDir.resolve("org/finos/legend/pure/m3/pureLanguage");
        Files.createDirectories(packageDir);

        StringBuilder sb = new StringBuilder();
        sb.append("// AUTO-GENERATED from m3.ttl - DO NOT EDIT\n");
        sb.append("package org.finos.legend.pure.m3.pureLanguage;\n\n");
        sb.append("import com.google.flatbuffers.FlatBufferBuilder;\n");
        sb.append("import org.eclipse.collections.api.list.MutableList;\n");
        sb.append("import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;\n");
        sb.append("import org.finos.legend.pure.m3.module.pdbModule.fbs.*;\n\n");

        // Import all metamodel types
        MutableSortedSet<String> typeImports = SortedSets.mutable.empty();
        m3Model.classInfoMap().valuesView().forEach(ci ->
        {
            String pkg = toJavaPackage(ci.packagePath);
            typeImports.add(pkg + "." + ci.name);
        });
        typeImports.forEach(imp -> sb.append("import ").append(imp).append(";\n"));
        sb.append("\n");

        sb.append("/**\n");
        sb.append(" * Generated FlatBuffer writer for all M3 metamodel types.\n");
        sb.append(" */\n");
        sb.append("public final class GeneratedFlatBufferWriter\n{\n");
        sb.append("    private final FlatBufferBuilder builder;\n\n");
        sb.append("    public GeneratedFlatBufferWriter(FlatBufferBuilder builder)\n");
        sb.append("    {\n");
        sb.append("        this.builder = builder;\n");
        sb.append("    }\n\n");
        sb.append("    private static String pointerPath(Object obj)\n");
        sb.append("    {\n");
        sb.append("        if (obj instanceof PackageableElement pe) { return _PackageableElement.path(pe); }\n");
        sb.append("        if (obj instanceof AbstractProperty ap && ap._owner() instanceof PackageableElement owner) { return _PackageableElement.path(owner) + \".\" + ap._name(); }\n");
        sb.append("        if (obj instanceof Stereotype s && s._profile() != null) { return _PackageableElement.path(s._profile()) + \".\" + s._value(); }\n");
        sb.append("        if (obj instanceof Tag t && t._profile() != null) { return _PackageableElement.path(t._profile()) + \"#\" + t._value(); }\n");
        sb.append("        return String.valueOf(obj);\n");
        sb.append("    }\n\n");

        // Generate a write method for each class
        m3Model.classInfoMap().valuesView().toSortedListBy(ci -> ci.name).forEach(classInfo ->
        {
            MutableList<PropertyInfo> allProps = collectAllProperties(m3Model, classInfo);

            sb.append("    public int write").append(classInfo.name).append("(").append(classInfo.name).append(" obj)\n");
            sb.append("    {\n");

            // Pre-create string and nested offsets
            allProps.forEach(prop ->
            {
                if (hasStereotype(prop.stereotypes, "excluded"))
                {
                    return;
                }
                // AtomicValue.value is handled by special union code below
                if ("AtomicValue".equals(classInfo.name) && "value".equals(prop.name))
                {
                    return;
                }
                String fbField = toJavaFbsFieldName(prop.name);
                String javaAccessor = toJavaAccessorName(fbField);
                // Builder methods use unescaped FBS field name (flatc convention)
                String builderAccessor = toJavaAccessorName(toFbsFieldName(prop.name));
                boolean isPointer = hasStereotype(prop.stereotypes, "pointer");
                boolean isClassType = m3Model.classInfoMap().containsKey(prop.typeName) && !isPointer && !"Any".equals(prop.typeName);

                if (prop.isMany)
                {
                    sb.append("        // ").append(prop.name).append("\n");
                    if (isPointer || "String".equals(prop.typeName) || (!isClassType && !"Boolean".equals(prop.typeName) && !"Integer".equals(prop.typeName) && !"Float".equals(prop.typeName)))
                    {
                        sb.append("        int[] ").append(fbField).append("Offsets = null;\n");
                        sb.append("        if (obj._").append(prop.name).append("() != null && obj._").append(prop.name).append("().notEmpty())\n");
                        sb.append("        {\n");
                        sb.append("            var ").append(fbField).append("List = obj._").append(prop.name).append("();\n");
                        sb.append("            ").append(fbField).append("Offsets = new int[").append(fbField).append("List.size()];\n");
                        sb.append("            for (int i = 0; i < ").append(fbField).append("List.size(); i++)\n");
                        sb.append("            {\n");
                        sb.append("                ").append(fbField).append("Offsets[i] = builder.createString(pointerPath(").append(fbField).append("List.get(i)));\n");
                        sb.append("            }\n");
                        sb.append("        }\n");
                    }
                    else if (isMainTaxonomyType(prop.typeName) && isClassType)
                    {
                        MutableList<String> subtypes = getMainTaxonomySubtypes(prop.typeName);
                        sb.append("        int[] ").append(fbField).append("Offsets = null;\n");
                        sb.append("        byte[] ").append(fbField).append("Types = null;\n");
                        sb.append("        if (obj._").append(prop.name).append("() != null && obj._").append(prop.name).append("().notEmpty())\n");
                        sb.append("        {\n");
                        sb.append("            var ").append(fbField).append("List = obj._").append(prop.name).append("();\n");
                        sb.append("            ").append(fbField).append("Offsets = new int[").append(fbField).append("List.size()];\n");
                        sb.append("            ").append(fbField).append("Types = new byte[").append(fbField).append("List.size()];\n");
                        sb.append("            for (int i = 0; i < ").append(fbField).append("List.size(); i++)\n");
                        sb.append("            {\n");
                        sb.append("                var _item = ").append(fbField).append("List.get(i);\n");

                        // Generate instanceof chain from most-specific to least-specific
                        // Most-specific subtypes first (check leaf subtypes before their parents)
                        MutableList<String> orderedSubtypes = orderMostSpecificFirst(subtypes);
                        orderedSubtypes.forEachWithIndex((subtype, idx) ->
                        {
                            // Find the union index: sorted position in the union + 1 (1-based)
                            int unionIdx = subtypes.indexOf(subtype) + 1;
                            String keyword = idx == 0 ? "if" : "else if";
                            sb.append("                ").append(keyword).append(" (_item instanceof ").append(subtype).append(" _v").append(idx).append(")\n");
                            sb.append("                {\n");
                            sb.append("                    ").append(fbField).append("Offsets[i] = write").append(subtype).append("(_v").append(idx).append(");\n");
                            sb.append("                    ").append(fbField).append("Types[i] = ").append(unionIdx).append(";\n");
                            sb.append("                }\n");
                        });

                        // Fallback to base type
                        int baseIdx = subtypes.size() + 1;
                        sb.append("                else\n");
                        sb.append("                {\n");
                        sb.append("                    ").append(fbField).append("Offsets[i] = write").append(prop.typeName).append("((").append(prop.typeName).append(") _item);\n");
                        sb.append("                    ").append(fbField).append("Types[i] = ").append(baseIdx).append(";\n");
                        sb.append("                }\n");

                        sb.append("            }\n");
                        sb.append("        }\n");
                    }
                    else if (isClassType)
                    {
                        sb.append("        int[] ").append(fbField).append("Offsets = null;\n");
                        sb.append("        if (obj._").append(prop.name).append("() != null && obj._").append(prop.name).append("().notEmpty())\n");
                        sb.append("        {\n");
                        sb.append("            var ").append(fbField).append("List = obj._").append(prop.name).append("();\n");
                        sb.append("            ").append(fbField).append("Offsets = new int[").append(fbField).append("List.size()];\n");
                        sb.append("            for (int i = 0; i < ").append(fbField).append("List.size(); i++)\n");
                        sb.append("            {\n");
                        sb.append("                ").append(fbField).append("Offsets[i] = write").append(prop.typeName).append("((").append(prop.typeName).append(") ").append(fbField).append("List.get(i));\n");
                        sb.append("            }\n");
                        sb.append("        }\n");
                    }
                }
                else if ("String".equals(prop.typeName) || isPointer || "Decimal".equals(prop.typeName))
                {
                    MutableList<String> nps = getNonPointerSubtypes(m3Model, prop);
                    if (nps.notEmpty() && !prop.isMany)
                    {
                        sb.append("        // ").append(prop.name).append(" (union pointer)\n");
                        sb.append("        int ").append(fbField).append("Offset = 0;\n");
                        sb.append("        byte ").append(fbField).append("UnionType = 0;\n");
                        sb.append("        if (obj._").append(prop.name).append("() != null)\n");
                        sb.append("        {\n");
                        nps.forEachWithIndex((subtype, idx) ->
                        {
                            int unionIdx = idx + 2;
                            String keyword = idx == 0 ? "if" : "else if";
                            sb.append("            ").append(keyword).append(" (obj._").append(prop.name).append("() instanceof ").append(subtype).append(" _").append(fbField).append("Val)\n");
                            sb.append("            {\n");
                            sb.append("                ").append(fbField).append("Offset = write").append(subtype).append("(_").append(fbField).append("Val);\n");
                            sb.append("                ").append(fbField).append("UnionType = ").append(unionIdx).append(";\n");
                            sb.append("            }\n");
                        });
                        sb.append("            else\n");
                        sb.append("            {\n");
                        sb.append("                int pathOff = builder.createString(pointerPath(obj._").append(prop.name).append("()));\n");
                        sb.append("                PointerRef.startPointerRef(builder);\n");
                        sb.append("                PointerRef.addPath(builder, pathOff);\n");
                        sb.append("                ").append(fbField).append("Offset = PointerRef.endPointerRef(builder);\n");
                        sb.append("                ").append(fbField).append("UnionType = 1;\n");
                        sb.append("            }\n");
                        sb.append("        }\n");
                    }
                    else
                    {
                        sb.append("        int ").append(fbField).append("Offset = 0;\n");
                        if (isPointer)
                        {
                            sb.append("        if (obj._").append(prop.name).append("() != null) { ").append(fbField).append("Offset = builder.createString(pointerPath(obj._").append(prop.name).append("())); }\n");
                        }
                        else
                        {
                            sb.append("        if (obj._").append(prop.name).append("() != null) { ").append(fbField).append("Offset = builder.createString(obj._").append(prop.name).append("().toString()); }\n");
                        }
                    }
                }
                else if (isClassType)
                {
                    sb.append("        int ").append(fbField).append("Offset = 0;\n");
                    sb.append("        if (obj._").append(prop.name).append("() != null) { ").append(fbField).append("Offset = write").append(prop.typeName).append("((").append(prop.typeName).append(") obj._").append(prop.name).append("()); }\n");
                }
            });

            sb.append("\n");

            // Special case: AtomicValue value union (PrimitiveValueDef or LambdaFunctionDef)
            if ("AtomicValue".equals(classInfo.name))
            {
                sb.append("        // AtomicValue.value is a union: PrimitiveValueDef (1) or LambdaFunctionDef (2)\n");
                sb.append("        int valueUnionOffset = 0;\n");
                sb.append("        byte valueUnionType = 0;\n");
                sb.append("        if (obj._value() instanceof LambdaFunction lambdaVal)\n");
                sb.append("        {\n");
                sb.append("            valueUnionOffset = writeLambdaFunction(lambdaVal);\n");
                sb.append("            valueUnionType = 2;\n");
                sb.append("        }\n");
                sb.append("        else if (obj._value() instanceof meta.pure.metamodel.PackageableElement pe)\n");
                sb.append("        {\n");
                sb.append("            int primStrOff = builder.createString(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe));\n");
                sb.append("            PrimitiveValueDef.startPrimitiveValueDef(builder);\n");
                sb.append("            PrimitiveValueDef.addVal(builder, primStrOff);\n");
                sb.append("            valueUnionOffset = PrimitiveValueDef.endPrimitiveValueDef(builder);\n");
                sb.append("            valueUnionType = 1;\n");
                sb.append("        }\n");
                sb.append("        else if (obj._value() instanceof meta.pure.metamodel.type.Enum ev)\n");
                sb.append("        {\n");
                sb.append("            // Enum value: serialize as enumerationPath.enumName\n");
                sb.append("            meta.pure.metamodel.type.Type enumType = obj._genericType() != null ? obj._genericType()._rawType() : null;\n");
                sb.append("            String enumPath = (enumType instanceof meta.pure.metamodel.PackageableElement enumPe)\n");
                sb.append("                ? org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(enumPe) + \".\" + ev._name()\n");
                sb.append("                : ev._name();\n");
                sb.append("            int primStrOff = builder.createString(enumPath);\n");
                sb.append("            PrimitiveValueDef.startPrimitiveValueDef(builder);\n");
                sb.append("            PrimitiveValueDef.addVal(builder, primStrOff);\n");
                sb.append("            valueUnionOffset = PrimitiveValueDef.endPrimitiveValueDef(builder);\n");
                sb.append("            valueUnionType = 1;\n");
                sb.append("        }\n");
                sb.append("        else if (obj._value() != null)\n");
                sb.append("        {\n");
                sb.append("            int primStrOff = builder.createString(obj._value().toString());\n");
                sb.append("            PrimitiveValueDef.startPrimitiveValueDef(builder);\n");
                sb.append("            PrimitiveValueDef.addVal(builder, primStrOff);\n");
                sb.append("            valueUnionOffset = PrimitiveValueDef.endPrimitiveValueDef(builder);\n");
                sb.append("            valueUnionType = 1;\n");
                sb.append("        }\n");
            }

            // Create vectors
            allProps.forEach(prop ->
            {
                if (hasStereotype(prop.stereotypes, "excluded") || !prop.isMany)
                {
                    return;
                }
                String fbField = toJavaFbsFieldName(prop.name);
                String builderAccessor = toJavaAccessorName(toFbsFieldName(prop.name));

                if (isMainTaxonomyType(prop.typeName) && !hasStereotype(prop.stereotypes, "pointer"))
                {
                    // Union vector: create both type vector and value vector
                    sb.append("        int ").append(fbField).append("Vector = 0;\n");
                    sb.append("        int ").append(fbField).append("TypeVector = 0;\n");
                    sb.append("        if (").append(fbField).append("Offsets != null)\n");
                    sb.append("        {\n");
                    sb.append("            ").append(fbField).append("Vector = ").append(classInfo.name).append("Def.create").append(capitalize(builderAccessor)).append("Vector(builder, ").append(fbField).append("Offsets);\n");
                    sb.append("            ").append(fbField).append("TypeVector = ").append(classInfo.name).append("Def.create").append(capitalize(builderAccessor)).append("TypeVector(builder, ").append(fbField).append("Types);\n");
                    sb.append("        }\n");
                }
                else
                {
                    sb.append("        int ").append(fbField).append("Vector = 0;\n");
                    sb.append("        if (").append(fbField).append("Offsets != null) { ").append(fbField).append("Vector = ").append(classInfo.name).append("Def.create").append(capitalize(builderAccessor)).append("Vector(builder, ").append(fbField).append("Offsets); }\n");
                }
            });

            // Build the table
            sb.append("        ").append(classInfo.name).append("Def.start").append(classInfo.name).append("Def(builder);\n");

            allProps.forEach(prop ->
            {
                if (hasStereotype(prop.stereotypes, "excluded"))
                {
                    return;
                }
                // AtomicValue.value is handled by special union code below
                if ("AtomicValue".equals(classInfo.name) && "value".equals(prop.name))
                {
                    return;
                }
                String fbField = toJavaFbsFieldName(prop.name);
                // Builder methods use unescaped FBS field name (flatc convention)
                String builderAccessor = toJavaAccessorName(toFbsFieldName(prop.name));
                boolean isPointer = hasStereotype(prop.stereotypes, "pointer");
                boolean isClassType = m3Model.classInfoMap().containsKey(prop.typeName) && !isPointer && !"Any".equals(prop.typeName);

                if (prop.isMany)
                {
                    if (isMainTaxonomyType(prop.typeName) && !isPointer)
                    {
                        // Union vector: add both type vector and value vector
                        sb.append("        if (").append(fbField).append("Vector != 0)\n");
                        sb.append("        {\n");
                        sb.append("            ").append(classInfo.name).append("Def.add").append(capitalize(builderAccessor)).append("Type(builder, ").append(fbField).append("TypeVector);\n");
                        sb.append("            ").append(classInfo.name).append("Def.add").append(capitalize(builderAccessor)).append("(builder, ").append(fbField).append("Vector);\n");
                        sb.append("        }\n");
                    }
                    else
                    {
                        sb.append("        if (").append(fbField).append("Vector != 0) { ").append(classInfo.name).append("Def.add").append(capitalize(builderAccessor)).append("(builder, ").append(fbField).append("Vector); }\n");
                    }
                }
                else if ("String".equals(prop.typeName) || isPointer || "Decimal".equals(prop.typeName) || isClassType)
                {
                    MutableList<String> nps2 = getNonPointerSubtypes(m3Model, prop);
                    if (nps2.notEmpty() && !prop.isMany)
                    {
                        sb.append("        if (").append(fbField).append("Offset != 0) { ").append(classInfo.name).append("Def.add").append(capitalize(builderAccessor)).append("Type(builder, ").append(fbField).append("UnionType); ").append(classInfo.name).append("Def.add").append(capitalize(builderAccessor)).append("(builder, ").append(fbField).append("Offset); }\n");
                    }
                    else
                    {
                        sb.append("        if (").append(fbField).append("Offset != 0) { ").append(classInfo.name).append("Def.add").append(capitalize(builderAccessor)).append("(builder, ").append(fbField).append("Offset); }\n");
                    }
                }
                else if ("Boolean".equals(prop.typeName))
                {
                    sb.append("        if (obj._").append(prop.name).append("() != null) { ").append(classInfo.name).append("Def.add").append(capitalize(builderAccessor)).append("(builder, obj._").append(prop.name).append("()); }\n");
                }
                else if ("Integer".equals(prop.typeName))
                {
                    sb.append("        if (obj._").append(prop.name).append("() != null) { ").append(classInfo.name).append("Def.add").append(capitalize(builderAccessor)).append("(builder, obj._").append(prop.name).append("()); }\n");
                }
                else if ("Float".equals(prop.typeName))
                {
                    sb.append("        if (obj._").append(prop.name).append("() != null) { ").append(classInfo.name).append("Def.add").append(capitalize(builderAccessor)).append("(builder, obj._").append(prop.name).append("()); }\n");
                }
            });

            // Special case: AtomicValue value union
            if ("AtomicValue".equals(classInfo.name))
            {
                sb.append("        if (valueUnionOffset != 0) { AtomicValueDef.addValueType(builder, valueUnionType); AtomicValueDef.addValue(builder, valueUnionOffset); }\n");
            }

            sb.append("        return ").append(classInfo.name).append("Def.end").append(classInfo.name).append("Def(builder);\n");
            sb.append("    }\n\n");
        });

        sb.append("}\n");

        Path filePath = packageDir.resolve("GeneratedFlatBufferWriter.java");
        Files.write(filePath, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("  Generated: GeneratedFlatBufferWriter.java");
    }

    /**
     * Order subtypes so that most-specific (leaf) types come first in instanceof chains.
     * A type is "more specific" if it transitively extends another type in the list.
     */
    private MutableList<String> orderMostSpecificFirst(MutableList<String> subtypes)
    {
        return subtypes.toSortedListBy(name ->
        {
            // Count how many other subtypes this type extends (deeper = more specific)
            int depth = 0;
            ClassInfo ci = m3Model.classInfoMap().get(name);
            if (ci != null)
            {
                for (String gen : ci.generalizations)
                {
                    if (subtypes.contains(gen))
                    {
                        depth++;
                    }
                }
            }
            return -depth; // negative so deeper types come first
        });
    }


    // =========================================================================
    // Main Entry Point
    // =========================================================================

    /**
     * Usage: {@code RdfFbsJavaGenerator <input.ttl> <javaOutputDir>}
     */
    public static void main(String[] args)
    {
        try
        {
            if (args.length < 2)
            {
                System.out.println("Usage: RdfFbsJavaGenerator <input.ttl> <javaOutputDir>");
                System.exit(1);
            }

            System.out.println("M3 FlatBuffer Java Generator");
            System.out.println("==============================");
            System.out.println("Input:       " + args[0]);
            System.out.println("Java Output: " + args[1]);
            System.out.println();

            new RdfFbsJavaGenerator(args[0]).generate(Paths.get(args[1]));
        }
        catch (Exception e)
        {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
