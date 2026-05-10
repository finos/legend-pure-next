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
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.LambdaFunction;
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
    private final PureLanguage language;
    private final TruffleLanguage.Env env;

    private TruffleMetadataAccess resolver;
    private org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry modules;
    private PureASTBuilder astBuilder;
    private PureParser pureParser;

    // Per-FD compilation cache: layout + lowered body
    private final WeakHashMap<FunctionDefinition, CompiledFunction> functionCache = new WeakHashMap<>();
    // Per-lambda caches
    private final WeakHashMap<LambdaFunction, RootCallTarget> lambdaCache = new WeakHashMap<>();

    // Construction stack for new/copy parent references (~)
    private final ArrayDeque<Object> constructionStack = new ArrayDeque<>();

    // Per-module state holds caches whose entries reference wrappers from a
    // specific module. Keying by owning module means we can wipe just the
    // affected slice on a recompile (via {@link #unregisterModule}) without
    // touching unrelated modules' caches.
    //
    // Why these caches need module-scope (rather than living flat on the
    // context):
    //   - enumCgts: keyed by Java enum constant (a JVM singleton); the
    //     cached CGT references a wrapper from the module that owns the
    //     enum's path. Wipe that module → cache must drop the entry.
    //   - typePathCgts: keyed by Pure type path; the cached CGT references
    //     a wrapper from the module that owns the path. Same logic.
    //
    // Legacy resolvers (anonymous TruffleMetadataAccess) without a registry
    // map to the {@link #DEFAULT_MODULE_KEY} bucket — same behavior as the
    // pre-module flat cache.
    private final java.util.HashMap<String, ModuleState> moduleStates = new java.util.HashMap<>();
    private static final String DEFAULT_MODULE_KEY = "<default>";

    private static final class ModuleState
    {
        final java.util.IdentityHashMap<Object, org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue> enumCgts = new java.util.IdentityHashMap<>();
        final java.util.HashMap<String, org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue> typePathCgts = new java.util.HashMap<>();
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
    public RootCallTarget getCallTarget(FunctionDefinition fd)
    {
        return compile(fd).callTarget();
    }


    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public Object executeFunction(FunctionDefinition fd, Object[] rawArgs)
    {
        RootCallTarget ct = compile(fd).callTarget();
        if (ct != null)
        {
            return ct.call(rawArgs);
        }
        throw new RuntimeException("No CallTarget for: " + getFunctionName(fd));
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public RootCallTarget callTargetForLambda(LambdaFunction lambda)
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
                Object exprSeqObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(lambda, "expressionSequence");
                PureNode[] body = astBuilder.lowerBody(exprSeqObj, cf.layout());
                Object openVarsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(lambda, "openVariables");
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
                        Object nameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(ov, "name");
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
    public Object coerceToJavaEnum(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enumeration en, String valueName)
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
        if (!enumClass.isEnum())
        {
            throw new RuntimeException(enumClassName + " exists but is not a Java enum.");
        }
        for (Object constant : enumClass.getEnumConstants())
        {
            if (constant instanceof java.lang.Enum<?> e && e.name().equals(valueName))
            {
                return constant;
            }
        }
        throw new RuntimeException(
                "Enum '" + enumPath + "' has no value named '" + valueName + "'. "
                        + "Available values: " + java.util.Arrays.stream(enumClass.getEnumConstants())
                        .map(c -> ((java.lang.Enum<?>) c).name()).toList());
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
    public org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue classifierGenericType(Object value)
    {
        if (value == null)
        {
            return null;
        }
        // Java enum constants implementing a generated Pure-Enum interface get
        // their CGT cached per-context (they're JVM singletons; can't stamp).
        if (value.getClass().isEnum())
        {
            return enumCgt(value);
        }
        // Anything else — read CGT via PropertyAccessor.readProperty (works
        // for both legacy XImpl and the future PureDynamicObject). The cast
        // is safe: only Pure metamodel objects (which all implement
        // PropertyAccessor) ever reach this method's `value`.
        Object cgt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(value, "classifierGenericType");
        return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue) cgt;
    }

    /**
     * Build/cache a CGT for the given Pure type path against this context's
     * resolver. Replaces the {@code private static GenericTypeValue} pattern
     * native nodes used to use, which leaked wrappers across contexts.
     *
     * <p>Cached in the owning module's {@link ModuleState}, so an
     * {@link #unregisterModule(String)} drops just this module's entries.</p>
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue cgtForType(String typePath)
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
        if (!(t instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type type))
        {
            return null;
        }
        var cgt = org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(type, resolver);
        state.typePathCgts.put(typePath, cgt);
        return cgt;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue enumCgt(Object enumConstant)
    {
        if (resolver == null)
        {
            return null;
        }
        Class<?>[] ifaces = enumConstant.getClass().getInterfaces();
        if (ifaces.length == 0)
        {
            return null;
        }
        String purePath = ifaces[0].getName()
                .replace("org.finos.legend.pure.truffle.pdb.", "")
                .replace(".", "::");
        String moduleKey = ownerModuleOfPath(purePath);
        ModuleState state = moduleStateFor(moduleKey);
        var cached = state.enumCgts.get(enumConstant);
        if (cached != null)
        {
            return cached;
        }
        Object enumType = resolver.getElement(purePath);
        if (!(enumType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t))
        {
            return null;
        }
        var cgt = org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(t, resolver);
        state.enumCgts.put(enumConstant, cgt);
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

    public FunctionDefinition resolveQpDispatch(
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty staticQp,
            Object[] rawArgs)
    {
        if (rawArgs.length == 0) return staticQp;
        Object target = rawArgs[0];
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType cgt = getClassifierGenericType(target);
        if (cgt == null) return staticQp;
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type runtimeType =
                org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
        if (runtimeType == null) return staticQp;
        String qpName = (String) org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(staticQp, "name");
        int argCount = rawArgs.length;
        java.util.List<org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type> mro =
                org.finos.legend.pure.truffle.runtime.helper._Type.linearize(runtimeType, resolver);
        for (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type type : mro)
        {
            Object qpsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(type, "qualifiedProperties");
            if (!(qpsObj instanceof org.finos.legend.pure.truffle.types.PureSequence qps))
            {
                continue;
            }
            for (Object candidate : qps.toBoxedArray())
            {
                if (candidate == null) continue;
                Object cqpName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(candidate, "name");
                if (!qpName.equals(cqpName)) continue;
                Object cqpParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(candidate, "parameters");
                if (cqpParamsObj instanceof org.finos.legend.pure.truffle.types.PureSequence cqpParams
                        && cqpParams.size() == argCount
                        && candidate instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty cqp)
                {
                    return cqp;
                }
            }
        }
        return staticQp;
    }

    public static void bindQpTypeVariablesStatic(Object target, VirtualFrame frame, FrameLayout layout)
    {
        Object cgt = getClassifierGenericType(target);
        if (cgt == null) return;
        Object tvvObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(cgt, "typeVariableValues");
        if (!(tvvObj instanceof org.finos.legend.pure.truffle.types.PureSequence typeVarVals) || typeVarVals.isEmpty())
        {
            return;
        }
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type ownerType =
                org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
        Object typeVarsObj = ownerType != null
                ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(ownerType, "typeVariables") : null;
        if (!(typeVarsObj instanceof org.finos.legend.pure.truffle.types.PureSequence typeVars) || typeVars.isEmpty())
        {
            return;
        }
        int count = Math.min(typeVars.size(), typeVarVals.size());
        for (int i = 0; i < count; i++)
        {
            Object tvObj = typeVars.getBoxed(i);
            Object tvNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(tvObj, "name");
            String name = tvNameObj instanceof String s ? s : String.valueOf(tvObj);
            Integer slot = layout.slotFor(name);
            if (slot != null)
            {
                Object tvVal = typeVarVals.getBoxed(i);
                if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(tvVal,
                        "meta::pure::metamodel::valuespecification::AtomicValue"))
                {
                    tvVal = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(tvVal, "value");
                }
                if (tvVal != null)
                {
                    frame.setObject(slot, tvVal);
                }
            }
        }
    }

    private static org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType getClassifierGenericType(Object target)
    {
        if (target instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any any)
        {
            return any._classifierGenericType();
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Compilation (private)
    // ---------------------------------------------------------------

    private CompiledFunction compile(FunctionDefinition fd)
    {
        CompiledFunction cached = functionCache.get(fd);
        if (cached != null) return cached;
        FrameLayout layout = FrameDescriptorBuilder.analyze(fd);
        if (layout == null) layout = FrameDescriptorBuilder.analyzeMinimal(fd);
        CompiledFunction cf = new CompiledFunction(layout);
        functionCache.put(fd, cf);
        try
        {
            Object exprSeqObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fd, "expressionSequence");
            PureNode[] body = astBuilder.lowerBody(exprSeqObj, layout);
            cf.setBody(body);
            if (!(fd instanceof LambdaFunction))
            {
                String name = getFunctionName(fd);
                com.oracle.truffle.api.source.SourceSection rootSource = null;
                try
                {
                    Object si = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fd, "sourceInformation");
                    if (si == null && exprSeqObj instanceof org.finos.legend.pure.truffle.types.PureSequence exprSeq && !exprSeq.isEmpty())
                    {
                        Object firstExpr = exprSeq.getBoxed(0);
                        si = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(firstExpr, "sourceInformation");
                    }
                    rootSource = org.finos.legend.pure.truffle.ast.PureSourceHelper.createSourceSection(si);
                }
                catch (Exception e) { throw new RuntimeException("Failed to set source information", e); }
                boolean mayBindTypeVars = fd instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty;
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

    private static String getFunctionName(FunctionDefinition fd)
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
    private static String lambdaProfileName(LambdaFunction lambda)
    {
        try
        {
            Object si = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(lambda, "sourceInformation");
            if (si != null)
            {
                Object sourceIdObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(si, "sourceId");
                if (sourceIdObj instanceof String sourceId)
                {
                    return "lambda@" + sourceId
                            + ":" + org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(si, "startLine")
                            + ":" + org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(si, "startColumn");
                }
            }
        }
        catch (RuntimeException ignored) {}
        return "lambda@" + System.identityHashCode(lambda);
    }
}
