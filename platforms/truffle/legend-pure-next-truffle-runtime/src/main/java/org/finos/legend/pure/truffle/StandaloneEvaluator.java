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
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.LambdaFunction;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.finos.legend.pure.truffle.builder.PureASTBuilder;
import org.finos.legend.pure.truffle.frame.CompiledFunction;
import org.finos.legend.pure.truffle.frame.FrameDescriptorBuilder;
import org.finos.legend.pure.truffle.frame.FrameLayout;

import java.util.WeakHashMap;

import org.finos.legend.pure.next.parser.PureParser;

/**
 * Standalone Pure interpreter — no {@code ValueSpecificationEvaluator},
 * no {@code NativeRepository}, no {@code ValueSpecification} types.
 *
 * <p>Every value flowing through this evaluator is a raw Java object:
 * {@code long}/{@code double}/{@code boolean}/{@code String} for
 * primitives, {@code PureSequence} for collections, generated Impl
 * classes for Pure class instances, {@code RawClosure} for lambdas,
 * {@code PureSequence.EMPTY} for empty.</p>
 *
 * <p>All scoping is frame-based ({@link VirtualFrame} with
 * {@link FrameLayout} slots). No HashMap {@code varStack}.</p>
 */
public final class StandaloneEvaluator
{

    private final TruffleMetadataAccess resolver;
    private final PureLanguage language;
    private final PureASTBuilder astBuilder;
    private PureParser pureParser;

    // Per-FD compilation cache: layout + lowered body
    private final WeakHashMap<FunctionDefinition, CompiledFunction> functionCache = new WeakHashMap<>();
    // Per-lambda RootCallTarget cache (lambdas don't have stable paths)
    private final WeakHashMap<LambdaFunction, RootCallTarget> lambdaCache = new WeakHashMap<>();

    // Current frame context (for sub-expression re-lowering under active layout)

    public StandaloneEvaluator(TruffleMetadataAccess resolver, PureLanguage language,
                               NativeNodeRegistry registry,
                               Object nativesFallback)
    {
        this.resolver = resolver;
        this.language = language;
        this.astBuilder = new PureASTBuilder(nativesFallback, registry);
    }

    public TruffleMetadataAccess resolver()
    {
        return resolver;
    }

    public PureParser pureParser()
    {
        return pureParser;
    }

    public void setPureParser(PureParser parser)
    {
        this.pureParser = parser;
    }

    // ---------------------------------------------------------------
    // CallTarget access — used by RawUserFunctionCallNode for Truffle-native calls
    // ---------------------------------------------------------------

    /**
     * Returns a compiled {@link RootCallTarget} for a FunctionDefinition (including QPs).
     */
    public RootCallTarget getCallTarget(FunctionDefinition fd)
    {
        CompiledFunction cf = compile(fd);
        return cf.callTarget();
    }

    // ---------------------------------------------------------------
    // Function execution — the main entry point
    // ---------------------------------------------------------------

    /**
     * Execute a compiled {@link FunctionDefinition} with raw args.
     * Returns a raw value (Long, String, PureSequence, etc.).
     */
    public Object executeFunction(FunctionDefinition fd, Object[] rawArgs)
    {
        CompiledFunction cf = compile(fd);
        com.oracle.truffle.api.RootCallTarget ct = cf.callTarget();
        if (ct != null)
        {
            return ct.call(rawArgs);
        }
        throw new RuntimeException("No CallTarget for: " + getFunctionName(fd));
    }

    // ---------------------------------------------------------------
    // Compilation
    // ---------------------------------------------------------------

