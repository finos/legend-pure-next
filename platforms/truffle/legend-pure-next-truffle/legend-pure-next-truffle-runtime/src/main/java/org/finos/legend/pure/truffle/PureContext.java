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

package org.finos.legend.pure.truffle;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.finos.legend.pure.truffle.builder.PureASTBuilder;
import org.finos.legend.pure.truffle.frame.CompiledFunction;
import org.finos.legend.pure.truffle.frame.FrameDescriptorBuilder;
import org.finos.legend.pure.truffle.frame.FrameLayout;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.next.parser.PureParser;

import java.util.ArrayDeque;
import java.util.WeakHashMap;

/**
 * Per-context state for the Pure Truffle language.
 * Holds the resolver, AST builder, compilation caches, and all runtime services.
 */
public final class PureContext
{

    private static final int SLOT_CLASSIFIER_GENERIC_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("classifierGenericType");
    private static final int SLOT_EXPRESSION_SEQUENCE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("expressionSequence");
    private static final int SLOT_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");
    private static final int SLOT_OPEN_VARIABLES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("openVariables");
    private static final int SLOT_PARAMETERS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("parameters");
    private static final int SLOT_QUALIFIED_PROPERTIES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("qualifiedProperties");
    private static final int SLOT_SOURCE_ID = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("sourceId");
    private static final int SLOT_SOURCE_INFORMATION = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("sourceInformation");
    private static final int SLOT_START_COLUMN = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("startColumn");
    private static final int SLOT_START_LINE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("startLine");
    private static final int SLOT_TYPE_VARIABLE_VALUES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("typeVariableValues");
    private static final int SLOT_TYPE_VARIABLES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("typeVariables");
    private static final int SLOT_VALUE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("value");
    private final PureLanguage language;
    private final TruffleLanguage.Env env;

    private TruffleMetadataAccess resolver;
    private org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry modules;
    private PureASTBuilder astBuilder;
    private PureParser pureParser;

    // Per-FD compilation cache: layout + lowered body
    private final WeakHashMap<Object, CompiledFunction> functionCache = new WeakHashMap<>();
    // Per-lambda caches
    private final WeakHashMap<Object, RootCallTarget> lambdaCache = new WeakHashMap<>();

    /** Per-context CGT cache for enum-value singletons. Enum PDOs are
     *  JVM-static (one instance shared across every PureContext / resolver),
     *  so we cannot stamp the resolver-specific CGT onto their slot table —
     *  a CGT built for resolver A would outlive A's PureContext and leak into
     *  resolver B's matches as a stale wrapper. Identity-keyed; the singleton
     *  identity is stable program-wide. */
    private final java.util.Map<org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject, Object> enumCgtCache =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    // Construction stack for new/copy parent references (~)
    private final ArrayDeque<Object> constructionStack = new ArrayDeque<>();

    // Per-module state holds caches whose entries reference wrappers from a
    // specific module. typePathCgts is keyed by Pure type path; the cached
    // CGT references a wrapper from the module that owns the path. Keying
    // by owning module means we can wipe just the affected slice on a
    // recompile (via {@link #unregisterModule}) without touching unrelated
    // modules' caches.
    private final java.util.HashMap<String, ModuleState> moduleStates = new java.util.HashMap<>();
    private static final String DEFAULT_MODULE_KEY = "<default>";

    private static final class ModuleState
    {
        final java.util.HashMap<String, Object> typePathCgts = new java.util.HashMap<>();
    }

    private ModuleState moduleStateFor(String moduleKey)
    {
        return moduleStates.computeIfAbsent(moduleKey, k -> new ModuleState());
    }

    /**
     * Drop all per-module state for the named module. Called by {@link
     * #unregisterModule(String)} and on context teardown.
     */
    private void clearModuleState(String moduleKey)
    {
        moduleStates.remove(moduleKey);
    }

    /**
     * Unregister a module from the registry and drop its associated state
     * (CGT caches, future shape registry). Cascades to dependents — anything
     * that depended on the wiped module is also wiped, since its references
     * are now stale.
     *
     * <p>Recompile flow: {@code unregisterModule("foo")} → re-create the
     * loader/module → {@link TruffleModuleRegistry#register register} again.
     * No need to rebuild unrelated modules.</p>
     */
    public void unregisterModule(String name)
    {
        if (modules == null)
        {
            return;
        }
        // Snapshot dependents BEFORE the registry's cascade unregisters them,
        // so we know which states to clear.
        java.util.List<String> toClear = new java.util.ArrayList<>();
        toClear.add(name);
        for (org.finos.legend.pure.truffle.runtime.TruffleModule m : modules.modules())
        {
            if (m.dependencies().contains(name))
            {
                toClear.add(m.name());
            }
        }
        modules.unregister(name);
        for (String n : toClear)
        {
            clearModuleState(n);
        }
    }

