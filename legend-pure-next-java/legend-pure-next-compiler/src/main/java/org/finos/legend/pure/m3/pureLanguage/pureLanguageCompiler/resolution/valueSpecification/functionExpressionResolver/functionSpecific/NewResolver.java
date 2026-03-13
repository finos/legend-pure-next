package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.functionSpecific;

import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;

public class NewResolver
{
    /**
     * Validate that all required properties (multiplicity lower bound >= 1 and no default value)
     * are provided as key expressions in a {@code new} expression.
     */
    public static void validateNewRequiredProperties(FunctionExpression fe, MetadataAccess model, CompilationContext context)
    {
        // Get the class being instantiated from the return type
        GenericType returnGT = fe._genericType();
        if (returnGT == null || !(returnGT._rawType() instanceof meta.pure.metamodel.type.Class cls))
        {
            return;
        }

        // Collect all provided property names from key expressions
        org.eclipse.collections.api.set.MutableSet<String> providedNames = org.eclipse.collections.impl.factory.Sets.mutable.empty();
        org.eclipse.collections.api.list.ListIterable<? extends ValueSpecification> params = fe._parametersValues();
        if (params.size() >= 2)
        {
            ValueSpecification keyParam = params.get(1);
            if (keyParam instanceof meta.pure.metamodel.valuespecification.Collection col)
            {
                col._values().forEach(v ->
                {
                    if (v instanceof FunctionExpression keyExpr && keyExpr._parametersValues().notEmpty())
                    {
                        ValueSpecification keyNameVS = keyExpr._parametersValues().getFirst();
                        if (keyNameVS instanceof meta.pure.metamodel.valuespecification.AtomicValue av && av._value() instanceof String name)
                        {
                            providedNames.add(name);
                        }
                    }
                });
            }
        }

        // Check all properties including inherited ones
        checkMissingProperties(cls, providedNames, fe, context);
    }

    private static void checkMissingProperties(
            meta.pure.metamodel.type.Type type,
            org.eclipse.collections.api.set.MutableSet<String> providedNames,
            FunctionExpression fe,
            CompilationContext context)
    {
        if (type instanceof meta.pure.metamodel.SimplePropertyOwner po && po._properties() != null)
        {
            po._properties().forEach(prop ->
            {
                // Skip inferred/excluded system properties (e.g., classifierGenericType)
                if (prop._stereotypes() != null && prop._stereotypes().anySatisfy(s ->
                        "inferred".equals(s._value()) || "excluded".equals(s._value())))
                {
                    return;
                }
                if (prop._multiplicity() != null
                        && _Multiplicity.lowerBound(prop._multiplicity()) >= 1
                        && prop._defaultValue() == null
                        && !providedNames.contains(prop._name()))
                {
                    SourceInformation srcInfo = fe._sourceInformation();
                    context.addError(new CompilationError(
                            "Missing required property '" + prop._name() + "' of type " + _GenericType.print(prop._genericType()) + " in new expression",
                            srcInfo));
                }
            });
        }
        // Check inherited properties
        if (type._generalizations() != null)
        {
            type._generalizations().forEach(gen ->
            {
                if (gen._general() != null && gen._general()._rawType() != null)
                {
                    checkMissingProperties(gen._general()._rawType(), providedNames, fe, context);
                }
            });
        }
    }

}