    private CompiledFunction compile(FunctionDefinition fd)
    {
        CompiledFunction cached = functionCache.get(fd);
        if (cached != null)
        {
            return cached;
        }
        FrameLayout layout = FrameDescriptorBuilder.analyze(fd);
        if (layout == null)
        {
            layout = FrameDescriptorBuilder.analyzeMinimal(fd);
        }
        CompiledFunction cf = new CompiledFunction(layout);
        // Cache first to prevent infinite recursion if lowering triggers compilation
        functionCache.put(fd, cf);

        // Pre-lower body once and create RootCallTarget for proper Truffle adoption.
        // If lowering fails (e.g. FlatBuffer wrapper hazards), body stays null and
        // executeBody falls back to re-lowering.
        try
        {
            PureNode[] body = astBuilder.lowerBody(fd._expressionSequence(), layout);
            cf.setBody(body);

            // Create RootCallTarget only for non-lambda FDs (lambdas use RawLambdaRootNode)
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
                        // Try getting source from the first expression in the body
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
            catch (RuntimeException ignored)
            {
            }
        }
        return "fn@" + System.identityHashCode(fd);
    }

    /**
     * Returns a cached RootCallTarget for a lambda, compiling its body
     * on first access. Used by RawLambdaCallNode for DirectCallNode dispatch.
     */
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
                PureNode[] body = astBuilder.lowerBody(
                        lambda._expressionSequence(), cf.layout());
                org.finos.legend.pure.truffle.types.PureSequence openVars =
                        lambda._openVariables();
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
    // Property access — simple + QP dispatch
    // ---------------------------------------------------------------



    /**
     * Ensure a Java enum constant has its classifierGenericType set.
     * Resolves the Enumeration type from the PDB using the enum's interface name.
     */
    private static void ensureEnumCGT(Object constant, Class<?> enumClass, TruffleMetadataAccess resolver)
    {
        if (constant instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any anyConst
                && anyConst._classifierGenericType() == null
                && resolver != null)
        {
            Class<?>[] ifaces = enumClass.getInterfaces();
            if (ifaces.length > 0)
            {
                String purePath = ifaces[0].getName()
                        .replace("org.finos.legend.pure.truffle.pdb.", "")
                        .replace(".", "::");
                Object enumType = resolver.getElement(purePath);
                if (enumType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t)
                {
                    anyConst._classifierGenericType(
                            org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(t, resolver));
                }
            }
        }
    }



    /**
     * Coerce a PDB enum value to the generated Java enum constant for identity preservation.
     */
    public Object coerceToJavaEnum(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enumeration en, String valueName)
    {
        if (en instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe)
        {
            String enumPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe);
            String enumClassName = "org.finos.legend.pure.truffle.pdb." + enumPath.replace("::", ".") + "Enum";
            try
            {
                Class<?> enumClass = Class.forName(enumClassName);
                if (enumClass.isEnum())
                {
                    for (Object constant : enumClass.getEnumConstants())
                    {
                        if (constant instanceof java.lang.Enum<?> e && e.name().equals(valueName))
                        {
                            ensureEnumCGT(constant, enumClass, resolver());
                            return constant;
                        }
                    }
                }
            }
            catch (ClassNotFoundException ignored)
            {
            }
        }
        return null;
    }

    public FunctionDefinition resolveQpDispatch(
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty staticQp,
            Object[] rawArgs)
    {
        if (rawArgs.length == 0)
        {
            return staticQp;
        }
        Object target = rawArgs[0];
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType cgt = getClassifierGenericType(target);
        if (cgt == null)
        {
            return staticQp;
        }
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type runtimeType =
                org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
        if (runtimeType == null)
        {
            return staticQp;
        }
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

    /**
     * Bind type variable values from the target's CGT into the frame.
     * Handles any number of type variables.
     */
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
        if (typeVars == null || typeVars.isEmpty())
        {
            return;
        }
        org.finos.legend.pure.truffle.types.PureSequence typeVarVals = gtv._typeVariableValues();
        int count = Math.min(typeVars.size(), typeVarVals.size());
        for (int i = 0; i < count; i++)
        {
            Object tvObj = typeVars.getBoxed(i);
            String name = (tvObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.VariableExpression ve) ? ve._name() : String.valueOf(tvObj);
            Integer slot = layout.slotFor(name);
            if (slot != null)
            {
                // Type variable values are AtomicValue in the PDB — unwrap to raw
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
    // Accessors for nodes
    // ---------------------------------------------------------------

    public PureASTBuilder astBuilder()
    {
        return astBuilder;
    }
}
