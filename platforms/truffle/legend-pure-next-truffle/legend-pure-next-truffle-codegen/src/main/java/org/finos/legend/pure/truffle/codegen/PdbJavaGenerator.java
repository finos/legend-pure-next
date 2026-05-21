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
 * Generates Java Interface + PDBHelper classes from Pure Class definitions in a PDB.
 *
 * <p>Reads Class elements from a compiled PDB (core.pdb, compiler.pdb) and
 * emits Java source files following the same pattern as RdfJavaGenerator:
 * underscore-prefixed getters/setters, fluent setters, MutableList for [*],
 * _copy() for shallow cloning.</p>
 *
 * <p>Skips classes whose PDBHelper already exists on the classpath (metamodel
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
        int helpers = 0;
        int enumCount = 0;
        int skipped = 0;

        for (ClassRecord cr : classes.valuesView())
        {
            // Skip codegen for non-metamodel classes — they work entirely
            // through PDO + PureShapeRegistry + PropertyMetadataRegistry
            // (populated at PDB load time from the Class element's
            // properties). Only the bootstrap metamodel + protocol/grammar
            // + core collection types need generated XPDBHelpers, because the
            // runtime reads their FB encoding via per-property `__load_X`
            // logic. Test/user classes living in core.pdb / compiler.pdb
            // are loaded as Pure-built PDOs from the resolver and never go
            // through the per-class FB decoder lambda.
            if (!isMetamodelClass(cr.fullPath))
            {
                skipped++;
                continue;
            }
            Path packageDir = outputDir.resolve(toJavaPackage(cr.packagePath).replace('.', '/'));
            Files.createDirectories(packageDir);

            // No interfaces emitted. The runtime is fully PDO-aware and the
            // generated writer's typed `instanceof <X>` clauses have been
            // dropped (they were paired with `pureTypeIs`/`pureTypeOf != null`
            // checks that already cover both PDO and the no-longer-existing
            // typed XPDBHelpers).

            if (!cr.isAbstract)
            {
                Files.write(packageDir.resolve(cr.name + "PDBHelper.java"),
                        generateHelper(cr).getBytes(StandardCharsets.UTF_8));
                helpers++;
            }
        }

        for (EnumRecord er : enums.valuesView())
        {
            // Mirror the user-class policy: only metamodel-scope Pure enums
            // need a Java enum class. User/test enums load as PDO Enumeration
            // instances at runtime; their values are PDO Enum instances
            // looked up by name via `Enumeration._values` traversal (the
            // existing fallback in {@code PureContext.coerceToJavaEnum}).
            if (!isMetamodelClass(er.fullPath))
            {
                skipped++;
                continue;
            }
            Path packageDir = outputDir.resolve(toJavaPackage(er.packagePath).replace('.', '/'));
            Files.createDirectories(packageDir);

            // No enum marker interface emitted; `<X>Enum` implements
            // PropertyAccessor directly.
            Files.write(packageDir.resolve(er.name + "Enum.java"),
                    generateEnumClass(er).getBytes(StandardCharsets.UTF_8));
            enumCount++;
        }

        if (fbsSchema != null)
        {
            generateFlatBufferWriter(outputDir);
        }

        // The "skipped" tally is everything outside meta::pure::metamodel::*
        // — those classes deliberately use the PDO + PureShapeRegistry path
        // at runtime instead of generated PDBHelpers. The previous "already on
        // classpath" label was misleading; this is by design, not a
        // collision.
        System.out.println("    Generated " + interfaces + " interfaces, "
                + helpers + " helpers, " + enumCount + " enums ("
                + skipped + " non-metamodel skipped — use PDO at runtime)");
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

    /**
     * Heuristic: is the Pure class part of the bootstrap metamodel (one that
     * actually needs an FB-decoder XPDBHelper)? Everything outside is treated as a
     * user/test class — its instances are Pure-built at runtime via
     * {@code TruffleInstanceFactory.createInstance} → {@code PureDynamicObject}
     * and don't go through per-class FB decode. Class-element-side metadata
     * (property types, equality keys) is populated at PDB-load time by the
     * loader walking the {@code Class} element's {@code properties} sequence,
     * not via {@code XPDBHelper.static{}} blocks.
     */
    private static boolean isMetamodelClass(String fullPath)
    {
        // Codegen for Pure metamodel ONLY. compiler / protocol / functions
        // Pure classes work through the PDO + PureShapeRegistry +
        // PropertyMetadataRegistry.ensurePopulated path — same as any
        // user-defined class.
        return fullPath.startsWith("meta::pure::metamodel::");
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
                        pr.multiplicity = multiplicityName(mult);

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
                        pr.multiplicity = multiplicityName(mult);

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
                findClass(g) != null || helperExistsOnClasspath(resolveFullPath(g)));
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

        // Body intentionally empty: post-PDO-migration the typed interface
        // is just a *marker* in the Java type system — used by the few
        // remaining `instanceof Class`/`Type`/etc. checks in the AST builder
        // and a handful of typed-cast sites in the runtime. The previously
        // emitted default `_X()` / `_X(v)` bodies routed through
        // `PropertyAccessor` but were never called; the universal
        // `PureObj.read`/`write` is the runtime surface. `_copy()` likewise
        // had no live caller after the typed-Any._copy() paths were dropped.
        sb.append("\n{\n}\n");
        return sb.toString();
    }

    // =========================================================================
    // PDBHelper generation
    // =========================================================================

    /**
     * Emits the merged Pure-type PDBHelper class. One class per Pure type
     * supports BOTH:
     * <ul>
     *   <li><b>Pure-built mode</b> — default constructor, fields populated
     *       via setters from {@code ^X(prop=val)} construction.</li>
     *   <li><b>PDB-loaded mode</b> — {@code (fb, resolver[, parent])}
     *       constructor; fields lazy-decoded from a FlatBuffer source on
     *       first read, then cached.</li>
     * </ul>
     *
     * <p>This replaces the previous two-class design ({@code XPDBHelper} +
     * {@code XFlatBufferWrapper}) which forced every Pure property access
     * to carry a bi-morphic class guard at runtime — even though Pure's
     * type system says regular (non-QP) properties are statically dispatched.
     * After the merge, every site is monomorphic at the Java class level
     * and PE specialises directly to the merged class.</p>
     *
     * <p>Storage uses an {@link #UNSET_CLASS_NAME UNSET} sentinel per
     * property to distinguish "never set / not yet decoded" from "set to
     * null". The sentinel lives at the package-level via the static field
     * (only emitted on classes that have FBW backing).</p>
     */
    private String generateHelper(ClassRecord cr)
    {
        StringBuilder sb = new StringBuilder();
        String pkg = toJavaPackage(cr.packagePath);

        // Determine if this class has a FlatBuffer schema definition. If so,
        // we generate the merged class with FBW lazy-decode capability.
        // Otherwise we generate the plain PDBHelper form (no fb field, no UNSET
        // sentinel — saves memory for classes that can never come from PDB).
        String defName = (fbsSchema != null) ? fbsSchema.findDefTableName(cr.name) : null;
        java.util.List<FbsSchema.FbsField> fbsFields = (defName != null) ? fbsSchema.getTableFields(defName) : null;
        boolean hasFbw = (fbsFields != null);

        sb.append("// AUTO-GENERATED from PDB - DO NOT EDIT\n");
        sb.append("package ").append(pkg).append(";\n\n");
        if (hasFbw)
        {
            sb.append("import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;\n");
            sb.append("import org.finos.legend.pure.truffle.types.ObjectSequence;\n");
            sb.append("import org.finos.legend.pure.truffle.types.PureSequence;\n\n");
        }
        sb.append("public class ").append(cr.name).append("PDBHelper");
        sb.append(" implements org.finos.legend.pure.truffle.runtime.PropertyAccessor");
        if (hasFbw)
        {
            sb.append(", org.finos.legend.pure.truffle.runtime.FbParented");
        }
        sb.append("\n{\n");

        // Collect all properties (own + inherited)
        MutableList<PropRecord> allProps = collectAllProperties(cr);

        // UNSET sentinel — only needed for FBW-capable classes to
        // distinguish "never decoded" from "decoded as null".
        if (hasFbw)
        {
            sb.append("    private static final Object UNSET = new Object();\n\n");
            // Static-init: register a PureFbDecoderRegistry decoder for this
            // Pure type so PureDynamicObject.readProperty can fall through
            // to here on cache miss when constructed with a raw XDef as fb.
            // Lambda allocates a transient XPDBHelper and calls its readProperty
            // (which has the per-property decode switch). PureDynamicObject
            // caches the result in its DOL slot so subsequent reads bypass
            // this path entirely.
            sb.append("    static\n    {\n");
            sb.append("        org.finos.legend.pure.truffle.runtime.dynobj.PureFbDecoderRegistry.register(\n");
            sb.append("                \"").append(cr.fullPath).append("\",\n");
            sb.append("                (__name, __fb, __resolver, __parent) -> {\n");
            sb.append("                    ").append(cr.name).append("PDBHelper __tmp = new ").append(cr.name).append("PDBHelper(\n");
            sb.append("                            (").append(FBS_PKG).append(".").append(defName).append(") __fb, __resolver, __parent);\n");
            // Return readProperty's result verbatim — including the ABSENT
            // sentinel for "no such property". PureFbDecoder propagates this
            // up to PureDynamicObject.readProperty so callers like
            // PropertyReadNode.executeOrAbsent can distinguish "missing" from
            // "null value". Coercing to null here was the bug that caused the
            // RawPropertyAccessNode enum-value lookup to short-circuit.
            sb.append("                    return __tmp.readProperty(__name);\n");
            sb.append("                });\n");
            // Register per-property declared types so PDO.writeProperty can
            // run PropertyCoercion (singleton → PureSequence wrapping for [*]
            // properties, enum coercion, etc.). Same paramType the XPDBHelper's
            // typed setter parameter carries.
            for (PropRecord pr : allProps)
            {
                String javaType = resolveJavaType(pr);
                // Typed-interface refs (e.g. ElementOverride, GenericTypeValue)
                // no longer exist post-typed-interface-drop; collapse to
                // Object.class. The registry only uses these to decide
                // whether to run PropertyCoercion at all — Object.class
                // means "no coercion".
                String registered = javaType.startsWith("org.finos.legend.pure.truffle.pdb.")
                        ? "Object" : boxType(javaType);
                sb.append("        org.finos.legend.pure.truffle.runtime.dynobj.PropertyMetadataRegistry.register(\n");
                sb.append("                \"").append(cr.fullPath).append("\", \"").append(pr.name).append("\", ").append(registered).append(".class);\n");
            }
            // Register equality-key property names so PDO.equals/hashCode
            // can match the typed XPDBHelper's `<<equality.Key>>`-based semantics.
            // Walks the hierarchy directly so an override that adds
            // @equality.Key on a redeclared inherited property still gets
            // picked up — `allProps` shadows the override.
            MutableList<String> _keyNames = collectEqualityKeyNames(cr);
            if (!_keyNames.isEmpty())
            {
                sb.append("        org.finos.legend.pure.truffle.runtime.dynobj.PropertyMetadataRegistry.registerEqualityKeys(\n");
                sb.append("                \"").append(cr.fullPath).append("\"");
                for (String kn : _keyNames)
                {
                    sb.append(", \"").append(kn).append("\"");
                }
                sb.append(");\n");
            }
            sb.append("    }\n\n");
        }
        else
        {
            // Non-FBW class: still register property metadata so PDO writes
            // can coerce correctly for Pure-built instances of this type.
            sb.append("    static\n    {\n");
            for (PropRecord pr : allProps)
            {
                String javaType = resolveJavaType(pr);
                // Typed-interface refs (e.g. ElementOverride, GenericTypeValue)
                // no longer exist post-typed-interface-drop; collapse to
                // Object.class. The registry only uses these to decide
                // whether to run PropertyCoercion at all — Object.class
                // means "no coercion".
                String registered = javaType.startsWith("org.finos.legend.pure.truffle.pdb.")
                        ? "Object" : boxType(javaType);
                sb.append("        org.finos.legend.pure.truffle.runtime.dynobj.PropertyMetadataRegistry.register(\n");
                sb.append("                \"").append(cr.fullPath).append("\", \"").append(pr.name).append("\", ").append(registered).append(".class);\n");
            }
            // Register equality-key property names so PDO.equals/hashCode
            // can match the typed XPDBHelper's `<<equality.Key>>`-based semantics.
            // Walks the hierarchy directly so an override that adds
            // @equality.Key on a redeclared inherited property still gets
            // picked up — `allProps` shadows the override.
            MutableList<String> _keyNames = collectEqualityKeyNames(cr);
            if (!_keyNames.isEmpty())
            {
                sb.append("        org.finos.legend.pure.truffle.runtime.dynobj.PropertyMetadataRegistry.registerEqualityKeys(\n");
                sb.append("                \"").append(cr.fullPath).append("\"");
                for (String kn : _keyNames)
                {
                    sb.append(", \"").append(kn).append("\"");
                }
                sb.append(");\n");
            }
            sb.append("    }\n\n");
        }

        // Per-property storage fields dropped post-XPDBHelper-slim: the typed
        // `_X()` getter is gone (interface defaults handle it via
        // PropertyAccessor.readProperty), and XPDBHelpers are transient
        // decoder-lambda locals so per-instance caching adds no value
        // (the PDO's DOL caches the decoded value on the user-visible side).
        // FBW back-reference (null for Pure-built instances). Final so PE
        // can branch-fold the lazy-decode path away when the instance was
        // built via the default constructor.
        if (hasFbw)
        {
            sb.append("    private final ").append(FBS_PKG).append(".").append(defName).append(" fb;\n");
            sb.append("    private final TruffleMetadataAccess resolver;\n");
            sb.append("    private final Object _parent;\n");
        }
        if (!allProps.isEmpty() || hasFbw)
        {
            sb.append("\n");
        }

        // Default constructor (Pure-built mode)
        if (hasFbw)
        {
            sb.append("    public ").append(cr.name).append("PDBHelper()\n");
            sb.append("    {\n        this.fb = null;\n        this.resolver = null;\n        this._parent = null;\n    }\n\n");
            // FBW-built constructors
            sb.append("    public ").append(cr.name).append("PDBHelper(")
                    .append(FBS_PKG).append(".").append(defName).append(" fb, TruffleMetadataAccess resolver)\n");
            sb.append("    {\n        this(fb, resolver, null);\n    }\n\n");
            sb.append("    public ").append(cr.name).append("PDBHelper(")
                    .append(FBS_PKG).append(".").append(defName).append(" fb, TruffleMetadataAccess resolver, Object parent)\n");
            sb.append("    {\n        this.fb = fb;\n        this.resolver = resolver;\n        this._parent = parent;\n    }\n\n");
            sb.append("    @Override\n    public Object _fbParent() { return this._parent; }\n\n");
        }
        else
        {
            sb.append("    public ").append(cr.name).append("PDBHelper() {}\n\n");
        }

        // FBW lazy-decode helpers `__load_X()` — used by `__readRaw__X()`
        // in the readProperty switch when the field is still UNSET. No
        // longer paired with typed `_X()`/`_X(v)` overrides: the typed
        // interface's default bodies route through PropertyAccessor, and
        // post-FB-decode-flip no caller invokes typed getters on an XPDBHelper
        // (decoder lambdas use readProperty directly; user-visible values
        // are PDOs).
        if (hasFbw)
        {
            for (PropRecord pr : allProps)
            {
                String javaType = resolveJavaType(pr);
                FbsSchema.FbsField fbsField = findFbsField(fbsFields, pr.name);
                sb.append("    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary\n");
                sb.append("    private Object __load_").append(pr.name).append("()\n    {\n");
                sb.append("        Object __raw = null;\n");
                generateWrapperGetterBody(sb, pr, fbsField);
                if (pr.isMany || javaType.equals("PureSequence") || javaType.endsWith(".PureSequence"))
                {
                    sb.append("        if (__raw == null) __raw = org.finos.legend.pure.truffle.types.PureSequence.EMPTY;\n");
                }
                sb.append("        return __raw;\n");
                sb.append("    }\n\n");
            }
        }

        // equals() / hashCode() — XPDBHelpers are transient (decoder-lambda
        // locals), never compared. PDO equality goes through the Shape's
        // ShapeMeta.equalityKeys. Default Object equals/hashCode (identity)
        // is sufficient.

        // _copy() — dropped entirely. The typed interface no longer declares
        // `_copy()`; nothing remaining in the runtime calls it on an XPDBHelper.

        appendReadPropertySwitch(sb, allProps, hasFbw);
        appendWritePropertySwitch(sb, allProps, false, hasFbw);

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Emits the {@code readProperty(String)} method required by {@link
     * org.finos.legend.pure.truffle.runtime.PropertyAccessor} — a switch on
     * the property name that delegates to the typed {@code _foo()} accessor
     * already generated above. Used by {@code PropertyReadNode} to dispatch
     * Pure-side {@code $obj.foo} reads without {@code MethodHandle} reflection.
     * Returns {@code PropertyAccessor.ABSENT} for unknown names so callers can
     * distinguish "no such property" from "property is the empty sequence".
     */
    private void appendReadPropertySwitch(StringBuilder sb, MutableList<PropRecord> allProps, boolean hasFbw)
    {
        sb.append("    @Override\n");
        sb.append("    public Object readProperty(String name)\n");
        sb.append("    {\n");
        // FBW classes: switch directly to `__load_X()` (FB decode). XPDBHelpers
        // are transient — the per-instance cache that `__readRaw__X` provided
        // is now redundant because the PDO's DOL caches the decoded value on
        // the user-visible side. Non-FBW classes have no `fb`, no storage
        // fields, so all property reads return the empty sequence (PDO-built
        // values flow through PureObj.write → DOL.put on PDO instead).
        sb.append("        return switch (name)\n");
        sb.append("        {\n");
        for (PropRecord pr : allProps)
        {
            String esc = pr.name.replace("\"", "\\\"");
            if (hasFbw)
            {
                sb.append("            case \"").append(esc).append("\" -> __load_").append(pr.name).append("();\n");
            }
            else
            {
                sb.append("            case \"").append(esc).append("\" -> org.finos.legend.pure.truffle.types.PureSequence.EMPTY;\n");
            }
        }
        sb.append("            default -> org.finos.legend.pure.truffle.runtime.PropertyAccessor.ABSENT;\n");
        sb.append("        };\n");
        sb.append("    }\n\n");
    }

    /**
     * Emits the {@code writeProperty(String, Object)} method required by
     * {@link org.finos.legend.pure.truffle.runtime.PropertyAccessor}. For
     * {@code XPDBHelper} classes this delegates to the typed setter
     * ({@code _foo(coerce(value))}); FlatBuffer wrappers throw
     * {@code UnsupportedOperationException} because they're read-only.
     * Replaces {@code MethodHandle.invoke} reflection in
     * {@code PropertyWriteNode}, which inlined the JDK's
     * {@code SignatureParser} machinery 33 levels deep on every Pure
     * {@code ^X(prop=val)} and contributed the bulk of remaining Graal
     * compilation failures.
     */
    private void appendWritePropertySwitch(StringBuilder sb, MutableList<PropRecord> allProps, boolean readOnly, boolean hasFbw)
    {
        sb.append("    @Override\n");
        sb.append("    public void writeProperty(String name, Object value)\n");
        sb.append("    {\n");
        // Post-FB-decode flip + ProtocolTranslator migration: XPDBHelpers are
        // transient decoder-lambda locals and never receive property writes
        // from any runtime path. Stubbing the body removes ~30 lines × 246
        // XPDBHelpers of dead code while preserving the PropertyAccessor contract.
        if (true)
        {
            sb.append("        throw new UnsupportedOperationException(\"XPDBHelper is transient; write to PDO instead\");\n");
            sb.append("    }\n\n");
            return;
        }
        if (readOnly)
        {
            sb.append("        throw new UnsupportedOperationException(\"Read-only FlatBuffer wrapper\");\n");
        }
        else
        {
            // For FBW-capable classes the field is Object (UNSET-sentinel
            // storage). PropertyCoercion still runs to handle single-value
            // → PureSequence wrapping for [*] properties (NewWithKeysNode's
            // applyDynamicKeyExpressions unwraps single-element sequences,
            // so [*] setters need to re-wrap). Assignment is direct to the
            // Object field — no typed cast that would CCE on PDO values.
            sb.append("        switch (name)\n");
            sb.append("        {\n");
            for (PropRecord pr : allProps)
            {
                String fieldName = escapeKeyword(pr.name);
                String esc = pr.name.replace("\"", "\\\"");
                String javaType = resolveJavaType(pr);
                String boxed = boxType(javaType);
                // Both FBW and plain PDBHelper classes use Object storage post-flip,
                // so the same direct-Object-assignment form works for both.
                // PropertyCoercion handles single-value → PureSequence wrapping
                // for [*] properties; no typed downcast that would CCE on PDO.
                sb.append("            case \"").append(esc).append("\" -> this.").append(fieldName)
                        .append(" = org.finos.legend.pure.truffle.runtime.dynobj.PropertyCoercion.coerce(value, ")
                        .append(boxed).append(".class);\n");
            }
            sb.append("            default -> throw new IllegalArgumentException(\"No such property: \" + name);\n");
            sb.append("        }\n");
        }
        sb.append("    }\n\n");
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

        sb.append("public class ").append(cr.name).append("FlatBufferWrapper implements ").append(cr.name)
                .append(", org.finos.legend.pure.truffle.runtime.FbParented")
                .append(", org.finos.legend.pure.truffle.runtime.PropertyAccessor\n{\n");
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

            // Getter — fast cached path inlines into PE; slow load is behind a
            //   @TruffleBoundary so FlatBuffer deserialization (Utf8Safe,
            //   PointerRef chains, etc.) doesn't pull JDK reflection /
            //   string-bounds / Locale machinery into the caller's compilation.
            sb.append("    @Override\n");
            sb.append("    public ").append(javaType).append(" _").append(pr.name).append("()\n    {\n");
            sb.append("        ").append(boxedType).append(" __cached = ").append(cacheField).append(";\n");
            sb.append("        if (__cached != null) return __cached;\n");
            sb.append("        return __load_").append(pr.name).append("();\n");
            sb.append("    }\n\n");

            sb.append("    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary\n");
            sb.append("    private ").append(javaType).append(" __load_").append(pr.name).append("()\n    {\n");
            sb.append("        Object __raw = null;\n");
            generateWrapperGetterBody(sb, pr, fbsField);
            // Normalize null → PureSequence.EMPTY for sequence-typed fields.
            // The cache lookup at the fast path uses {@code __cached != null};
            // without this normalization an unset [0..1] / [*] field returns
            // null, the cache stays null, and every read re-enters the
            // boundary __load_. With normalization, unset returns become
            // EMPTY (non-null sentinel) and cache properly. Downstream
            // consumers (DirectPropertyAccessNode etc.) no longer need
            // explicit null-vs-EMPTY branches — PE proves the value is
            // non-null and folds the check away.
            if (javaType.equals("PureSequence") || javaType.endsWith(".PureSequence"))
            {
                sb.append("        if (__raw == null) __raw = org.finos.legend.pure.truffle.types.PureSequence.EMPTY;\n");
            }
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
        sb.append("    @Override\n    public ").append(cr.name).append("PDBHelper _copy()\n    {\n");
        sb.append("        ").append(cr.name).append("PDBHelper copy = new ").append(cr.name).append("PDBHelper();\n");
        for (PropRecord pr : allProps)
        {
            sb.append("        copy._").append(pr.name).append("(this._").append(pr.name).append("());\n");
        }
        sb.append("        return copy;\n    }\n\n");

        appendReadPropertySwitch(sb, allProps, true);
        appendWritePropertySwitch(sb, allProps, true, true);

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

    // =========================================================================
    // FlatBuffer writer generation — produces a single GeneratedFlatBufferWriter
    // class with one writeXxx(Xxx) per concrete metamodel class plus a
    // dispatchWrite(Object) entry point. Mirrors the bootstrap-side writer
    // emitted by RdfFbsJavaGenerator, but drives every per-property emit
    // decision off the FBS schema (so field order and slot IDs are identical
    // to what flatc produced from m3.fbs).
    //
    // Phase 1 — skeleton: every writeXxx shell throws UnsupportedOperationException;
    // helpers (writeAncestorRef / writePointerRef / pointerPath / sourceInfo) are
    // fully emitted; dispatchWrite delegates to the shells. Subsequent phases fill
    // in the eight per-property emit cases.
    // =========================================================================

    // The generated FB writer lives alongside the XPDBHelpers in the pdb/codec
    // package — it's part of the PDB codec, not the runtime.
    private static final String WRITER_PKG = "org.finos.legend.pure.truffle.pdb.codec";
    private static final String FBS_FQN = "org.finos.legend.pure.m3.module.pdbModule.fbs";

    private void generateFlatBufferWriter(Path outputDir) throws IOException
    {
        if (fbsSchema == null)
        {
            return; // no schema → no writer
        }

        Path packageDir = outputDir.resolve(WRITER_PKG.replace('.', '/'));
        Files.createDirectories(packageDir);

        StringBuilder sb = new StringBuilder();

        sb.append("// AUTO-GENERATED from PDB - DO NOT EDIT\n");
        sb.append("package ").append(WRITER_PKG).append(";\n\n");

        sb.append("import com.google.flatbuffers.FlatBufferBuilder;\n");
        sb.append("import java.util.IdentityHashMap;\n");
        sb.append("import org.finos.legend.pure.truffle.runtime.PropertyAccessor;\n");
        sb.append("import org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject;\n");
        sb.append("import ").append(FBS_FQN).append(".*;\n\n");
        // Note: helper logic (pathOf, unwrap, sequence ops) is inlined as
        // private static methods below so this file compiles standalone within
        // the codegen module — runtime-side helpers (WriterHelpers,
        // _PackageableElement) live in a downstream module and can't be
        // imported here.

        sb.append("/**\n");
        sb.append(" * Generated FlatBuffer writer for the Truffle PDB metamodel.\n");
        sb.append(" * Mirrors the bootstrap GeneratedFlatBufferWriter, consumes\n");
        sb.append(" * truffle-namespace PDB types, FBS-driven slot ordering.\n");
        sb.append(" */\n");
        sb.append("public final class GeneratedFlatBufferWriter\n{\n");

        // State
        sb.append("    private final FlatBufferBuilder builder;\n");
        sb.append("    private final boolean validateRequired;\n");
        sb.append("    private final IdentityHashMap<Object, Integer> _writing = new IdentityHashMap<>();\n");
        sb.append("    private int _depth = 0;\n\n");

        // Constructors
        sb.append("    public GeneratedFlatBufferWriter(FlatBufferBuilder builder)\n    {\n");
        sb.append("        this(builder, true);\n    }\n\n");
        sb.append("    public GeneratedFlatBufferWriter(FlatBufferBuilder builder, boolean validateRequired)\n    {\n");
        sb.append("        this.builder = builder;\n");
        sb.append("        this.validateRequired = validateRequired;\n    }\n\n");

        emitWriterHelpers(sb);

        // Sort classes by simple name for deterministic output. Exclude
        // abstract classes (no impl to dispatch to) and classes that don't
        // have an FBS table — those are user-defined Pure classes that get
        // serialised as instances of the M3 ClassDef table, not as their
        // own table type.
        MutableList<ClassRecord> writableClasses = classes.valuesView()
                .toSortedListBy(c -> c.name)
                .select(c -> !c.isAbstract && fbsSchema.findDefTableName(c.name) != null);

        for (ClassRecord cr : writableClasses)
        {
            emitWriteMethod(sb, cr);
        }

        emitDispatchWrite(sb, writableClasses);

        sb.append("}\n");

        Files.write(packageDir.resolve("GeneratedFlatBufferWriter.java"),
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void emitWriterHelpers(StringBuilder sb)
    {
        // pathOf — recursive ::-separated path of a PackageableElement,
        // inlined here so the generated writer is self-contained within codegen.
        // Mirrors the bootstrap _PackageableElement.path() / packagePath()
        // pair: the *root anchor* is detected by `_package() == null`, not
        // by matching a sentinel name. Bootstrap creates the root with name
        // `"::"` (m3.ttl convention); compile-pure's synthetic root uses
        // the same name. A name-based check would leak `::::` into every
        // path that walks through it.
        //
        // Pointer short-circuit: a TempCompilerPointer carries its canonical
        // path directly in `.path` and leaves the inherited `.name`/`.package`
        // fields unset. Without this short-circuit, a Pure-compiler skeleton
        // whose `.package` is a `PackagePointer` (pass-1 wrap that keeps parent
        // refs identity-stable) would walk into the pointer, see null name/package,
        // and collapse to a BARE element name (e.g. "IndexEntry" instead of
        // "meta::pure::compiler::IndexEntry") — manifesting as `[PTR-FAIL]`
        // logs at PDB load time. Mirrors bootstrap `_PackageableElement.path`.
        sb.append("    private static String pathOf(Object pe)\n    {\n");
        sb.append("        if (pe == null) { return null; }\n");
        sb.append("        String _ptype = pureTypeOf(pe);\n");
        sb.append("        if (_ptype != null && _ptype.startsWith(\"meta::pure::metamodel::pointer::\"))\n");
        sb.append("        {\n");
        sb.append("            Object _p = readProp(pe, \"path\");\n");
        sb.append("            if (_p instanceof String _ps) { return _ps; }\n");
        sb.append("        }\n");
        sb.append("        Object nameObj = readProp(pe, \"name\");\n");
        sb.append("        String name = nameObj instanceof String _ns ? _ns : null;\n");
        sb.append("        Object pkg = readProp(pe, \"package\");\n");
        sb.append("        if (pkg == null) { return name; }\n");
        sb.append("        String pkgPath = pathOfPkg(pkg);\n");
        sb.append("        return pkgPath.isEmpty() ? name : pkgPath + \"::\" + name;\n");
        sb.append("    }\n\n");
        sb.append("    private static String pathOfPkg(Object pkg)\n    {\n");
        sb.append("        if (pkg == null) { return \"\"; }\n");
        sb.append("        String _ptype = pureTypeOf(pkg);\n");
        sb.append("        if (_ptype != null && _ptype.startsWith(\"meta::pure::metamodel::pointer::\"))\n");
        sb.append("        {\n");
        sb.append("            Object _p = readProp(pkg, \"path\");\n");
        sb.append("            if (_p instanceof String _ps) { return _ps; }\n");
        sb.append("        }\n");
        sb.append("        Object grandparent = readProp(pkg, \"package\");\n");
        sb.append("        Object nameObj = readProp(pkg, \"name\");\n");
        sb.append("        String name = nameObj instanceof String _ns ? _ns : null;\n");
        sb.append("        if (grandparent == null || name == null) { return \"\"; }\n");
        sb.append("        String parentPath = pathOfPkg(grandparent);\n");
        sb.append("        return parentPath.isEmpty() ? name : parentPath + \"::\" + name;\n");
        sb.append("    }\n\n");

        // writeAncestorRef — back-edge for cycles. depth=0 is self,
        // depth=N walks N `_fbParent` hops at read time. Subtract 1 from
        // the natural `_depth - _writing.get(obj)` because the writer
        // increments `_depth` immediately after `_writing.put`, so while
        // we're inside obj, `_depth - _writing.get(obj)` is one off.
        sb.append("    private int writeAncestorRef(Object obj)\n    {\n");
        sb.append("        AncestorRef.startAncestorRef(builder);\n");
        sb.append("        AncestorRef.addDepth(builder, _depth - _writing.get(obj) - 1);\n");
        sb.append("        return AncestorRef.endAncestorRef(builder);\n");
        sb.append("    }\n\n");

        // pointerPath — diagnostic path for validation messages. Uses
        // pureTypeIs to dispatch on the Pure type, falling back to pathOf for
        // any PackageableElement-like value (typed XPDBHelper OR PDO).
        sb.append("    private static String pointerPath(Object obj)\n    {\n");
        sb.append("        if (pureTypeIs(obj, \"meta::pure::metamodel::function::property::QualifiedProperty\"))\n");
        sb.append("        {\n");
        sb.append("            Object _ow = readProp(obj, \"owner\");\n");
        sb.append("            Object _nm = readProp(obj, \"name\");\n");
        sb.append("            if (_ow != null) return pathOf(_ow) + \".qp:\" + _nm;\n");
        sb.append("        }\n");
        sb.append("        if (pureTypeIs(obj, \"meta::pure::metamodel::function::property::Property\"))\n");
        sb.append("        {\n");
        sb.append("            Object _ow = readProp(obj, \"owner\");\n");
        sb.append("            Object _nm = readProp(obj, \"name\");\n");
        sb.append("            if (_ow != null) return pathOf(_ow) + \".\" + _nm;\n");
        sb.append("        }\n");
        sb.append("        if (pureTypeIs(obj, \"meta::pure::metamodel::extension::Stereotype\"))\n");
        sb.append("        {\n");
        sb.append("            Object _pf = readProp(obj, \"profile\");\n");
        sb.append("            Object _vl = readProp(obj, \"value\");\n");
        sb.append("            if (_pf != null) return pathOf(_pf) + \".\" + _vl;\n");
        sb.append("        }\n");
        sb.append("        if (pureTypeIs(obj, \"meta::pure::metamodel::extension::Tag\"))\n");
        sb.append("        {\n");
        sb.append("            Object _pf = readProp(obj, \"profile\");\n");
        sb.append("            Object _vl = readProp(obj, \"value\");\n");
        sb.append("            if (_pf != null) return pathOf(_pf) + \"#\" + _vl;\n");
        sb.append("        }\n");
        sb.append("        if (pureTypeOf(obj) != null)\n");
        sb.append("        {\n            return pathOf(obj);\n        }\n");
        sb.append("        return String.valueOf(obj);\n");
        sb.append("    }\n\n");

        // writePointerRef — typed pointer for union PointerRef fields.
        //
        // TempCompilerPointer arms come FIRST. Pointers are opaque (only .path
        // and .element are populated; all inherited fields like .name / .owner /
        // .package / .profile are null). Reading them through pathOf(...) would
        // NPE on null name/package. Pointer arms read .path (and .element for
        // owner-keyed pointers) directly. pureTypeIs is exact-class — list each
        // pointer subtype we care about.
        sb.append("    private int writePointerRef(Object obj)\n    {\n");
        sb.append("        byte kind;\n        String[] segments;\n");
        // PE pointers: single-path identity, encoded as kind=0 (Element).
        sb.append("        if (pureTypeIs(obj, \"meta::pure::metamodel::pointer::PackageableFunctionPointer\")\n");
        sb.append("                || pureTypeIs(obj, \"meta::pure::metamodel::pointer::ClassPointer\")\n");
        sb.append("                || pureTypeIs(obj, \"meta::pure::metamodel::pointer::PrimitiveTypePointer\")\n");
        sb.append("                || pureTypeIs(obj, \"meta::pure::metamodel::pointer::EnumerationPointer\")\n");
        sb.append("                || pureTypeIs(obj, \"meta::pure::metamodel::pointer::ProfilePointer\")\n");
        sb.append("                || pureTypeIs(obj, \"meta::pure::metamodel::pointer::AssociationPointer\")\n");
        sb.append("                || pureTypeIs(obj, \"meta::pure::metamodel::pointer::PackagePointer\"))\n");
        sb.append("        {\n            kind = 0;\n");
        sb.append("            segments = new String[]{(String) readProp(obj, \"path\")};\n");
        sb.append("        }\n");
        // QualifiedPropertyPointer (kind=2)
        sb.append("        else if (pureTypeIs(obj, \"meta::pure::metamodel::pointer::QualifiedPropertyPointer\"))\n");
        sb.append("        {\n            kind = 2;\n");
        sb.append("            segments = new String[]{(String) readProp(obj, \"path\"), (String) readProp(obj, \"element\")};\n");
        sb.append("        }\n");
        // PropertyPointer (kind=1) — also handles ColumnPointer if a ColumnPointer
        // ever lands here (column has no owner today so this is defensive).
        sb.append("        else if (pureTypeIs(obj, \"meta::pure::metamodel::pointer::PropertyPointer\")\n");
        sb.append("                || pureTypeIs(obj, \"meta::pure::metamodel::pointer::ColumnPointer\"))\n");
        sb.append("        {\n            kind = 1;\n");
        sb.append("            segments = new String[]{(String) readProp(obj, \"path\"), (String) readProp(obj, \"element\")};\n");
        sb.append("        }\n");
        // StereotypePointer (kind=3)
        sb.append("        else if (pureTypeIs(obj, \"meta::pure::metamodel::pointer::StereotypePointer\"))\n");
        sb.append("        {\n            kind = 3;\n");
        sb.append("            segments = new String[]{(String) readProp(obj, \"path\"), (String) readProp(obj, \"element\")};\n");
        sb.append("        }\n");
        // TagPointer (kind=4)
        sb.append("        else if (pureTypeIs(obj, \"meta::pure::metamodel::pointer::TagPointer\"))\n");
        sb.append("        {\n            kind = 4;\n");
        sb.append("            segments = new String[]{(String) readProp(obj, \"path\"), (String) readProp(obj, \"element\")};\n");
        sb.append("        }\n");
        sb.append("        else if (pureTypeIs(obj, \"meta::pure::metamodel::function::property::QualifiedProperty\"))\n");
        sb.append("        {\n            kind = 2;\n");
        sb.append("            Object _ow = readProp(obj, \"owner\");\n");
        sb.append("            Object _nm = readProp(obj, \"name\");\n");
        sb.append("            segments = new String[]{pathOf(_ow), String.valueOf(_nm)};\n");
        sb.append("        }\n");
        sb.append("        else if (pureTypeIs(obj, \"meta::pure::metamodel::function::property::Property\"))\n");
        sb.append("        {\n            kind = 1;\n");
        sb.append("            Object _ow = readProp(obj, \"owner\");\n");
        sb.append("            Object _nm = readProp(obj, \"name\");\n");
        sb.append("            segments = new String[]{pathOf(_ow), String.valueOf(_nm)};\n");
        sb.append("        }\n");
        sb.append("        else if (pureTypeIs(obj, \"meta::pure::metamodel::extension::Stereotype\"))\n");
        sb.append("        {\n            kind = 3;\n");
        sb.append("            Object _pf = readProp(obj, \"profile\");\n");
        sb.append("            Object _vl = readProp(obj, \"value\");\n");
        sb.append("            segments = new String[]{pathOf(_pf), String.valueOf(_vl)};\n        }\n");
        sb.append("        else if (pureTypeIs(obj, \"meta::pure::metamodel::extension::Tag\"))\n");
        sb.append("        {\n            kind = 4;\n");
        sb.append("            Object _pf = readProp(obj, \"profile\");\n");
        sb.append("            Object _vl = readProp(obj, \"value\");\n");
        sb.append("            segments = new String[]{pathOf(_pf), String.valueOf(_vl)};\n        }\n");
        sb.append("        else if (pureTypeOf(obj) != null)\n");
        sb.append("        {\n            kind = 0;\n");
        sb.append("            segments = new String[]{pathOf(obj)};\n        }\n");
        sb.append("        else\n        {\n            kind = 0;\n");
        sb.append("            segments = new String[]{String.valueOf(obj)};\n        }\n");
        sb.append("        int[] segOffsets = new int[segments.length];\n");
        sb.append("        for (int i = 0; i < segments.length; i++) { segOffsets[i] = builder.createString(segments[i]); }\n");
        sb.append("        int pathVector = PointerRef.createPathVector(builder, segOffsets);\n");
        sb.append("        PointerRef.startPointerRef(builder);\n");
        sb.append("        PointerRef.addKind(builder, kind);\n");
        sb.append("        PointerRef.addPath(builder, pathVector);\n");
        sb.append("        return PointerRef.endPointerRef(builder);\n");
        sb.append("    }\n\n");

        // sourceInfo — diagnostic suffix for validation messages.
        sb.append("    private static String sourceInfo(Object obj)\n    {\n");
        sb.append("        Object si = readProp(obj, \"sourceInformation\");\n");
        sb.append("        if (si != null)\n        {\n");
        sb.append("            Object _id = readProp(si, \"sourceId\");\n");
        sb.append("            Object _ln = readProp(si, \"startLine\");\n");
        sb.append("            Object _co = readProp(si, \"startColumn\");\n");
        sb.append("            return \" at \" + _id + \":\" + _ln + \"c\" + _co;\n");
        sb.append("        }\n");
        sb.append("        return \"\";\n");
        sb.append("    }\n\n");

        // readProp — single entry point for reading a Pure property off
        // either a typed XPDBHelper or a PureDynamicObject. Both implement
        // PropertyAccessor. Coerces PropertyAccessor.ABSENT → null so callers
        // see the same "missing" sentinel as the typed `_X()` getter.
        sb.append("    private static Object readProp(Object o, String name)\n    {\n");
        sb.append("        if (o instanceof PropertyAccessor pa)\n");
        sb.append("        {\n");
        sb.append("            Object v = pa.readProperty(name);\n");
        sb.append("            return v == PropertyAccessor.ABSENT ? null : v;\n");
        sb.append("        }\n");
        sb.append("        return null;\n");
        sb.append("    }\n\n");

        // pureTypeOf — extract the Pure type path from a PureDynamicObject
        // via its classInfo (replaces the dropped Shape.dynamicType lookup).
        sb.append("    private static String pureTypeOf(Object o)\n    {\n");
        sb.append("        if (o instanceof PureDynamicObject pdo)\n");
        sb.append("        {\n");
        sb.append("            return pdo.classInfo.purePath;\n");
        sb.append("        }\n");
        sb.append("        return null;\n");
        sb.append("    }\n\n");

        // pureTypeIs — convenience for exact-match leaf checks.
        sb.append("    private static boolean pureTypeIs(Object o, String purePath)\n    {\n");
        sb.append("        String t = pureTypeOf(o);\n");
        sb.append("        return t != null && t.equals(purePath);\n");
        sb.append("    }\n\n");
    }

    /**
     * Build the unique writer-method name for a class. Uses the full Pure
     * path (underscore-separated) so two classes with the same simple name
     * in different packages — e.g. `metamodel.extension.Annotation` and
     * `protocol.grammar.extension.Annotation` — don't collide once we widen
     * the parameter to {@code Object}.
     */
    private String writerMethodName(ClassRecord cr)
    {
        return "write_" + cr.fullPath.replace("::", "_");
    }

    private static final String FUNCTION_EXPRESSION_PATH =
            "meta::pure::metamodel::valuespecification::FunctionExpression";

    private boolean extendsFunctionExpression(ClassRecord cr)
    {
        if (cr == null) return false;
        if (FUNCTION_EXPRESSION_PATH.equals(cr.fullPath)) return true;
        for (String parent : cr.generalizations)
        {
            ClassRecord parentCr = findClass(parent);
            if (parentCr != null && extendsFunctionExpression(parentCr))
            {
                return true;
            }
        }
        return false;
    }

    private void emitWriteMethod(StringBuilder sb, ClassRecord cr)
    {
        // Parameter is Object so both typed XPDBHelper and PureDynamicObject can
        // flow through. Method name uses the full Pure path so two classes
        // sharing a simple name across packages don't collide on the Object
        // overload. Reads inside use readProp(obj, "X") (PropertyAccessor).
        sb.append("    public int ").append(writerMethodName(cr)).append("(Object obj)\n    {\n");
        sb.append("        if (obj == null) { return 0; }\n");
        sb.append("        if (_writing.containsKey(obj)) { return writeAncestorRef(obj); }\n");
        sb.append("        _writing.put(obj, _depth);\n");
        sb.append("        _depth++;\n");

        String defName = fbsSchema.findDefTableName(cr.name);
        java.util.List<FbsSchema.FbsField> fbsFields = (defName != null) ? fbsSchema.getTableFields(defName) : null;
        if (defName == null || fbsFields == null)
        {
            sb.append("        throw new UnsupportedOperationException(\"write").append(cr.name).append(": no FBS table\");\n");
            sb.append("    }\n\n");
            return;
        }

        MutableList<PropRecord> allProps = collectAllProperties(cr);
        // Property + matched FBS field (or null when the prop isn't in the schema).
        // Phase 2a only covers scalar non-union cases (primitive / string / reference).
        // Anything else falls through to a skeleton-style throw.
        boolean allHandleable = true;
        for (PropRecord pr : allProps)
        {
            FbsSchema.FbsField fb = findFbsField(fbsFields, pr.name);
            if (fb == null) continue;
            if (!isHandleablePhase2a(fb))
            {
                allHandleable = false;
                break;
            }
        }
        if (!allHandleable)
        {
            sb.append("        throw new UnsupportedOperationException(\"write").append(cr.name).append(" not yet emitted (vector/union cases pending)\");\n");
            sb.append("    }\n\n");
            return;
        }

        // 1. Required-property validation
        for (PropRecord pr : allProps)
        {
            FbsSchema.FbsField fb = findFbsField(fbsFields, pr.name);
            if (fb == null) continue;
            emitValidation(sb, pr, cr.name);
        }

        // FunctionExpression hierarchy: `func` is declared multiplicity [0..1]
        // on the abstract base (a freshly-parsed FE has it null), but a
        // RESOLVED FE written to PDB must have it set — a null `func` here
        // means compile-pure left an unresolved call (typically an operator
        // like `or`/`||`), and downstream readers see "absent", which
        // surfaces far from origin as an opaque "_func() returned null"
        // crash at lazy Truffle walk. NEVER silent-fail — catch it here.
        if (extendsFunctionExpression(cr))
        {
            sb.append("        if (validateRequired && readProp(obj, \"func\") == null) { throw new IllegalArgumentException(")
              .append("\"Validation error: Property 'func' on resolved '").append(cr.name)
              .append("' is null (functionName='\" + readProp(obj, \"functionName\") + \"') — compile-pure left an unresolved call: \" + pointerPath(obj) + sourceInfo(obj)); }\n");
        }

        // 2. Pre-table offset/value computation
        for (PropRecord pr : allProps)
        {
            FbsSchema.FbsField fb = findFbsField(fbsFields, pr.name);
            if (fb == null) continue;
            emitPreTable(sb, pr, fb, defName);
        }

        // 3. Open the table
        sb.append("        ").append(defName).append(".start").append(defName).append("(builder);\n");

        // 4. In-table field additions
        for (PropRecord pr : allProps)
        {
            FbsSchema.FbsField fb = findFbsField(fbsFields, pr.name);
            if (fb == null) continue;
            emitInTable(sb, pr, fb, defName);
        }

        // 5. Close + cleanup
        sb.append("        int _result = ").append(defName).append(".end").append(defName).append("(builder);\n");
        sb.append("        _depth--;\n");
        sb.append("        _writing.remove(obj);\n");
        sb.append("        return _result;\n");
        sb.append("    }\n\n");
    }

    private static boolean isFbsPrimitive(String type)
    {
        return switch (type)
        {
            case "long", "int", "byte", "short", "ulong", "uint", "ubyte", "ushort",
                 "bool", "float", "double" -> true;
            default -> false;
        };
    }

    /** AtomicValue.value's union — handled specially because its members are
     *  primitive-literal Defs (no Pure ClassRecord counterpart). */
    private static final String ATOMIC_VALUE_CONTENT_UNION = "AtomicValueContentUnion";

    private boolean isHandleablePhase2a(FbsSchema.FbsField fb)
    {
        // Cases 4 and 8 (scalar / vector union) require every concrete
        // (non-special) member to map to a known ClassRecord — except for
        // AtomicValueContentUnion, which we special-case below.
        if (fb.isUnion())
        {
            if (ATOMIC_VALUE_CONTENT_UNION.equals(fb.type())) return true;
            java.util.List<String> members = fbsSchema.getUnionMembers(fb.type());
            if (members == null) return false;
            for (String m : members)
            {
                if (FbsSchema.isSpecialRef(m)) continue;
                String pureName = FbsSchema.defToPureClassName(m);
                if (findClassByShortName(pureName) == null) return false;
            }
            return true;
        }
        return true;  // cases 1, 2, 3, 5, 6, 7
    }

    private void emitValidation(StringBuilder sb, PropRecord pr, String className)
    {
        // readProp returns Object; works for both XPDBHelper (typed) and PDO.
        if ("PureOne".equals(pr.multiplicity))
        {
            sb.append("        if (validateRequired && readProp(obj, \"").append(pr.name).append("\") == null) { throw new IllegalArgumentException(")
                    .append("\"Validation error: Property '").append(pr.name).append("' on '").append(className)
                    .append("' has multiplicity [1] but is null: \" + pointerPath(obj) + sourceInfo(obj)); }\n");
        }
        else if ("OneMany".equals(pr.multiplicity))
        {
            sb.append("        { Object _v = readProp(obj, \"").append(pr.name).append("\");\n");
            sb.append("          if (validateRequired && (_v == null || (_v instanceof org.finos.legend.pure.truffle.types.PureSequence _s && _s.isEmpty()))) { throw new IllegalArgumentException(")
                    .append("\"Validation error: Property '").append(pr.name).append("' on '").append(className)
                    .append("' has multiplicity [1..*] but is null or empty: \" + pointerPath(obj) + sourceInfo(obj)); } }\n");
        }
    }

    private void emitPreTable(StringBuilder sb, PropRecord pr, FbsSchema.FbsField fb, String defName)
    {
        // Variables keep the keyword-escaped camel; method-name suffixes go
        // through resolveCreateVectorSuffix which looks up flatc's actual
        // generated form (varies between Java-only and FBS-side keywords).
        String camel = FbsSchema.snakeToCamel(fb.name());
        String methodCamel = resolveCreateVectorSuffix(defName, camel);
        // Case 1: scalar primitive — no pre-table local needed (read inline at addX time)
        if (!fb.isVector() && !fb.isUnion() && isFbsPrimitive(fb.type()))
        {
            return;
        }
        // Case 2: scalar string (covers plain String, Enum-as-name, and PointerValue-as-path)
        if (!fb.isVector() && !fb.isUnion() && "string".equals(fb.type()))
        {
            String enumFqn = enumValueFqn(pr);
            String shortName = pr.typeName == null ? "" : (pr.typeName.contains("::") ? pr.typeName.substring(pr.typeName.lastIndexOf("::") + 2) : pr.typeName);
            if (enumFqn != null)
            {
                // Post enum-to-PDO migration: every enum value is a PDO with
                // a {@code name} property; the {@code XEnum} class still
                // holds the PDO singletons but isn't a Java enum.
                sb.append("        int ").append(camel).append("Off = 0;\n");
                sb.append("        { Object _e = readProp(obj, \"").append(pr.name).append("\");\n");
                sb.append("          if (_e != null) {\n");
                sb.append("            Object _en = readProp(_e, \"name\");\n");
                sb.append("            if (_en instanceof String _ens) ").append(camel).append("Off = builder.createString(_ens);\n");
                sb.append("          } }\n");
            }
            else if ("String".equals(shortName))
            {
                // Plain String value.
                sb.append("        int ").append(camel).append("Off = 0;\n");
                sb.append("        { Object _s = readProp(obj, \"").append(pr.name).append("\"); if (_s instanceof String _ss) ").append(camel).append("Off = builder.createString(_ss); }\n");
            }
            else
            {
                // Pointer-typed property whose FBS encoding is the path string.
                // Defensive: instanceof PointerValue, fall back to toString().
                sb.append("        int ").append(camel).append("Off = 0;\n");
                sb.append("        { Object _s_").append(camel).append(" = readProp(obj, \"").append(pr.name).append("\");\n");
                sb.append("          if (_s_").append(camel).append(" != null) {\n");
                sb.append("            String _str_").append(camel).append(" = pureTypeIs(_s_").append(camel).append(", \"meta::pure::protocol::grammar::PointerValue\") ? String.valueOf(((org.finos.legend.pure.truffle.runtime.PropertyAccessor) _s_").append(camel).append(").readProperty(\"value\")) : String.valueOf(_s_").append(camel).append(");\n");
                sb.append("            ").append(camel).append("Off = builder.createString(_str_").append(camel).append(");\n");
                sb.append("          } }\n");
            }
            return;
        }
        // Case 3: scalar reference (writes a child table inline, dispatched on runtime type).
        // PointerRef-typed fields (e.g. FunctionInvocation.func, ArrowInvocation.func)
        // serialize as typed pointers, NOT inline tables — otherwise top-level
        // PackageableElements get re-emitted recursively and the buffer blows up.
        if (!fb.isVector() && !fb.isUnion())
        {
            String writeCall = "PointerRef".equals(fb.type()) ? "writePointerRef" : "dispatchWrite";
            sb.append("        int ").append(camel).append("Off = 0;\n");
            sb.append("        { Object _v = readProp(obj, \"").append(pr.name).append("\"); if (_v != null) ").append(camel).append("Off = ").append(writeCall).append("(_v); }\n");
            return;
        }
        // Case 4: scalar union (e.g. classifierGenericType, multiplicity)
        if (!fb.isVector() && fb.isUnion())
        {
            if (ATOMIC_VALUE_CONTENT_UNION.equals(fb.type()))
            {
                emitAtomicValueContentPreTable(sb, pr, camel);
                return;
            }
            emitUnionScalarPreTable(sb, pr, fb, camel);
            return;
        }
        // Case 5: vector primitive ([long], [bool], [double], …)
        if (fb.isVector() && !fb.isUnion() && isFbsPrimitive(fb.type()))
        {
            emitVectorPrimitivePreTable(sb, pr, fb, camel, methodCamel, defName);
            return;
        }
        // Case 6: vector string (covers plain strings, Enum.name lists, PointerValue paths)
        if (fb.isVector() && !fb.isUnion() && "string".equals(fb.type()))
        {
            emitVectorStringPreTable(sb, pr, fb, camel, methodCamel, defName);
            return;
        }
        // Case 7: vector reference ([ChildDef])
        if (fb.isVector() && !fb.isUnion())
        {
            emitVectorRefPreTable(sb, pr, fb, camel, methodCamel, defName);
            return;
        }
        // Case 8: vector union ([SomeUnion]) — parallel offsets + type-tag arrays.
        if (fb.isVector() && fb.isUnion())
        {
            emitVectorUnionPreTable(sb, pr, fb, camel, methodCamel, defName);
            return;
        }
    }

    private void emitVectorUnionPreTable(StringBuilder sb, PropRecord pr, FbsSchema.FbsField fb, String camel, String methodCamel, String defName)
    {
        String unionName = fb.type();
        java.util.List<String> members = fbsSchema.getUnionMembers(unionName);
        sb.append("        int ").append(camel).append("Vec = 0;\n");
        sb.append("        int ").append(camel).append("TypeVec = 0;\n");
        sb.append("        { Object _raw_").append(camel).append(" = readProp(obj, \"").append(pr.name).append("\");\n");
        sb.append("        if (_raw_").append(camel).append(" instanceof org.finos.legend.pure.truffle.types.PureSequence _seq_").append(camel).append(" && !_seq_").append(camel).append(".isEmpty())\n");
        sb.append("        {\n");
        sb.append("            int _n_").append(camel).append(" = _seq_").append(camel).append(".size();\n");
        sb.append("            int[] _offs_").append(camel).append(" = new int[_n_").append(camel).append("];\n");
        sb.append("            byte[] _types_").append(camel).append(" = new byte[_n_").append(camel).append("];\n");
        sb.append("            for (int i = 0; i < _n_").append(camel).append("; i++)\n");
        sb.append("            {\n");
        sb.append("                Object _item = _seq_").append(camel).append(".getBoxed(i);\n");

        if (members != null)
        {
            int ancestorIdx = members.indexOf("AncestorRef");
            int pointerIdx = members.indexOf("PointerRef");
            boolean wroteFirst = false;

            if (ancestorIdx >= 0)
            {
                sb.append("                if (_writing.containsKey(_item))\n");
                sb.append("                {\n");
                sb.append("                    _offs_").append(camel).append("[i] = writeAncestorRef(_item);\n");
                sb.append("                    _types_").append(camel).append("[i] = ").append(ancestorIdx + 1).append(";\n");
                sb.append("                }\n");
                wroteFirst = true;
            }
            // Concrete Def members first (typed + PDO twin), PointerRef as fallback.
            for (int i = 0; i < members.size(); i++)
            {
                String member = members.get(i);
                if (FbsSchema.isSpecialRef(member)) continue;
                String pureName = FbsSchema.defToPureClassName(member);
                ClassRecord cr = findClassByShortName(pureName);
                if (cr == null) continue;
                String typeFqn = toJavaPackage(cr.packagePath) + "." + cr.name;
                sb.append("                ").append(wroteFirst ? "else " : "");
                sb.append("if (pureTypeIs(_item, \"").append(cr.fullPath).append("\"))\n");
                sb.append("                {\n");
                sb.append("                    _offs_").append(camel).append("[i] = ").append(writerMethodName(cr)).append("(_item);\n");
                sb.append("                    _types_").append(camel).append("[i] = ").append(i + 1).append(";\n");
                sb.append("                }\n");
                wroteFirst = true;
            }
            if (pointerIdx >= 0)
            {
                sb.append("                ").append(wroteFirst ? "else " : "");
                sb.append("if (pureTypeOf(_item) != null)\n");
                sb.append("                {\n");
                sb.append("                    _offs_").append(camel).append("[i] = writePointerRef(_item);\n");
                sb.append("                    _types_").append(camel).append("[i] = ").append(pointerIdx + 1).append(";\n");
                sb.append("                }\n");
                wroteFirst = true;
            }
        }
        sb.append("            }\n");
        String createOffsetMethod = "create" + capitalize(methodCamel) + "Vector";
        String createTypeMethod = resolveMethodOnDef(defName,
                "create" + capitalize(methodCamel) + "TypeVector",
                "create" + capitalize(methodCamel) + "typeVector");
        sb.append("            ").append(camel).append("Vec = ").append(defName).append(".").append(createOffsetMethod).append("(builder, _offs_").append(camel).append(");\n");
        sb.append("            ").append(camel).append("TypeVec = ").append(defName).append(".").append(createTypeMethod).append("(builder, _types_").append(camel).append(");\n");
        sb.append("        } }\n");
    }

    private void emitVectorPrimitivePreTable(StringBuilder sb, PropRecord pr, FbsSchema.FbsField fb, String camel, String methodCamel, String defName)
    {
        String primType = primitiveJavaTypeName(fb.type());
        String boxedType = primitiveBoxedTypeName(fb.type());
        sb.append("        int ").append(camel).append("Vec = 0;\n");
        sb.append("        { Object _raw_").append(camel).append(" = readProp(obj, \"").append(pr.name).append("\");\n");
        sb.append("        if (_raw_").append(camel).append(" instanceof org.finos.legend.pure.truffle.types.PureSequence _seq_").append(camel).append(" && !_seq_").append(camel).append(".isEmpty())\n");
        sb.append("        {\n");
        sb.append("            int _n_").append(camel).append(" = _seq_").append(camel).append(".size();\n");
        sb.append("            ").append(primType).append("[] _arr_").append(camel).append(" = new ").append(primType).append("[_n_").append(camel).append("];\n");
        sb.append("            for (int i = 0; i < _n_").append(camel).append("; i++) { _arr_").append(camel).append("[i] = (").append(boxedType).append(") _seq_").append(camel).append(".getBoxed(i); }\n");
        sb.append("            ").append(camel).append("Vec = ").append(defName).append(".create").append(capitalize(methodCamel)).append("Vector(builder, _arr_").append(camel).append(");\n");
        sb.append("        } }\n");
    }

    private void emitVectorStringPreTable(StringBuilder sb, PropRecord pr, FbsSchema.FbsField fb, String camel, String methodCamel, String defName)
    {
        String enumFqn = enumValueFqn(pr);
        String shortName = pr.typeName == null ? "" : (pr.typeName.contains("::") ? pr.typeName.substring(pr.typeName.lastIndexOf("::") + 2) : pr.typeName);
        sb.append("        int ").append(camel).append("Vec = 0;\n");
        sb.append("        { Object _raw_").append(camel).append(" = readProp(obj, \"").append(pr.name).append("\");\n");
        sb.append("        if (_raw_").append(camel).append(" instanceof org.finos.legend.pure.truffle.types.PureSequence _seq_").append(camel).append(" && !_seq_").append(camel).append(".isEmpty())\n");
        sb.append("        {\n");
        sb.append("            int _n_").append(camel).append(" = _seq_").append(camel).append(".size();\n");
        sb.append("            int[] _offs_").append(camel).append(" = new int[_n_").append(camel).append("];\n");
        sb.append("            for (int i = 0; i < _n_").append(camel).append("; i++)\n");
        sb.append("            {\n");
        sb.append("                Object _item = _seq_").append(camel).append(".getBoxed(i);\n");
        if (enumFqn != null)
        {
            // Post enum-to-PDO migration: enum values expose `name` via readProp.
            sb.append("                Object _en = readProp(_item, \"name\"); _offs_").append(camel).append("[i] = (_en instanceof String _ens) ? builder.createString(_ens) : 0;\n");
        }
        else if ("String".equals(shortName))
        {
            sb.append("                _offs_").append(camel).append("[i] = builder.createString((String) _item);\n");
        }
        else
        {
            sb.append("                String _str = pureTypeIs(_item, \"meta::pure::protocol::grammar::PointerValue\") ? String.valueOf(((org.finos.legend.pure.truffle.runtime.PropertyAccessor) _item).readProperty(\"value\")) : String.valueOf(_item);\n");
            sb.append("                _offs_").append(camel).append("[i] = builder.createString(_str);\n");
        }
        sb.append("            }\n");
        sb.append("            ").append(camel).append("Vec = ").append(defName).append(".create").append(capitalize(methodCamel)).append("Vector(builder, _offs_").append(camel).append(");\n");
        sb.append("        } }\n");
    }

    private void emitVectorRefPreTable(StringBuilder sb, PropRecord pr, FbsSchema.FbsField fb, String camel, String methodCamel, String defName)
    {
        // Special case: [PointerRef] — write each element through writePointerRef
        // (the special-ref helper) rather than dispatchWrite (which would emit
        // a concrete Def table instead of a typed PointerRef table).
        boolean isPointerRefVector = "PointerRef".equals(fb.type());
        String writeCall = isPointerRefVector ? "writePointerRef" : "dispatchWrite";
        sb.append("        int ").append(camel).append("Vec = 0;\n");
        sb.append("        { Object _raw_").append(camel).append(" = readProp(obj, \"").append(pr.name).append("\");\n");
        sb.append("        if (_raw_").append(camel).append(" instanceof org.finos.legend.pure.truffle.types.PureSequence _seq_").append(camel).append(" && !_seq_").append(camel).append(".isEmpty())\n");
        sb.append("        {\n");
        sb.append("            int _n_").append(camel).append(" = _seq_").append(camel).append(".size();\n");
        sb.append("            int[] _offs_").append(camel).append(" = new int[_n_").append(camel).append("];\n");
        sb.append("            for (int i = 0; i < _n_").append(camel).append("; i++) { _offs_").append(camel).append("[i] = ").append(writeCall).append("(_seq_").append(camel).append(".getBoxed(i)); }\n");
        sb.append("            ").append(camel).append("Vec = ").append(defName).append(".create").append(capitalize(methodCamel)).append("Vector(builder, _offs_").append(camel).append(");\n");
        sb.append("        } }\n");
    }

    private static String primitiveJavaTypeName(String fbsType)
    {
        return switch (fbsType)
        {
            case "bool" -> "boolean";
            case "byte", "ubyte" -> "byte";
            case "short", "ushort" -> "short";
            case "int", "uint" -> "int";
            case "long", "ulong" -> "long";
            case "float" -> "float";
            case "double" -> "double";
            default -> "Object";
        };
    }

    private static String primitiveBoxedTypeName(String fbsType)
    {
        return switch (fbsType)
        {
            case "bool" -> "Boolean";
            case "byte", "ubyte" -> "Byte";
            case "short", "ushort" -> "Short";
            case "int", "uint" -> "Integer";
            case "long", "ulong" -> "Long";
            case "float" -> "Float";
            case "double" -> "Double";
            default -> "Object";
        };
    }

    private static String capitalize(String s)
    {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Resolve the actual {@code add{Field}} method name on {@code DefName}.
     * flatc varies between {@code addPackage} (Java-only keyword) and
     * {@code addType_} (FBS-side keyword) for the same Pure-side concept of
     * "field name needed escape" — rather than replicate its rules, look up
     * the method by reflection.
     */
    private static String resolveAddMethod(String defName, String camel)
    {
        String stripped = camel.endsWith("_") ? camel.substring(0, camel.length() - 1) : camel;
        String candidateNoUnderscore = "add" + capitalize(stripped);
        String candidateUnderscore = candidateNoUnderscore + "_";
        return resolveMethodOnDef(defName, candidateNoUnderscore, candidateUnderscore);
    }

    /**
     * Same as {@link #resolveAddMethod} but for {@code create{Field}Vector}.
     * Returns the suffix to use (e.g. {@code "Type"} or {@code "Type_"}) so the
     * caller can compose both {@code create{X}Vector} and {@code add{X}}.
     * Returns the bare camel without leading capitalisation, with or without
     * trailing underscore, matching whatever flatc emitted.
     */
    private static String resolveCreateVectorSuffix(String defName, String camel)
    {
        String stripped = camel.endsWith("_") ? camel.substring(0, camel.length() - 1) : camel;
        String candNoUnder = "create" + capitalize(stripped) + "Vector";
        String candUnder = "create" + capitalize(stripped) + "_Vector";
        // Pick whichever method actually exists.
        String chosen = resolveMethodOnDef(defName, candNoUnder, candUnder);
        return chosen.equals(candUnder) ? stripped + "_" : stripped;
    }

    private static String resolveMethodOnDef(String defName, String preferred, String fallback)
    {
        try
        {
            Class<?> defClass = Class.forName("org.finos.legend.pure.m3.module.pdbModule.fbs." + defName);
            for (java.lang.reflect.Method m : defClass.getMethods())
            {
                if (m.getName().equals(preferred)) return preferred;
            }
            for (java.lang.reflect.Method m : defClass.getMethods())
            {
                if (m.getName().equals(fallback)) return fallback;
            }
        }
        catch (Exception ignored)
        {
        }
        return preferred;
    }

    /**
     * Camel-case the FBS field name while preserving any trailing underscore
     * (used by flatc to escape Java keywords like {@code package_}).
     * {@code package_ -> package_}, {@code source_id -> sourceId},
     * {@code start_line -> startLine}.
     */
    private static String fbsFieldIdent(String snake)
    {
        boolean trailingUnderscore = snake.endsWith("_");
        String camel = FbsSchema.snakeToCamel(snake);
        return trailingUnderscore ? camel + "_" : camel;
    }

    /**
     * AtomicValueContentUnion is special: its members are primitive-literal Defs
     * (IntegerValueDef, FloatValueDef, BooleanValueDef, StringValueDef,
     * DecimalValueDef) plus LambdaFunctionDef and PointerRef. The runtime value
     * is a Java primitive (Long, Double, Boolean, String, BigDecimal) or a
     * LambdaFunction or a PackageableElement (for enum-pointer-style references).
     * We construct the matching Def table inline at write time.
     *
     * <p>Tag indices come from the FBS union declaration:
     * IntegerValueDef=1, FloatValueDef=2, BooleanValueDef=3, StringValueDef=4,
     * LambdaFunctionDef=5, PointerRef=6, DecimalValueDef=7.</p>
     */
    private void emitAtomicValueContentPreTable(StringBuilder sb, PropRecord pr, String camel)
    {
        sb.append("        int ").append(camel).append("Off = 0;\n");
        sb.append("        byte ").append(camel).append("Type = 0;\n");
        sb.append("        { Object _av = readProp(obj, \"").append(pr.name).append("\");\n");
        sb.append("        if (_av != null)\n");
        sb.append("        {\n");
        // 1. Long → IntegerValueDef
        sb.append("            if (_av instanceof Long _av_long)\n");
        sb.append("            {\n");
        sb.append("                IntegerValueDef.startIntegerValueDef(builder);\n");
        sb.append("                IntegerValueDef.addVal(builder, _av_long);\n");
        sb.append("                ").append(camel).append("Off = IntegerValueDef.endIntegerValueDef(builder);\n");
        sb.append("                ").append(camel).append("Type = 1;\n");
        sb.append("            }\n");
        // 2. Double → FloatValueDef
        sb.append("            else if (_av instanceof Double _av_dbl)\n");
        sb.append("            {\n");
        sb.append("                FloatValueDef.startFloatValueDef(builder);\n");
        sb.append("                FloatValueDef.addVal(builder, _av_dbl);\n");
        sb.append("                ").append(camel).append("Off = FloatValueDef.endFloatValueDef(builder);\n");
        sb.append("                ").append(camel).append("Type = 2;\n");
        sb.append("            }\n");
        // 3. Boolean → BooleanValueDef
        sb.append("            else if (_av instanceof Boolean _av_bool)\n");
        sb.append("            {\n");
        sb.append("                BooleanValueDef.startBooleanValueDef(builder);\n");
        sb.append("                BooleanValueDef.addVal(builder, _av_bool);\n");
        sb.append("                ").append(camel).append("Off = BooleanValueDef.endBooleanValueDef(builder);\n");
        sb.append("                ").append(camel).append("Type = 3;\n");
        sb.append("            }\n");
        // 4. java.math.BigDecimal → DecimalValueDef (encoded as string)
        sb.append("            else if (_av instanceof java.math.BigDecimal _av_dec)\n");
        sb.append("            {\n");
        sb.append("                int _av_decOff = builder.createString(_av_dec.toPlainString());\n");
        sb.append("                DecimalValueDef.startDecimalValueDef(builder);\n");
        sb.append("                DecimalValueDef.addVal(builder, _av_decOff);\n");
        sb.append("                ").append(camel).append("Off = DecimalValueDef.endDecimalValueDef(builder);\n");
        sb.append("                ").append(camel).append("Type = 7;\n");
        sb.append("            }\n");
        // 5. LambdaFunction → write directly via the LambdaFunction writer.
        sb.append("            else if (pureTypeIs(_av, \"meta::pure::metamodel::function::LambdaFunction\"))\n");
        sb.append("            {\n");
        sb.append("                ").append(camel).append("Off = write_meta_pure_metamodel_function_LambdaFunction(_av);\n");
        sb.append("                ").append(camel).append("Type = 5;\n");
        sb.append("            }\n");
        // 6. Any other PDO with a Pure type (covers PackageableElement subtypes) → PointerRef
        sb.append("            else if (pureTypeOf(_av) != null)\n");
        sb.append("            {\n");
        sb.append("                ").append(camel).append("Off = writePointerRef(_av);\n");
        sb.append("                ").append(camel).append("Type = 6;\n");
        sb.append("            }\n");
        // 7. String → StringValueDef
        sb.append("            else if (_av instanceof String _av_str)\n");
        sb.append("            {\n");
        sb.append("                int _av_strOff = builder.createString(_av_str);\n");
        sb.append("                StringValueDef.startStringValueDef(builder);\n");
        sb.append("                StringValueDef.addVal(builder, _av_strOff);\n");
        sb.append("                ").append(camel).append("Off = StringValueDef.endStringValueDef(builder);\n");
        sb.append("                ").append(camel).append("Type = 4;\n");
        sb.append("            }\n");
        sb.append("        } }\n");
    }

    private void emitUnionScalarPreTable(StringBuilder sb, PropRecord pr, FbsSchema.FbsField fb, String camel)
    {
        String unionName = fb.type();
        java.util.List<String> members = fbsSchema.getUnionMembers(unionName);
        sb.append("        int ").append(camel).append("Off = 0;\n");
        sb.append("        byte ").append(camel).append("Type = 0;\n");
        // Self-ref flows through the AncestorRef branch (encoded explicitly
        // as depth=0), so `uType == 0` strictly means "field absent."
        // Reads through readProp so both XPDBHelper and PDO receivers work.
        // Dispatch order: AncestorRef (cycle break) → concrete Defs (typed +
        // PDO twin) → PointerRef as fallback for any remaining PE-like value.
        sb.append("        { Object _u_").append(camel).append(" = readProp(obj, \"").append(pr.name).append("\");\n");
        sb.append("        if (_u_").append(camel).append(" != null)\n");
        sb.append("        {\n");

        boolean wroteFirst = false;
        if (members != null)
        {
            int ancestorIdx = members.indexOf("AncestorRef");
            if (ancestorIdx >= 0)
            {
                sb.append("            if (_writing.containsKey(_u_").append(camel).append("))\n");
                sb.append("            {\n");
                sb.append("                ").append(camel).append("Off = writeAncestorRef(_u_").append(camel).append(");\n");
                sb.append("                ").append(camel).append("Type = ").append(ancestorIdx + 1).append(";\n");
                sb.append("            }\n");
                wroteFirst = true;
            }
            // Concrete Def members first (each gets a typed instanceof branch
            // AND a PDO pure-type-path branch so PDO union members route too).
            for (int i = 0; i < members.size(); i++)
            {
                String member = members.get(i);
                if (FbsSchema.isSpecialRef(member)) continue;
                String pureName = FbsSchema.defToPureClassName(member);
                ClassRecord cr = findClassByShortName(pureName);
                if (cr == null) continue;
                sb.append("            ").append(wroteFirst ? "else " : "");
                sb.append("if (pureTypeIs(_u_").append(camel).append(", \"").append(cr.fullPath).append("\"))\n");
                sb.append("            {\n");
                sb.append("                ").append(camel).append("Off = ").append(writerMethodName(cr)).append("(_u_").append(camel).append(");\n");
                sb.append("                ").append(camel).append("Type = ").append(i + 1).append(";\n");
                sb.append("            }\n");
                wroteFirst = true;
            }
            // PointerRef as fallback — covers PE-like values not matched above.
            int pointerIdx = members.indexOf("PointerRef");
            if (pointerIdx >= 0)
            {
                sb.append("            ").append(wroteFirst ? "else " : "");
                sb.append("if (pureTypeOf(_u_").append(camel).append(") != null)\n");
                sb.append("            {\n");
                sb.append("                ").append(camel).append("Off = writePointerRef(_u_").append(camel).append(");\n");
                sb.append("                ").append(camel).append("Type = ").append(pointerIdx + 1).append(";\n");
                sb.append("            }\n");
                wroteFirst = true;
            }
        }
        sb.append("        } }\n");
    }

    private void emitInTable(StringBuilder sb, PropRecord pr, FbsSchema.FbsField fb, String defName)
    {
        // FbsSchema.snakeToCamel appends `_` for keywords. Variables keep the
        // escape. Method names depend on what flatc actually emitted (it varies:
        // Java-only keywords like `package` drop the `_`, FBS-side keywords like
        // `type` keep it). We resolve via reflection.
        String camel = FbsSchema.snakeToCamel(fb.name());
        String addMethod = resolveAddMethod(defName, camel);

        // Case 1: scalar primitive
        if (!fb.isVector() && !fb.isUnion() && isFbsPrimitive(fb.type()))
        {
            String defaultLit = primitiveDefaultLiteral(fb.type());
            String boxedType = primitiveBoxedTypeName(fb.type());
            // readProp returns Object; cast through the boxed type for the
            // typed FBS add(...). Works for both XPDBHelper (returns boxed) and PDO.
            sb.append("        { Object _p = readProp(obj, \"").append(pr.name).append("\");\n");
            sb.append("          if (_p != null) ").append(defName).append(".").append(addMethod).append("(builder, (").append(boxedType).append(") _p); }\n");
            // Rely on FBS default-omission for null; no explicit add when null.
            return;
        }
        // Cases 2 and 3: scalar string or scalar reference — emit Def.addX(builder, off) when nonzero
        if (!fb.isVector() && !fb.isUnion())
        {
            sb.append("        if (").append(camel).append("Off != 0) { ").append(defName).append(".").append(addMethod).append("(builder, ").append(camel).append("Off); }\n");
            return;
        }
        // Case 4: scalar union — emit Def.add{X}Type(builder, type) AND Def.add{X}(builder, off) when type != 0
        if (!fb.isVector() && fb.isUnion())
        {
            String addTypeMethod = resolveTypeTagAddMethod(defName, addMethod);
            sb.append("        if (").append(camel).append("Type != 0) { ")
                    .append(defName).append(".").append(addTypeMethod).append("(builder, ").append(camel).append("Type); ")
                    .append(defName).append(".").append(addMethod).append("(builder, ").append(camel).append("Off); }\n");
            return;
        }
        // Cases 5, 6, 7: vector (non-union) — emit Def.addX(builder, vec) when nonzero
        if (fb.isVector() && !fb.isUnion())
        {
            sb.append("        if (").append(camel).append("Vec != 0) { ").append(defName).append(".").append(addMethod).append("(builder, ").append(camel).append("Vec); }\n");
            return;
        }
        // Case 8: vector union — emit both the offsets vector AND the types vector.
        if (fb.isVector() && fb.isUnion())
        {
            String addTypeMethod = resolveTypeTagAddMethod(defName, addMethod);
            sb.append("        if (").append(camel).append("Vec != 0) { ")
                    .append(defName).append(".").append(addTypeMethod).append("(builder, ").append(camel).append("TypeVec); ")
                    .append(defName).append(".").append(addMethod).append("(builder, ").append(camel).append("Vec); }\n");
            return;
        }
    }

    /**
     * For a union field whose offset-add method is e.g. {@code addType_}, the
     * type-tag-add method might be {@code addType_type} (FBS-keyword style) or
     * {@code addType_Type} (capital). For non-keyword fields like
     * {@code classifier_generic_type}, the offset-add is
     * {@code addClassifierGenericType} and the type-tag-add is
     * {@code addClassifierGenericTypeType}.
     */
    private static String resolveTypeTagAddMethod(String defName, String addOffsetMethod)
    {
        String capital = addOffsetMethod + "Type";
        String lower = addOffsetMethod + "type";
        return resolveMethodOnDef(defName, capital, lower);
    }

    private static String primitiveDefaultLiteral(String fbsType)
    {
        return switch (fbsType)
        {
            case "bool" -> "false";
            case "float", "double" -> "0.0";
            default -> "0L";
        };
    }

    /**
     * If the property's Pure type resolves to an enum, return the FQN of the
     * generated enum's Java class (e.g. {@code AggregationKindEnum}); otherwise null.
     */
    private String enumValueFqn(PropRecord pr)
    {
        if (pr.typeName == null) return null;
        String shortName = pr.typeName.contains("::") ? pr.typeName.substring(pr.typeName.lastIndexOf("::") + 2) : pr.typeName;
        EnumRecord er = findEnum(pr.typeName);
        if (er == null) er = findEnumByShortName(shortName);
        if (er == null) return null;
        return toJavaPackage(er.packagePath) + "." + er.name + "Enum";
    }

    private void emitDispatchWrite(StringBuilder sb, MutableList<ClassRecord> writableClasses)
    {
        sb.append("    /** Dispatch on runtime type to the corresponding write method.\n");
        sb.append("     *  Single-pass pure-type switch — every Pure value is a PDO\n");
        sb.append("     *  post-PDO-flip. */\n");
        sb.append("    public int dispatchWrite(Object obj)\n    {\n");
        sb.append("        if (obj == null) { return 0; }\n");
        sb.append("        String __pt = pureTypeOf(obj);\n");
        sb.append("        if (__pt != null)\n");
        sb.append("        {\n");
        sb.append("            switch (__pt)\n");
        sb.append("            {\n");
        for (ClassRecord cr : writableClasses)
        {
            sb.append("                case \"").append(cr.fullPath).append("\" -> { return ").append(writerMethodName(cr)).append("(obj); }\n");
        }
        sb.append("                default -> { /* fall through to throw */ }\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        throw new IllegalArgumentException(\"GeneratedFlatBufferWriter has no writer for: \" + obj.getClass().getName());\n");
        sb.append("    }\n");
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
                    sb.append("          if (path != null) { __raw = resolver.getElement(path); if (__raw == null) __raw = org.finos.legend.pure.truffle.runtime.dynobj.FbsResolverHelper.resolveNestedElement(path, resolver); } }\n");
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
                sb.append("              if (ref != null && ref.pathLength() > 0) { arr[i] = org.finos.legend.pure.truffle.runtime.dynobj.FbsResolverHelper.resolvePointerRef(ref, resolver); }\n");
            }
            else if (isStringPointerVector && "Stereotype".equals(typeShortName))
            {
                // Stereotype: "profilePath.StereotypeName" → resolve Profile, find by name
                sb.append("              String path = fb.").append(camel).append("(i);\n");
                sb.append("              if (path != null) {\n");
                sb.append("                int dotIdx = path.lastIndexOf('.');\n");
                sb.append("                if (dotIdx > 0) {\n");
                sb.append("                  Object prof = resolver.getElement(path.substring(0, dotIdx));\n");
                sb.append("                  if (prof != null) {\n");
                sb.append("                    String stName = path.substring(dotIdx + 1);\n");
                sb.append("                    Object _ps = ((org.finos.legend.pure.truffle.runtime.PropertyAccessor) prof).readProperty(\"p_stereotypes\");\n");
                sb.append("                    if (_ps instanceof org.finos.legend.pure.truffle.types.PureSequence stSeq) for (Object st : stSeq.toBoxedArray()) {\n");
                sb.append("                      Object _v = ((org.finos.legend.pure.truffle.runtime.PropertyAccessor) st).readProperty(\"value\");\n");
                sb.append("                      if (stName.equals(_v)) { arr[i] = st; break; }\n");
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
                sb.append("                  if (prof != null) {\n");
                sb.append("                    String tName = path.substring(hashIdx + 1);\n");
                sb.append("                    Object _ts = ((org.finos.legend.pure.truffle.runtime.PropertyAccessor) prof).readProperty(\"p_tags\");\n");
                sb.append("                    if (_ts instanceof org.finos.legend.pure.truffle.types.PureSequence tSeq) for (Object t : tSeq.toBoxedArray()) {\n");
                sb.append("                      Object _v = ((org.finos.legend.pure.truffle.runtime.PropertyAccessor) t).readProperty(\"value\");\n");
                sb.append("                      if (tName.equals(_v)) { arr[i] = t; break; }\n");
                sb.append("                    }\n");
                sb.append("                  }\n");
                sb.append("                }\n");
                sb.append("              }\n");
            }
            else if (isStringPointerVector)
            {
                sb.append("              String path = fb.").append(camel).append("(i);\n");
                sb.append("              if (path != null) { arr[i] = resolver.getElement(path); if (arr[i] == null) arr[i] = org.finos.legend.pure.truffle.runtime.dynobj.FbsResolverHelper.resolveNestedElement(path, resolver); }\n");
            }
            else
            {
                sb.append("              var item = fb.").append(camel).append("(i);\n");
                String _wppA = resolveWrapperPurePath(defType);
                if (_wppA != null)
                {
                    sb.append("              if (item == null) throw new RuntimeException(\"Null element in FBS array for ").append(_wppA).append("\");\n");
                    sb.append("              arr[i] = new org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject(\n");
                    sb.append("                      org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.classInfoFor(\"").append(_wppA).append("\", resolver),\n");
                    sb.append("                      item, resolver, this);\n");
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
                sb.append("          __raw = (ref != null && ref.pathLength() > 0) ? org.finos.legend.pure.truffle.runtime.dynobj.FbsResolverHelper.resolvePointerRef(ref, resolver) : null; }\n");
            }
            else
            {
                String wrapperPurePath = resolveWrapperPurePath(defType);
                if (wrapperPurePath != null)
                {
                    sb.append("        { var raw = fb.").append(camel).append("();\n");
                    sb.append("          __raw = raw != null\n");
                    sb.append("                  ? new org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject(\n");
                    sb.append("                          org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.classInfoFor(\"").append(wrapperPurePath).append("\", resolver),\n");
                    sb.append("                          raw, resolver, this)\n");
                    sb.append("                  : null; }\n");
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
                generateUnionCase(sb, idx + 1, members.get(idx), camelName, true, unionName);
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
        // `uType == 0` strictly means absent. Self-references are encoded
        // explicitly as `AncestorRef(depth=0)` and resolve to `this` via
        // the AncestorRef case below.
        sb.append("        { byte uType = fb.").append(camelName).append(typeSuffix2).append("();\n");
        sb.append("          if (uType == 0) { __raw = null; }\n");
        sb.append("          else { switch (uType) {\n");
        if (members != null)
        {
            for (int idx = 0; idx < members.size(); idx++)
            {
                generateUnionCase(sb, idx + 1, members.get(idx), camelName, false, unionName);
            }
        }
        sb.append("            default: __raw = null; break;\n");
        sb.append("          } } }\n");
    }

    private void generateUnionCase(StringBuilder sb, int discriminator, String defName,
                                   String camelName, boolean isVector, String unionName)
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
                  .append(target).append(" = org.finos.legend.pure.truffle.runtime.dynobj.FbsResolverHelper.resolvePointerRef(pr, resolver); } break; }\n");
            }
            else
            {
                sb.append("                case ").append(discriminator).append(": { ")
                        .append(FBS_PKG).append(".AncestorRef ar = (").append(FBS_PKG)
                        .append(".AncestorRef) fb.").append(camelName).append("(new ").append(FBS_PKG)
                        .append(".AncestorRef()");
                if (isVector) sb.append(", i");
                sb.append("); if (ar != null) { Object t = this; for (int d = 0; d < ar.depth(); d++) { if (!(t instanceof org.finos.legend.pure.truffle.runtime.FbParented fp)) break; t = fp._fbParent(); if (t == null) break; } ")
                  .append(target).append(" = t; } break; }\n");
            }
            return;
        }

        String wrapperPurePath = resolveWrapperPurePath(defName);
        sb.append("                case ").append(discriminator).append(": { ")
                .append(FBS_PKG).append(".").append(defName).append(" d = (")
                .append(FBS_PKG).append(".").append(defName).append(") fb.").append(camelName)
                .append("(new ").append(FBS_PKG).append(".").append(defName).append("()");
        if (isVector) sb.append(", i");
        sb.append("); ");
        if (wrapperPurePath != null)
        {
            sb.append("if (d != null) ").append(target).append(" = new org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject(")
                    .append("org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.classInfoFor(\"").append(wrapperPurePath).append("\", resolver), d, resolver, this); ");
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
            if (ATOMIC_VALUE_CONTENT_UNION.equals(unionName))
            {
                // AtomicValue.value can hold an Enum instance. The bootstrap
                // writer (RdfFbsJavaGenerator emit for AtomicValue.value)
                // flattens such values into a StringValueDef containing the
                // enum-value path "OwnerEnum.ValueName" — there's no dedicated
                // EnumValueDef union member, and Enum PDOs are too small to
                // pay the table overhead. Mirror Java-direct's
                // AtomicValueFlatBufferWrapper: when the AtomicValue's
                // genericType.rawType is an Enumeration, rebuild a
                // PureDynamicObject for the enum value with `name` set so
                // downstream Pure code (enumValues(), .name->toLower(),
                // match dispatch) sees a proper Enum PDO instead of a raw
                // String. Plain-string AtomicValues fall through untouched.
                sb.append("if (d != null) ").append(target)
                  .append(" = org.finos.legend.pure.truffle.runtime.dynobj.AtomicValueEnumReconstructor.reconstruct(d.val(), readProperty(\"genericType\"), resolver); ");
            }
            else
            {
                sb.append("if (d != null) ").append(target).append(" = d.val(); ");
            }
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
            // Post-XFBW-drop: nested decode constructs PDBHelper directly (which
            // has the same {@code (XDef fb, TruffleMetadataAccess resolver,
            // Object parent)} constructor as the old FBW had).
            return toJavaPackage(cr.packagePath) + "." + cr.name + "PDBHelper";
        }
        return null;
    }

    /** Pure-form path for a given FBS def name, or {@code null} if no class is registered. */
    private String resolveWrapperPurePath(String defName)
    {
        if (FbsSchema.isSpecialRef(defName) || FbsSchema.isPrimitiveValueDef(defName))
        {
            return null;
        }
        String pureName = FbsSchema.defToPureClassName(defName);
        ClassRecord cr = findClassByShortName(pureName);
        return cr != null ? cr.fullPath : null;
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
        String cls = er.name + "Enum";
        String pdoFqn = "org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject";
        String registryFqn = "org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry";
        String enumRegFqn = "org.finos.legend.pure.truffle.runtime.dynobj.PureEnumRegistry";
        String enumerationPath = (er.packagePath.isEmpty() ? "" : er.packagePath + "::") + er.name;

        sb.append("// AUTO-GENERATED from PDB - DO NOT EDIT\n");
        sb.append("package ").append(pkg).append(";\n\n");

        // The previous design used a Java {@code enum} for JVM-singleton
        // identity. That broke the post-migration invariant that every Pure
        // metamodel value is a {@link PureDynamicObject} (Java enums extend
        // {@link java.lang.Enum} and can't be re-parented to PDO), forcing
        // an {@code instanceof Enum} fallback at every property-read site.
        // This class holds PDO singletons instead — identity-comparison via
        // {@code ==} still works, but every value is a real PDO and the
        // typed read paths apply uniformly. Each singleton's Pure
        // Enumeration path is registered in {@link PureEnumRegistry} so the
        // CGT can be resolved lazily per-context (the resolver isn't
        // available at class-load time).
        sb.append("public final class ").append(cls).append("\n{\n");
        sb.append("    private ").append(cls).append("() {}\n\n");

        sb.append("    public static final String ENUMERATION_PATH = \"").append(enumerationPath).append("\";\n\n");

        for (String v : er.values)
        {
            sb.append("    public static final ").append(pdoFqn).append(" ").append(v)
                    .append(" = makeValue(\"").append(v).append("\");\n");
        }
        sb.append("\n");

        sb.append("    private static final java.util.Map<String, ").append(pdoFqn).append("> BY_NAME = new java.util.HashMap<>();\n\n");

        sb.append("    static\n    {\n");
        for (String v : er.values)
        {
            sb.append("        BY_NAME.put(\"").append(v).append("\", ").append(v).append(");\n");
        }
        sb.append("    }\n\n");

        sb.append("    private static ").append(pdoFqn).append(" makeValue(String name)\n    {\n");
        sb.append("        ").append(pdoFqn).append(" pdo = new ").append(pdoFqn).append("(\n");
        sb.append("                ").append(registryFqn).append(".enumClassInfo(), null, null, null);\n");
        sb.append("        pdo.writeProperty(\"name\", name);\n");
        sb.append("        ").append(enumRegFqn).append(".register(pdo, ENUMERATION_PATH);\n");
        sb.append("        return pdo;\n");
        sb.append("    }\n\n");

        sb.append("    public static ").append(pdoFqn).append(" valueOf(String name)\n    {\n");
        sb.append("        ").append(pdoFqn).append(" v = BY_NAME.get(name);\n");
        sb.append("        if (v == null) throw new IllegalArgumentException(\"No enum value '\" + name + \"' in ").append(cls).append("\");\n");
        sb.append("        return v;\n");
        sb.append("    }\n");

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

    /**
     * Collect equality-key property names for a class. Uses the
     * **most-derived** declaration of each property: an own declaration
     * (with or without @equality.Key) overrides the inherited one. This
     * handles both:
     * - adding @equality.Key to an inherited property (own says key, parent didn't)
     * - dropping @equality.Key from an inherited property (own declares without key,
     *   parent had it as key)
     */
    private MutableList<String> collectEqualityKeyNames(ClassRecord cr)
    {
        // Walk hierarchy gathering the most-derived declaration per property name.
        java.util.LinkedHashMap<String, Boolean> declarations = new java.util.LinkedHashMap<>();
        gatherDeclarations(cr, declarations, Sets.mutable.empty());
        MutableList<String> keys = Lists.mutable.empty();
        for (java.util.Map.Entry<String, Boolean> e : declarations.entrySet())
        {
            if (Boolean.TRUE.equals(e.getValue())) keys.add(e.getKey());
        }
        return keys;
    }

    private void gatherDeclarations(ClassRecord cr,
                                     java.util.LinkedHashMap<String, Boolean> declarations,
                                     MutableSet<String> visited)
    {
        if (visited.contains(cr.fullPath)) return;
        visited.add(cr.fullPath);
        // Parents first — gives base declarations; own then overrides.
        for (String parentName : cr.generalizations)
        {
            ClassRecord parent = findClass(parentName);
            if (parent != null) gatherDeclarations(parent, declarations, visited);
        }
        // Own declarations override inherited ones.
        for (PropRecord pr : cr.properties)
        {
            declarations.put(pr.name, pr.isEqualityKey);
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

    /**
     * Classify a {@link Multiplicity} into the four canonical names used by
     * the writer for required-property validation. Returns {@code null} for
     * any multiplicity outside the {0,1,*}-bound family — those properties
     * get no validation gate.
     */
    private static String multiplicityName(Multiplicity mult)
    {
        if (!(mult instanceof ConcreteMultiplicity cm))
        {
            return null;
        }
        Long lowerBox = cm._lowerBound() == null ? null : cm._lowerBound()._value();
        long lower = lowerBox == null ? 0 : lowerBox;
        boolean upperUnbounded = cm._upperBound() == null || cm._upperBound()._value() == null;
        long upper = upperUnbounded ? -1 : cm._upperBound()._value();
        if (!upperUnbounded && upper == 1 && lower == 1) return "PureOne";
        if (!upperUnbounded && upper == 1 && lower == 0) return "ZeroOne";
        if (upperUnbounded && lower == 1) return "OneMany";
        if (upperUnbounded && lower == 0) return "ZeroMany";
        return null;
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

    private static boolean helperExistsOnClasspath(String fullPurePath)
    {
        String javaClassName = fullPurePath.replace("::", ".") + "PDBHelper";
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
        // Multiplicity name used by the writer for required-property validation.
        // One of: "PureOne" (lower=1, upper=1), "ZeroOne" (0,1), "OneMany" (1,*),
        // "ZeroMany" (0,*), or null when the multiplicity is not one of those
        // four canonical shapes.
        String multiplicity;
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

        System.out.println();
        System.out.println("PDB Java Generator");
        System.out.println("==================");

        // Load PDB modules
        MutableList<PDBModule> modules = Lists.mutable.empty();
        MutableList<String> moduleNames = Lists.mutable.empty();
        java.util.List<String> inputPaths = new java.util.ArrayList<>();
        for (int i = 1; i < args.length; i++)
        {
            if ("--fbs".equals(args[i]))
            {
                i++; // skip the path argument
                continue;
            }
            Path pdbPath = Path.of(args[i]);
            // Canonicalize for the banner — Maven passes
            // ${project.basedir}/../../../../shared/core.pdb which is
            // functionally correct but unreadable in build logs.
            inputPaths.add(pdbPath.toAbsolutePath().normalize().toString());
            PDBModule pdb = new PDBModule(pdbPath, PDBModule.Mode.EXECUTION);
            modules.add(pdb);
            moduleNames.add(pdb.getName());
        }
        // Load FBS schema if --fbs flag is provided (for wrapper generation)
        FbsSchema fbsSchema = null;
        for (int i = 1; i < args.length - 1; i++)
        {
            if ("--fbs".equals(args[i]))
            {
                fbsSchema = FbsSchema.parse(Path.of(args[i + 1]));
                inputPaths.add(Path.of(args[i + 1]).toAbsolutePath().normalize().toString());
                break;
            }
        }
        for (int i = 0; i < inputPaths.size(); i++)
        {
            System.out.println((i == 0 ? "  Inputs: " : "          ") + inputPaths.get(i));
        }
        System.out.println("  Output: " + outputDir.toAbsolutePath().normalize());

        // Build and compile the model (required for FlatBuffer elements to resolve)
        MutableList<org.finos.legend.pure.m3.module.Module> moduleList = Lists.mutable.empty();
        moduleList.addAllIterable(modules);
        PureModel model = PureModel.withModules(moduleList)
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        // Use a SINGLE generator — collect from compiled model for reliable generalization resolution
        PdbJavaGenerator generator = new PdbJavaGenerator(null, outputDir);
        generator.setFbsSchema(fbsSchema);
        generator.collectFromCompiledModel(moduleList);
        generator.generateAll();
    }
}
