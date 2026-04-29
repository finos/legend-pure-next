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

package org.finos.legend.pure.truffle.codegen;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.function.property.Property;
import meta.pure.metamodel.multiplicity.ConcreteMultiplicity;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.relationship.Generalization;
import meta.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;

import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Generates Java Interface + Impl classes from Pure Class definitions in a PDB.
 *
 * <p>Reads Class elements from a compiled PDB (core.pdb, compiler.pdb) and
 * emits Java source files following the same pattern as RdfJavaGenerator:
 * underscore-prefixed getters/setters, fluent setters, MutableList for [*],
 * _copy() for shallow cloning.</p>
 *
 * <p>Skips classes whose Impl already exists on the classpath (metamodel
 * classes generated from m3.ttl by RdfJavaGenerator).</p>
 *
 * <p>This is temporary Java code — will eventually be rewritten in Pure.</p>
 */
public class PdbJavaGenerator
{
    private final PDBModule pdb;
    private final Path outputDir;
    private FbsSchema fbsSchema;

    // Collected model
    private final MutableMap<String, ClassRecord> classes = Maps.mutable.empty();
    private final MutableMap<String, EnumRecord> enums = Maps.mutable.empty();
    private final MutableSet<String> classesWithSubtypes = Sets.mutable.empty();

    public PdbJavaGenerator(PDBModule pdb, Path outputDir)
    {
        this.pdb = pdb;
        this.outputDir = outputDir;
    }

    public void setFbsSchema(FbsSchema schema)
    {
        this.fbsSchema = schema;
    }

    /** Collect classes/enums from an additional PDB module (for multi-module). */
    public void collectFrom(PDBModule module)
    {
        collectModel(module);
    }

    /** Generate all collected classes/enums. Call after all collectFrom() calls. */
    public void generateAll() throws IOException
    {
        computeSubtypes();
        generate();
    }

