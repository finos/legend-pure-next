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
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Class;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Property;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._VariableExpression;
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

        // Resolve the receiver to get its genericType before property lookup
        ValueSpecification receiver = ValueSpecificationResolver.resolve(expr._parametersValues().getFirst(), model, context);
        expr._parametersValues().set(0, receiver);

        if (receiver._genericType() == null)
        {
            return expr;
        }

        Type ownerType = _GenericType.type(receiver._genericType());
        context.debug("resolveDotApplication: .%s receiverGT=%s receiverMul=%s",
                functionName, lazy(() -> _GenericType.print(receiver._genericType())), lazy(() -> _Multiplicity.print(receiver._multiplicity())));

        // Type parameter reference (e.g., Z from a PCT function) — can't resolve properties
        if (ownerType == null)
        {
            context.addError(new CompilationError(
                    "Can't resolve property '" + functionName + "' on unresolved type parameter '"
                            + _GenericType.print(receiver._genericType()) + "'",
                    expr._sourceInformation()));
            return expr;
        }

        Function result = lookupFunction(functionName, receiver, ownerType, expr, model, context);
        if (result == null)
        {
            return expr;  // error already recorded in lookupFunction
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

    /**
     * Look up the function/property/column for {@code functionName} on
     * the receiver's type. Returns {@code null} and records an error if not found.
     */
    private static Function lookupFunction(
            String functionName,
            ValueSpecification receiver,
            Type ownerType,
            FunctionExpression expr,
            MetadataAccess model,
            CompilationContext context)
    {
        // Enum value property on a specific Enumeration instance
        if (receiver instanceof AtomicValue av && av._value() instanceof Enumeration enumeration)
        {
            Property matchedProp = _Class.findProperty(enumeration, functionName);
            if (matchedProp != null)
            {
                context.debug("  enum property found: %s", matchedProp._name());
                return matchedProp;
            }
            String enumName = (_GenericType.typeArguments(receiver._genericType()) != null && _GenericType.typeArguments(receiver._genericType()).notEmpty())
                    ? _GenericType.print(_GenericType.typeArguments(receiver._genericType()).getFirst())
                    : _GenericType.print(receiver._genericType());
            context.addError(new CompilationError(
                    "Can't find enum value '" + functionName + "' in enumeration '" + enumName + "'",
                    expr._sourceInformation()));
            return null;
        }

        // Property/qualified-property access on a PropertyOwner (Class, Association, etc.)
        if (ownerType instanceof meta.pure.metamodel.SimplePropertyOwner po)
        {
            Property matchedProp = _Class.findProperty(po, functionName);
            if (matchedProp != null)
            {
                Function resolved = _Property.resolveProperty(matchedProp, receiver._genericType(), model);
                context.debug("  property found: %s -> %s", matchedProp._name(), lazy(() -> CompilationContext.debugFunc(resolved)));
                return resolved;
            }
            if (po instanceof meta.pure.metamodel.type.Class cls)
            {
                meta.pure.metamodel.function.property.QualifiedProperty matchedQP = _Class.findQualifiedProperty(cls, functionName);
                if (matchedQP != null)
                {
                    context.debug("  qualified property found: %s", matchedQP._name());
                    return matchedQP;
                }
            }
            context.addError(new CompilationError(
                    "Can't find property '" + functionName + "' in class '" + _GenericType.print(receiver._genericType()) + "'",
                    expr._sourceInformation()));
            return null;
        }

        // Column access on a RelationType
        if (ownerType instanceof meta.pure.metamodel.relation.RelationType relationType)
        {
            Function col = relationType._columns().detect(c -> functionName.equals(c._name()));
            context.debug("  column lookup: %s %s", functionName, col != null ? "found" : "NOT FOUND");
            if (col != null)
            {
                return col;
            }
            context.addError(new CompilationError(
                    "Can't find column '" + functionName + "' in relation type '" + _GenericType.print(receiver._genericType()) + "'",
                    expr._sourceInformation()));
            return null;
        }

        return null;
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
        VariableExpression lambdaParam = _VariableExpression.newVariableExpression(model)
                ._name("v_automap")
                ._genericType(receiver._genericType())
                ._multiplicity(pureOne);

        // Build lambda body: $v_automap.name (an unresolved DotApplication)
        VariableExpression varRef = _VariableExpression.newVariableExpression(model)
                ._name("v_automap");

        meta.pure.metamodel.valuespecification.DotApplicationImpl dotBody =
                new meta.pure.metamodel.valuespecification.DotApplicationImpl();
        dotBody._classifierGenericType(_GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::valuespecification::DotApplication"), model));
        dotBody._functionName(accessName);
        dotBody._parametersValues(Lists.mutable.with(varRef));
        dotBody._sourceInformation(expr._sourceInformation());

        // Build the lambda (no genericType — Phase 2 will resolve it)
        meta.pure.metamodel.function.LambdaFunctionImpl lambda = new meta.pure.metamodel.function.LambdaFunctionImpl();
        lambda._classifierGenericType(_GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::function::LambdaFunction"), model));
        lambda._parameters(Lists.mutable.with(lambdaParam));
        lambda._expressionSequence(Lists.mutable.with(dotBody));

        // Wrap in an AtomicValue (no genericType — treated as unresolved lambda)
        meta.pure.metamodel.valuespecification.AtomicValueImpl lambdaAV =
                new meta.pure.metamodel.valuespecification.AtomicValueImpl();
        lambdaAV._classifierGenericType(_GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::valuespecification::AtomicValue"), model));
        lambdaAV._value(lambda);
        lambdaAV._multiplicity(pureOne);

        // Create a new FunctionApplication for 'map' wrapping the DotApplication
        meta.pure.metamodel.valuespecification.FunctionInvocationImpl mapExpr =
                new meta.pure.metamodel.valuespecification.FunctionInvocationImpl();
        mapExpr._classifierGenericType(_GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::valuespecification::FunctionInvocation"), model));
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

        if (mapExpr._func() == null)
        {
            context.addError(new CompilationError(
                    "Can't resolve automap for property '" + accessName + "' on '"
                            + _GenericType.print(receiver._genericType()) + _Multiplicity.print(receiver._multiplicity()) + "'",
                    expr._sourceInformation()));
        }

        return mapExpr;
    }

    /**
     * Check whether a FunctionExpression is an automap: a {@code map(...)} call
     * with a lambda whose parameter is named {@code v_automap}.
     */
    public static boolean isAutomap(FunctionExpression fe)
    {
        if (fe._parametersValues().size() == 2)
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
