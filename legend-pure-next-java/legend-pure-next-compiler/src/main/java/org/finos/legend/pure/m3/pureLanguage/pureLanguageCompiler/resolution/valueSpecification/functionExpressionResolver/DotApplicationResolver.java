package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver;

import meta.pure.metamodel.function.Function;
import meta.pure.metamodel.function.property.Property;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.Enumeration;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.DotApplication;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Class;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Property;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.ValueSpecificationResolver;

import static org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext.lazy;


/**
 * Resolve DotApplication expressions: property and column access on a receiver type.
 * <p>
 * When the receiver multiplicity is not exactly [1], an <b>automap</b>
 * transformation is applied: the DotApplication is rewritten as
 * {@code map(receiver, {v:T[1] | $v.prop})}.
 */
public final class DotApplicationResolver
{
    private DotApplicationResolver()
    {
    }

    /**
     * Resolve a DotApplication expression by looking up property access
     * on the receiver type (simple properties and association properties).
     * <p>
     * When the receiver multiplicity is not exactly [1], an <b>automap</b>
     * transformation is applied: a new {@code FunctionApplicationImpl} for
     * {@code map(receiver, {v:T[1] | $v.prop})} is created and returned
     * as a replacement node in the expression tree.
     *
     * @return the same {@code expr} for normal [1] access, or a new
     *         {@code FunctionApplicationImpl} for automap
     */
    public static ValueSpecification resolveDotApplication(
            DotApplication expr,
            MetadataAccess model,
            CompilationContext context)
    {
        String functionName = expr._functionName();
        ListIterable<? extends ValueSpecification> parametersValues = expr._parametersValues();

        // Resolve the receiver to get its genericType before property lookup
        ValueSpecification receiver = parametersValues.getFirst();
        ValueSpecificationResolver.resolve(receiver, model, context);

        if (receiver._genericType() != null)
        {
            Type ownerType = receiver._genericType()._rawType();
            context.debug("resolveDotApplication: .%s receiverGT=%s receiverMul=%s",
                    functionName, lazy(() -> _GenericType.print(receiver._genericType())), lazy(() -> _Multiplicity.print(receiver._multiplicity())));

            Function result = null;

            // --- Enum value property on a specific Enumeration instance ---
            // When the receiver IS an Enumeration (e.g., CC_GeographicEntityType),
            // look for properties on that specific enumeration instance.
            if (receiver instanceof AtomicValue av && av._value() instanceof Enumeration enumeration)
            {
                Property matchedProp = _Class.findProperty(enumeration, functionName);
                if (matchedProp != null)
                {
                    result = matchedProp;
                    context.debug("  enum property found: %s", matchedProp._name());
                }
                else
                {
                    String enumName = (receiver._genericType()._typeArguments() != null && receiver._genericType()._typeArguments().notEmpty())
                            ? _GenericType.print(receiver._genericType()._typeArguments().getFirst())
                            : _GenericType.print(receiver._genericType());
                    context.addError(new CompilationError(
                            "Can't find enum value '" + functionName + "' in enumeration '"
                                    + enumName + "'",
                            expr._sourceInformation()));
                    return expr;
                }
            }

            // --- Property access on a PropertyOwner (Class, Association, etc.) ---
            if (result == null && ownerType instanceof meta.pure.metamodel.SimplePropertyOwner po)
            {
                Property matchedProp = _Class.findProperty(po, functionName);
                if (matchedProp != null)
                {
                    result = _Property.resolveProperty(matchedProp, receiver._genericType(), model);
                    Function resolved = result;
                    context.debug("  property found: %s -> %s", matchedProp._name(), lazy(() -> CompilationContext.debugFunc(resolved)));
                }
                else if (po instanceof meta.pure.metamodel.type.Class cls)
                {
                    // Fall back to qualified properties (e.g., .res(), .res('z'))
                    meta.pure.metamodel.function.property.QualifiedProperty matchedQP =
                            _Class.findQualifiedProperty(cls, functionName);
                    if (matchedQP != null)
                    {
                        result = matchedQP;
                        context.debug("  qualified property found: %s", matchedQP._name());
                    }
                    else
                    {
                        context.addError(new CompilationError(
                                "Can't find property '" + functionName + "' in class '"
                                        + _GenericType.print(receiver._genericType()) + "'",
                                expr._sourceInformation()));
                        return expr;
                    }
                }
                else
                {
                    context.addError(new CompilationError(
                            "Can't find property '" + functionName + "' in class '"
                                    + _GenericType.print(receiver._genericType()) + "'",
                            expr._sourceInformation()));
                    return expr;
                }
            }

            // --- Column access on a RelationType directly ---
            // e.g. lag returns T[0..1] where T resolves to a RelationType
            else if (ownerType instanceof meta.pure.metamodel.relation.RelationType relationType)
            {
                result = relationType._columns().detect(c -> functionName.equals(c._name()));
                Function colResult = result;
                context.debug("  column lookup: %s %s", functionName, colResult != null ? "found" : "NOT FOUND");
                if (colResult == null)
                {
                    context.addError(new CompilationError(
                            "Can't find column '" + functionName + "' in relation type '"
                                    + _GenericType.print(receiver._genericType()) + "'",
                            expr._sourceInformation()));
                    return expr;
                }
            }

            Multiplicity receiverMul = receiver._multiplicity();
            if (receiverMul != null
                    && _Multiplicity.lowerBound(receiverMul) == 1
                    && _Multiplicity.upperBound(receiverMul) == 1)
            {
                // Normal case: [1] receiver, direct access
                context.debug("  direct access [1]");
                expr._func(result);
                if (result instanceof Property prop)
                {
                    expr._genericType(prop._genericType());
                    expr._multiplicity(prop._multiplicity());
                }
                return expr;
            }
            else
            {
                context.debug("  AUTOMAP: receiverMul=%s", lazy(() -> _Multiplicity.print(receiverMul)));
                return buildAutomap(expr, receiver, functionName, model, context);
            }
        }
        return expr;
    }