    public void generate() throws IOException
    {
        if (pdb != null)
        {
            collectModel(pdb);
        }
        computeSubtypes();

        System.out.println("  Collected " + classes.size() + " classes, " + enums.size() + " enums");

        Files.createDirectories(outputDir);

        int interfaces = 0;
        int impls = 0;
        int enumCount = 0;
        int skipped = 0;

        for (ClassRecord cr : classes.valuesView())
        {
            Path packageDir = outputDir.resolve(toJavaPackage(cr.packagePath).replace('.', '/'));
            Files.createDirectories(packageDir);

            Files.write(packageDir.resolve(cr.name + ".java"),
                    generateInterface(cr).getBytes(StandardCharsets.UTF_8));
            interfaces++;

            if (!cr.isAbstract)
            {
                Files.write(packageDir.resolve(cr.name + "Impl.java"),
                        generateImpl(cr).getBytes(StandardCharsets.UTF_8));
                impls++;

                // Generate FlatBuffer wrapper if FBS schema is available
                if (fbsSchema != null)
                {
                    String wrapperCode = generateFlatBufferWrapper(cr);
                    if (wrapperCode != null)
                    {
                        Files.write(packageDir.resolve(cr.name + "FlatBufferWrapper.java"),
                                wrapperCode.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        }

        for (EnumRecord er : enums.valuesView())
        {
            Path packageDir = outputDir.resolve(toJavaPackage(er.packagePath).replace('.', '/'));
            Files.createDirectories(packageDir);

            Files.write(packageDir.resolve(er.name + ".java"),
                    generateEnumInterface(er).getBytes(StandardCharsets.UTF_8));
            Files.write(packageDir.resolve(er.name + "Enum.java"),
                    generateEnumClass(er).getBytes(StandardCharsets.UTF_8));
            enumCount++;
        }

        System.out.println("PdbJavaGenerator: " + interfaces + " interfaces, "
                + impls + " impls, " + enumCount + " enums generated ("
                + skipped + " skipped, already on classpath) to " + outputDir);
    }

    // =========================================================================
    // Model collection
    // =========================================================================

    private void collectModel(PDBModule module)
    {
        Set<String> paths = module.elementPaths();
        MutableMap<String, Integer> typeCounts = Maps.mutable.empty();
        int nullCount = 0;
        for (String path : paths)
        {
            PackageableElement elem = module.getElement(path);
            if (elem == null)
            {
                nullCount++;
                continue;
            }
            String typeName = elem.getClass().getSimpleName();
            typeCounts.put(typeName, typeCounts.getIfAbsentPut(typeName, 0) + 1);


            if (elem instanceof meta.pure.metamodel.type.Class classElem)
            {
                collectClass(path, classElem);
            }
            else if (elem instanceof meta.pure.metamodel.type.Enumeration enumElem)
            {
                collectEnum(path, enumElem);
            }
            // Associations are handled via _propertiesFromAssociations() on each Class
        }
        System.out.println("  " + paths.size() + " paths, " + nullCount + " null elements");
        System.out.println("  Element types: " + typeCounts);
        // Debug: print first 10 classes
        int debugCount = 0;
        for (ClassRecord cr : classes.valuesView())
        {
            if (debugCount++ >= 10)
            {
                break;
            }
            System.out.println("    Class: " + cr.fullPath + " -> name=" + cr.name + " pkg=" + cr.packagePath + " props=" + cr.properties.collect(p -> p.name));
        }
    }

    private void collectClass(String fullPath, meta.pure.metamodel.type.Class classElem)
    {
        ClassRecord cr = new ClassRecord();
        cr.fullPath = fullPath;
        // Extract simple name and package from the full path
        int lastSep = fullPath.lastIndexOf("::");
        if (lastSep >= 0)
        {
            cr.name = fullPath.substring(lastSep + 2);
            cr.packagePath = fullPath.substring(0, lastSep);
        }
        else
        {
            cr.name = fullPath;
            cr.packagePath = "";
        }
        if (cr.name == null || cr.name.isEmpty())
        {
            return;
        }

        // Generalizations
        try
        {
            if (classElem._generalizations() != null)
            {
                for (Generalization gen : classElem._generalizations())
                {
                    GenericType gt = gen._general();
                    if (gt != null && _GenericType.type(gt) != null)
                    {
                        meta.pure.metamodel.type.Type rawType = _GenericType.type(gt);
                        if (rawType instanceof meta.pure.metamodel.PackageableElement pe)
                        {
                            cr.generalizations.add(_PackageableElement.path(pe));
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Generalization access failed for " + fullPath, e);
        }

        // Check abstract
        try
        {
            if (classElem instanceof meta.pure.metamodel.extension.ElementWithStereotypes ews && ews._stereotypes() != null)
            {
                for (var s : ews._stereotypes())
                {
                    if (s != null && "abstract".equals(s._value()))
                    {
                        cr.isAbstract = true;
                        break;
                    }
                }
            }
        }
        catch (Exception ignored)
        {
            // FlatBuffer stereotype access can fail for some elements
        }

        // Properties
        try
        {
            if (classElem._properties() != null)
            {
                for (Property prop : classElem._properties())
                {
                    try
                    {
                        PropRecord pr = new PropRecord();
                        pr.name = prop._name();
                        if (pr.name == null || pr.name.contains("::"))
                        {
                            continue;
                        }
                        pr.ownerName = cr.name;

                        // Type
                        GenericType propGt = prop._genericType();
                        if (propGt != null && _GenericType.type(propGt) != null)
                        {
                            meta.pure.metamodel.type.Type propType = _GenericType.type(propGt);
                            if (propType instanceof meta.pure.metamodel.PackageableElement pe && pe._name() != null)
                            {
                                pr.typeName = _PackageableElement.path(pe);
                            }
                            else
                            {
                                pr.typeName = "Object";
                            }
                        }
                        else
                        {
                            pr.typeName = "Object";
                        }

                        // Multiplicity
                        Multiplicity mult = prop._multiplicity();
                        pr.isMany = isMultiplicityMany(mult);

                        // Equality key stereotype
                        pr.isEqualityKey = hasEqualityKeyStereotype(prop);

                        cr.properties.add(pr);
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException("Failed to read property on " + fullPath, e);
                    }
                }
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to access properties on " + fullPath, e);
        }

        // Association properties (e.g., firm on LA_Person from LA_Person_Firm association)
        try
        {
            if (classElem._propertiesFromAssociations() != null)
            {
                for (Property prop : classElem._propertiesFromAssociations())
                {
                    try
                    {
                        PropRecord pr = new PropRecord();
                        pr.name = prop._name();
                        if (pr.name == null || pr.name.contains("::"))
                        {
                            continue;
                        }
                        pr.ownerName = cr.name;

                        GenericType propGt = prop._genericType();
                        if (propGt != null && _GenericType.type(propGt) != null)
                        {
                            meta.pure.metamodel.type.Type propType = _GenericType.type(propGt);
                            if (propType instanceof meta.pure.metamodel.PackageableElement pe && pe._name() != null)
                            {
                                pr.typeName = _PackageableElement.path(pe);
                            }
                            else
                            {
                                pr.typeName = "Object";
                            }
                        }
                        else
                        {
                            pr.typeName = "Object";
                        }

                        Multiplicity mult = prop._multiplicity();
                        pr.isMany = isMultiplicityMany(mult);

                        cr.properties.add(pr);
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException("Failed to read association property on " + fullPath, e);
                    }
                }
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to access propertiesFromAssociations on " + fullPath, e);
        }

        classes.put(cr.fullPath, cr);
    }

    private void collectEnum(String fullPath, meta.pure.metamodel.type.Enumeration enumElem)
    {
        EnumRecord er = new EnumRecord();
        er.fullPath = fullPath;
        // Extract simple name and package from the full path
        int lastSep = fullPath.lastIndexOf("::");
        if (lastSep >= 0)
        {
            er.name = fullPath.substring(lastSep + 2);
            er.packagePath = fullPath.substring(0, lastSep);
        }
        else
        {
            er.name = fullPath;
            er.packagePath = "";
        }
        if (er.name == null || er.name.isEmpty())
        {
            return;
        }

        // Collect enum values from properties with defaultValue
        if (enumElem._properties() != null)
        {
            for (Property prop : enumElem._properties())
            {
                if (prop._name() != null)
                {
                    er.values.add(prop._name());
                }
            }
        }

        enums.put(er.fullPath, er);
    }

    private void computeSubtypes()
    {
        for (ClassRecord cr : classes.valuesView())
        {
            for (String parent : cr.generalizations)
            {
                if (findClass(parent) != null)
                {
                    classesWithSubtypes.add(parent);
                }
            }
        }
    }

    // =========================================================================
    // Interface generation
    // =========================================================================

    private String generateInterface(ClassRecord cr)
    {
        StringBuilder sb = new StringBuilder();
        String pkg = toJavaPackage(cr.packagePath);

        sb.append("// AUTO-GENERATED from PDB - DO NOT EDIT\n");
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("public interface ").append(cr.name);

        MutableList<String> validExtends = cr.generalizations.select(g ->
                findClass(g) != null || implExistsOnClasspath(resolveFullPath(g)));
        if (validExtends.isEmpty() && !"Any".equals(cr.name))
        {
            if (!cr.generalizations.isEmpty())
            {
                // Generalizations were declared but couldn't resolve — hard fail
                throw new RuntimeException("[CODEGEN] Generalizations declared but not resolved for " + cr.fullPath
                        + " (raw: " + cr.generalizations
                        + ", findClass: " + cr.generalizations.collect(g -> g + "=" + (findClass(g) != null))
                        + ")");
            }
            // No generalizations declared — implicitly extends Any
            ClassRecord anyRecord = findClass("meta::pure::metamodel::type::Any");
            if (anyRecord != null)
            {
                sb.append(" extends ").append(toJavaPackage(anyRecord.packagePath)).append(".Any");
            }
        }
        else if (!validExtends.isEmpty())
        {
            sb.append(" extends ");
            for (int i = 0; i < validExtends.size(); i++)
            {
                if (i > 0)
                {
                    sb.append(", ");
                }
                String parent = validExtends.get(i);
                ClassRecord parentCr = findClass(parent);
                if (parentCr != null)
                {
                    sb.append(toJavaPackage(parentCr.packagePath)).append(".").append(parentCr.name);
                }
                else
                {
                    sb.append(resolveJavaFqn(parent));
                }
            }
        }

        sb.append("\n{\n");

        for (PropRecord pr : cr.properties)
        {
            String javaType = resolveJavaType(pr);
            sb.append("    ").append(javaType).append(" _").append(pr.name).append("();\n\n");
            sb.append("    ").append(cr.name).append(" _").append(pr.name).append("(").append(javaType).append(" value);\n\n");
        }

        // _copy() — required for all classes
        if (!cr.isAbstract)
        {
            sb.append("    ").append(cr.name).append(" _copy();\n\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    // =========================================================================
    // Impl generation
    // =========================================================================

    private String generateImpl(ClassRecord cr)
    {
        StringBuilder sb = new StringBuilder();
        String pkg = toJavaPackage(cr.packagePath);

        sb.append("// AUTO-GENERATED from PDB - DO NOT EDIT\n");
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("public class ").append(cr.name).append("Impl");
        sb.append(" implements ").append(cr.name);
        sb.append("\n{\n");

        // Collect all properties (own + inherited)
        MutableList<PropRecord> allProps = collectAllProperties(cr);

        // Fields
        for (PropRecord pr : allProps)
        {
            String javaType = resolveJavaType(pr);
            String fieldName = escapeKeyword(pr.name);
            sb.append("    private ").append(javaType).append(" ").append(fieldName).append(";\n");
        }
        if (!allProps.isEmpty())
        {
            sb.append("\n");
        }

        // Constructor
        sb.append("    public ").append(cr.name).append("Impl() {}\n\n");

        // Getters and setters
        for (PropRecord pr : allProps)
        {
            String javaType = resolveJavaType(pr);
            String fieldName = escapeKeyword(pr.name);

            // Getter
            sb.append("    @Override\n");
            sb.append("    public ").append(javaType).append(" _").append(pr.name).append("()\n");
            sb.append("    {\n");
            if (pr.isMany)
            {
                sb.append("        return this.").append(fieldName).append(" != null ? this.").append(fieldName).append(" : org.finos.legend.pure.truffle.types.PureSequence.EMPTY;\n");
            }
            else
            {
                sb.append("        return this.").append(fieldName).append(";\n");
            }
            sb.append("    }\n\n");

            // Setter
            sb.append("    @Override\n");
            sb.append("    public ").append(cr.name).append("Impl _").append(pr.name).append("(").append(javaType).append(" value)\n");
            sb.append("    {\n");
            sb.append("        this.").append(fieldName).append(" = value;\n");
            sb.append("        return this;\n");
            sb.append("    }\n\n");
        }

        // equals() / hashCode() — based on <<equality.Key>> properties
        MutableList<PropRecord> keyProps = allProps.select(p -> p.isEqualityKey);
        MutableList<PropRecord> equalityProps = keyProps.isEmpty() ? allProps : keyProps;
        sb.append("    @Override\n");
        sb.append("    public boolean equals(Object o)\n");
        sb.append("    {\n");
        sb.append("        if (this == o) return true;\n");
        sb.append("        if (!(o instanceof ").append(cr.name).append("Impl other)) return false;\n");
        for (PropRecord pr : equalityProps)
        {
            String field = escapeKeyword(pr.name);
            sb.append("        if (!java.util.Objects.equals(this.").append(field).append(", other.").append(field).append(")) return false;\n");
        }
        sb.append("        return true;\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public int hashCode()\n");
        sb.append("    {\n");
        // Only use primitive/String properties to avoid circular reference StackOverflow
        MutableList<PropRecord> hashProps = equalityProps.select(p -> !p.isMany && isPrimitiveType(p.typeName));
        if (hashProps.isEmpty())
        {
            sb.append("        return System.identityHashCode(this);\n");
        }
        else
        {
            sb.append("        return java.util.Objects.hash(");
            for (int i = 0; i < hashProps.size(); i++)
            {
                if (i > 0)
                {
                    sb.append(", ");
                }
                sb.append("this.").append(escapeKeyword(hashProps.get(i).name));
            }
            sb.append(");\n");
        }
        sb.append("    }\n\n");

        // _copy()
        sb.append("    @Override\n");
        sb.append("    public ").append(cr.name).append("Impl _copy()\n");
        sb.append("    {\n");
        sb.append("        ").append(cr.name).append("Impl copy = new ").append(cr.name).append("Impl();\n");
        for (PropRecord pr : allProps)
        {
            String fieldName = escapeKeyword(pr.name);
            sb.append("        copy.").append(fieldName).append(" = this.").append(fieldName).append(";\n");
        }
        sb.append("        return copy;\n");
        sb.append("    }\n\n");

        sb.append("}\n");
        return sb.toString();
    }

    // =========================================================================
    // FlatBuffer wrapper generation
    // =========================================================================

    private static final String FBS_PKG = "org.finos.legend.pure.m3.module.pdbModule.fbs";

    private String generateFlatBufferWrapper(ClassRecord cr)
    {
        String defName = fbsSchema.findDefTableName(cr.name);
        if (defName == null)
        {
            return null;
        }
        java.util.List<FbsSchema.FbsField> fbsFields = fbsSchema.getTableFields(defName);
        if (fbsFields == null)
        {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        String pkg = toJavaPackage(cr.packagePath);
        MutableList<PropRecord> allProps = collectAllProperties(cr);

        sb.append("// AUTO-GENERATED from PDB - DO NOT EDIT\n");
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;\n");
        sb.append("import org.finos.legend.pure.truffle.types.ObjectSequence;\n");
        sb.append("import org.finos.legend.pure.truffle.types.PureSequence;\n\n");

        sb.append("public class ").append(cr.name).append("FlatBufferWrapper implements ").append(cr.name).append("\n{\n");
        sb.append("    private final ").append(FBS_PKG).append(".").append(defName).append(" fb;\n");
        sb.append("    private final TruffleMetadataAccess resolver;\n");
        sb.append("    private final Object _parent;\n");

        // Cache fields
        for (PropRecord pr : allProps)
        {
            String javaType = resolveJavaType(pr);
            sb.append("    private ").append(boxType(javaType)).append(" cached_").append(escapeKeyword(pr.name)).append(";\n");
        }
        sb.append("    private static final Object UNSET = new Object();\n\n");

        // Constructors
        sb.append("    public ").append(cr.name).append("FlatBufferWrapper(")
                .append(FBS_PKG).append(".").append(defName).append(" fb, TruffleMetadataAccess resolver)\n");
        sb.append("    {\n        this(fb, resolver, null);\n    }\n\n");
        sb.append("    public ").append(cr.name).append("FlatBufferWrapper(")
                .append(FBS_PKG).append(".").append(defName).append(" fb, TruffleMetadataAccess resolver, Object parent)\n");
        sb.append("    {\n        this.fb = fb;\n        this.resolver = resolver;\n        this._parent = parent;\n    }\n\n");
        sb.append("    public Object _fbParent() { return this._parent; }\n\n");

        for (PropRecord pr : allProps)
        {
            String javaType = resolveJavaType(pr);
            String boxedType = boxType(javaType);
            String cacheField = "cached_" + escapeKeyword(pr.name);
            FbsSchema.FbsField fbsField = findFbsField(fbsFields, pr.name);

            // Getter with caching
            sb.append("    @Override\n");
            sb.append("    public ").append(javaType).append(" _").append(pr.name).append("()\n    {\n");
            sb.append("        if (").append(cacheField).append(" != null) return ").append(cacheField).append(";\n");
            // Generate the actual resolution into a local, then cache
            sb.append("        Object __raw = null;\n");
            generateWrapperGetterBody(sb, pr, fbsField);
            sb.append("        ").append(cacheField).append(" = (").append(boxedType).append(") __raw;\n");
            sb.append("        return ").append(cacheField).append(";\n");
            sb.append("    }\n\n");

            // Setter — read-only
            sb.append("    @Override\n");
            sb.append("    public ").append(cr.name).append("FlatBufferWrapper _").append(pr.name)
                    .append("(").append(javaType).append(" value)\n");
            sb.append("    {\n        throw new UnsupportedOperationException(\"Read-only FlatBuffer wrapper\");\n    }\n\n");
        }

        // _copy()
        sb.append("    @Override\n    public ").append(cr.name).append("Impl _copy()\n    {\n");
        sb.append("        ").append(cr.name).append("Impl copy = new ").append(cr.name).append("Impl();\n");
        for (PropRecord pr : allProps)
        {
            sb.append("        copy._").append(pr.name).append("(this._").append(pr.name).append("());\n");
        }
        sb.append("        return copy;\n    }\n\n");

        sb.append("}\n");
        return sb.toString();
    }

    private FbsSchema.FbsField findFbsField(java.util.List<FbsSchema.FbsField> fields, String propName)
    {
        String snake = camelToSnake(propName);
        for (FbsSchema.FbsField f : fields)
        {
            // FlatBuffers escapes keywords with trailing underscore (e.g. "type" → "type_")
            if (f.name().equals(snake) || f.name().equals(snake + "_"))
            {
                return f;
            }
        }
        return null;
    }

    /**
     * Generate the body of a wrapper getter that assigns to {@code result}.
     */
    private void generateWrapperGetterBody(StringBuilder sb, PropRecord pr, FbsSchema.FbsField fbsField)
    {
        if (fbsField == null)
        {
            sb.append("        __raw = null;\n");
            return;
        }
        String camel = FbsSchema.snakeToCamel(fbsField.name());

        if ("string".equals(fbsField.type()) && !fbsField.isVector())
        {
            // If Pure type is String, return raw. Otherwise it's a pointer path — resolve.
            if (isPrimitiveType(pr.typeName) || "String".equals(pr.typeName))
            {
                sb.append("        __raw = fb.").append(camel).append("();\n");
            }
            else
            {
                // Check if the target type is an enum — if so, resolve by valueOf
                EnumRecord enumRec = findEnum(pr.typeName);
                if (enumRec == null)
                {
                    // Short-name fallback
                    String shortName = pr.typeName.contains("::") ? pr.typeName.substring(pr.typeName.lastIndexOf("::") + 2) : pr.typeName;
                    enumRec = findEnumByShortName(shortName);
                }
                if (enumRec != null)
                {
                    String enumFqn = toJavaPackage(enumRec.packagePath) + "." + enumRec.name + "Enum";
                    sb.append("        { String __enumName = fb.").append(camel).append("();\n");
                    sb.append("          if (__enumName != null) { try { __raw = ").append(enumFqn).append(".valueOf(__enumName); } catch (IllegalArgumentException e) { __raw = null; } } }\n");
                }
                else
                {
                    // Pointer path — resolve via MetadataAccess
                    sb.append("        { String path = fb.").append(camel).append("();\n");
                    sb.append("          if (path != null) { __raw = resolver.getElement(path); if (__raw == null) __raw = org.finos.legend.pure.truffle.runtime.FbsResolverHelper.resolveNestedElement(path, resolver); } }\n");
                }
            }
        }
        else if (fbsField.type().equals("long") || fbsField.type().equals("int")
                || fbsField.type().equals("double") || fbsField.type().equals("bool"))
        {
            sb.append("        __raw = fb.").append(camel).append("();\n");
        }
        else if (pr.isMany && !fbsField.isUnion())
        {
            String defType = fbsField.type();
            String typeShortName = pr.typeName.contains("::") ? pr.typeName.substring(pr.typeName.lastIndexOf("::") + 2) : pr.typeName;
            boolean isPointerRefVector = "PointerRef".equals(defType);
            boolean isStringPointerVector = "string".equals(defType) && !"String".equals(typeShortName);
            String wrapperClass = (isStringPointerVector || isPointerRefVector) ? null : resolveWrapperFqn(defType);
            sb.append("        { int len = fb.").append(camel).append("Length();\n");
            sb.append("          if (len == 0) { __raw = new ObjectSequence(new Object[0]); }\n");
            sb.append("          else { Object[] arr = new Object[len];\n");
            sb.append("            for (int i = 0; i < len; i++) {\n");
            if (isPointerRefVector)
            {
                // Typed PointerRef vector — resolve via PointerRefResolver
                sb.append("              var ref = fb.").append(camel).append("(new ").append(FBS_PKG).append(".PointerRef(), i);\n");
                sb.append("              if (ref != null && ref.pathLength() > 0) { arr[i] = org.finos.legend.pure.truffle.runtime.FbsResolverHelper.resolvePointerRef(ref, resolver); }\n");
            }
            else if (isStringPointerVector && "Stereotype".equals(typeShortName))
            {
                // Stereotype: "profilePath.StereotypeName" → resolve Profile, find by name
                sb.append("              String path = fb.").append(camel).append("(i);\n");
                sb.append("              if (path != null) {\n");
                sb.append("                int dotIdx = path.lastIndexOf('.');\n");
                sb.append("                if (dotIdx > 0) {\n");
                sb.append("                  Object prof = resolver.getElement(path.substring(0, dotIdx));\n");
                sb.append("                  if (prof instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.extension.Profile p) {\n");
                sb.append("                    String stName = path.substring(dotIdx + 1);\n");
                sb.append("                    var stSeq = p._p_stereotypes();\n");
                sb.append("                    if (stSeq != null) for (Object st : stSeq.toBoxedArray()) {\n");
                sb.append("                      if (st instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.extension.Stereotype s && stName.equals(s._value())) { arr[i] = s; break; }\n");
                sb.append("                    }\n");
                sb.append("                  }\n");
                sb.append("                }\n");
                sb.append("              }\n");
            }
            else if (isStringPointerVector && "Tag".equals(typeShortName))
            {
                // Tag: "profilePath#TagName" → resolve Profile, find by name
                sb.append("              String path = fb.").append(camel).append("(i);\n");
                sb.append("              if (path != null) {\n");
                sb.append("                int hashIdx = path.lastIndexOf('#');\n");
                sb.append("                if (hashIdx > 0) {\n");
                sb.append("                  Object prof = resolver.getElement(path.substring(0, hashIdx));\n");
                sb.append("                  if (prof instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.extension.Profile p) {\n");
                sb.append("                    String tName = path.substring(hashIdx + 1);\n");
                sb.append("                    var tSeq = p._p_tags();\n");
                sb.append("                    if (tSeq != null) for (Object t : tSeq.toBoxedArray()) {\n");
                sb.append("                      if (t instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.extension.Tag tag && tName.equals(tag._value())) { arr[i] = tag; break; }\n");
                sb.append("                    }\n");
                sb.append("                  }\n");
                sb.append("                }\n");
                sb.append("              }\n");
            }
            else if (isStringPointerVector)
            {
                sb.append("              String path = fb.").append(camel).append("(i);\n");
                sb.append("              if (path != null) { arr[i] = resolver.getElement(path); if (arr[i] == null) arr[i] = org.finos.legend.pure.truffle.runtime.FbsResolverHelper.resolveNestedElement(path, resolver); }\n");
            }
            else
            {
                sb.append("              var item = fb.").append(camel).append("(i);\n");
                if (wrapperClass != null)
                {
                    sb.append("              if (item == null) throw new RuntimeException(\"Null element in FBS array for ").append(wrapperClass).append("\");\n");
                    sb.append("              arr[i] = new ").append(wrapperClass).append("(item, resolver, this);\n");
                }
                else
                {
                    sb.append("              arr[i] = item;\n");
                }
            }
            sb.append("            }\n");
            sb.append("            __raw = new ObjectSequence(java.util.Arrays.stream(arr).filter(java.util.Objects::nonNull).toArray()); } }\n");
        }
        else if (pr.isMany && fbsField.isUnion())
        {
            generateUnionVectorGetterBody(sb, camel, fbsField.type());
        }
        else if (!pr.isMany && fbsField.isUnion())
        {
            generateUnionSingleGetterBody(sb, camel, fbsField.type());
        }
        else
        {
            String defType = fbsField.type();
            if ("PointerRef".equals(defType))
            {
                // Typed pointer — resolve via PointerRefResolver
                sb.append("        { var ref = fb.").append(camel).append("();\n");
                sb.append("          __raw = (ref != null && ref.pathLength() > 0) ? org.finos.legend.pure.truffle.runtime.FbsResolverHelper.resolvePointerRef(ref, resolver) : null; }\n");
            }
            else
            {
                String wrapperClass = resolveWrapperFqn(defType);
                if (wrapperClass != null)
                {
                    sb.append("        { var raw = fb.").append(camel).append("();\n");
                    sb.append("          __raw = raw != null ? new ").append(wrapperClass).append("(raw, resolver, this) : null; }\n");
                }
                else
                {
                    sb.append("        __raw = fb.").append(camel).append("();\n");
                }
            }
        }
    }

    private void generateUnionVectorGetterBody(StringBuilder sb, String camelName, String unionName)
    {
        java.util.List<String> members = fbsSchema.getUnionMembers(unionName);
        sb.append("        { int len = fb.").append(camelName).append("Length();\n");
        sb.append("          if (len == 0) { __raw = new ObjectSequence(new Object[0]); }\n");
        sb.append("          else { Object[] arr = new Object[len];\n");
        sb.append("            for (int i = 0; i < len; i++) {\n");
        String typeSuffix = camelName.endsWith("_") ? "type" : "Type";
        sb.append("              byte uType = fb.").append(camelName).append(typeSuffix).append("(i);\n");
        sb.append("              switch (uType) {\n");
        if (members != null)
        {
            for (int idx = 0; idx < members.size(); idx++)
            {
                generateUnionCase(sb, idx + 1, members.get(idx), camelName, true);
            }
        }
        sb.append("                default: break;\n");
        sb.append("              }\n");
        sb.append("            }\n");
        sb.append("            __raw = new ObjectSequence(arr); } }\n");
    }

    private void generateUnionSingleGetterBody(StringBuilder sb, String camelName, String unionName)
    {
        java.util.List<String> members = fbsSchema.getUnionMembers(unionName);
        String typeSuffix2 = camelName.endsWith("_") ? "type" : "Type";
        sb.append("        { byte uType = fb.").append(camelName).append(typeSuffix2).append("();\n");
        sb.append("          if (uType == 0) { __raw = null; }\n");
        sb.append("          else { switch (uType) {\n");
        if (members != null)
        {
            for (int idx = 0; idx < members.size(); idx++)
            {
                generateUnionCase(sb, idx + 1, members.get(idx), camelName, false);
            }
        }
        sb.append("            default: __raw = null; break;\n");
        sb.append("          } } }\n");
    }

    private void generateUnionCase(StringBuilder sb, int discriminator, String defName,
                                   String camelName, boolean isVector)
    {
        String target = isVector ? "arr[i]" : "__raw";
        if (FbsSchema.isSpecialRef(defName))
        {
            if ("PointerRef".equals(defName))
            {
                sb.append("                case ").append(discriminator).append(": { ")
                        .append(FBS_PKG).append(".PointerRef pr = (").append(FBS_PKG)
                        .append(".PointerRef) fb.").append(camelName).append("(new ").append(FBS_PKG)
                        .append(".PointerRef()");
                if (isVector) sb.append(", i");
                sb.append("); if (pr != null && pr.pathLength() > 0) { ")
                  .append(target).append(" = org.finos.legend.pure.truffle.runtime.FbsResolverHelper.resolvePointerRef(pr, resolver); } break; }\n");
            }
            else
            {
                sb.append("                case ").append(discriminator).append(": { ")
                        .append(FBS_PKG).append(".AncestorRef ar = (").append(FBS_PKG)
                        .append(".AncestorRef) fb.").append(camelName).append("(new ").append(FBS_PKG)
                        .append(".AncestorRef()");
                if (isVector) sb.append(", i");
                sb.append("); if (ar != null) { Object t = this; for (int d = 0; d < ar.depth(); d++) { try { t = t.getClass().getMethod(\"_fbParent\").invoke(t); } catch (Exception e) { break; } } ")
                  .append(target).append(" = t; } break; }\n");
            }
            return;
        }

        String wrapperFqn = resolveWrapperFqn(defName);
        sb.append("                case ").append(discriminator).append(": { ")
                .append(FBS_PKG).append(".").append(defName).append(" d = (")
                .append(FBS_PKG).append(".").append(defName).append(") fb.").append(camelName)
                .append("(new ").append(FBS_PKG).append(".").append(defName).append("()");
        if (isVector) sb.append(", i");
        sb.append("); ");
        if (wrapperFqn != null)
        {
            sb.append("if (d != null) ").append(target).append(" = new ").append(wrapperFqn).append("(d, resolver, this); ");
        }
        else if ("IntegerValueDef".equals(defName))
        {
            sb.append("if (d != null) ").append(target).append(" = d.val(); ");
        }
        else if ("FloatValueDef".equals(defName))
        {
            sb.append("if (d != null) ").append(target).append(" = d.val(); ");
        }
        else if ("BooleanValueDef".equals(defName))
        {
            sb.append("if (d != null) ").append(target).append(" = d.val(); ");
        }
        else if ("StringValueDef".equals(defName))
        {
            sb.append("if (d != null) ").append(target).append(" = d.val(); ");
        }
        else if ("DecimalValueDef".equals(defName))
        {
            sb.append("if (d != null) ").append(target).append(" = d.val() != null ? new java.math.BigDecimal(d.val()) : null; ");
        }
        sb.append("break; }\n");
    }

    /**
     * Resolve the fully-qualified truffle wrapper class name for a Def type.
     * Returns null if no wrapper exists (primitive value types, etc.)
     */
    private String resolveWrapperFqn(String defName)
    {
        if (FbsSchema.isSpecialRef(defName) || FbsSchema.isPrimitiveValueDef(defName))
        {
            return null;
        }
        String pureName = FbsSchema.defToPureClassName(defName);
        // FBS def names are short names (e.g. "Class", "Property") — use short-name lookup
        ClassRecord cr = findClassByShortName(pureName);
        if (cr != null)
        {
            return toJavaPackage(cr.packagePath) + "." + cr.name + "FlatBufferWrapper";
        }
        return null;
    }

    private static String camelToSnake(String camel)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++)
        {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0)
            {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    // =========================================================================
    // Enum generation
    // =========================================================================

    private String generateEnumInterface(EnumRecord er)
    {
        StringBuilder sb = new StringBuilder();
        String pkg = toJavaPackage(er.packagePath);

        sb.append("// AUTO-GENERATED from PDB - DO NOT EDIT\n");
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("public interface ").append(er.name).append(" extends ").append(TRUFFLE_PACKAGE_PREFIX).append("meta.pure.metamodel.type.Enum\n{\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String generateEnumClass(EnumRecord er)
    {
        StringBuilder sb = new StringBuilder();
        String pkg = toJavaPackage(er.packagePath);
        String tp = TRUFFLE_PACKAGE_PREFIX;

        sb.append("// AUTO-GENERATED from PDB - DO NOT EDIT\n");
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("public enum ").append(er.name).append("Enum implements ").append(er.name).append("\n{\n");

        for (int i = 0; i < er.values.size(); i++)
        {
            sb.append("    ").append(er.values.get(i));
            sb.append(i < er.values.size() - 1 ? ",\n" : ";\n");
        }

        sb.append("\n");

        // Fields from Any + AnnotatedElement + Enum (all truffle-namespaced)
        sb.append("    private ").append(tp).append("meta.pure.metamodel.type.generics.GenericTypeValue classifierGenericType;\n");
        sb.append("    private ").append(tp).append("meta.pure.metamodel.SourceInformation sourceInformation;\n");
        sb.append("    private ").append(tp).append("meta.pure.metamodel.type.ElementOverride elementOverride;\n");
        sb.append("    private org.finos.legend.pure.truffle.types.PureSequence taggedValues = new org.finos.legend.pure.truffle.types.ObjectSequence(new Object[0]);\n");
        sb.append("    private org.finos.legend.pure.truffle.types.PureSequence stereotypes = new org.finos.legend.pure.truffle.types.ObjectSequence(new Object[0]);\n\n");

        // _classifierGenericType
        sb.append("    @Override public ").append(tp).append("meta.pure.metamodel.type.generics.GenericTypeValue _classifierGenericType() { return this.classifierGenericType; }\n");
        sb.append("    @Override public ").append(er.name).append("Enum _classifierGenericType(").append(tp).append("meta.pure.metamodel.type.generics.GenericTypeValue value) { this.classifierGenericType = value; return this; }\n\n");

        // _sourceInformation
        sb.append("    @Override public ").append(tp).append("meta.pure.metamodel.SourceInformation _sourceInformation() { return this.sourceInformation; }\n");
        sb.append("    @Override public ").append(er.name).append("Enum _sourceInformation(").append(tp).append("meta.pure.metamodel.SourceInformation value) { this.sourceInformation = value; return this; }\n\n");

        // _elementOverride
        sb.append("    @Override public ").append(tp).append("meta.pure.metamodel.type.ElementOverride _elementOverride() { return this.elementOverride; }\n");
        sb.append("    @Override public ").append(er.name).append("Enum _elementOverride(").append(tp).append("meta.pure.metamodel.type.ElementOverride value) { this.elementOverride = value; return this; }\n\n");

        // _name (from Enum interface)
        sb.append("    public String _name() { return this.name(); }\n");
        sb.append("    public ").append(er.name).append("Enum _name(String value) { return this; }\n\n");

        // _taggedValues, _stereotypes
        sb.append("    public org.finos.legend.pure.truffle.types.PureSequence _taggedValues() { return this.taggedValues; }\n");
        sb.append("    public ").append(er.name).append("Enum _taggedValues(org.finos.legend.pure.truffle.types.PureSequence value) { this.taggedValues = value; return this; }\n\n");

        sb.append("    public org.finos.legend.pure.truffle.types.PureSequence _stereotypes() { return this.stereotypes; }\n");
        sb.append("    public ").append(er.name).append("Enum _stereotypes(org.finos.legend.pure.truffle.types.PureSequence value) { this.stereotypes = value; return this; }\n\n");

        // _copy() — enum values are singletons
        sb.append("    @Override public ").append(er.name).append("Enum _copy() { return this; }\n\n");

        sb.append("}\n");
        return sb.toString();
    }

    // =========================================================================
    // Property inheritance
    // =========================================================================

    private MutableList<PropRecord> collectAllProperties(ClassRecord cr)
    {
        MutableList<PropRecord> result = Lists.mutable.empty();
        MutableSet<String> seen = Sets.mutable.empty();

        // Add Any's properties (all classes implicitly extend Any)
        if (!"Any".equals(cr.name))
        {
            ClassRecord anyRecord = findClass("meta::pure::metamodel::type::Any");
            if (anyRecord != null)
            {
                for (PropRecord anyProp : anyRecord.properties)
                {
                    if (!seen.contains(anyProp.name))
                    {
                        seen.add(anyProp.name);
                        result.add(anyProp);
                    }
                }
            }
        }

        collectPropsFromHierarchy(cr, result, seen, Sets.mutable.empty());
        return result;
    }

    private void collectPropsFromHierarchy(ClassRecord cr, MutableList<PropRecord> result,
                                           MutableSet<String> seen, MutableSet<String> visited)
    {
        if (visited.contains(cr.fullPath))
        {
            return;
        }
        visited.add(cr.fullPath);

        // Parents first
        for (String parentName : cr.generalizations)
        {
            ClassRecord parent = findClass(parentName);
            if (parent != null)
            {
                collectPropsFromHierarchy(parent, result, seen, visited);
            }
        }

        // Own properties
        for (PropRecord pr : cr.properties)
        {
            if (!seen.contains(pr.name))
            {
                seen.add(pr.name);
                result.add(pr);
            }
        }
    }

    // =========================================================================
    // Type mapping
    // =========================================================================

    private String resolveJavaType(PropRecord pr)
    {
        if (pr.javaTypeFqn != null)
        {
            return pr.isMany ? "org.finos.legend.pure.truffle.types.PureSequence" : pr.javaTypeFqn;
        }
        return mapToJavaType(pr.typeName, pr.isMany);
    }

    private String mapToJavaType(String pureName, boolean isMany)
    {
        String base = mapPrimitive(pureName);
        return isMany ? "org.finos.legend.pure.truffle.types.PureSequence" : base;
    }

    private String mapPrimitive(String pureName)
    {
        String shortName = pureName.contains("::") ? pureName.substring(pureName.lastIndexOf("::") + 2) : pureName;
        return switch (shortName)
        {
            case "String" -> "String";
            case "Boolean" -> "Boolean";
            case "Integer" -> "Long";
            case "Float" -> "Double";
            case "Decimal" -> "java.math.BigDecimal";
            case "Date" -> "java.time.temporal.Temporal";
            case "DateTime" -> "java.time.ZonedDateTime";
            case "StrictDate" -> "java.time.LocalDate";
            case "StrictTime" -> "java.time.LocalTime";
            case "Number" -> "Number";
            case "Byte" -> "Byte";
            case "Any" -> "Object";
            default ->
            {
                // Check if it's a known class — try full path first, then short name
                ClassRecord cr = findClass(pureName);
                if (cr != null)
                {
                    yield toJavaPackage(cr.packagePath) + "." + cr.name;
                }
                EnumRecord er = findEnum(pureName);
                if (er != null)
                {
                    yield toJavaPackage(er.packagePath) + "." + er.name;
                }
                // Unknown type — use Object
                yield "Object";
            }
        };
    }

    private static String boxType(String type)
    {
        return switch (type)
        {
            case "boolean" -> "Boolean";
            case "long" -> "Long";
            case "double" -> "Double";
            default -> type;
        };
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private static final String TRUFFLE_PACKAGE_PREFIX = "org.finos.legend.pure.truffle.pdb.";

    private static String toJavaPackage(String purePackagePath)
    {
        if (purePackagePath == null || purePackagePath.isEmpty())
        {
            return TRUFFLE_PACKAGE_PREFIX + "generated";
        }
        String[] segments = purePackagePath.split("::");
        StringBuilder result = new StringBuilder(TRUFFLE_PACKAGE_PREFIX);
        for (int i = 0; i < segments.length; i++)
        {
            if (i > 0)
            {
                result.append('.');
            }
            String seg = segments[i];
            if (JAVA_KEYWORDS.contains(seg))
            {
                result.append(seg).append('_');
            }
            else
            {
                result.append(seg);
            }
        }
        return result.toString();
    }

    private static String extractPackagePath(String fullPath, String name)
    {
        if (fullPath.endsWith("::" + name))
        {
            return fullPath.substring(0, fullPath.length() - name.length() - 2);
        }
        return fullPath;
    }

    private static boolean isMultiplicityMany(Multiplicity mult)
    {
        if (mult instanceof ConcreteMultiplicity cm)
        {
            if (cm._upperBound() == null)
            {
                return true;  // unbounded = *
            }
            Long upper = cm._upperBound()._value();
            return upper == null || upper > 1;
        }
        return false;
    }

    private static boolean isPrimitiveType(String typeName)
    {
        String shortName = typeName.contains("::") ? typeName.substring(typeName.lastIndexOf("::") + 2) : typeName;
        return switch (shortName)
        {
            case "String", "Boolean", "Integer", "Float", "Decimal", "Number" -> true;
            default -> false;
        };
    }

    private static boolean hasEqualityKeyStereotype(Property prop)
    {
        try
        {
            if (prop instanceof meta.pure.metamodel.extension.ElementWithStereotypes ews && ews._stereotypes() != null)
            {
                for (var st : ews._stereotypes())
                {
                    if (st != null && "Key".equals(st._value())
                            && st._profile() instanceof PackageableElement pe
                            && "equality".equals(pe._name()))
                    {
                        return true;
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    private static boolean implExistsOnClasspath(String fullPurePath)
    {
        String javaClassName = fullPurePath.replace("::", ".") + "Impl";
        try
        {
            Class.forName(javaClassName);
            return true;
        }
        catch (ClassNotFoundException e)
        {
            return false;
        }
    }

    private ClassRecord findClass(String name)
    {
        // Full path lookup only — no short-name fallback (avoids ambiguity)
        return classes.get(name);
    }

    /** Reverse lookup by short name — used only for FBS wrapper resolution where names are unambiguous. */
    private ClassRecord findClassByShortName(String shortName)
    {
        ClassRecord found = null;
        for (ClassRecord cr : classes.valuesView())
        {
            if (cr.name.equals(shortName))
            {
                if (found != null)
                {
                    // Ambiguous — prefer metamodel over protocol
                    if (cr.fullPath.startsWith("meta::pure::metamodel"))
                    {
                        found = cr;
                    }
                    continue;
                }
                found = cr;
            }
        }
        return found;
    }

    private EnumRecord findEnum(String name)
    {
        return enums.get(name);
    }

    /** Reverse lookup for enum by short name. */
    private EnumRecord findEnumByShortName(String shortName)
    {
        for (EnumRecord er : enums.valuesView())
        {
            if (er.name.equals(shortName))
            {
                return er;
            }
        }
        return null;
    }

    private String resolveFullPath(String simpleName)
    {
        ClassRecord cr = findClass(simpleName);
        if (cr != null)
        {
            return cr.fullPath;
        }
        return simpleName;
    }

    private String resolveJavaFqn(String fullPath)
    {
        ClassRecord cr = findClass(fullPath);
        if (cr != null)
        {
            return toJavaPackage(cr.packagePath) + "." + cr.name;
        }
        // Fallback: convert Pure path to Java FQN directly
        return TRUFFLE_PACKAGE_PREFIX + fullPath.replace("::", ".");
    }

    private static String escapeKeyword(String name)
    {
        return JAVA_KEYWORDS.contains(name) ? name + "_" : name;
    }

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "true", "false", "null");

    // =========================================================================
    // Data records
    // =========================================================================

    static class ClassRecord
    {
        String fullPath;
        String name;
        String packagePath;
        boolean isAbstract;
        MutableList<String> generalizations = Lists.mutable.empty();
        MutableList<PropRecord> properties = Lists.mutable.empty();
    }

    static class PropRecord
    {
        String name;
        String ownerName;
        String typeName;
        boolean isMany;
        boolean isEqualityKey;
        String javaTypeFqn; // pre-resolved Java FQN, bypasses mapToJavaType
    }

    static class EnumRecord
    {
        String fullPath;
        String name;
        String packagePath;
        MutableList<String> values = Lists.mutable.empty();
    }

    // =========================================================================
    // Main
    // =========================================================================

    // =========================================================================
    // Pure source parser — extracts class/enum definitions directly from .pure files
    // since the PDB doesn't inline properties in ClassDef FlatBuffers.
    // =========================================================================

    public void collectFromPureSources(Path... sourceDirs) throws IOException
    {
        for (Path dir : sourceDirs)
        {
            if (!Files.isDirectory(dir))
            {
                continue;
            }
            Files.walk(dir)
                    .filter(p -> p.toString().endsWith(".pure"))
                    .forEach(p ->
                    {
                        try
                        {
                            parsePureFile(p);
                        }
                        catch (IOException e)
                        {
                            throw new RuntimeException(e);
                        }
                    });
        }
        computeSubtypes();
    }

    private void parsePureFile(Path file) throws IOException
    {
        java.util.List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        ClassRecord currentClass = null;
        EnumRecord currentEnum = null;
        int braceDepth = 0;
        boolean debug = false;

        for (int i = 0; i < lines.size(); i++)
        {
            String line = lines.get(i).trim();

            // Skip comments
            if (line.startsWith("//"))
            {
                continue;
            }

            if (debug && i >= 16 && i <= 22)
            {
                System.out.println("  TRACE line " + (i + 1) + " depth=" + braceDepth + " cc=" + (currentClass != null ? currentClass.name : "null") + " : " + line.substring(0, Math.min(60, line.length())));
            }

            // Class definition
            if (debug && (line.startsWith("Class ") || (line.contains("Class ") && line.contains(">>"))))
            {
                System.out.println("  DEBUG line " + (i + 1) + ": " + line.substring(0, Math.min(80, line.length())) + " depth=" + braceDepth);
            }
            if (line.startsWith("Class ") || (line.contains("Class ") && line.contains(">>")))
            {
                String classPart = line;
                // Strip stereotypes like <<test.TestDependency>>
                int classIdx = classPart.lastIndexOf("Class ");
                if (classIdx >= 0)
                {
                    classPart = classPart.substring(classIdx + 6).trim();
                }

                // Strip stereotypes FIRST (before type params, since << looks like <)
                while (classPart.contains("<<") && classPart.contains(">>"))
                {
                    int start = classPart.indexOf("<<");
                    int end = classPart.indexOf(">>", start);
                    if (end > start)
                    {
                        classPart = (classPart.substring(0, start) + classPart.substring(end + 2)).trim();
                    }
                    else
                    {
                        break;
                    }
                }

                // Handle extends
                String extendsParent = null;
                if (classPart.contains(" extends "))
                {
                    int extIdx = classPart.indexOf(" extends ");
                    extendsParent = classPart.substring(extIdx + 9).trim();
                    classPart = classPart.substring(0, extIdx).trim();
                }

                // Strip type parameters (e.g., Pair<U,V>) and constructor params (e.g., Foo(x:Integer[1]))
                String fullPath = classPart;
                if (fullPath.contains("<"))
                {
                    fullPath = fullPath.substring(0, fullPath.indexOf("<")).trim();
                }
                if (fullPath.contains("("))
                {
                    fullPath = fullPath.substring(0, fullPath.indexOf("(")).trim();
                }

                // Skip if no :: in path — not a valid fully-qualified Pure class
                if (debug)
                {
                    System.out.println("  CLASS_DETECT line " + (i + 1) + " fullPath='" + fullPath + "' classPart='" + classPart + "' raw='" + line.substring(0, Math.min(60, line.length())) + "'");
                }
                if (!fullPath.contains("::"))
                {
                    continue;
                }

                currentClass = new ClassRecord();
                currentClass.fullPath = fullPath;
                int lastSep = fullPath.lastIndexOf("::");
                currentClass.name = fullPath.substring(lastSep + 2);
                currentClass.packagePath = fullPath.substring(0, lastSep);

                if (extendsParent != null)
                {
                    // May have multiple parents: "extends ParentA, ParentB"
                    for (String parent : extendsParent.split(","))
                    {
                        String parentName = parent.trim();
                        if (parentName.contains("<"))
                        {
                            parentName = parentName.substring(0, parentName.indexOf("<"));
                        }
                        if (parentName.contains("::"))
                        {
                            parentName = parentName.substring(parentName.lastIndexOf("::") + 2);
                        }
                        if (!parentName.isEmpty())
                        {
                            currentClass.generalizations.add(parentName);
                        }
                    }
                }

                // Check for abstract
                if (line.contains("<<abstract>>") || line.contains("<<meta::pure::profiles::abstract>>"))
                {
                    currentClass.isAbstract = true;
                }

                braceDepth = 0;
                if (line.contains("{"))
                {
                    braceDepth = 1;
                }
                continue;
            }

            // Enum definition
            if (line.startsWith("Enum "))
            {
                String enumPath = line.substring(5).trim();
                if (enumPath.contains("<"))
                {
                    enumPath = enumPath.substring(0, enumPath.indexOf("<")).trim();
                }

                if (!enumPath.contains("::"))
                {
                    continue;
                }

                currentEnum = new EnumRecord();
                currentEnum.fullPath = enumPath;
                int lastSep = enumPath.lastIndexOf("::");
                currentEnum.name = enumPath.substring(lastSep + 2);
                currentEnum.packagePath = enumPath.substring(0, lastSep);

                braceDepth = 0;
                if (line.contains("{"))
                {
                    braceDepth = 1;
                }
                continue;
            }

            // Track brace depth
            int prevDepth = braceDepth;
            for (char c : line.toCharArray())
            {
                if (c == '{')
                {
                    braceDepth++;
                }
                else if (c == '}')
                {
                    braceDepth--;
                }
            }
            if (debug && braceDepth != prevDepth)
            {
                System.out.println("  DEBUG brace line " + (i + 1) + ": depth " + prevDepth + " -> " + braceDepth + " : " + line.substring(0, Math.min(60, line.length())));
            }

            // Inside class body at depth 1 — property declaration
            // Must be "propName : Type[mult];" — reject method bodies, closing braces, etc.
            if (currentClass != null && braceDepth == 1 && line.contains(":")
                    && !line.contains("(") && !line.contains("}")
                    && !line.contains("$") && !line.startsWith("//")
                    && !line.startsWith("{") && !line.startsWith("*"))
            {
                // Property line: "<<equality.Key>> first : U[1];"
                String propLine = line;
                // Strip stereotypes
                while (propLine.contains("<<") && propLine.contains(">>"))
                {
                    int start = propLine.indexOf("<<");
                    int end = propLine.indexOf(">>", start);
                    if (end > start)
                    {
                        propLine = (propLine.substring(0, start) + propLine.substring(end + 2)).trim();
                    }
                    else
                    {
                        break;
                    }
                }

                // Parse "propName : Type[mult];"
                int colonIdx = propLine.indexOf(':');
                if (colonIdx > 0)
                {
                    String propName = propLine.substring(0, colonIdx).trim();
                    String typeAndMult = propLine.substring(colonIdx + 1).trim();
                    if (typeAndMult.endsWith(";"))
                    {
                        typeAndMult = typeAndMult.substring(0, typeAndMult.length() - 1).trim();
                    }

                    // Extract type and multiplicity
                    String typeName = typeAndMult;
                    boolean isMany = false;
                    if (typeName.contains("["))
                    {
                        String mult = typeName.substring(typeName.lastIndexOf("[") + 1, typeName.lastIndexOf("]")).trim();
                        typeName = typeName.substring(0, typeName.lastIndexOf("[")).trim();
                        isMany = mult.contains("*") || mult.contains("..");
                    }

                    // Resolve type — single letter means type parameter → Object
                    if (typeName.length() <= 2)
                    {
                        typeName = "Any";
                    }
                    // Strip package prefix from type name
                    if (typeName.contains("::"))
                    {
                        typeName = typeName.substring(typeName.lastIndexOf("::") + 2);
                    }

                    PropRecord pr = new PropRecord();
                    pr.name = propName;
                    pr.ownerName = currentClass.name;
                    pr.typeName = typeName;
                    pr.isMany = isMany;
                    currentClass.properties.add(pr);
                }
            }

            // Inside enum body — enum value(s)
            if (currentEnum != null && braceDepth == 1 && !line.isEmpty() && !line.startsWith("//"))
            {
                // Enum values can be on one line: "ASC, DESC" or one per line: "VALUE_NAME,"
                // Strip tagged values like {doc.doc = '...'} and stereotypes
                String cleanLine = line;
                while (cleanLine.contains("{") && cleanLine.contains("}"))
                {
                    int s = cleanLine.indexOf("{");
                    int e = cleanLine.indexOf("}", s);
                    if (e > s)
                    {
                        cleanLine = (cleanLine.substring(0, s) + cleanLine.substring(e + 1)).trim();
                    }
                    else
                    {
                        break;
                    }
                }
                while (cleanLine.contains("<<") && cleanLine.contains(">>"))
                {
                    int s = cleanLine.indexOf("<<");
                    int e = cleanLine.indexOf(">>", s);
                    if (e > s)
                    {
                        cleanLine = (cleanLine.substring(0, s) + cleanLine.substring(e + 2)).trim();
                    }
                    else
                    {
                        break;
                    }
                }
                for (String part : cleanLine.split("[,;]"))
                {
                    String enumValue = part.trim();
                    if (!enumValue.isEmpty() && !enumValue.contains(" ") && !enumValue.contains("{") && !enumValue.contains("}"))
                    {
                        currentEnum.values.add(enumValue);
                    }
                }
            }

            // End of class/enum
            if (debug && braceDepth == 0 && (currentClass != null || currentEnum != null))
            {
                System.out.println("  DEBUG depth=0 at line " + (i + 1) + " currentClass=" + (currentClass != null ? currentClass.name : "null") + " currentEnum=" + (currentEnum != null ? currentEnum.name : "null"));
            }
            if (braceDepth == 0)
            {
                if (currentClass != null)
                {
                    if (debug)
                    {
                        System.out.println("  DEBUG end class " + currentClass.name + " at line " + (i + 1) + " with " + currentClass.properties.size() + " props");
                    }
                    classes.put(currentClass.fullPath, currentClass);
                    currentClass = null;
                }
                if (currentEnum != null)
                {
                    if (debug)
                    {
                        System.out.println("  DEBUG end enum " + currentEnum.name + " with " + currentEnum.values.size() + " values");
                    }
                    enums.put(currentEnum.fullPath, currentEnum);
                    currentEnum = null;
                }
            }
            if (debug && braceDepth < 0)
            {
                System.out.println("  DEBUG NEGATIVE braceDepth=" + braceDepth + " at line " + (i + 1) + ": " + line);
                braceDepth = 0;
            }
        }
    }

    /**
     * Collect classes from a compiled PureModel (in-memory, before PDB serialization).
     * This is the correct way to get properties — they're lost during FlatBuffer round-trip.
     */
    public void collectFromCompiledModel(
            org.eclipse.collections.api.list.MutableList<org.finos.legend.pure.m3.module.Module> modules)
    {
        for (org.finos.legend.pure.m3.module.Module module : modules)
        {
            for (String path : module.elementPaths())
            {
                PackageableElement elem = module.getElement(path);
                if (elem == null)
                {
                    continue;
                }
                if (elem instanceof meta.pure.metamodel.type.Class classElem)
                {
                    collectClass(path, classElem);
                }
                else if (elem instanceof meta.pure.metamodel.type.Enumeration enumElem)
                {
                    collectEnumFromCompiled(path, enumElem);
                }
            }
        }
        computeSubtypes();
    }

    private void collectEnumFromCompiled(String fullPath, meta.pure.metamodel.type.Enumeration enumElem)
    {
        int lastSep = fullPath.lastIndexOf("::");
        if (lastSep < 0)
        {
            return;
        }

        EnumRecord er = new EnumRecord();
        er.fullPath = fullPath;
        er.name = fullPath.substring(lastSep + 2);
        er.packagePath = fullPath.substring(0, lastSep);

        // Extract enum values from the enumeration's properties
        try
        {
            if (enumElem._properties() != null)
            {
                for (var prop : enumElem._properties())
                {
                    if (prop._name() != null)
                    {
                        er.values.add(prop._name());
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }

        if (!er.values.isEmpty())
        {
            enums.put(er.fullPath, er);
        }
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length < 2)
        {
            System.out.println("Usage: PdbJavaGenerator <output-dir> <input.pdb> [additional.pdb ...] [--fbs <m3.fbs>]");
            System.exit(1);
        }

        Path outputDir = Path.of(args[0]);

        // Load PDB modules
        MutableList<PDBModule> modules = Lists.mutable.empty();
        MutableList<String> moduleNames = Lists.mutable.empty();
        for (int i = 1; i < args.length; i++)
        {
            if ("--fbs".equals(args[i]))
            {
                i++; // skip the path argument
                continue;
            }
            Path pdbPath = Path.of(args[i]);
            String moduleName = pdbPath.getFileName().toString().replace(".pdb", "");
            MutableList<String> deps = Lists.mutable.withAll(moduleNames);
            PDBModule pdb = new PDBModule(pdbPath, PDBModule.Mode.EXECUTION,
                    moduleName, "*", deps);
            modules.add(pdb);
            moduleNames.add(moduleName);
        }

        // Build and compile the model (required for FlatBuffer elements to resolve)
        MutableList<org.finos.legend.pure.m3.module.Module> moduleList = Lists.mutable.empty();
        moduleList.addAllIterable(modules);
        PureModel model = PureModel.withModules(moduleList)
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        // Load FBS schema if --fbs flag is provided (for wrapper generation)
        FbsSchema fbsSchema = null;
        for (int i = 1; i < args.length - 1; i++)
        {
            if ("--fbs".equals(args[i]))
            {
                fbsSchema = FbsSchema.parse(Path.of(args[i + 1]));
                System.out.println("Loaded FBS schema from " + args[i + 1]);
                break;
            }
        }

        // Use a SINGLE generator — collect from compiled model for reliable generalization resolution
        PdbJavaGenerator generator = new PdbJavaGenerator(null, outputDir);
        generator.setFbsSchema(fbsSchema);
        generator.collectFromCompiledModel(moduleList);
        generator.generateAll();
    }
}