    public PureContext(PureLanguage language, TruffleLanguage.Env env)
    {
        this.language = language;
        this.env = env;
    }

    void initialize(TruffleMetadataAccess resolver, NativeNodeRegistry registry)
    {
        this.resolver = resolver;
        // If the resolver is a TruffleModuleRegistry (the standard case after
        // the module refactor), keep a typed reference so callers can ask
        // about module ownership / lifecycle. Anonymous TruffleMetadataAccess
        // implementations (e.g. legacy tests, ad-hoc resolvers) leave this
        // null — module-aware features simply degrade to "single anonymous
        // module" behavior in that case.
        this.modules = (resolver instanceof org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry r) ? r : null;
        this.astBuilder = new PureASTBuilder(null, registry);
    }

    // ---------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------

    public TruffleMetadataAccess resolver() { return resolver; }

    /**
     * The module registry, if the resolver was a {@link
     * org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry}. Returns
     * null for legacy anonymous resolvers — callers that need module info
     * must check.
     */
    public org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry modules() { return modules; }
    public PureASTBuilder astBuilder() { return astBuilder; }
    public PureParser pureParser() { return pureParser; }
    public void setPureParser(PureParser parser) { this.pureParser = parser; }

    // ---------------------------------------------------------------
    // Construction stack (for new/copy parent references)
    // ---------------------------------------------------------------

    public void pushConstruction(Object instance) { constructionStack.push(instance); }
    public void popConstruction() { constructionStack.pop(); }
    public Object peekConstruction(int depth)
    {
        int i = 0;
        for (Object obj : constructionStack)
        {
            if (i == depth) return obj;
            i++;
        }
        throw new RuntimeException("Parent reference ~ at depth " + depth
                + " exceeds stack size " + constructionStack.size());
    }

