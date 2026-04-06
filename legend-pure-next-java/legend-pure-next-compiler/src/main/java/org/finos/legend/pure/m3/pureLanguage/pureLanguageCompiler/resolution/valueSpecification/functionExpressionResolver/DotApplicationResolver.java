package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver;

import meta.pure.metamodel.function.Function;
import meta.pure.metamodel.function.property.AbstractProperty;
import meta.pure.metamodel.function.property.Property;
import meta.pure.metamodel.function.property.QualifiedProperty;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.relation.Column;
import meta.pure.metamodel.type.Enumeration;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.DotApplication;
import meta.pure.metamodel.valuespecification.DotApplicationImpl;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.ParametersBinding;
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
        MutableList<ValueSpecification> processParameters = expr._parametersValues().collect(p -> ValueSpecificationResolver.resolve(p, model, context));
        ValueSpecification receiver = processParameters.getFirst();

        Type ownerType = _GenericType.type(receiver._genericType());
        context.debug("resolveDotApplication: .%s receiverGT=%s receiverMul=%s additionalParams=%d",
                functionName, lazy(() -> _GenericType.print(receiver._genericType())), lazy(() -> _Multiplicity.print(receiver._multiplicity())), processParameters.size());

        // Type parameter reference (e.g., Z from a PCT function) — can't resolve properties
        if (ownerType == null)
        {
            context.addError(new CompilationError(
                    "Can't resolve property '" + functionName + "' on unresolved type parameter '"
                            + _GenericType.print(receiver._genericType()) + "'",
                    expr._sourceInformation()));
            return expr;
        }

        Function result = lookupFunction(functionName, processParameters.size(), receiver, ownerType, expr, model, context);
        if (result == null)
        {
            return expr;  // error already recorded in lookupFunction
        }

        // Resolve return type and multiplicity; for qualified properties, apply class type bindings
        GenericType resolvedGT;
        Multiplicity resolvedMul;
        if (result instanceof QualifiedProperty qp)
        {
            resolvedGT = qp._genericType();
            resolvedMul = qp._multiplicity();
            if (_GenericType.type(receiver._genericType()) instanceof meta.pure.metamodel.type.Class ownerClass)
            {
                ParametersBinding bindings = _Class.buildBindingsFromGenericType(ownerClass, receiver._genericType());
                if (!bindings.typeBindings().isEmpty() || !bindings.multiplicityBindings().isEmpty())
                {
                    resolvedGT = _GenericType.makeAsConcreteAsPossible(resolvedGT, bindings, model);
                    resolvedMul = _Multiplicity.makeAsConcreteAsPossible(resolvedMul, bindings);
                }
            }
        }
        else if (result instanceof AbstractProperty ap)
        {
            resolvedGT = ap._genericType();
            resolvedMul = ap._multiplicity();
        }
        else
        {
            resolvedGT = ((Column) result)._genericType();
            resolvedMul = ((Column) result)._multiplicity();
        }

        Multiplicity receiverMul = receiver._multiplicity();
        if (receiverMul != null
                && _Multiplicity.lowerBound(receiverMul) == 1
                && _Multiplicity.upperBound(receiverMul) == 1)
        {
            // Normal case: [1] receiver, direct access
            context.debug("  direct access [1]");
            return  ((DotApplicationImpl) expr._copy())
                                        ._parametersValues(processParameters)
                                        ._func(result)
                                        ._genericType(resolvedGT)
                                        ._multiplicity(resolvedMul);
        }
        else
        {
            context.debug("  AUTOMAP: receiverMul=%s", lazy(() -> _Multiplicity.print(receiverMul)));
            return buildAutomap(expr, receiver, functionName, processParameters, model, context);
        }
    }

    /**
     * Look up the function/property/column for {@code functionName} on
     * the receiver's type. Returns {@code null} and records an error if not found.
     */
    private static Function lookupFunction(
            String functionName,
            int paramCount,
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
            // Simple property: only match when no additional params (just the receiver)
            if (paramCount == 1)
            {
                Property matchedProp = _Class.findProperty(po, functionName);
                if (matchedProp != null)
                {
                    Function resolved = _Property.resolveProperty(matchedProp, receiver._genericType(), model);
                    context.debug("  property found: %s -> %s", matchedProp._name(), lazy(() -> CompilationContext.debugFunc(resolved)));
                    return resolved;
                }
            }
            if (po instanceof meta.pure.metamodel.type.Class cls)
            {
                // Find all QPs by name, then filter by arity (paramCount includes the receiver = this)
                MutableList<QualifiedProperty> allQPs = _Class.findQualifiedProperties(cls, functionName);
                MutableList<QualifiedProperty> matched = allQPs.select(qp -> qp._parameters().size() == paramCount);
                if (matched.size() == 1)
                {
                    context.debug("  qualified property found: %s (arity %d)", matched.getFirst()._name(), paramCount);
                    return matched.getFirst();
                }
                else if (matched.size() > 1)
                {
                    // Multiple QPs with same name and arity — ambiguous (future: type-based disambiguation)
                    context.addError(new CompilationError(
                            "Ambiguous qualified property '" + functionName + "' with " + (paramCount - 1) + " parameter(s) in class '" + _GenericType.print(receiver._genericType()) + "'",
                            expr._sourceInformation()));
                    return null;
                }
                else if (allQPs.notEmpty())
                {
                    // QPs exist but none match by arity
                    String available = allQPs.collect(qp -> functionName + "(" + (qp._parameters().size() - 1) + " params)").makeString(", ");
                    context.addError(new CompilationError(
                            "No qualified property '" + functionName + "' with " + (paramCount - 1) + " parameter(s) found in class '" + _GenericType.print(receiver._genericType()) + "'. Available: " + available,
                            expr._sourceInformation()));
                    return null;
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
            MutableList<ValueSpecification> processParameters,
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
        // For qualified properties, includes additional parameters (e.g., $v_automap.name('ok3'))
        VariableExpression varRef = _VariableExpression.newVariableExpression(model)
                ._name("v_automap");

        MutableList<ValueSpecification> dotBodyParams = Lists.mutable.with(varRef);
        dotBodyParams.addAll(processParameters.subList(1, processParameters.size()));

        meta.pure.metamodel.valuespecification.DotApplicationImpl dotBody =
                new meta.pure.metamodel.valuespecification.DotApplicationImpl(model);
        dotBody._classifierGenericType(_GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::valuespecification::DotApplication"), model));
        dotBody._functionName(accessName);
        dotBody._parametersValues(dotBodyParams);
        dotBody._sourceInformation(expr._sourceInformation());

        // Build the lambda (no genericType — Phase 2 will resolve it)
        meta.pure.metamodel.function.LambdaFunctionImpl lambda = new meta.pure.metamodel.function.LambdaFunctionImpl();
        lambda._classifierGenericType(_GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::function::LambdaFunction"), model));
        lambda._parameters(Lists.mutable.with(lambdaParam));
        lambda._expressionSequence(Lists.mutable.with(dotBody));

        // Wrap in an AtomicValue (no genericType — treated as unresolved lambda)
        meta.pure.metamodel.valuespecification.AtomicValueImpl lambdaAV =
                new meta.pure.metamodel.valuespecification.AtomicValueImpl(model);
        lambdaAV._classifierGenericType(_GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::valuespecification::AtomicValue"), model));
        lambdaAV._value(lambda);
        lambdaAV._multiplicity(pureOne);

        // Create a new FunctionApplication for 'map' wrapping the DotApplication
        meta.pure.metamodel.valuespecification.FunctionInvocationImpl mapExpr =
                new meta.pure.metamodel.valuespecification.FunctionInvocationImpl(model);
        mapExpr._classifierGenericType(_GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::valuespecification::FunctionInvocation"), model));
        mapExpr._functionName("map");
        mapExpr._parametersValues(Lists.mutable.with(receiver, lambdaAV));
        mapExpr._sourceInformation(expr._sourceInformation());

        // Resolve the map function through the standard path
        context.debug("  automap: resolving map() expression");
        context.debugDepthInc();
        ValueSpecification resolved = ValueSpecificationResolver.resolve(mapExpr, model, context);
        context.debugDepthDec();
        context.debug("  automap: map() resolved gt=%s mul=%s",
                lazy(() -> _GenericType.print(resolved._genericType())), lazy(() -> _Multiplicity.print(resolved._multiplicity())));

        if (!(resolved instanceof FunctionExpression fe) || fe._func() == null)
        {
            context.addError(new CompilationError(
                    "Can't resolve automap for property '" + accessName + "' on '"
                            + _GenericType.print(receiver._genericType()) + _Multiplicity.print(receiver._multiplicity()) + "'",
                    expr._sourceInformation()));
        }

        return (FunctionExpression) resolved;
    }
}
