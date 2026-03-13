package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.function.LambdaFunctionImpl;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.AtomicValueImpl;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Unit;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Function;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.FunctionDefinitionResolver;
import org.jspecify.annotations.Nullable;

import static org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext.lazy;

public class AtomicValueResolver
{
    /**
     * Resolve an AtomicValue: if it wraps a LambdaFunction, resolve the lambda body;
     * if it wraps a Package_Pointer, resolve it to the actual element from the model.
     */
    public static AtomicValue resolveAtomicValue(AtomicValue av, MetadataAccess model, CompilationContext context)
    {
        Object value = av._value();
        if (value instanceof LambdaFunction lambda)
        {
            return processLambda(av, model, context, lambda);
        }
        else if (value instanceof meta.pure.protocol.grammar.Package_Pointer pp)
        {
            String pointerValue = pp._pointerValue();
            if (pointerValue.indexOf('~') >= 0)
            {
                // Unit reference: MeasurePath~UnitName
                meta.pure.metamodel.type.Unit unit = _Unit.findUnit(pointerValue, context.imports(), model, context, av._sourceInformation());
                if (unit != null)
                {
                    ((AtomicValueImpl) av)
                            ._value(unit)
                            ._genericType(_GenericType.asInferred(unit._classifierGenericType()));
                }
            }
            else
            {
                int checkpoint = context.currentErrorCount();
                PackageableElement element = _PackageableElement.findElementOrReportError(pointerValue, context.imports(), model, context, av._sourceInformation());
                if (element != null)
                {
                    ((AtomicValueImpl) av)
                            ._value(element)
                            ._genericType(_GenericType.asInferred(element._classifierGenericType()));
                }
                else if (context.currentErrorCount() == checkpoint)
                {
                    context.addError(new CompilationError("The element '" + pointerValue + "' can't be found", av._sourceInformation()));
                }
            }
        }
        return av;
    }

    private static @Nullable AtomicValue processLambda(AtomicValue av, MetadataAccess model, CompilationContext context, LambdaFunction lambda)
    {
        MutableList<VariableExpression> params = lambda._parameters();

        // Skip body resolution if lambda params don't have concrete types.
        // Phase 2 (resolveLambdaWithBindings) will set param types
        // from bindings before calling resolve again.
        // Use isConcreteInContext to allow in-scope type params (e.g., class-level T)
        if (params.anySatisfy(p -> !_GenericType.isConcreteInContext(p._genericType(), context.compilerContextExtensions(PureLanguageCompilerContext.class).scopeTypeParamNames()) ||
                                   !_Multiplicity.isConcreteInContext(p._multiplicity(), context.compilerContextExtensions(PureLanguageCompilerContext.class).scopeMultiplicityParamNames())))
        {
            context.addError(new CompilationError("Can't resolve lambda parameter types", av._sourceInformation()));
            return av;
        }

        // Check for lambda param names that conflict with existing scope variables
        _Function.validateFunctionParameters(params, context, av._sourceInformation());
        context.compilerContextExtensions(PureLanguageCompilerContext.class).pushScope(params);
        try
        {
            context.debug("resolveAtomicValue: LAMBDA resolving body");
            lambda._expressionSequence(FunctionDefinitionResolver.resolveExpressionSequence(lambda._expressionSequence(), model, context));
        }
        finally
        {
            context.compilerContextExtensions(PureLanguageCompilerContext.class).popScope();
        }

        // Collect open variables: variable references whose names
        // are not lambda parameters (they come from enclosing scopes)
        MutableSet<String> paramNames = params.collect(VariableExpression::_name).toSet();
        MutableList<VariableExpression> openVars = Lists.mutable.empty();
        MutableSet<String> seenNames = Sets.mutable.empty();
        if (lambda._expressionSequence() != null)
        {
            lambda._expressionSequence().forEach(vs -> collectVariableReferences(vs, paramNames, seenNames, openVars));
        }
        ((LambdaFunctionImpl) lambda)._openVariables(openVars);

        // Always (re)build the lambda's genericType: LambdaFunction<{paramTypes -> returnType}>
        if (lambda._expressionSequence() != null && lambda._expressionSequence().notEmpty())
        {
            ValueSpecification lastExpr = lambda._expressionSequence().getLast();
            if (lastExpr._genericType() != null)
            {
                meta.pure.metamodel.type.FunctionTypeImpl ft = new meta.pure.metamodel.type.FunctionTypeImpl();
                ft._parameters(params);
                ft._returnType(lastExpr._genericType());
                ft._returnMultiplicity(lastExpr._multiplicity());

                meta.pure.metamodel.type.Type lambdaType = (meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::function::LambdaFunction");
                GenericType lambdaGT = new meta.pure.metamodel.type.generics.InferredGenericTypeImpl()
                        ._rawType(lambdaType)
                        ._typeArguments(org.eclipse.collections.impl.factory.Lists.mutable.with(
                                new meta.pure.metamodel.type.generics.InferredGenericTypeImpl()._rawType(ft)));
                ((AtomicValueImpl) av)._genericType(lambdaGT);
                context.debug("resolveAtomicValue: LAMBDA gt=%s", lazy(() -> _GenericType.print(lambdaGT)));
            }
        }
        return av;
    }

    /**
     * Recursively collect VariableExpression references from a value specification tree,
     * filtering to those not in the given parameter names (i.e., open variables).
     */
    private static void collectVariableReferences(
            ValueSpecification vs,
            MutableSet<String> paramNames,
            MutableSet<String> seenNames,
            MutableList<VariableExpression> result)
    {
        if (vs instanceof VariableExpression varExpr)
        {
            String name = varExpr._name();
            if (!paramNames.contains(name) && seenNames.add(name))
            {
                result.add(varExpr);
            }
        }
        else if (vs instanceof FunctionExpression fe && fe._parametersValues() != null)
        {
            fe._parametersValues().forEach(pv -> collectVariableReferences(pv, paramNames, seenNames, result));
        }
        else if (vs instanceof AtomicValue av && av._value() instanceof LambdaFunction innerLambda)
        {
            // Recurse into nested lambda bodies, but exclude the inner lambda's own params
            MutableSet<String> innerParamNames = org.eclipse.collections.impl.factory.Sets.mutable.withAll(paramNames);
            if (innerLambda._parameters() != null)
            {
                innerLambda._parameters().forEach(p -> innerParamNames.add(p._name()));
            }
            if (innerLambda._expressionSequence() != null)
            {
                innerLambda._expressionSequence().forEach(inner -> collectVariableReferences(inner, innerParamNames, seenNames, result));
            }
        }
        else if (vs instanceof meta.pure.metamodel.valuespecification.Collection col && col._values() != null)
        {
            col._values().forEach(v -> collectVariableReferences(v, paramNames, seenNames, result));
        }
    }

}