    // ---------------------------------------------------------------
    // Function compilation & execution
    // ---------------------------------------------------------------

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public RootCallTarget getCallTarget(Object fn)
    {
        return compile(fn).callTarget();
    }


    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public Object executeFunction(Object fn, Object[] rawArgs)
    {
        RootCallTarget ct = compile(fn).callTarget();
        if (ct != null)
        {
            return ct.call(rawArgs);
        }
        throw new RuntimeException("No CallTarget for: " + getFunctionName(fn));
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public RootCallTarget callTargetForLambda(Object lambda)
    {
        RootCallTarget cached = lambdaCache.get(lambda);
        if (cached != null)
        {
            return cached;
        }
        CompiledFunction cf = compile(lambda);
        try
        {
            FrameLayout prevLayout = astBuilder.pushLayout(cf.layout());
            try
            {
                Object exprSeqObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(lambda, SLOT_EXPRESSION_SEQUENCE);
                PureNode[] body = astBuilder.lowerBody(exprSeqObj, cf.layout());
                Object openVarsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(lambda, SLOT_OPEN_VARIABLES);
                String[] openVarNames;
                if (!(openVarsObj instanceof org.finos.legend.pure.truffle.types.PureSequence openVars) || openVars.isEmpty())
                {
                    openVarNames = new String[0];
                }
                else
                {
                    openVarNames = new String[openVars.size()];
                    for (int i = 0; i < openVars.size(); i++)
                    {
                        Object ov = openVars.getBoxed(i);
                        Object nameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(ov, SLOT_NAME);
                        if (nameObj instanceof String s)
                        {
                            openVarNames[i] = s;
                        }
                        else if (ov instanceof String s)
                        {
                            openVarNames[i] = s;
                        }
                        else
                        {
                            openVarNames[i] = String.valueOf(ov);
                        }
                    }
                }
                RawLambdaRootNode root = new RawLambdaRootNode(
                        language, lambdaProfileName(lambda),
                        cf.layout(), cf.layout().paramSlots(), openVarNames, body);
                RootCallTarget ct = root.getCallTarget();
                lambdaCache.put(lambda, ct);
                return ct;
            }
            finally
            {
                astBuilder.popLayout(prevLayout);
            }
        }
        catch (RuntimeException e)
        {
            throw new RuntimeException("Failed to compile lambda call target", e);
        }
    }

    // ---------------------------------------------------------------
    // Enum coercion
    // ---------------------------------------------------------------

    /**
     * Look up a Pure enum value by name and return the Java enum constant.
     * Returns null if no Java class exists for the enumeration (runtime enum
     * created via {@code newEnumeration} — caller is responsible for falling
     * back to {@code _values()} traversal). Throws if the class exists but
     * the named value isn't one of its constants.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public Object coerceToJavaEnum(Object en, String valueName)
    {
        if (en == null)
        {
            return null;
        }
        String enumPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(en);
        if (enumPath == null)
        {
            return null;
        }
        // Post enum-to-PDO migration: the generated {@code XEnum} class is
        // no longer a Java {@code enum} — it's a final class holding PDO
        // singletons. Look up the value via the static {@code valueOf(String)}
        // method which now returns a {@link PureDynamicObject}.
        String enumClassName = "org.finos.legend.pure.truffle.pdb." + pureFqnToJavaFqn(enumPath) + "Enum";
        Class<?> enumClass;
        try
        {
            enumClass = Class.forName(enumClassName);
        }
        catch (ClassNotFoundException e)
        {
            // No Java class — could be a runtime-created enum. Caller falls
            // back to _values() traversal.
            return null;
        }
        try
        {
            return enumClass.getMethod("valueOf", String.class).invoke(null, valueName);
        }
        catch (java.lang.reflect.InvocationTargetException e)
        {
            if (e.getCause() instanceof IllegalArgumentException iae) throw iae;
            throw new RuntimeException(e.getCause());
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Convert a Pure path like {@code meta::pure::functions::boolean::tests::X}
     * into the Java FQN the truffle codegen produces. Codegen escapes Java
     * reserved words in package segments by appending an underscore (so
     * {@code boolean} → {@code boolean_}, {@code class} → {@code class_}, etc.).
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String pureFqnToJavaFqn(String purePath)
    {
        String[] segments = purePath.split("::");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++)
        {
            if (i > 0) sb.append('.');
            String seg = segments[i];
            // Last segment is the type name — never collides with a Java keyword
            // in practice, but escape package segments only.
            sb.append(i < segments.length - 1 && JAVA_KEYWORDS.contains(seg) ? seg + "_" : seg);
        }
        return sb.toString();
    }

    private static final java.util.Set<String> JAVA_KEYWORDS = java.util.Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while");

    /**
     * Look up the classifier generic type for any Pure value.
     *
     * <p>For ordinary objects we read {@code _classifierGenericType()} off the
     * value. Java enum constants (e.g. {@code GenericTypeOperationTypeEnum.Union})
     * are JVM singletons, so we cannot stamp the CGT onto them — a CGT built
     * against one PureContext's resolver would outlive that context and feed
     * stale wrappers into a later context's matches (different resolver →
     * different wrapper for the same Pure type → identity check fails). We
     * cache enum CGTs in this context instead, so each context owns its own
     * wrapper that's GC'd when the context dies.</p>
     */
    public Object classifierGenericType(Object value)
    {
        // Post enum-to-PDO migration: every Pure metamodel value (including
        // enum singletons) is a {@link PureDynamicObject} with CGT in its
        // slot table. Enum-value singletons can't have their CGT set at
        // codegen class-load (no resolver yet) — fill lazily on first read
        // gated by classInfo identity so non-enum PDOs skip the registry
        // lookup entirely.
        if (value instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdo)
        {
            // Codegen-emitted enum-value singletons (e.g.
            // {@code GenericTypeOperationTypeEnum.Union}) are JVM-static and
            // shared across every PureContext — must NOT read/write SLOT_CGT
            // on them, or resolver A's CGT leaks into resolver B's matches.
            // {@link PureEnumRegistry#enumerationPathOf} returns non-null only
            // for those static singletons; runtime-created enums (e.g. via
            // {@code newEnumeration}) aren't registered there and keep their
            // CGT in the slot table like ordinary PDOs.
            if (pdo.classInfo == ENUM_CLASS_INFO)
            {
                String enumPath = org.finos.legend.pure.truffle.runtime.dynobj.PureEnumRegistry.enumerationPathOf(pdo);
                if (enumPath != null) return resolveStaticEnumCgt(pdo, enumPath);
            }
            Object cgt = pdo.readSlot(SLOT_CGT);
            if (cgt != null) return cgt;
            return null;
        }
        // Primitive Pure values resolve type-cgts by Java class.
        if (value instanceof Long || value instanceof Integer) return cgtForType("Integer");
        if (value instanceof Double || value instanceof Float) return cgtForType("Float");
        if (value instanceof String) return cgtForType("String");
        if (value instanceof Boolean) return cgtForType("Boolean");
        return null;
    }

    private static final org.finos.legend.pure.truffle.runtime.dynobj.PureClassInfo ENUM_CLASS_INFO =
            org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.enumClassInfo();

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object resolveStaticEnumCgt(org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdo, String enumPath)
    {
        Object cached = enumCgtCache.get(pdo);
        if (cached != null) return cached;
        Object cgt = cgtForType(enumPath);
        if (cgt != null) enumCgtCache.put(pdo, cgt);
        return cgt;
    }

    private static final int SLOT_CGT =
            org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("classifierGenericType");

    /**
     * Build/cache a CGT for the given Pure type path against this context's
     * resolver. Replaces the {@code private static GenericTypeValue} pattern
     * native nodes used to use, which leaked wrappers across contexts.
     *
     * <p>Cached in the owning module's {@link ModuleState}, so an
     * {@link #unregisterModule(String)} drops just this module's entries.</p>
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public Object cgtForType(String typePath)
    {
        if (resolver == null)
        {
            return null;
        }
        String moduleKey = ownerModuleOfPath(typePath);
        ModuleState state = moduleStateFor(moduleKey);
        var cached = state.typePathCgts.get(typePath);
        if (cached != null)
        {
            return cached;
        }
        Object t = resolver.getElement(typePath);
        if (t == null)
        {
            return null;
        }
        var cgt = org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(t, resolver);
        state.typePathCgts.put(typePath, cgt);
        return cgt;
    }

    /**
     * Find the module that owns a given path. Falls back to
     * {@link #DEFAULT_MODULE_KEY} when the registry isn't available (legacy
     * resolver) or when no module claims the path (path is unknown).
     */
    private String ownerModuleOfPath(String path)
    {
        if (modules == null)
        {
            return DEFAULT_MODULE_KEY;
        }
        var owner = modules.moduleOfPath(path);
        return owner != null ? owner.name() : DEFAULT_MODULE_KEY;
    }

    // ---------------------------------------------------------------
    // QP dispatch
    // ---------------------------------------------------------------

    public Object resolveQpDispatch(Object staticQp, Object[] rawArgs)
    {
        if (rawArgs.length == 0) return staticQp;
        Object target = rawArgs[0];
        Object cgt = getClassifierGenericType(target);
        if (cgt == null) return staticQp;
        Object runtimeType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
        if (runtimeType == null) return staticQp;
        String qpName = (String) org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(staticQp, SLOT_NAME);
        int argCount = rawArgs.length;
        java.util.List<Object> mro =
                org.finos.legend.pure.truffle.runtime.helper._Type.linearize(runtimeType, resolver);
        for (Object type : mro)
        {
            Object qpsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(type, SLOT_QUALIFIED_PROPERTIES);
            if (!(qpsObj instanceof org.finos.legend.pure.truffle.types.PureSequence qps))
            {
                continue;
            }
            for (Object candidate : qps.toBoxedArray())
            {
                if (candidate == null) continue;
                Object cqpName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(candidate, SLOT_NAME);
                if (!qpName.equals(cqpName)) continue;
                Object cqpParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(candidate, SLOT_PARAMETERS);
                if (cqpParamsObj instanceof org.finos.legend.pure.truffle.types.PureSequence cqpParams
                        && cqpParams.size() == argCount
                        && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(candidate,
                                "meta::pure::metamodel::function::property::QualifiedProperty", resolver))
                {
                    return candidate;
                }
            }
        }
        return staticQp;
    }

    public static void bindQpTypeVariablesStatic(Object target, VirtualFrame frame, FrameLayout layout)
    {
        Object cgt = getClassifierGenericType(target);
        if (cgt == null) return;
        Object tvvObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(cgt, SLOT_TYPE_VARIABLE_VALUES);
        if (!(tvvObj instanceof org.finos.legend.pure.truffle.types.PureSequence typeVarVals) || typeVarVals.isEmpty())
        {
            return;
        }
        Object ownerType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
        Object typeVarsObj = ownerType != null
                ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(ownerType, SLOT_TYPE_VARIABLES) : null;
        if (!(typeVarsObj instanceof org.finos.legend.pure.truffle.types.PureSequence typeVars) || typeVars.isEmpty())
        {
            return;
        }
        int count = Math.min(typeVars.size(), typeVarVals.size());
        for (int i = 0; i < count; i++)
        {
            Object tvObj = typeVars.getBoxed(i);
            Object tvNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(tvObj, SLOT_NAME);
            String name = tvNameObj instanceof String s ? s : String.valueOf(tvObj);
            Integer slot = layout.slotFor(name);
            if (slot != null)
            {
                Object tvVal = typeVarVals.getBoxed(i);
                if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(tvVal,
                        "meta::pure::metamodel::valuespecification::AtomicValue"))
                {
                    tvVal = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(tvVal, SLOT_VALUE);
                }
                if (tvVal != null)
                {
                    frame.setObject(slot, tvVal);
                }
            }
        }
    }

