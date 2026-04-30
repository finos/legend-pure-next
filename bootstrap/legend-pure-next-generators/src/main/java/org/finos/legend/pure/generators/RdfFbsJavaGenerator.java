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
import org.finos.legend.pure.specification.generation.fbs.FbsSchema;
import org.finos.legend.pure.specification.generation.fbs.FbsSchemaParser;
import org.finos.legend.pure.specification.generation.model.ClassInfo;
import org.finos.legend.pure.specification.generation.model.M3MetamodelReader;
import org.finos.legend.pure.specification.generation.model.M3Model;
import org.finos.legend.pure.specification.generation.model.PropertyInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.finos.legend.pure.generators.JavaGeneratorUtils.*;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.*;

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
    /**
     * Parsed view of {@code m3.fbs} — single source of truth for union member
     * ordering and byte discriminators shared between the writer codegen below,
     * the wrapper (reader) codegen, and the FlatBuffer reader. Treat as
     * authoritative: any byte emitted by codegen must come from a schema lookup.
     */
    private final FbsSchema schema;
    /** Maps mainTaxonomy class name -> sorted list of all subtypes */
    private final MutableMap<String, MutableList<String>> mainTaxonomySubtypes;

    public RdfFbsJavaGenerator(String ttlPath, String fbsPath)
    {
        this(new M3MetamodelReader(ttlPath).read(), parseSchema(fbsPath), DEFAULT_OUTPUT_PACKAGE);
    }

    public RdfFbsJavaGenerator(M3Model m3Model, FbsSchema schema, String outputPackage)
    {
        this.m3Model = m3Model;
        this.schema = schema;
        this.outputPackage = outputPackage;
        this.mainTaxonomySubtypes = Maps.mutable.empty();
        m3Model.classInfoMap().valuesView().forEach(ci ->
        {
            if (isMainTaxonomy(m3Model, ci))
            {
                MutableList<String> subtypes = collectAllSubtypes(m3Model, ci.name);
                if (subtypes.notEmpty())
                {
                    mainTaxonomySubtypes.put(ci.name, subtypes);
                }
            }
        });
    }

    private static FbsSchema parseSchema(String fbsPath)
    {
        try
        {
            return FbsSchemaParser.parse(Paths.get(fbsPath));
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to parse FBS schema at " + fbsPath, e);
        }
    }

    /**
     * Look up the union backing the given class+property. Returns null when
     * the field isn't union-typed in the schema.
     */
    private FbsSchema.FbsUnion unionFor(String className, String propName)
    {
        return schema.unionForField(className + "Def", toFbsFieldName(propName));
    }

    /**
     * Resolve the byte discriminator for {@code memberName} in the union
     * backing {@code className.propName}. Throws if the field isn't a union or
     * the union doesn't declare the member — both are codegen/schema drift bugs
     * we want to fail loudly on at build time.
     */
    private int unionByte(String className, String propName, String memberName)
    {
        FbsSchema.FbsUnion u = unionFor(className, propName);
        if (u == null)
        {
            throw new IllegalStateException(
                    "No union for " + className + "." + propName + " (looked up table=" + className + "Def, field=" + toFbsFieldName(propName) + "). "
                            + "Schema and codegen disagree about whether this property uses a union.");
        }
        return u.byteFor(memberName);
    }

    /** Strip the trailing "Def" from a schema union member name like "FooDef" → "Foo". */
    private static String stripDefSuffix(String memberName)
    {
        if (!memberName.endsWith("Def"))
        {
            throw new IllegalArgumentException("Expected Def-suffixed schema member, got: " + memberName);
        }
        return memberName.substring(0, memberName.length() - 3);
    }

    /**
     * Emit writer code for a single-valued union-typed property. Walks the
     * schema's union members in declaration order and emits the appropriate
     * branch for each — PointerRef / AncestorRef are special-cased; concrete
     * Def members become {@code instanceof}-dispatched calls to {@code writeX}.
     */
    private void emitSingleUnionWriter(StringBuilder sb, String className, PropertyInfo prop, String fbField)
    {
        FbsSchema.FbsUnion u = unionFor(className, prop.name);
        if (u == null)
        {
            throw new IllegalStateException("No FBS union for " + className + "." + prop.name + " — writer expected one.");
        }
        sb.append("        int ").append(fbField).append("Offset = 0;\n");
        sb.append("        byte ").append(fbField).append("UnionType = 0;\n");
        sb.append("        if (obj._").append(prop.name).append("() != null && obj._").append(prop.name).append("() != obj)\n");
        sb.append("        {\n");
        boolean priorBranch = false;
        for (String member : u.members())
        {
            int byteVal = u.byteFor(member);
            String prefix = priorBranch ? "            else if" : "            if";
            if ("PointerRef".equals(member))
            {
                sb.append(prefix).append(" (obj._").append(prop.name).append("() instanceof meta.pure.metamodel.PackageableElement || obj._").append(prop.name).append("() instanceof AbstractProperty || obj._").append(prop.name).append("() instanceof Stereotype || obj._").append(prop.name).append("() instanceof Tag)\n");
                sb.append("            {\n");
                sb.append("                ").append(fbField).append("Offset = writePointerRef(obj._").append(prop.name).append("());\n");
                sb.append("                ").append(fbField).append("UnionType = ").append(byteVal).append(";\n");
                sb.append("            }\n");
            }
            else if ("AncestorRef".equals(member))
            {
                sb.append(prefix).append(" (_writing.containsKey(obj._").append(prop.name).append("()))\n");
                sb.append("            {\n");
                sb.append("                ").append(fbField).append("Offset = writeAncestorRef(obj._").append(prop.name).append("());\n");
                sb.append("                ").append(fbField).append("UnionType = ").append(byteVal).append(";\n");
                sb.append("            }\n");
            }
            else
            {
                String subtype = stripDefSuffix(member);
                sb.append(prefix).append(" (obj._").append(prop.name).append("() instanceof ").append(subtype).append(" _sub_").append(subtype).append(")\n");
                sb.append("            {\n");
                sb.append("                ").append(fbField).append("Offset = write").append(subtype).append("(_sub_").append(subtype).append(");\n");
                sb.append("                ").append(fbField).append("UnionType = ").append(byteVal).append(";\n");
                sb.append("            }\n");
            }
            priorBranch = true;
        }
        sb.append("        }\n");
    }

    /**
     * Emit writer code for a many-valued union-typed property. Same shape as
     * the single-valued helper, but inside a {@code for} loop building offset
     * and type-byte arrays.
     */
    private void emitListUnionWriter(StringBuilder sb, String className, PropertyInfo prop, String fbField)
    {
        FbsSchema.FbsUnion u = unionFor(className, prop.name);
        if (u == null)
        {
            throw new IllegalStateException("No FBS union for " + className + "." + prop.name + " — writer expected one.");
        }
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
        boolean priorBranch = false;
        for (String member : u.members())
        {
            int byteVal = u.byteFor(member);
            String prefix = priorBranch ? "                else if" : "                if";
            if ("PointerRef".equals(member))
            {
                sb.append(prefix).append(" (_item instanceof meta.pure.metamodel.PackageableElement || _item instanceof AbstractProperty || _item instanceof Stereotype || _item instanceof Tag)\n");
                sb.append("                {\n");
                sb.append("                    ").append(fbField).append("Offsets[i] = writePointerRef(_item);\n");
                sb.append("                    ").append(fbField).append("Types[i] = ").append(byteVal).append(";\n");
                sb.append("                }\n");
            }
            else if ("AncestorRef".equals(member))
            {
                sb.append(prefix).append(" (_writing.containsKey(_item))\n");
                sb.append("                {\n");
                sb.append("                    ").append(fbField).append("Offsets[i] = writeAncestorRef(_item);\n");
                sb.append("                    ").append(fbField).append("Types[i] = ").append(byteVal).append(";\n");
                sb.append("                }\n");
            }
            else
            {
                String subtype = stripDefSuffix(member);
                sb.append(prefix).append(" (_item instanceof ").append(subtype).append(" _v_").append(subtype).append(")\n");
                sb.append("                {\n");
                sb.append("                    ").append(fbField).append("Offsets[i] = write").append(subtype).append("(_v_").append(subtype).append(");\n");
                sb.append("                    ").append(fbField).append("Types[i] = ").append(byteVal).append(";\n");
                sb.append("                }\n");
            }
            priorBranch = true;
        }
        sb.append("            }\n");
        sb.append("        }\n");
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
        generateFlatBufferWrapperInterface(outputDir);


        System.out.println("    FlatBuffer Java generation complete.");
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
    private void generateFlatBufferWrapperInterface(Path outputDir) throws IOException
    {
        Path packageDir = outputDir.resolve("org/finos/legend/pure/m3/pureLanguage");
        Files.createDirectories(packageDir);

        String code = """
                // AUTO-GENERATED - DO NOT EDIT
                package org.finos.legend.pure.m3.pureLanguage;

                /**
                 * Interface for FlatBuffer-backed wrapper objects that support parent-chain traversal.
                 * Used by AncestorRef to resolve cyclic back-references during deserialization.
                 */
                public interface FlatBufferWrapper
                {
                    Object _fbParent();
                }
                """;

        Path filePath = packageDir.resolve("FlatBufferWrapper.java");
        Files.write(filePath, code.getBytes(StandardCharsets.UTF_8));
    }

    private void generateFlatBufferWrappers(Path outputDir) throws IOException
    {
        for (ClassInfo classInfo : m3Model.classInfoMap().valuesView())
        {
            if (isAbstract(classInfo))
            {
                continue;
            }
            String javaPackage = toJavaPackage(classInfo.packagePath);
            Path packageDir = outputDir.resolve(javaPackage.replace('.', '/'));
            Files.createDirectories(packageDir);

            String javaCode = generateWrapperCode(classInfo);
            Path filePath = packageDir.resolve(classInfo.name + "FlatBufferWrapper.java");
            Files.write(filePath, javaCode.getBytes(StandardCharsets.UTF_8));
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
        imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs.IntegerValueDef");
        imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs.FloatValueDef");
        imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs.DecimalValueDef");
        imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs.BooleanValueDef");
        imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs.StringValueDef");

        allProps.forEach(prop ->
        {
            addTypeImport(imports, prop.typeName, thisPackage);

            if (hasStereotype(prop.stereotypes, "excluded")) { return; }

            boolean isPointer = hasStereotype(prop.stereotypes, "pointer");
            boolean isClassType = m3Model.classInfoMap().containsKey(prop.typeName) && !isPointer && !"Any".equals(prop.typeName);

            if (isPointer)
            {
                imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs.PointerRef");
            }

            // Concrete-table classType property — import its Def/Wrapper directly.
            if (!isMainTaxonomyType(prop.typeName) && isClassType)
            {
                boolean baseIsAbstract = isAbstract(m3Model.classInfoMap().get(prop.typeName));
                if (!baseIsAbstract)
                {
                    imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs." + prop.typeName + "Def");
                }
                ClassInfo propTypeInfo = m3Model.classInfoMap().get(prop.typeName);
                if (propTypeInfo != null && !baseIsAbstract)
                {
                    String propPkg = toJavaPackage(propTypeInfo.packagePath);
                    if (!propPkg.equals(thisPackage))
                    {
                        imports.add(propPkg + "." + prop.typeName + "FlatBufferWrapper");
                    }
                }
            }

            // Schema is the source of truth for which Def types this wrapper
            // dispatches over. Walk its union members instead of guessing from
            // the model — that keeps imports aligned with the cases we emit.
            FbsSchema.FbsUnion u = unionFor(classInfo.name, prop.name);
            if (u != null)
            {
                imports.add("org.finos.legend.pure.m3.pureLanguage.FlatBufferWrapper");
                for (String member : u.members())
                {
                    if ("PointerRef".equals(member))
                    {
                        imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs.PointerRef");
                    }
                    else if ("AncestorRef".equals(member))
                    {
                        imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs.AncestorRef");
                    }
                    else
                    {
                        // member ends in "Def"
                        String subtype = stripDefSuffix(member);
                        imports.add("org.finos.legend.pure.m3.module.pdbModule.fbs." + member);
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
                    }
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
        sb.append(" implements ").append(classInfo.name).append(", FlatBufferWrapper\n{\n");

        // Fields
        sb.append("    private final ").append(classInfo.name).append("Def fb;\n");
        sb.append("    private final MetadataAccess resolver;\n");
        sb.append("    private final Object _parent;\n");

        // Lazy cache fields for properties that create wrapper objects
        allProps.forEach(prop ->
        {
            if (hasStereotype(prop.stereotypes, "excluded") || ("AtomicValue".equals(classInfo.name) && "value".equals(prop.name))) { return; }
            boolean isPointer = hasStereotype(prop.stereotypes, "pointer");
            boolean isClassType = m3Model.classInfoMap().containsKey(prop.typeName) && !isPointer
                    && !"Any".equals(prop.typeName);
            boolean isEnumType = m3Model.enumInfoMap().containsKey(prop.typeName);
            boolean createsWrapper = isPointer || isClassType || isEnumType
                    || isMainTaxonomyType(prop.typeName)
                    || (getNonPointerSubtypes(m3Model, prop).notEmpty());
            if (createsWrapper)
            {
                if (prop.isMany)
                {
                    String innerType = mapToJavaType(prop.typeName, false);
                    sb.append("    private MutableList<").append(boxType(innerType)).append("> cached_").append(prop.name).append(";\n");
                }
                else
                {
                    String javaType = mapToJavaType(prop.typeName, false);
                    sb.append("    private ").append(javaType).append(" cached_").append(prop.name).append(";\n");
                }
            }
        });
        // Add cached_value field for AtomicValue's union value getter
        if ("AtomicValue".equals(classInfo.name))
        {
            sb.append("    private Object cached_value;\n");
        }
        sb.append("\n");

        // Constructor
        sb.append("    public ").append(classInfo.name).append("FlatBufferWrapper(");
        sb.append(classInfo.name).append("Def fb, MetadataAccess resolver, Object parent)\n");
        sb.append("    {\n");
        sb.append("        this.fb = fb;\n");
        sb.append("        this.resolver = resolver;\n");
        sb.append("        this._parent = parent;\n");
        sb.append("    }\n\n");

        // Convenience constructor without parent (for top-level elements)
        sb.append("    public ").append(classInfo.name).append("FlatBufferWrapper(");
        sb.append(classInfo.name).append("Def fb, MetadataAccess resolver)\n");
        sb.append("    {\n");
        sb.append("        this(fb, resolver, null);\n");
        sb.append("    }\n\n");

        // FlatBufferWrapper interface method
        sb.append("    @Override\n");
        sb.append("    public Object _fbParent() { return this._parent; }\n\n");

        // Generate getters and setters for all properties
        allProps.forEach(prop ->
        {
            if (hasStereotype(prop.stereotypes, "excluded"))
            {
                String javaType = mapToJavaType(prop.typeName, prop.isMany);
                generateExcludedProperty(sb, classInfo.name, prop, javaType);
                return;
            }

            String javaType = mapToJavaType(prop.typeName, prop.isMany);
            String fbField = toJavaFbsFieldName(prop.name);
            String javaAccessor = toJavaAccessorName(fbField);
            boolean isPointer = hasStereotype(prop.stereotypes, "pointer");
            boolean isClassType = m3Model.classInfoMap().containsKey(prop.typeName) && !isPointer
                    && !"Any".equals(prop.typeName);
            boolean isEnumType = m3Model.enumInfoMap().containsKey(prop.typeName);

            // Determine if this getter needs caching (creates wrapper objects)
            boolean needsCache = isPointer || isClassType || isEnumType
                    || isMainTaxonomyType(prop.typeName)
                    || (getNonPointerSubtypes(m3Model, prop).notEmpty());

            // Getter
            sb.append("    @Override\n");
            sb.append("    public ").append(javaType).append(" _").append(prop.name).append("()\n");
            sb.append("    {\n");

            if (needsCache)
            {
                sb.append("        if (cached_").append(prop.name).append(" != null) { return cached_").append(prop.name).append("; }\n");
            }

            String cacheField = needsCache ? "cached_" + prop.name : null;

            if (isPointer)
            {
                generatePointerGetter(sb, classInfo.name, prop, javaType, javaAccessor, cacheField);
            }
            else if (isEnumType)
            {
                generateEnumGetter(sb, prop, javaType, javaAccessor, cacheField);
            }
            else if ("AtomicValue".equals(classInfo.name) && "value".equals(prop.name))
            {
                int rByteInt = unionByte("AtomicValue", "value", "IntegerValueDef");
                int rByteFloat = unionByte("AtomicValue", "value", "FloatValueDef");
                int rByteBool = unionByte("AtomicValue", "value", "BooleanValueDef");
                int rByteStr = unionByte("AtomicValue", "value", "StringValueDef");
                int rByteLambda = unionByte("AtomicValue", "value", "LambdaFunctionDef");
                int rBytePtr = unionByte("AtomicValue", "value", "PointerRef");
                int rByteDecimal = unionByte("AtomicValue", "value", "DecimalValueDef");
                sb.append("        if (cached_value != null) { return cached_value; }\n");
                sb.append("        byte vType = fb.valueType();\n");
                sb.append("        if (vType == ").append(rByteLambda).append(")\n");
                sb.append("        {\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.LambdaFunctionDef ld = (org.finos.legend.pure.m3.module.pdbModule.fbs.LambdaFunctionDef) fb.value(new org.finos.legend.pure.m3.module.pdbModule.fbs.LambdaFunctionDef());\n");
                sb.append("            cached_value = ld != null ? new meta.pure.metamodel.function.LambdaFunctionFlatBufferWrapper(ld, resolver, this) : null;\n");
                sb.append("            return cached_value;\n");
                sb.append("        }\n");
                sb.append("        if (vType == ").append(rBytePtr).append(")\n");
                sb.append("        {\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.PointerRef pr = (org.finos.legend.pure.m3.module.pdbModule.fbs.PointerRef) fb.value(new org.finos.legend.pure.m3.module.pdbModule.fbs.PointerRef());\n");
                sb.append("            if (pr == null || pr.pathLength() == 0) { return null; }\n");
                sb.append("            cached_value = org.finos.legend.pure.m3.pureLanguage.PointerRefResolver.resolve(pr, resolver);\n");
                sb.append("            return cached_value;\n");
                sb.append("        }\n");
                sb.append("        if (vType == ").append(rByteInt).append(")\n");
                sb.append("        {\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.IntegerValueDef iv = (org.finos.legend.pure.m3.module.pdbModule.fbs.IntegerValueDef) fb.value(new org.finos.legend.pure.m3.module.pdbModule.fbs.IntegerValueDef());\n");
                sb.append("            if (iv == null) { return null; }\n");
                sb.append("            cached_value = iv.val();\n");
                sb.append("            return cached_value;\n");
                sb.append("        }\n");
                sb.append("        if (vType == ").append(rByteFloat).append(")\n");
                sb.append("        {\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.FloatValueDef fv = (org.finos.legend.pure.m3.module.pdbModule.fbs.FloatValueDef) fb.value(new org.finos.legend.pure.m3.module.pdbModule.fbs.FloatValueDef());\n");
                sb.append("            if (fv == null) { return null; }\n");
                sb.append("            cached_value = fv.val();\n");
                sb.append("            return cached_value;\n");
                sb.append("        }\n");
                sb.append("        if (vType == ").append(rByteBool).append(")\n");
                sb.append("        {\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.BooleanValueDef bv = (org.finos.legend.pure.m3.module.pdbModule.fbs.BooleanValueDef) fb.value(new org.finos.legend.pure.m3.module.pdbModule.fbs.BooleanValueDef());\n");
                sb.append("            if (bv == null) { return null; }\n");
                sb.append("            cached_value = bv.val();\n");
                sb.append("            return cached_value;\n");
                sb.append("        }\n");
                sb.append("        if (vType == ").append(rByteDecimal).append(")\n");
                sb.append("        {\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.DecimalValueDef dv = (org.finos.legend.pure.m3.module.pdbModule.fbs.DecimalValueDef) fb.value(new org.finos.legend.pure.m3.module.pdbModule.fbs.DecimalValueDef());\n");
                sb.append("            if (dv == null) { return null; }\n");
                sb.append("            cached_value = new java.math.BigDecimal(dv.val());\n");
                sb.append("            return cached_value;\n");
                sb.append("        }\n");
                sb.append("        if (vType == ").append(rByteStr).append(")\n");
                sb.append("        {\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.StringValueDef pv = (org.finos.legend.pure.m3.module.pdbModule.fbs.StringValueDef) fb.value(new org.finos.legend.pure.m3.module.pdbModule.fbs.StringValueDef());\n");
                sb.append("            if (pv == null) { return null; }\n");
                sb.append("            String raw = pv.val();\n");
                sb.append("            if (raw == null) { return null; }\n");
                sb.append("            // Use genericType to interpret the primitive value\n");
                sb.append("            meta.pure.metamodel.type.generics.GenericType gt = _genericType();\n");
                sb.append("            if (gt instanceof meta.pure.metamodel.type.generics.GenericTypeValue gtv && gtv._type() != null)\n");
                sb.append("            {\n");
                sb.append("                String typeName = (gtv._type() instanceof meta.pure.metamodel.PackageableElement pe) ? pe._name() : null;\n");
                sb.append("                if (typeName != null");
                for (String pType : m3Model.primitiveTypes())
                {
                    if (!"Integer".equals(pType) && !"Float".equals(pType) && !"Boolean".equals(pType))
                    {
                        sb.append(" && !\"").append(pType).append("\".equals(typeName)");
                    }
                }
                sb.append(")\n");
                sb.append("                {\n");
                sb.append("                    // Non-primitive type — resolve as element pointer\n");
                sb.append("                    meta.pure.metamodel.PackageableElement resolved = resolver.getElement(raw);\n");
                sb.append("                    if (resolved != null) { cached_value = resolved; return cached_value; }\n");
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
                sb.append("                                    // Create typed Enum instance via reflection\n");
                sb.append("                                    String implClass = enumOwnerPath.replace(\"::\", \".\") + \"Impl\";\n");
                sb.append("                                    meta.pure.metamodel.type.EnumImpl ei;\n");
                sb.append("                                    try { ei = (meta.pure.metamodel.type.EnumImpl) java.lang.Class.forName(implClass).getDeclaredConstructor().newInstance(); }\n");
                sb.append("                                    catch (Exception _ex) { ei = new meta.pure.metamodel.type.EnumImpl(); }\n");
                sb.append("                                    cached_value = ei._name(enumValueName)\n");
                sb.append("                                            ._classifierGenericType(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) enumeration, resolver));\n");
                sb.append("                                    return cached_value;\n");
                sb.append("                                }\n");
                sb.append("                            }\n");
                sb.append("                        }\n");
                sb.append("                    }\n");
                sb.append("                    throw new RuntimeException(\"Failed to resolve element pointer '\" + raw + \"' (type: \" + typeName + \")\");\n");
                sb.append("                }\n");
                sb.append("            }\n");
                sb.append("            cached_value = raw;\n");
                sb.append("            return cached_value;\n");
                sb.append("        }\n");
                sb.append("        return null;\n");
            }
            else if (isMainTaxonomyType(prop.typeName) && prop.isMany)
            {
                generateMainTaxonomyListGetter(sb, classInfo.name, prop, javaType, javaAccessor);
            }
            else if (isMainTaxonomyType(prop.typeName))
            {
                generateMainTaxonomySingleGetter(sb, classInfo.name, prop, javaType, javaAccessor, cacheField);
            }
            else if (isClassType && prop.isMany)
            {
                generateOwnedListGetter(sb, prop, javaType, javaAccessor);
            }
            else if (isClassType)
            {
                generateOwnedSingleGetter(sb, prop, javaType, javaAccessor);
            }
            else
            {
                generatePrimitiveGetter(sb, prop, javaType, javaAccessor);
            }

            // For cached getters, replace return statements with cache assignment
            if (needsCache)
            {
                if (prop.isMany)
                {
                    // List getters use "return result;" pattern
                    String returnResult = "        return result;\n";
                    String cacheAssign = "        cached_" + prop.name + " = result;\n        return cached_" + prop.name + ";\n";
                    int lastIdx = sb.lastIndexOf(returnResult);
                    if (lastIdx >= 0)
                    {
                        sb.replace(lastIdx, lastIdx + returnResult.length(), cacheAssign);
                    }
                }
                else
                {
                    // Single-valued getters use "return <expr>;" or "return new Wrapper(...);" patterns
                    // Replace all "return <expr>;" with "cached_X = <expr>; return cached_X;"
                    // but not "return null;" (null means absent, should not be cached)
                    String search = "        return ";
                    int searchFrom = sb.lastIndexOf("    {\n") + 1; // start of getter body
                    int idx;
                    while ((idx = sb.indexOf(search, searchFrom)) >= 0)
                    {
                        // Find the end of this return statement
                        int lineEnd = sb.indexOf("\n", idx);
                        String returnLine = sb.substring(idx, lineEnd + 1);
                        String returnExpr = returnLine.substring(search.length(), returnLine.length() - 2); // strip "return " prefix and ";\n" suffix
                        if (!"null".equals(returnExpr.trim()) && !cacheField.equals(returnExpr.trim()))
                        {
                            String replacement = "        " + cacheField + " = " + returnExpr + ";\n        return " + cacheField + ";\n";
                            sb.replace(idx, lineEnd + 1, replacement);
                            searchFrom = idx + replacement.length();
                        }
                        else
                        {
                            searchFrom = lineEnd + 1;
                        }
                    }
                }
            }

            sb.append("    }\n\n");

            // Setter (throws)
            generateReadOnlySetter(sb, classInfo.name, prop, javaType);
        });

        // Generate _copy() that materializes into a mutable Impl
        boolean isAbstract = classInfo.stereotypes.anySatisfy(stereo -> bareName(stereo).equals("abstract"));
        if (!isAbstract)
        {
            sb.append("    @Override\n");
            sb.append("    public ").append(classInfo.name).append("Impl _copy()\n");
            sb.append("    {\n");
            sb.append("        ").append(classInfo.name).append("Impl copy = new ").append(classInfo.name).append("Impl();\n");
            allProps.forEach(prop ->
                sb.append("        copy._").append(prop.name).append("(this._").append(prop.name).append("());\n"));
            sb.append("        return copy;\n");
            sb.append("    }\n\n");
        }
        else
        {
            sb.append("    @Override\n");
            sb.append("    public ").append(classInfo.name).append(" _copy()\n");
            sb.append("    {\n");
            sb.append("        throw new UnsupportedOperationException(\"Cannot copy abstract FlatBuffer wrapper\");\n");
            sb.append("    }\n\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private void generatePointerGetter(StringBuilder sb, String parentClass, PropertyInfo prop, String javaType, String fbField, String cacheField)
    {
        MutableList<String> nps = getNonPointerSubtypes(m3Model, prop);
        if (nps.notEmpty() && !prop.isMany)
        {
            generateMainTaxonomySingleGetter(sb, parentClass, prop, javaType, fbField, cacheField);
        }
        else if (nps.notEmpty() && prop.isMany)
        {
            generateMainTaxonomyListGetter(sb, parentClass, prop, javaType, fbField);
        }
        else if (prop.isMany)
        {
            String innerType = mapToJavaType(prop.typeName, false);
            sb.append("        int len = fb.").append(fbField).append("Length();\n");
            sb.append("        MutableList<").append(boxType(innerType)).append("> result = Lists.mutable.ofInitialCapacity(len);\n");
            sb.append("        for (int i = 0; i < len; i++)\n");
            sb.append("        {\n");
            sb.append("            PointerRef ref = fb.").append(fbField).append("(new PointerRef(), i);\n");
            sb.append("            if (ref != null && ref.pathLength() > 0) { Object _resolved = org.finos.legend.pure.m3.pureLanguage.PointerRefResolver.resolve(ref, resolver); if (_resolved != null) result.add((").append(innerType).append(") _resolved); }\n");

            sb.append("        }\n");
            sb.append("        return result;\n");
        }
        else
        {
            sb.append("        PointerRef ref = fb.").append(fbField).append("();\n");
            sb.append("        if (ref == null || ref.pathLength() == 0) { return null; }\n");
            sb.append("        return (").append(javaType).append(") org.finos.legend.pure.m3.pureLanguage.PointerRefResolver.resolve(ref, resolver);\n");
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
        sb.append("            if (item != null) { result.add(new ").append(wrapperType).append("(item, resolver, this)); }\n");
        sb.append("        }\n");
        sb.append("        return result;\n");
    }

    /**
     * Generate a getter for a list property whose type is a mainTaxonomy class.
     * Uses the FlatBuffer union vector to dispatch to the correct concrete wrapper.
     */
    private void generateMainTaxonomyListGetter(StringBuilder sb, String parentClass, PropertyInfo prop, String javaType, String fbField)
    {
        String innerType = mapToJavaType(prop.typeName, false);
        FbsSchema.FbsUnion u = unionFor(parentClass, prop.name);
        if (u == null)
        {
            throw new IllegalStateException("No FBS union for " + parentClass + "." + prop.name + " — wrapper list getter expected one.");
        }

        sb.append("        int len = fb.").append(fbField).append("Length();\n");
        sb.append("        MutableList<").append(boxType(innerType)).append("> result = Lists.mutable.ofInitialCapacity(len);\n");
        sb.append("        for (int i = 0; i < len; i++)\n");
        sb.append("        {\n");
        sb.append("            byte uType = fb.").append(unionTypeAccessor(fbField)).append("(i);\n");
        sb.append("            switch (uType)\n");
        sb.append("            {\n");

        for (String member : u.members())
        {
            int byteVal = u.byteFor(member);
            sb.append("                case ").append(byteVal).append(": { ");
            if ("PointerRef".equals(member))
            {
                sb.append("PointerRef ref = (PointerRef) fb.").append(fbField)
                  .append("(new PointerRef(), i); if (ref != null && ref.pathLength() > 0) { Object _resolved = org.finos.legend.pure.m3.pureLanguage.PointerRefResolver.resolve(ref, resolver); if (_resolved != null) result.add((").append(innerType)
                  .append(") _resolved); } break; }\n");
            }
            else if ("AncestorRef".equals(member))
            {
                sb.append("AncestorRef ar = (AncestorRef) fb.").append(fbField)
                  .append("(new AncestorRef(), i); if (ar != null) { Object t = this; for (int d = 0; d < ar.depth(); d++) { if (t instanceof FlatBufferWrapper w) t = w._fbParent(); else break; } result.add((").append(innerType)
                  .append(") t); } break; }\n");
            }
            else
            {
                String subtype = stripDefSuffix(member);
                String wrapperType = subtype + "FlatBufferWrapper";
                sb.append(member).append(" d = (").append(member).append(") fb.").append(fbField).append("(new ").append(member).append("(), i); ");
                sb.append("if (d != null) result.add(new ").append(wrapperType).append("(d, resolver, this)); break; }\n");
            }
        }

        sb.append("                default: break;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return result;\n");
    }

    /**
     * Generate a getter for a single-valued property whose type is a mainTaxonomy class.
     * Reads the FlatBuffer union discriminator to create the correct wrapper.
     */
    private void generateMainTaxonomySingleGetter(StringBuilder sb, String parentClass, PropertyInfo prop, String javaType, String fbField, String cacheField)
    {
        FbsSchema.FbsUnion u = unionFor(parentClass, prop.name);
        if (u == null)
        {
            throw new IllegalStateException(
                    "No FBS union for " + parentClass + "." + prop.name + " — wrapper getter expected one. "
                            + "If this property uses a concrete table, the wrapper should dispatch through generateOwnedSingleGetter instead.");
        }

        sb.append("        byte uType = fb.").append(unionTypeAccessor(fbField)).append("();\n");
        sb.append("        if (uType == 0)\n");
        sb.append("        {\n");
        sb.append("            if (this instanceof ").append(prop.typeName).append(") { return (").append(prop.typeName).append(") (java.lang.Object) this; }\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        switch (uType)\n");
        sb.append("        {\n");

        // Drive cases off the schema's union members — the schema is the
        // single source of truth for what's encodable in this slot. Using the
        // model's full subtype list here would emit cases for subtypes the
        // schema doesn't declare (e.g. inline-only ones excluded by
        // @nonPointerSubtypes), and the bytes wouldn't line up with the
        // reader.
        for (String member : u.members())
        {
            int byteVal = u.byteFor(member);
            sb.append("            case ").append(byteVal).append(": { ");
            if ("PointerRef".equals(member))
            {
                sb.append("PointerRef pr = (PointerRef) fb.").append(fbField).append("(new PointerRef()); ");
                sb.append("if (pr == null || pr.pathLength() == 0) { return null; } ");
                if (cacheField != null)
                {
                    sb.append(cacheField).append(" = (").append(javaType).append(") org.finos.legend.pure.m3.pureLanguage.PointerRefResolver.resolve(pr, resolver); return ").append(cacheField).append("; }\n");
                }
                else
                {
                    sb.append("return (").append(javaType).append(") org.finos.legend.pure.m3.pureLanguage.PointerRefResolver.resolve(pr, resolver); }\n");
                }
            }
            else if ("AncestorRef".equals(member))
            {
                sb.append("AncestorRef ar = (AncestorRef) fb.").append(fbField).append("(new AncestorRef()); ");
                sb.append("if (ar != null) { Object t = this; for (int d = 0; d < ar.depth(); d++) { if (t instanceof FlatBufferWrapper w) t = w._fbParent(); else break; } return (").append(javaType).append(") t; } return null; }\n");
            }
            else
            {
                String subtype = stripDefSuffix(member);
                String wrapperType = subtype + "FlatBufferWrapper";
                sb.append(member).append(" d = (").append(member).append(") fb.").append(fbField).append("(new ").append(member).append("()); ");
                if (cacheField != null)
                {
                    sb.append(cacheField).append(" = d != null ? new ").append(wrapperType).append("(d, resolver, this) : null; return ").append(cacheField).append("; }\n");
                }
                else
                {
                    sb.append("return d != null ? new ").append(wrapperType).append("(d, resolver, this) : null; }\n");
                }
            }
        }

        sb.append("            default: return null;\n");
        sb.append("        }\n");
    }


    /**
     * Generate a getter for a list property whose type has nonPointerSubtypes.
     * Uses the FlatBuffer union vector to dispatch to the correct concrete wrapper.
     * Union layout: PointerRef=1, then subtypes from index 2+.
     */
    private void generateNonPointerSubtypeListGetter(StringBuilder sb, String parentClass, PropertyInfo prop, String javaType, String fbField)
    {
        // Same shape as the mainTaxonomy list getter — both drive cases off
        // the schema's union members. They differ only in which path the
        // outer dispatch picks, so consolidating here would be reasonable
        // future cleanup.
        generateMainTaxonomyListGetter(sb, parentClass, prop, javaType, fbField);
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

    private void generateEnumGetter(StringBuilder sb, PropertyInfo prop, String javaType, String fbField, String cacheField)
    {
        String enumType = prop.typeName;
        var enumInfo = m3Model.enumInfoMap().get(enumType);
        String purePath = enumInfo.packagePath + "::" + enumInfo.name;
        if (prop.isMany)
        {
            sb.append("        int len = fb.").append(fbField).append("Length();\n");
            sb.append("        MutableList<").append(enumType).append("> result = Lists.mutable.ofInitialCapacity(len);\n");
            sb.append("        for (int i = 0; i < len; i++)\n");
            sb.append("        {\n");
            sb.append("            String v = fb.").append(fbField).append("(i);\n");
            sb.append("            if (v != null) { result.add((").append(enumType).append(") org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Enumeration.resolveEnumValue(\"").append(purePath).append("\", v, resolver)); }\n");
            sb.append("        }\n");
            if (cacheField != null)
            {
                sb.append("        ").append(cacheField).append(" = result;\n");
            }
            sb.append("        return result;\n");
        }
        else
        {
            sb.append("        String v = fb.").append(fbField).append("();\n");
            sb.append("        if (v == null) return null;\n");
            sb.append("        ").append(enumType).append(" resolved = (").append(enumType).append(") org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Enumeration.resolveEnumValue(\"").append(purePath).append("\", v, resolver);\n");
            if (cacheField != null)
            {
                sb.append("        ").append(cacheField).append(" = resolved;\n");
            }
            sb.append("        return resolved;\n");
        }
    }

    private void generateExcludedProperty(StringBuilder sb, String returnType, PropertyInfo prop, String javaType)
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
        generateReadOnlySetter(sb, returnType, prop, javaType);
    }

    private void generateReadOnlySetter(StringBuilder sb, String returnType, PropertyInfo prop, String javaType)
    {
        sb.append("    @Override\n");
        sb.append("    public ").append(returnType).append(" _").append(prop.name).append("(");
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
        sb.append("import java.util.IdentityHashMap;\n");
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
        sb.append("    private final FlatBufferBuilder builder;\n");
        sb.append("    private final IdentityHashMap<Object, Integer> _writing = new IdentityHashMap<>();\n");
        sb.append("    private int _depth = 0;\n\n");
        sb.append("    public GeneratedFlatBufferWriter(FlatBufferBuilder builder)\n");
        sb.append("    {\n");
        sb.append("        this.builder = builder;\n");
        sb.append("    }\n\n");
        sb.append("    private int writeAncestorRef(Object obj)\n");
        sb.append("    {\n");
        sb.append("        AncestorRef.startAncestorRef(builder);\n");
        sb.append("        AncestorRef.addDepth(builder, _depth - _writing.get(obj));\n");
        sb.append("        return AncestorRef.endAncestorRef(builder);\n");
        sb.append("    }\n\n");
        sb.append("    private static String pointerPath(Object obj)\n");
        sb.append("    {\n");
        sb.append("        if (obj instanceof PackageableElement pe) { return _PackageableElement.path(pe); }\n");
        sb.append("        if (obj instanceof meta.pure.metamodel.function.property.QualifiedProperty qp && qp._owner() instanceof PackageableElement owner) { return _PackageableElement.path(owner) + \".qp:\" + qp._name(); }\n");
        sb.append("        if (obj instanceof AbstractProperty ap && ap._owner() instanceof PackageableElement owner) { return _PackageableElement.path(owner) + \".\" + ap._name(); }\n");
        sb.append("        if (obj instanceof Stereotype s && s._profile() != null) { return _PackageableElement.path(s._profile()) + \".\" + s._value(); }\n");
        sb.append("        if (obj instanceof Tag t && t._profile() != null) { return _PackageableElement.path(t._profile()) + \"#\" + t._value(); }\n");
        sb.append("        return String.valueOf(obj);\n");
        sb.append("    }\n\n");
        // writePointerRef: typed pointer for union PointerRef fields
        sb.append("    private int writePointerRef(Object obj)\n");
        sb.append("    {\n");
        sb.append("        byte kind;\n");
        sb.append("        String[] segments;\n");
        sb.append("        if (obj instanceof meta.pure.metamodel.function.property.QualifiedProperty qp && qp._owner() instanceof PackageableElement owner)\n");
        sb.append("        {\n");
        sb.append("            kind = 2; // QualifiedProperty\n");
        sb.append("            segments = new String[]{_PackageableElement.path(owner), qp._name()};\n");
        sb.append("        }\n");
        sb.append("        else if (obj instanceof AbstractProperty ap && ap._owner() instanceof PackageableElement owner)\n");
        sb.append("        {\n");
        sb.append("            kind = 1; // Property\n");
        sb.append("            segments = new String[]{_PackageableElement.path(owner), ap._name()};\n");
        sb.append("        }\n");
        sb.append("        else if (obj instanceof Stereotype s && s._profile() != null)\n");
        sb.append("        {\n");
        sb.append("            kind = 3; // Stereotype\n");
        sb.append("            segments = new String[]{_PackageableElement.path(s._profile()), s._value()};\n");
        sb.append("        }\n");
        sb.append("        else if (obj instanceof Tag t && t._profile() != null)\n");
        sb.append("        {\n");
        sb.append("            kind = 4; // Tag\n");
        sb.append("            segments = new String[]{_PackageableElement.path(t._profile()), t._value()};\n");
        sb.append("        }\n");
        sb.append("        else if (obj instanceof PackageableElement pe)\n");
        sb.append("        {\n");
        sb.append("            kind = 0; // Element\n");
        sb.append("            segments = new String[]{_PackageableElement.path(pe)};\n");
        sb.append("        }\n");
        sb.append("        else\n");
        sb.append("        {\n");
        sb.append("            kind = 0;\n");
        sb.append("            segments = new String[]{String.valueOf(obj)};\n");
        sb.append("        }\n");
        sb.append("        int[] segOffsets = new int[segments.length];\n");
        sb.append("        for (int i = 0; i < segments.length; i++) { segOffsets[i] = builder.createString(segments[i]); }\n");
        sb.append("        int pathVector = PointerRef.createPathVector(builder, segOffsets);\n");
        sb.append("        PointerRef.startPointerRef(builder);\n");
        sb.append("        PointerRef.addKind(builder, kind);\n");
        sb.append("        PointerRef.addPath(builder, pathVector);\n");
        sb.append("        return PointerRef.endPointerRef(builder);\n");
        sb.append("    }\n\n");
        sb.append("    private static String sourceInfo(Object obj)\n");
        sb.append("    {\n");
        sb.append("        if (obj instanceof Any a && a._sourceInformation() != null)\n");
        sb.append("        {\n");
        sb.append("            meta.pure.metamodel.SourceInformation si = a._sourceInformation();\n");
        sb.append("            return \" at \" + si._sourceId() + \":\" + si._startLine() + \"c\" + si._startColumn();\n");
        sb.append("        }\n");
        sb.append("        return \"\";\n");
        sb.append("    }\n\n");

        // Generate a write method for each class
        m3Model.classInfoMap().valuesView().toSortedListBy(ci -> ci.name).forEach(classInfo ->
        {
            if (isAbstract(classInfo))
            {
                return;
            }

            MutableList<PropertyInfo> allProps = collectAllProperties(m3Model, classInfo);

            sb.append("    public int write").append(classInfo.name).append("(").append(classInfo.name).append(" obj)\n");
            sb.append("    {\n");
            sb.append("        if (_writing.containsKey(obj)) { return writeAncestorRef(obj); }\n");
            sb.append("        _writing.put(obj, _depth);\n");
            sb.append("        _depth++;\n");

            // Semantic validation for required properties
            allProps.forEach(prop ->
            {
                if (hasStereotype(prop.stereotypes, "excluded") || ("AtomicValue".equals(classInfo.name) && "value".equals(prop.name)))
                {
                    return;
                }

                if ("PureOne".equals(prop.multiplicity))
                {
                    sb.append("        if (obj._").append(prop.name).append("() == null) { throw new IllegalArgumentException(\"Validation error: Property '").append(prop.name).append("' on '").append(classInfo.name).append("' has multiplicity [1] but is null: \" + pointerPath(obj) + sourceInfo(obj)); }\n");
                }
                else if ("OneMany".equals(prop.multiplicity))
                {
                    sb.append("        if (obj._").append(prop.name).append("() == null || obj._").append(prop.name).append("().isEmpty()) { throw new IllegalArgumentException(\"Validation error: Property '").append(prop.name).append("' on '").append(classInfo.name).append("' has multiplicity [1..*] but is null or empty: \" + pointerPath(obj) + sourceInfo(obj)); }\n");
                }
            });


            // Pre-create string and nested offsets
            allProps.forEach(prop ->
            {
                if (hasStereotype(prop.stereotypes, "excluded") || ("AtomicValue".equals(classInfo.name) && "value".equals(prop.name)))
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
                boolean isEnumType = m3Model.enumInfoMap().containsKey(prop.typeName);

                if (prop.isMany)
                {
                    sb.append("        // ").append(prop.name).append("\n");
                    if (unionFor(classInfo.name, prop.name) != null)
                    {
                        // Schema declares a union for this list field — drive
                        // the dispatch entirely from its members. Covers both
                        // PropertyUnion-shape (PointerRef + AncestorRef + nps)
                        // and mainTaxonomy-shape (subtypes + AncestorRef).
                        emitListUnionWriter(sb, classInfo.name, prop, fbField);
                    }
                    else if (isEnumType)
                    {
                        sb.append("        int[] ").append(fbField).append("Offsets = null;\n");
                        sb.append("        if (obj._").append(prop.name).append("() != null && obj._").append(prop.name).append("().notEmpty())\n");
                        sb.append("        {\n");
                        sb.append("            var ").append(fbField).append("List = obj._").append(prop.name).append("();\n");
                        sb.append("            ").append(fbField).append("Offsets = new int[").append(fbField).append("List.size()];\n");
                        sb.append("            for (int i = 0; i < ").append(fbField).append("List.size(); i++)\n");
                        sb.append("            {\n");
                        sb.append("                ").append(fbField).append("Offsets[i] = builder.createString(((meta.pure.metamodel.type.Enum) ").append(fbField).append("List.get(i))._name());\n");
                        sb.append("            }\n");
                        sb.append("        }\n");
                    }
                    else if (isPointer)
                    {
                        // Pointer many-valued → [PointerRef]
                        sb.append("        int[] ").append(fbField).append("Offsets = null;\n");
                        sb.append("        if (obj._").append(prop.name).append("() != null && obj._").append(prop.name).append("().notEmpty())\n");
                        sb.append("        {\n");
                        sb.append("            var ").append(fbField).append("List = obj._").append(prop.name).append("();\n");
                        sb.append("            ").append(fbField).append("Offsets = new int[").append(fbField).append("List.size()];\n");
                        sb.append("            for (int i = 0; i < ").append(fbField).append("List.size(); i++)\n");
                        sb.append("            {\n");
                        sb.append("                ").append(fbField).append("Offsets[i] = writePointerRef(").append(fbField).append("List.get(i));\n");
                        sb.append("            }\n");
                        sb.append("        }\n");
                    }
                    else if ("String".equals(prop.typeName) || (!isClassType && !"Boolean".equals(prop.typeName) && !"Integer".equals(prop.typeName) && !"Float".equals(prop.typeName)))
                    {
                        sb.append("        int[] ").append(fbField).append("Offsets = null;\n");
                        sb.append("        if (obj._").append(prop.name).append("() != null && obj._").append(prop.name).append("().notEmpty())\n");
                        sb.append("        {\n");
                        sb.append("            var ").append(fbField).append("List = obj._").append(prop.name).append("();\n");
                        sb.append("            ").append(fbField).append("Offsets = new int[").append(fbField).append("List.size()];\n");
                        sb.append("            for (int i = 0; i < ").append(fbField).append("List.size(); i++)\n");
                        sb.append("            {\n");
                        sb.append("                ").append(fbField).append("Offsets[i] = builder.createString(String.valueOf(").append(fbField).append("List.get(i)));\n");
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
                else if ("String".equals(prop.typeName) || isPointer || "Decimal".equals(prop.typeName) || isEnumType)
                {
                    if (!prop.isMany && unionFor(classInfo.name, prop.name) != null)
                    {
                        // Schema says this single-valued field is union-typed.
                        emitSingleUnionWriter(sb, classInfo.name, prop, fbField);
                    }
                    else
                    {
                        sb.append("        int ").append(fbField).append("Offset = 0;\n");
                        if (isPointer)
                        {
                            sb.append("        if (obj._").append(prop.name).append("() != null) { ").append(fbField).append("Offset = writePointerRef(obj._").append(prop.name).append("()); }\n");
                        }
                        else if (isEnumType)
                        {
                            sb.append("        if (obj._").append(prop.name).append("() != null) { ").append(fbField).append("Offset = builder.createString(((meta.pure.metamodel.type.Enum) obj._").append(prop.name).append("())._name()); }\n");
                        }
                        else
                        {
                            sb.append("        if (obj._").append(prop.name).append("() != null) { ").append(fbField).append("Offset = builder.createString(obj._").append(prop.name).append("().toString()); }\n");
                        }
                    }
                }
                else if (isMainTaxonomyType(prop.typeName) && isClassType && !prop.isMany)
                {
                    // Single-valued union dispatch driven entirely by the schema.
                    // We enumerate the parsed union's members in declaration
                    // order, emitting one instanceof / cycle / PE branch per
                    // member. That guarantees the writer can never emit a byte
                    // the schema doesn't acknowledge.
                    emitSingleUnionWriter(sb, classInfo.name, prop, fbField);
                }
                else if (isClassType)
                {
                    sb.append("        int ").append(fbField).append("Offset = 0;\n");
                    sb.append("        if (obj._").append(prop.name).append("() != null && obj._").append(prop.name).append("() != obj) { ").append(fbField).append("Offset = write").append(prop.typeName).append("((").append(prop.typeName).append(") obj._").append(prop.name).append("()); }\n");
                }
            });

            sb.append("\n");

            // Special case: AtomicValue.value union (primitives, LambdaFunction, or PointerRef)
            if ("AtomicValue".equals(classInfo.name))
            {
                int byteInt = unionByte("AtomicValue", "value", "IntegerValueDef");
                int byteFloat = unionByte("AtomicValue", "value", "FloatValueDef");
                int byteBool = unionByte("AtomicValue", "value", "BooleanValueDef");
                int byteStr = unionByte("AtomicValue", "value", "StringValueDef");
                int byteLambda = unionByte("AtomicValue", "value", "LambdaFunctionDef");
                int bytePtr = unionByte("AtomicValue", "value", "PointerRef");
                int byteDecimal = unionByte("AtomicValue", "value", "DecimalValueDef");
                sb.append("        // AtomicValue.value is a union (bytes from m3.fbs)\n");
                sb.append("        int valueUnionOffset = 0;\n");
                sb.append("        byte valueUnionType = 0;\n");
                sb.append("        if (obj._value() instanceof meta.pure.metamodel.function.LambdaFunction lambdaVal)\n");
                sb.append("        {\n");
                sb.append("            valueUnionOffset = writeLambdaFunction(lambdaVal);\n");
                sb.append("            valueUnionType = ").append(byteLambda).append(";\n");
                sb.append("        }\n");
                sb.append("        else if (obj._value() instanceof meta.pure.metamodel.PackageableElement pe)\n");
                sb.append("        {\n");
                sb.append("            valueUnionOffset = writePointerRef(pe);\n");
                sb.append("            valueUnionType = ").append(bytePtr).append(";\n");
                sb.append("        }\n");
                sb.append("        else if (obj._value() instanceof meta.pure.metamodel.type.Enum ev)\n");
                sb.append("        {\n");
                sb.append("            meta.pure.metamodel.type.Type enumType = (obj._genericType() instanceof meta.pure.metamodel.type.generics.GenericTypeValue _gtv) ? _gtv._type() : null;\n");
                sb.append("            String enumPath = (enumType instanceof meta.pure.metamodel.PackageableElement enumPe)\n");
                sb.append("                ? org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(enumPe) + \".\" + ev._name()\n");
                sb.append("                : ev._name();\n");
                sb.append("            int primStrOff = builder.createString(enumPath);\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.StringValueDef.startStringValueDef(builder);\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.StringValueDef.addVal(builder, primStrOff);\n");
                sb.append("            valueUnionOffset = org.finos.legend.pure.m3.module.pdbModule.fbs.StringValueDef.endStringValueDef(builder);\n");
                sb.append("            valueUnionType = ").append(byteStr).append(";\n");
                sb.append("        }\n");
                sb.append("        else if (obj._value() instanceof Long v)\n");
                sb.append("        {\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.IntegerValueDef.startIntegerValueDef(builder);\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.IntegerValueDef.addVal(builder, v);\n");
                sb.append("            valueUnionOffset = org.finos.legend.pure.m3.module.pdbModule.fbs.IntegerValueDef.endIntegerValueDef(builder);\n");
                sb.append("            valueUnionType = ").append(byteInt).append(";\n");
                sb.append("        }\n");
                sb.append("        else if (obj._value() instanceof java.math.BigDecimal bd)\n");
                sb.append("        {\n");
                sb.append("            int decStrOff = builder.createString(bd.toPlainString());\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.DecimalValueDef.startDecimalValueDef(builder);\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.DecimalValueDef.addVal(builder, decStrOff);\n");
                sb.append("            valueUnionOffset = org.finos.legend.pure.m3.module.pdbModule.fbs.DecimalValueDef.endDecimalValueDef(builder);\n");
                sb.append("            valueUnionType = ").append(byteDecimal).append(";\n");
                sb.append("        }\n");
                sb.append("        else if (obj._value() instanceof Double v)\n");
                sb.append("        {\n");
                sb.append("            boolean isDecimal = false;\n");
                sb.append("            if (obj._genericType() instanceof meta.pure.metamodel.type.generics.GenericTypeValue gtv && gtv._type() instanceof meta.pure.metamodel.PackageableElement tpe)\n");
                sb.append("            {\n");
                sb.append("                isDecimal = \"Decimal\".equals(tpe._name());\n");
                sb.append("            }\n");
                sb.append("            if (isDecimal)\n");
                sb.append("            {\n");
                sb.append("                int decStrOff = builder.createString(v.toString());\n");
                sb.append("                org.finos.legend.pure.m3.module.pdbModule.fbs.DecimalValueDef.startDecimalValueDef(builder);\n");
                sb.append("                org.finos.legend.pure.m3.module.pdbModule.fbs.DecimalValueDef.addVal(builder, decStrOff);\n");
                sb.append("                valueUnionOffset = org.finos.legend.pure.m3.module.pdbModule.fbs.DecimalValueDef.endDecimalValueDef(builder);\n");
                sb.append("                valueUnionType = ").append(byteDecimal).append(";\n");
                sb.append("            }\n");
                sb.append("            else\n");
                sb.append("            {\n");
                sb.append("                org.finos.legend.pure.m3.module.pdbModule.fbs.FloatValueDef.startFloatValueDef(builder);\n");
                sb.append("                org.finos.legend.pure.m3.module.pdbModule.fbs.FloatValueDef.addVal(builder, v);\n");
                sb.append("                valueUnionOffset = org.finos.legend.pure.m3.module.pdbModule.fbs.FloatValueDef.endFloatValueDef(builder);\n");
                sb.append("                valueUnionType = ").append(byteFloat).append(";\n");
                sb.append("            }\n");
                sb.append("        }\n");
                sb.append("        else if (obj._value() instanceof Boolean v)\n");
                sb.append("        {\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.BooleanValueDef.startBooleanValueDef(builder);\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.BooleanValueDef.addVal(builder, v);\n");
                sb.append("            valueUnionOffset = org.finos.legend.pure.m3.module.pdbModule.fbs.BooleanValueDef.endBooleanValueDef(builder);\n");
                sb.append("            valueUnionType = ").append(byteBool).append(";\n");
                sb.append("        }\n");
                sb.append("        else if (obj._value() != null)\n");
                sb.append("        {\n");
                sb.append("            int strOff = builder.createString(obj._value().toString());\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.StringValueDef.startStringValueDef(builder);\n");
                sb.append("            org.finos.legend.pure.m3.module.pdbModule.fbs.StringValueDef.addVal(builder, strOff);\n");
                sb.append("            valueUnionOffset = org.finos.legend.pure.m3.module.pdbModule.fbs.StringValueDef.endStringValueDef(builder);\n");
                sb.append("            valueUnionType = ").append(byteStr).append(";\n");
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
                else if (getNonPointerSubtypes(m3Model, prop).notEmpty())
                {
                    // pointer+nonPointerSubtypes union vector: create both type vector and value vector
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
                if (hasStereotype(prop.stereotypes, "excluded") || ("AtomicValue".equals(classInfo.name) && "value".equals(prop.name)))
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
                boolean isEnumType = m3Model.enumInfoMap().containsKey(prop.typeName);

                if (prop.isMany)
                {
                    if ((isMainTaxonomyType(prop.typeName) && !isPointer) || getNonPointerSubtypes(m3Model, prop).notEmpty())
                    {
                        // Union vector: add both type vector and value vector
                        sb.append("        if (").append(fbField).append("Vector != 0)\n");
                        sb.append("        {\n");
                        sb.append("            ").append(classInfo.name).append("Def.add").append(unionTypeAccessor(capitalize(builderAccessor))).append("(builder, ").append(fbField).append("TypeVector);\n");
                        sb.append("            ").append(classInfo.name).append("Def.add").append(capitalize(builderAccessor)).append("(builder, ").append(fbField).append("Vector);\n");
                        sb.append("        }\n");
                    }
                    else
                    {
                        sb.append("        if (").append(fbField).append("Vector != 0) { ").append(classInfo.name).append("Def.add").append(capitalize(builderAccessor)).append("(builder, ").append(fbField).append("Vector); }\n");
                    }
                }
                else if ("String".equals(prop.typeName) || isPointer || "Decimal".equals(prop.typeName) || isClassType || isEnumType)
                {
                    MutableList<String> nps2 = getNonPointerSubtypes(m3Model, prop);
                    if ((nps2.notEmpty() && !prop.isMany) || (isMainTaxonomyType(prop.typeName) && isClassType && !prop.isMany))
                    {
                        sb.append("        if (").append(fbField).append("Offset != 0) { ").append(classInfo.name).append("Def.add").append(unionTypeAccessor(capitalize(builderAccessor))).append("(builder, ").append(fbField).append("UnionType); ").append(classInfo.name).append("Def.add").append(capitalize(builderAccessor)).append("(builder, ").append(fbField).append("Offset); }\n");
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

            sb.append("        int _result = ").append(classInfo.name).append("Def.end").append(classInfo.name).append("Def(builder);\n");
            sb.append("        _depth--;\n");
            sb.append("        _writing.remove(obj);\n");
            sb.append("        return _result;\n");
            sb.append("    }\n\n");
        });

        sb.append("}\n");

        Path filePath = packageDir.resolve("GeneratedFlatBufferWriter.java");
        Files.write(filePath, sb.toString().getBytes(StandardCharsets.UTF_8));
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
     * Usage: {@code RdfFbsJavaGenerator <input.ttl> <input.fbs> <javaOutputDir>}
     *
     * <p>{@code input.fbs} must be the same schema {@code flatc} consumed —
     * both writer and reader codegen derive byte values from it, so any drift
     * relative to the {@code *Def} reader classes would corrupt round-trips.</p>
     */
    public static void main(String[] args)
    {
        try
        {
            if (args.length < 3)
            {
                System.out.println("Usage: RdfFbsJavaGenerator <input.ttl> <input.fbs> <javaOutputDir>");
                System.exit(1);
            }

            System.out.println("M3 FlatBuffer Java Generator");
            System.out.println("==============================");
            System.out.println("  TTL:         " + args[0]);
            System.out.println("  FBS:         " + args[1]);
            System.out.println("  Java Output: " + args[2]);

            new RdfFbsJavaGenerator(args[0], args[1]).generate(Paths.get(args[2]));
        }
        catch (Exception e)
        {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
