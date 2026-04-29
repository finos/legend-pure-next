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
    private PureASTBuilder astBuilder;
    private PureParser pureParser;

    // Per-FD compilation cache: layout + lowered body
    private final WeakHashMap<FunctionDefinition, CompiledFunction> functionCache = new WeakHashMap<>();
    // Per-lambda caches
    private final WeakHashMap<LambdaFunction, RootCallTarget> lambdaCache = new WeakHashMap<>();

    // Construction stack for new/copy parent references (~)
    private final ArrayDeque<Object> constructionStack = new ArrayDeque<>();

    // CGT for Java enum constants. Java enums are JVM singletons, so we cannot
    // store CGT on them — a CGT built against one PureContext's resolver would
    // outlive that context and feed stale wrappers into a later context's
    // matches (different resolver = different wrapper for the same Pure type
    // = identity check fails). Cache here instead so each context owns its
    // own per-enum CGT, garbage-collected when the context dies.
    private final java.util.IdentityHashMap<Object, org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue> enumCgtCache = new java.util.IdentityHashMap<>();

    public PureContext(PureLanguage language, TruffleLanguage.Env env)
    {
        this.language = language;
        this.env = env;
    }

    void initialize(TruffleMetadataAccess resolver, NativeNodeRegistry registry)
    {
        this.resolver = resolver;
        this.astBuilder = new PureASTBuilder(null, registry);
    }

    // ---------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------

    public TruffleMetadataAccess resolver() { return resolver; }
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

    public RootCallTarget getCallTarget(FunctionDefinition fd)
    {
        return compile(fd).callTarget();
    }


    public Object executeFunction(FunctionDefinition fd, Object[] rawArgs)
    {
        RootCallTarget ct = compile(fd).callTarget();
        if (ct != null)
        {
            return ct.call(rawArgs);
        }
        throw new RuntimeException("No CallTarget for: " + getFunctionName(fd));
    }

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
                PureNode[] body = astBuilder.lowerBody(lambda._expressionSequence(), cf.layout());
                org.finos.legend.pure.truffle.types.PureSequence openVars = lambda._openVariables();
                String[] openVarNames;
                if (openVars == null || openVars.isEmpty())
                {
                    openVarNames = new String[0];
                }
                else
                {
                    openVarNames = new String[openVars.size()];
                    for (int i = 0; i < openVars.size(); i++)
                    {
                        Object ov = openVars.getBoxed(i);
                        if (ov instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.VariableExpression ve)
                        {
                            openVarNames[i] = ve._name();
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
                        language, "lambda@" + System.identityHashCode(lambda),
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
    public Object coerceToJavaEnum(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enumeration en, String valueName)
    {
        if (!(en instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe))
        {
            return null;
        }
        String enumPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe);
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
        if (value instanceof java.lang.Enum<?>
                && value instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any)
        {
            return enumCgt(value);
        }
        if (value instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any any)
        {
            return any._classifierGenericType();
        }
        return null;
    }

    private org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue enumCgt(Object enumConstant)
    {
        var cached = enumCgtCache.get(enumConstant);
        if (cached != null)
        {
            return cached;
        }
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
        Object enumType = resolver.getElement(purePath);
        if (!(enumType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t))
        {
            return null;
        }
        var cgt = org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(t, resolver);
        enumCgtCache.put(enumConstant, cgt);
        return cgt;
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
        String qpName = staticQp._name();
        int argCount = rawArgs.length;
        java.util.List<org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type> mro =
                org.finos.legend.pure.truffle.runtime.helper._Type.linearize(runtimeType, resolver);
        for (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type type : mro)
        {
            if (type instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class cls && cls._qualifiedProperties() != null)
            {
                for (Object candidate : cls._qualifiedProperties().toBoxedArray())
                {
                    if (candidate instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty cqp
                            && qpName.equals(cqp._name())
                            && cqp._parameters() != null
                            && cqp._parameters().size() == argCount)
                    {
                        return cqp;
                    }
                }
            }
        }
        return staticQp;
    }

    public static void bindQpTypeVariablesStatic(Object target, VirtualFrame frame, FrameLayout layout)
    {
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType cgt = getClassifierGenericType(target);
        if (!(cgt instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue gtv)
                || gtv._typeVariableValues() == null || gtv._typeVariableValues().isEmpty())
        {
            return;
        }
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type ownerType =
                org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
        org.finos.legend.pure.truffle.types.PureSequence typeVars = null;
        if (ownerType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class cls)
        {
            typeVars = cls._typeVariables();
        }
        if (typeVars == null || typeVars.isEmpty()) return;
        org.finos.legend.pure.truffle.types.PureSequence typeVarVals = gtv._typeVariableValues();
        int count = Math.min(typeVars.size(), typeVarVals.size());
        for (int i = 0; i < count; i++)
        {
            Object tvObj = typeVars.getBoxed(i);
            String name = (tvObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.VariableExpression ve) ? ve._name() : String.valueOf(tvObj);
            Integer slot = layout.slotFor(name);
            if (slot != null)
            {
                Object tvVal = typeVarVals.getBoxed(i);
                if (tvVal instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue av)
                {
                    tvVal = av._value();
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
            PureNode[] body = astBuilder.lowerBody(fd._expressionSequence(), layout);
            cf.setBody(body);
            if (!(fd instanceof LambdaFunction))
            {
                String name = getFunctionName(fd);
                com.oracle.truffle.api.source.SourceSection rootSource = null;
                try
                {
                    org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SourceInformation si = null;
                    if (fd instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any any)
                    {
                        si = any._sourceInformation();
                    }
                    if (si == null && fd._expressionSequence() != null && !fd._expressionSequence().isEmpty())
                    {
                        Object firstExpr = fd._expressionSequence().getBoxed(0);
                        if (firstExpr instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.ValueSpecification vs)
                        {
                            si = vs._sourceInformation();
                        }
                    }
                    rootSource = org.finos.legend.pure.truffle.ast.PureSourceHelper.createSourceSection(si);
                }
                catch (Exception e) { throw new RuntimeException("Failed to set source information", e); }
                PureFunctionRootNode root = new PureFunctionRootNode(language, name, layout, body, rootSource);
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
        if (fd instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe)
        {
            try
            {
                return org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe);
            }
            catch (RuntimeException ignored) {}
        }
        return "fn@" + System.identityHashCode(fd);
    }
}
