// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.truffle.compiler.module.pdbModule;

import org.finos.legend.pure.truffle.execution.PureDynamicObject;
import org.finos.legend.pure.truffle.compiler.module.PureClassRegistry;
import org.finos.legend.pure.truffle.compiler.module.EnumValueSingletons;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.finos.legend.pure.truffle.compiler.module.MetadataAccess;
import org.finos.legend.pure.truffle.execution.types.ObjectSequence;
import org.finos.legend.pure.truffle.execution.types.PureSequence;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Schema-driven FlatBuffer property decoder — the single runtime replacement
 * for the 74 generated {@code XPDBHelper} decode switches. Interprets
 * {@code m3.fbs} (parsed once from the classpath) against the flatc-generated
 * {@code *Def} accessors via cached reflection, producing exactly the values
 * the generated {@code __load_*} methods produced:
 *
 * <ul>
 *   <li>string scalars — raw for primitive-typed Pure properties; enum-value
 *       PDO ({@link EnumValueSingletons}) when the Pure property type is an
 *       Enumeration; otherwise element-path resolution</li>
 *   <li>numeric/bool scalars — raw (boxed as returned by flatc)</li>
 *   <li>scalar/vector tables — nested {@link PureDynamicObject}s, parented to
 *       the reading PDO so the parent chain has ONE node per FlatBuffer table
 *       level (matching the reference Java wrappers' {@code _fbParent} chain;
 *       the generated helpers interleaved transient helper objects)</li>
 *   <li>{@code PointerRef} — {@link FbsResolverHelper#resolvePointerRef}</li>
 *   <li>{@code AncestorRef} — walk the PDO parent chain {@code depth} hops,
 *       the reference reader's semantics (the generated walk stopped at the
 *       first PDO because PDOs don't implement {@code FbParented})</li>
 *   <li>unions (scalar + vector) — discriminator dispatch over the schema's
 *       member list; primitive value defs unwrap {@code val()};
 *       {@code StringValueDef} inside {@code AtomicValueContentUnion} goes
 *       through {@link AtomicValueEnumReconstructor}; {@code DecimalValueDef}
 *       becomes {@link java.math.BigDecimal}</li>
 *   <li>Pure properties with no fbs field (e.g. {@code elementOverride}) —
 *       {@code null}, as generated</li>
 * </ul>
 *
 * <p>String-field semantics (raw vs enum vs pointer vs Stereotype/Tag) depend
 * on the PURE property type, which the generator baked in at codegen time.
 * Here it is resolved lazily per (table, field) by walking the owning class
 * definition through the resolver, and memoised. The walk itself reads
 * properties through this decoder; a thread-local in-flight guard breaks the
 * resulting recursion by treating a string field as raw (uncached) while its
 * own resolution is in progress — every string field readable on the
 * class-definition walk ({@code name} etc.) genuinely is raw, so the
 * fallback is exact, and the final memoised verdict is computed outside the
 * guard.</p>
 */
public final class GenericFbDecoder
{
    private static final String FBS_PKG = "org.finos.legend.pure.m3.module.pdbModule.fbs";
    private static final String SCHEMA_RESOURCE = "generated-specification/m3.fbs";
    private static final String ENUMERATION_PATH = "meta::pure::metamodel::type::Enumeration";
    private static final String ATOMIC_VALUE_CONTENT_UNION = "AtomicValueContentUnion";

    private GenericFbDecoder() {}

    // =========================================================================
    // Entry point (called directly by PureDynamicObject.decodeFromFb)
    // =========================================================================

    /**
     * @param parent the {@link PureDynamicObject} whose slot is being read —
     *               the face of the table {@code fb} belongs to; becomes the
     *               parent of nested PDOs and the AncestorRef walk origin
     */
    @TruffleBoundary
    public static Object decode(String name, Object fb, MetadataAccess resolver, Object parent)
    {
        if (fb == null)
        {
            return null;
        }
        FieldPlan plan = planFor(fb.getClass()).fields.get(name);
        if (plan != null)
        {
            return plan.read(fb, resolver, parent);
        }
        // No fbs field. A DECLARED Pure property without wire data
        // (elementOverride, …) decodes to null — the generated __load did the
        // same. A name that isn't a property at all returns ABSENT, matching
        // the generated readProperty switch default: callers depend on the
        // distinction (RawPropertyAccessNode falls through ABSENT on
        // `Enumeration.ValueName` to the enum-value lookup; a null there
        // would BE the value and read as an unset [1] property).
        if (parent instanceof PureDynamicObject pdo && pdo.classInfo.slotIndex(name) >= 0)
        {
            return null;
        }
        return org.finos.legend.pure.truffle.execution.PropertyAccessor.ABSENT;
    }

    // =========================================================================
    // Schema
    // =========================================================================

    private static volatile FbsSchema schema;

    private static FbsSchema schema()
    {
        FbsSchema s = schema;
        if (s == null)
        {
            synchronized (GenericFbDecoder.class)
            {
                s = schema;
                if (s == null)
                {
                    schema = s = loadSchema();
                }
            }
        }
        return s;
    }

    private static FbsSchema loadSchema()
    {
        try (InputStream in = GenericFbDecoder.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE))
        {
            if (in == null)
            {
                throw new IllegalStateException("Schema resource not on classpath: " + SCHEMA_RESOURCE);
            }
            return FbsSchema.parseContent(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        catch (java.io.IOException e)
        {
            throw new IllegalStateException("Failed to read schema resource " + SCHEMA_RESOURCE, e);
        }
    }

    // =========================================================================
    // Def table name → Pure path (the generator's findClassByShortName,
    // re-derived at runtime from the loaded element universe)
    // =========================================================================

    private static final ConcurrentHashMap<String, String> DEF_PURE_PATH = new ConcurrentHashMap<>();

    static
    {
        // Seed the M3 tables with their well-known metamodel paths (the same
        // fixed mapping the retired TYPE_TO_WRAPPER table encoded). Keeps
        // root-element typing and nested decode independent of which archives
        // the resolver happens to hold — a bare user archive contains no
        // metamodel elements to scan. Anything NOT seeded (schema growth)
        // still resolves via the elementPaths suffix scan below.
        String p = "meta::pure::metamodel::";
        String vs = p + "valuespecification::";
        String gt = p + "type::generics::";
        String mu = p + "multiplicity::";
        String[][] wellKnown = {
                {"UserDefinedFunction", p + "function::UserDefinedFunction"},
                {"NativeFunction", p + "function::NativeFunction"},
                {"LambdaFunction", p + "function::LambdaFunction"},
                {"Class", p + "type::Class"},
                {"Enumeration", p + "type::Enumeration"},
                {"PrimitiveType", p + "type::PrimitiveType"},
                {"FunctionType", p + "type::FunctionType"},
                {"Property", p + "function::property::Property"},
                {"QualifiedProperty", p + "function::property::QualifiedProperty"},
                {"ArrowInvocation", vs + "ArrowInvocation"},
                {"AtomicValue", vs + "AtomicValue"},
                {"Collection", vs + "Collection"},
                {"DotApplication", vs + "DotApplication"},
                {"FunctionInvocation", vs + "FunctionInvocation"},
                {"VariableExpression", vs + "VariableExpression"},
                {"GenericTypeAndMultiplicityHolder", vs + "GenericTypeAndMultiplicityHolder"},
                {"UserDefinedGenericTypeAndMultiplicityHolder", vs + "UserDefinedGenericTypeAndMultiplicityHolder"},
                {"CompilerGenericTypeAndMultiplicityHolder", vs + "CompilerGenericTypeAndMultiplicityHolder"},
                {"UserDefinedGenericType", gt + "UserDefinedGenericType"},
                {"UserDefinedPackageableGenericType", gt + "UserDefinedPackageableGenericType"},
                {"InferredGenericType", gt + "InferredGenericType"},
                {"InferredPackageableGenericType", gt + "InferredPackageableGenericType"},
                {"UndefinedGenericType", gt + "UndefinedGenericType"},
                {"CompilerNotSetGenericType", gt + "CompilerNotSetGenericType"},
                {"TypeParameter", gt + "TypeParameter"},
                {"ResolvedTypeParameter", gt + "ResolvedTypeParameter"},
                {"ResolvedMultiplicityParameter", gt + "ResolvedMultiplicityParameter"},
                {"GenericTypeOperation", p + "relation::GenericTypeOperation"},
                {"UserDefinedAdHocMultiplicity", mu + "UserDefinedAdHocMultiplicity"},
                {"UserDefinedPackageableMultiplicity", mu + "UserDefinedPackageableMultiplicity"},
                {"UserDefinedMultiplicityParameter", mu + "UserDefinedMultiplicityParameter"},
                {"InferredAdHocMultiplicity", mu + "InferredAdHocMultiplicity"},
                {"InferredPackageableMultiplicity", mu + "InferredPackageableMultiplicity"},
                {"InferredMultiplicityParameter", mu + "InferredMultiplicityParameter"},
                {"UndefinedMultiplicity", mu + "UndefinedMultiplicity"},
                {"CompilerNotSetMultiplicity", mu + "CompilerNotSetMultiplicity"},
                {"MultiplicityValue", mu + "MultiplicityValue"},
                {"Package", p + "Package"},
                {"Association", p + "relationship::Association"},
                {"Generalization", p + "relationship::Generalization"},
                {"Constraint", p + "constraint::Constraint"},
                {"Profile", p + "extension::Profile"},
                {"Enum", p + "type::Enum"},
                {"Stereotype", p + "extension::Stereotype"},
                {"Tag", p + "extension::Tag"},
                {"TaggedValue", p + "extension::TaggedValue"},
                {"Annotation", p + "extension::Annotation"},
                {"SourceInformation", p + "SourceInformation"},
                {"ConstraintsGetterOverride", p + "constraint::ConstraintsGetterOverride"},
                {"Relation", p + "relation::Relation"},
                {"RelationElementAccessor", p + "relation::RelationElementAccessor"},
                {"RelationType", p + "relation::RelationType"},
                {"Column", p + "relation::Column"},
                {"Nil", p + "type::Nil"},
                {"Test", p + "testable::Test"},
        };
        for (String[] entry : wellKnown)
        {
            DEF_PURE_PATH.put(entry[0] + "Def", entry[1]);
        }
    }

    /**
     * Pure path for an m3.fbs table name (also the archive entry type name +
     * "Def"). Public: {@code PdbModule} uses it to type root elements —
     * the replacement for its hardcoded TYPE_TO_WRAPPER map.
     */
    @TruffleBoundary
    public static String purePathForDef(String defName, MetadataAccess resolver)
    {
        String cached = DEF_PURE_PATH.get(defName);
        if (cached != null)
        {
            return cached;
        }
        String pureName = FbsSchema.defToPureClassName(defName);
        String suffix = "::" + pureName;
        String best = null;
        for (String path : resolver.elementPaths())
        {
            if (!path.endsWith(suffix))
            {
                continue;
            }
            if (best == null || rank(path) < rank(best)
                    || (rank(path) == rank(best) && path.compareTo(best) < 0))
            {
                best = path;
            }
        }
        if (best == null)
        {
            throw new IllegalStateException("Cannot map FBS table '" + defName
                    + "' to a Pure class: no loaded element named '" + pureName + "'");
        }
        DEF_PURE_PATH.put(defName, best);
        return best;
    }

    /** Metamodel classes win over same-named user classes deterministically. */
    private static int rank(String path)
    {
        if (path.startsWith("meta::pure::metamodel::"))
        {
            return 0;
        }
        return path.startsWith("meta::pure::") ? 1 : 2;
    }

    // =========================================================================
    // Per-Def-class decode plans
    // =========================================================================

    private static final ConcurrentHashMap<Class<?>, TablePlan> PLANS = new ConcurrentHashMap<>();

    private static TablePlan planFor(Class<?> defClass)
    {
        TablePlan plan = PLANS.get(defClass);
        if (plan == null)
        {
            plan = buildPlan(defClass);
            PLANS.putIfAbsent(defClass, plan);
        }
        return plan;
    }

    private static final class TablePlan
    {
        final ConcurrentHashMap<String, FieldPlan> fields = new ConcurrentHashMap<>();
    }

    private enum Shape
    {
        STRING_SCALAR, PRIM_SCALAR, TABLE_SCALAR, POINTERREF_SCALAR, UNION_SCALAR,
        STRING_VECTOR, PRIM_VECTOR, TABLE_VECTOR, POINTERREF_VECTOR, UNION_VECTOR
    }

    private static TablePlan buildPlan(Class<?> defClass)
    {
        TablePlan plan = new TablePlan();
        String tableName = defClass.getSimpleName();
        List<FbsSchema.FbsField> fields = schema().getTableFields(tableName);
        if (fields == null)
        {
            return plan; // not an m3.fbs table — every property decodes to null
        }
        for (FbsSchema.FbsField f : fields)
        {
            // The Pure property name is derived from the fbs field name, but
            // the writer's pure→fbs mapping (camelToSnake) is not invertible:
            // "expression_sequence" ← "expressionSequence", yet
            // "p_stereotypes" ← "p_stereotypes" verbatim. Register the plan
            // under BOTH candidates (dropping the Java-keyword escape:
            // "package" → snakeToCamel → "package_"); each bogus alias can
            // never collide with another field's real Pure name.
            String camelKey = FbsSchema.fbsFieldToPureProperty(f.name());
            if (camelKey.endsWith("_"))
            {
                camelKey = camelKey.substring(0, camelKey.length() - 1);
            }
            try
            {
                FieldPlan fieldPlan = new FieldPlan(tableName, camelKey, f, defClass);
                plan.fields.put(camelKey, fieldPlan);
                plan.fields.put(f.name(), fieldPlan);
            }
            catch (ReflectiveOperationException e)
            {
                throw new IllegalStateException("Cannot plan decode of " + tableName + "." + f.name(), e);
            }
        }
        return plan;
    }

    private static final class FieldPlan
    {
        final String tableName;
        final String pureProperty;
        final String rawFieldName; // fbs spelling — fallback Pure name (see buildPlan)
        final Shape shape;
        final Method get;        // scalar getter / indexed vector getter / union value getter
        final Method length;     // vectors only
        final Method unionType;  // unions only: camelType() or camelType(int)
        final String unionName;  // unions only
        final UnionMember[] members; // unions only, index = discriminator - 1
        final String tableDefName;   // table fields only
        private volatile StringTypeInfo stringInfo; // string fields, lazily resolved

        FieldPlan(String tableName, String pureProperty, FbsSchema.FbsField f, Class<?> defClass)
                throws ReflectiveOperationException
        {
            this.tableName = tableName;
            this.pureProperty = pureProperty;
            this.rawFieldName = f.name();
            String camel = FbsSchema.snakeToCamel(f.name());
            Class<?> fbTable = com.google.flatbuffers.Table.class;
            if (f.isUnion())
            {
                this.shape = f.isVector() ? Shape.UNION_VECTOR : Shape.UNION_SCALAR;
                this.unionName = f.type();
                this.get = f.isVector()
                        ? defClass.getMethod(camel, fbTable, int.class)
                        : defClass.getMethod(camel, fbTable);
                this.unionType = unionTypeMethod(defClass, camel, f.isVector());
                this.length = f.isVector() ? defClass.getMethod(camel + "Length") : null;
                List<String> memberNames = schema().getUnionMembers(f.type());
                if (memberNames == null)
                {
                    throw new IllegalStateException("Unknown union type: " + f.type());
                }
                this.members = new UnionMember[memberNames.size()];
                for (int i = 0; i < memberNames.size(); i++)
                {
                    this.members[i] = new UnionMember(memberNames.get(i));
                }
                this.tableDefName = null;
            }
            else
            {
                this.unionName = null;
                this.unionType = null;
                this.members = null;
                boolean isTable = schema().hasTable(f.type()) || "PointerRef".equals(f.type()) || "AncestorRef".equals(f.type());
                switch (f.type())
                {
                    case "string":
                        this.shape = f.isVector() ? Shape.STRING_VECTOR : Shape.STRING_SCALAR;
                        this.tableDefName = null;
                        break;
                    case "long": case "int": case "double": case "bool": case "float": case "short": case "byte": case "ubyte":
                        this.shape = f.isVector() ? Shape.PRIM_VECTOR : Shape.PRIM_SCALAR;
                        this.tableDefName = null;
                        break;
                    default:
                        if (!isTable)
                        {
                            throw new IllegalStateException("Unsupported fbs field type '" + f.type()
                                    + "' on " + tableName + "." + f.name());
                        }
                        if ("PointerRef".equals(f.type()))
                        {
                            this.shape = f.isVector() ? Shape.POINTERREF_VECTOR : Shape.POINTERREF_SCALAR;
                        }
                        else
                        {
                            this.shape = f.isVector() ? Shape.TABLE_VECTOR : Shape.TABLE_SCALAR;
                        }
                        this.tableDefName = f.type();
                        break;
                }
                this.get = f.isVector() ? defClass.getMethod(camel, int.class) : defClass.getMethod(camel);
                this.length = f.isVector() ? defClass.getMethod(camel + "Length") : null;
            }
        }

        /**
         * flatc names the union discriminator accessor {@code <camel>Type()}
         * — except for keyword-escaped fields, where it comes out as e.g.
         * {@code type_type()} (base {@code type_}); resolve empirically.
         */
        private static Method unionTypeMethod(Class<?> defClass, String camel, boolean isVector)
                throws NoSuchMethodException
        {
            Class<?>[] params = isVector ? new Class<?>[]{int.class} : new Class<?>[0];
            try
            {
                return defClass.getMethod(camel + "Type", params);
            }
            catch (NoSuchMethodException e)
            {
                return defClass.getMethod(camel + "type", params);
            }
        }

        Object read(Object fb, MetadataAccess resolver, Object parent)
        {
            try
            {
                return switch (shape)
                {
                    case PRIM_SCALAR -> get.invoke(fb);
                    case STRING_SCALAR -> decodeStringScalar((String) get.invoke(fb), fb, resolver);
                    case POINTERREF_SCALAR ->
                    {
                        var pr = (org.finos.legend.pure.m3.module.pdbModule.fbs.PointerRef) get.invoke(fb);
                        yield (pr != null && pr.pathLength() > 0)
                                ? FbsResolverHelper.resolvePointerRef(pr, resolver) : null;
                    }
                    case TABLE_SCALAR ->
                    {
                        Object nested = get.invoke(fb);
                        yield nested != null ? nestedPdo(tableDefName, nested, resolver, parent) : null;
                    }
                    case UNION_SCALAR ->
                    {
                        byte uType = (Byte) unionType.invoke(fb);
                        yield uType == 0 ? null : decodeUnionMember(uType, fb, null, resolver, parent);
                    }
                    case PRIM_VECTOR, STRING_VECTOR, POINTERREF_VECTOR, TABLE_VECTOR -> decodeVector(fb, resolver, parent);
                    case UNION_VECTOR ->
                    {
                        int len = (Integer) length.invoke(fb);
                        Object[] arr = new Object[len];
                        for (int i = 0; i < len; i++)
                        {
                            byte uType = (Byte) unionType.invoke(fb, i);
                            arr[i] = uType == 0 ? null : decodeUnionMember(uType, fb, i, resolver, parent);
                        }
                        yield toSequence(arr);
                    }
                };
            }
            catch (RuntimeException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite
                        ? ite.getCause() : e;
                if (cause instanceof RuntimeException re)
                {
                    throw re;
                }
                throw new IllegalStateException("Decode failed for " + tableName + "." + pureProperty, cause);
            }
        }

        private Object decodeVector(Object fb, MetadataAccess resolver, Object parent) throws Exception
        {
            int len = (Integer) length.invoke(fb);
            if (len == 0)
            {
                return new ObjectSequence(new Object[0]);
            }
            Object[] arr = new Object[len];
            for (int i = 0; i < len; i++)
            {
                switch (shape)
                {
                    case PRIM_VECTOR -> arr[i] = get.invoke(fb, i);
                    case STRING_VECTOR -> arr[i] = decodeStringVectorItem((String) get.invoke(fb, i), resolver);
                    case POINTERREF_VECTOR ->
                    {
                        var ref = (org.finos.legend.pure.m3.module.pdbModule.fbs.PointerRef) get.invoke(fb, i);
                        if (ref != null && ref.pathLength() > 0)
                        {
                            arr[i] = FbsResolverHelper.resolvePointerRef(ref, resolver);
                        }
                    }
                    case TABLE_VECTOR ->
                    {
                        Object item = get.invoke(fb, i);
                        if (item == null)
                        {
                            throw new RuntimeException("Null element in FBS array for "
                                    + purePathForDef(tableDefName, resolver));
                        }
                        arr[i] = nestedPdo(tableDefName, item, resolver, parent);
                    }
                    default -> throw new IllegalStateException("Not a vector shape: " + shape);
                }
            }
            return toSequence(arr);
        }

        private Object decodeUnionMember(byte uType, Object fb, Integer idx,
                MetadataAccess resolver, Object parent) throws Exception
        {
            int memberIdx = (uType & 0xFF) - 1;
            if (memberIdx < 0 || memberIdx >= members.length)
            {
                return null;
            }
            UnionMember m = members[memberIdx];
            Object member = idx == null
                    ? get.invoke(fb, m.newInstance())
                    : get.invoke(fb, m.newInstance(), idx);
            if (member == null)
            {
                return null;
            }
            return switch (m.kind)
            {
                case POINTER ->
                {
                    var pr = (org.finos.legend.pure.m3.module.pdbModule.fbs.PointerRef) member;
                    yield pr.pathLength() > 0 ? FbsResolverHelper.resolvePointerRef(pr, resolver) : null;
                }
                case ANCESTOR ->
                {
                    // Reference-reader semantics: hop `depth` table levels up
                    // from the current table. The generic parent chain has one
                    // PDO per table level, so the walk is a plain parent walk.
                    var ar = (org.finos.legend.pure.m3.module.pdbModule.fbs.AncestorRef) member;
                    Object t = parent;
                    for (int d = 0; d < ar.depth(); d++)
                    {
                        if (!(t instanceof PureDynamicObject pdo))
                        {
                            break;
                        }
                        t = pdo.parent;
                        if (t == null)
                        {
                            break;
                        }
                    }
                    yield t;
                }
                case INT_VAL, FLOAT_VAL, BOOL_VAL -> m.val.invoke(member);
                case STRING_VAL ->
                {
                    String raw = (String) m.val.invoke(member);
                    if (ATOMIC_VALUE_CONTENT_UNION.equals(unionName))
                    {
                        // AtomicValue.value flattens enum instances into a
                        // StringValueDef; rebuild the enum PDO from the sibling
                        // genericType (a fresh load, as the generated helper did).
                        yield AtomicValueEnumReconstructor.reconstruct(raw,
                                decode("genericType", fb, resolver, parent), resolver);
                    }
                    yield raw;
                }
                case DECIMAL_VAL ->
                {
                    String raw = (String) m.val.invoke(member);
                    yield raw != null ? new java.math.BigDecimal(raw) : null;
                }
                case TABLE -> nestedPdo(m.defName, member, resolver, parent);
            };
        }

        private Object decodeStringScalar(String raw, Object fb, MetadataAccess resolver)
        {
            if (raw == null)
            {
                return null;
            }
            StringTypeInfo info = stringTypeInfo(resolver);
            if (info.isEnum)
            {
                return EnumValueSingletons.valueOf(info.enumerationPath, raw);
            }
            if (info.raw)
            {
                return raw;
            }
            // Pointer path — never emitted by current writers for scalars, but
            // the generator supported it; kept for schema faithfulness.
            Object resolved = resolver.getElement(raw);
            return resolved != null ? resolved : FbsResolverHelper.resolveNestedElement(raw, resolver);
        }

        private Object decodeStringVectorItem(String raw, MetadataAccess resolver)
        {
            if (raw == null)
            {
                return null;
            }
            StringTypeInfo info = stringTypeInfo(resolver);
            if (info.raw || info.isEnum)
            {
                // Enum string VECTORS don't occur on the wire; the generated
                // vector branch treated only String as raw. Keep raw for both.
                return raw;
            }
            if ("Stereotype".equals(info.typeName))
            {
                return resolveAnnotation(raw, '.', "p_stereotypes", resolver);
            }
            if ("Tag".equals(info.typeName))
            {
                return resolveAnnotation(raw, '#', "p_tags", resolver);
            }
            Object resolved = resolver.getElement(raw);
            return resolved != null ? resolved : FbsResolverHelper.resolveNestedElement(raw, resolver);
        }

        /** "profilePath.Name" / "profilePath#Name" → matching Stereotype/Tag PDO. */
        private static Object resolveAnnotation(String path, char sep, String listProperty,
                MetadataAccess resolver)
        {
            int sepIdx = path.lastIndexOf(sep);
            if (sepIdx <= 0)
            {
                return null;
            }
            Object prof = resolver.getElement(path.substring(0, sepIdx));
            if (!(prof instanceof org.finos.legend.pure.truffle.execution.PropertyAccessor pa))
            {
                return null;
            }
            String name = path.substring(sepIdx + 1);
            if (pa.readProperty(listProperty) instanceof PureSequence seq)
            {
                for (int i = 0; i < seq.size(); i++)
                {
                    Object ann = seq.getBoxed(i);
                    if (ann instanceof org.finos.legend.pure.truffle.execution.PropertyAccessor annPa
                            && name.equals(annPa.readProperty("value")))
                    {
                        return ann;
                    }
                }
            }
            return null;
        }

        private StringTypeInfo stringTypeInfo(MetadataAccess resolver)
        {
            StringTypeInfo info = stringInfo;
            if (info == null)
            {
                info = resolveStringTypeInfo(tableName, pureProperty, rawFieldName, resolver);
                if (info.cacheable)
                {
                    stringInfo = info;
                }
            }
            return info;
        }
    }

    private static Object nestedPdo(String defName, Object def, MetadataAccess resolver, Object parent)
    {
        return new PureDynamicObject(
                PureClassRegistry.classInfoFor(purePathForDef(defName, resolver), resolver),
                def, resolver, parent);
    }

    private static Object toSequence(Object[] arr)
    {
        return new ObjectSequence(Arrays.stream(arr).filter(Objects::nonNull).toArray());
    }

    // =========================================================================
    // Union members
    // =========================================================================

    private enum MemberKind { POINTER, ANCESTOR, INT_VAL, FLOAT_VAL, BOOL_VAL, STRING_VAL, DECIMAL_VAL, TABLE }

    private static final class UnionMember
    {
        final String defName;
        final MemberKind kind;
        final java.lang.reflect.Constructor<?> ctor;
        final Method val; // primitive value defs only

        UnionMember(String defName)
        {
            this.defName = defName;
            this.kind = switch (defName)
            {
                case "PointerRef" -> MemberKind.POINTER;
                case "AncestorRef" -> MemberKind.ANCESTOR;
                case "IntegerValueDef" -> MemberKind.INT_VAL;
                case "FloatValueDef" -> MemberKind.FLOAT_VAL;
                case "BooleanValueDef" -> MemberKind.BOOL_VAL;
                case "StringValueDef" -> MemberKind.STRING_VAL;
                case "DecimalValueDef" -> MemberKind.DECIMAL_VAL;
                default -> MemberKind.TABLE;
            };
            try
            {
                Class<?> memberClass = Class.forName(FBS_PKG + "." + defName);
                this.ctor = memberClass.getConstructor();
                this.val = (kind == MemberKind.INT_VAL || kind == MemberKind.FLOAT_VAL
                        || kind == MemberKind.BOOL_VAL || kind == MemberKind.STRING_VAL
                        || kind == MemberKind.DECIMAL_VAL)
                        ? memberClass.getMethod("val") : null;
            }
            catch (ReflectiveOperationException e)
            {
                throw new IllegalStateException("Cannot reflect union member " + defName, e);
            }
        }

        com.google.flatbuffers.Table newInstance() throws ReflectiveOperationException
        {
            return (com.google.flatbuffers.Table) ctor.newInstance();
        }
    }

    // =========================================================================
    // String-field semantics (raw vs enum vs pointer vs Stereotype/Tag),
    // resolved from the Pure property type via the metamodel
    // =========================================================================

    private static final ConcurrentHashMap<String, StringTypeInfo> STRING_INFO = new ConcurrentHashMap<>();
    private static final ThreadLocal<Set<String>> RESOLVING = ThreadLocal.withInitial(HashSet::new);
    private static final Set<String> PRIMITIVE_TYPE_NAMES =
            Set.of("String", "Boolean", "Integer", "Float", "Decimal", "Number");

    private static final class StringTypeInfo
    {
        static final StringTypeInfo RAW = new StringTypeInfo(null, true, false, null, true);
        static final StringTypeInfo RAW_UNCACHED = new StringTypeInfo(null, true, false, null, false);

        final String typeName;
        final boolean raw;
        final boolean isEnum;
        final String enumerationPath;
        final boolean cacheable;

        StringTypeInfo(String typeName, boolean raw, boolean isEnum, String enumerationPath, boolean cacheable)
        {
            this.typeName = typeName;
            this.raw = raw;
            this.isEnum = isEnum;
            this.enumerationPath = enumerationPath;
            this.cacheable = cacheable;
        }
    }

    private static StringTypeInfo resolveStringTypeInfo(String tableName, String pureProperty,
            String rawFieldName, MetadataAccess resolver)
    {
        String key = tableName + "#" + pureProperty;
        StringTypeInfo cached = STRING_INFO.get(key);
        if (cached != null)
        {
            return cached;
        }
        Set<String> inFlight = RESOLVING.get();
        if (!inFlight.add(key))
        {
            // Re-entered while resolving this very field (the class-definition
            // walk read it): every string field on that walk is a true String
            // property, so raw is exact. Not cached — the outer resolution
            // computes and memoises the real verdict.
            return StringTypeInfo.RAW_UNCACHED;
        }
        try
        {
            StringTypeInfo info = computeStringTypeInfo(tableName, pureProperty, rawFieldName, resolver);
            STRING_INFO.put(key, info);
            return info;
        }
        finally
        {
            inFlight.remove(key);
        }
    }

    private static StringTypeInfo computeStringTypeInfo(String tableName, String pureProperty,
            String rawFieldName, MetadataAccess resolver)
    {
        Object classElem = resolver.getElement(purePathForDef(tableName, resolver));
        Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Object property = findProperty(classElem, pureProperty, visited);
        if (property == null && !rawFieldName.equals(pureProperty))
        {
            // Snake-ish Pure names survive camelToSnake verbatim — retry with
            // the fbs spelling (same reason buildPlan registers both keys).
            visited.clear();
            property = findProperty(classElem, rawFieldName, visited);
        }
        if (property == null)
        {
            return StringTypeInfo.RAW;
        }
        Object genericType = readField(property, "genericType");
        Object type = genericType != null ? readField(genericType, "type") : null;
        if (type == null)
        {
            return StringTypeInfo.RAW;
        }
        Object typeNameObj = readField(type, "name");
        String typeName = typeNameObj instanceof String s ? s : null;
        if (typeName == null || PRIMITIVE_TYPE_NAMES.contains(typeName))
        {
            return StringTypeInfo.RAW;
        }
        if (type instanceof PureDynamicObject pdoType && ENUMERATION_PATH.equals(pdoType.purePath()))
        {
            String enumerationPath = resolver.pathOf(pdoType);
            // Without a canonical path the value can't be registered for
            // lazy-CGT lookup; pass the raw name through (degraded, explicit).
            return enumerationPath != null
                    ? new StringTypeInfo(typeName, false, true, enumerationPath, true)
                    : StringTypeInfo.RAW;
        }
        return new StringTypeInfo(typeName, false, false, null, true);
    }

    /** Depth-first property lookup over the class and its generalizations. */
    private static Object findProperty(Object classElem, String propName, Set<Object> visited)
    {
        if (classElem == null || !visited.add(classElem))
        {
            return null;
        }
        for (String listName : new String[]{"properties", "propertiesFromAssociations"})
        {
            if (readField(classElem, listName) instanceof PureSequence props)
            {
                for (int i = 0; i < props.size(); i++)
                {
                    Object p = props.getBoxed(i);
                    if (p != null && propName.equals(readField(p, "name")))
                    {
                        return p;
                    }
                }
            }
        }
        if (readField(classElem, "generalizations") instanceof PureSequence gens)
        {
            for (int i = 0; i < gens.size(); i++)
            {
                Object general = readField(gens.getBoxed(i), "general");
                Object superType = general != null ? readField(general, "type") : null;
                Object found = findProperty(superType, propName, visited);
                if (found != null)
                {
                    return found;
                }
            }
        }
        return null;
    }

    private static Object readField(Object obj, String name)
    {
        if (obj instanceof org.finos.legend.pure.truffle.execution.PropertyAccessor pa)
        {
            Object v = pa.readProperty(name);
            return v == org.finos.legend.pure.truffle.execution.PropertyAccessor.ABSENT ? null : v;
        }
        return null;
    }
}