    private static Object getClassifierGenericType(Object target)
    {
        if (target == null)
        {
            return null;
        }
        // Read the property generically — works for both legacy XImpl
        // (PropertyAccessor) and the post-flip PureDynamicObject.
        return org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(target, SLOT_CLASSIFIER_GENERIC_TYPE);
    }

    // ---------------------------------------------------------------
    // Compilation (private)
    // ---------------------------------------------------------------

    /** Public accessor for the {@link CompiledFunction} of a lambda
     *  (FrameLayout + body PureNodes), used by inline-fold AST building. */
    public org.finos.legend.pure.truffle.frame.CompiledFunction compileLambdaFunction(Object lambda)
    {
        return compile(lambda);
    }

    private CompiledFunction compile(Object fd)
    {
        CompiledFunction cached = functionCache.get(fd);
        if (cached != null) return cached;
        FrameLayout layout = FrameDescriptorBuilder.analyze(fd);
        if (layout == null) layout = FrameDescriptorBuilder.analyzeMinimal(fd);
        CompiledFunction cf = new CompiledFunction(layout);
        functionCache.put(fd, cf);
        try
        {
            Object exprSeqObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fd, SLOT_EXPRESSION_SEQUENCE);
            PureNode[] body = astBuilder.lowerBody(exprSeqObj, layout);
            cf.setBody(body);
            // LambdaFunction has its own AST-build via callTargetForLambda;
            // top-level functions get a PureFunctionRootNode here. pureTypeIs
            // is exact-match against the leaf Pure path — class-keyed-cached.
            boolean isLambda = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(fd,
                    "meta::pure::metamodel::function::LambdaFunction");
            if (!isLambda)
            {
                String name = getFunctionName(fd);
                com.oracle.truffle.api.source.SourceSection rootSource = null;
                try
                {
                    Object si = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fd, SLOT_SOURCE_INFORMATION);
                    if (si == null && exprSeqObj instanceof org.finos.legend.pure.truffle.types.PureSequence exprSeq && !exprSeq.isEmpty())
                    {
                        Object firstExpr = exprSeq.getBoxed(0);
                        si = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(firstExpr, SLOT_SOURCE_INFORMATION);
                    }
                    rootSource = org.finos.legend.pure.truffle.ast.PureSourceHelper.createSourceSection(si);
                }
                catch (Exception e) { throw new RuntimeException("Failed to set source information", e); }
                boolean mayBindTypeVars = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(fd,
                        "meta::pure::metamodel::function::property::QualifiedProperty");
                PureFunctionRootNode root = new PureFunctionRootNode(language, name, layout, body, rootSource, mayBindTypeVars);
                cf.setCallTarget(root.getCallTarget());
            }
        }
        catch (RuntimeException e)
        {
            throw new RuntimeException("Failed to compile: " + getFunctionName(fd), e);
        }
        return cf;
    }

    private static String getFunctionName(Object fd)
    {
        try
        {
            String path = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(fd);
            if (path != null) return path;
        }
        catch (RuntimeException ignored) {}
        return "fn@" + System.identityHashCode(fd);
    }

    /**
     * Profiler-friendly name for a lambda: prefer the source location
     * (file:line:col) so the per-function CPU report points back to the
     * lambda's body in source. Falls back to identity hash when the
     * lambda has no SourceInformation attached.
     */
    private static String lambdaProfileName(Object lambda)
    {
        try
        {
            Object si = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(lambda, SLOT_SOURCE_INFORMATION);
            if (si != null)
            {
                Object sourceIdObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(si, SLOT_SOURCE_ID);
                if (sourceIdObj instanceof String sourceId)
                {
                    return "lambda@" + sourceId
                            + ":" + org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(si, SLOT_START_LINE)
                            + ":" + org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(si, SLOT_START_COLUMN);
                }
            }
        }
        catch (RuntimeException ignored) {}
        return "lambda@" + System.identityHashCode(lambda);
    }
}