    /**
     * Build an automap: transform {@code receiver.name} into
     * {@code map(receiver, {v:T[1] | $v.name})} when receiver
     * multiplicity is not [1].
     * <p>
     * Creates a new {@code FunctionApplicationImpl} for the {@code map} call,
     * wrapping the DotApplication inside a lambda body. The new expression
     * replaces the DotApplication in the parent's expression tree.
     *
     * @return a new, fully resolved {@code FunctionApplicationImpl}
     */
    private static FunctionExpression buildAutomap(
            FunctionExpression expr,
            ValueSpecification receiver,
            String accessName,
            MetadataAccess model,
            CompilationContext context)
    {
        Multiplicity pureOne = (Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::PureOne");

        // Build lambda param: v_automap:ReceiverElementType[1]
        VariableExpression lambdaParam = new VariableExpressionImpl()
                ._name("v_automap")
                ._genericType(receiver._genericType())
                ._multiplicity(pureOne);

        // Build lambda body: $v_automap.name (an unresolved DotApplication)
        VariableExpression varRef = new VariableExpressionImpl()
                ._name("v_automap");

        meta.pure.metamodel.valuespecification.DotApplicationImpl dotBody =
                new meta.pure.metamodel.valuespecification.DotApplicationImpl();
        dotBody._functionName(accessName);
        dotBody._parametersValues(Lists.mutable.with(varRef));
        dotBody._sourceInformation(expr._sourceInformation());

        // Build the lambda (no genericType — Phase 2 will resolve it)
        meta.pure.metamodel.function.LambdaFunctionImpl lambda = new meta.pure.metamodel.function.LambdaFunctionImpl();
        lambda._parameters(Lists.mutable.with(lambdaParam));
        lambda._expressionSequence(Lists.mutable.with(dotBody));

        // Wrap in an AtomicValue (no genericType — treated as unresolved lambda)
        meta.pure.metamodel.valuespecification.AtomicValueImpl lambdaAV =
                new meta.pure.metamodel.valuespecification.AtomicValueImpl();
        lambdaAV._value(lambda);
        lambdaAV._multiplicity(pureOne);

        // Create a new FunctionApplication for 'map' wrapping the DotApplication
        meta.pure.metamodel.valuespecification.FunctionInvocationImpl mapExpr =
                new meta.pure.metamodel.valuespecification.FunctionInvocationImpl();
        mapExpr._functionName("map");
        mapExpr._parametersValues(Lists.mutable.with(receiver, lambdaAV));
        mapExpr._sourceInformation(expr._sourceInformation());

        // Resolve the map function through the standard path
        context.debug("  automap: resolving map() expression");
        context.debugDepthInc();
        ValueSpecificationResolver.resolve(mapExpr, model, context);
        context.debugDepthDec();
        context.debug("  automap: map() resolved gt=%s mul=%s",
                lazy(() -> _GenericType.print(mapExpr._genericType())), lazy(() -> _Multiplicity.print(mapExpr._multiplicity())));

        return mapExpr;
    }
    /**
     * Check whether a FunctionExpression is an automap: a {@code map(...)} call
     * with a lambda whose parameter is named {@code v_automap}.
     */
    public static boolean isAutomap(FunctionExpression fe)
    {
        if (fe._parametersValues() != null && fe._parametersValues().size() == 2)
        {
            ValueSpecification second = fe._parametersValues().get(1);
            if (second instanceof meta.pure.metamodel.valuespecification.AtomicValue av
                    && av._value() instanceof meta.pure.metamodel.function.LambdaFunction lambda)
            {
                return lambda._parameters() != null
                        && lambda._parameters().size() == 1
                        && "v_automap".equals(lambda._parameters().getFirst()._name());
            }
        }
        return false;
    }
}
