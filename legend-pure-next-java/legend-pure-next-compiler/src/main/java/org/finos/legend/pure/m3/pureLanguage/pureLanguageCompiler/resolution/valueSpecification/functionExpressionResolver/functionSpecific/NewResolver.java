package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.functionSpecific;

import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;

public class NewResolver
{
    /**
     * Validate that all required properties (multiplicity lower bound >= 1 and no default value)
     * are provided as key expressions in a {@code new} expression.
     */
    /**
     * Validate copy expressions — only key expression checks (unknown, multiplicity, type).
     * Does NOT check abstract or missing properties (copy preserves the original's values).
     */
    public static void validateCopyProperties(FunctionExpression fe, MetadataAccess model, CompilationContext context)
    {
        GenericType returnGT = fe._genericType();
        if (returnGT == null || !(_GenericType.type(returnGT) instanceof meta.pure.metamodel.type.Class cls))
        {
            return;
        }

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

        checkKeyExpressions(cls, providedNames, fe, model, context);
    }

    public static void validateNewRequiredProperties(FunctionExpression fe, MetadataAccess model, CompilationContext context)
    {
        // Get the class being instantiated from the return type
        GenericType returnGT = fe._genericType();
        if (returnGT == null || !(_GenericType.type(returnGT) instanceof meta.pure.metamodel.type.Class cls))
        {
            return;
        }

        // Check if the class is abstract
        if (cls._stereotypes() != null && cls._stereotypes().anySatisfy(s ->
                "abstract".equals(s._value()) &&
                s._profile() != null &&
                "meta::pure::profiles::typemodifiers".equals(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(s._profile()))))
        {
            SourceInformation srcInfo = fe._sourceInformation();
            context.addError(new CompilationError(
                    "Cannot instantiate abstract class '" + org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(cls) + "'",
                    srcInfo));
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

        // Check unknown properties, multiplicity and type compatibility for each provided key expression
        checkKeyExpressions(cls, providedNames, fe, model, context);
    }

    /**
     * Validate each key expression: unknown property, multiplicity mismatch, type mismatch.
     * Uses _Multiplicity.subsumes for multiplicity and _GenericType.isCompatible for types.
     */
    private static void checkKeyExpressions(
            meta.pure.metamodel.type.Class cls,
            org.eclipse.collections.api.set.MutableSet<String> providedNames,
            FunctionExpression fe,
            MetadataAccess model,
            CompilationContext context)
    {
        // Build class type parameter bindings from the new/copy expression's return type
        // e.g., MyClass<String> → {Z → String}, so we can resolve property types
        org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.ParametersBinding classBindings =
                (fe._genericType() != null && !(fe._genericType() instanceof meta.pure.metamodel.type.generics.UndefinedGenericType) && !(fe._genericType() instanceof meta.pure.metamodel.type.generics.CompilerNotSetGenericType))
                        ? org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Class.buildBindingsFromGenericType(cls, fe._genericType())
                        : org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PlainParametersBinding.empty();

        // Check unknown properties (including deep property paths)
        providedNames.forEach(name ->
        {
            if (name.contains("."))
            {
                // Deep property path: traverse each segment
                validateDeepPropertyPath(cls, name, fe, model, context);
            }
            else if (findProperty(cls, name) == null)
            {
                context.addError(new CompilationError(
                        "Unknown property '" + name + "' in class '" +
                                org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(cls) + "'",
                        fe._sourceInformation()));
            }
        });

        // Check multiplicity and type for each key expression value
        org.eclipse.collections.api.list.ListIterable<? extends ValueSpecification> params = fe._parametersValues();
        if (params.size() < 2)
        {
            return;
        }
        ValueSpecification keyParam = params.get(1);
        if (!(keyParam instanceof meta.pure.metamodel.valuespecification.Collection col))
        {
            return;
        }
        col._values().forEach(v ->
        {
            if (v instanceof FunctionExpression keyExpr && keyExpr._parametersValues().size() >= 2)
            {
                ValueSpecification keyNameVS = keyExpr._parametersValues().getFirst();
                ValueSpecification keyValueVS = keyExpr._parametersValues().get(1);
                if (keyNameVS instanceof meta.pure.metamodel.valuespecification.AtomicValue av && av._value() instanceof String propName)
                {
                    meta.pure.metamodel.function.property.Property prop = findProperty(cls, propName);
                    if (prop == null)
                    {
                        return; // unknown property — already reported above
                    }

                    // Multiplicity check: does property multiplicity subsume value multiplicity?
                    if (prop._multiplicity() != null && keyValueVS._multiplicity() != null
                            && !_Multiplicity.subsumes(prop._multiplicity(), keyValueVS._multiplicity()))
                    {
                        context.addError(new CompilationError(
                                "Multiplicity mismatch for property '" + propName + "': expected "
                                        + formatMultiplicity(prop._multiplicity()) + ", got "
                                        + formatMultiplicity(keyValueVS._multiplicity()),
                                fe._sourceInformation()));
                    }

                    // Type check: resolve property type through class bindings, then compare
                    if (prop._genericType() != null && keyValueVS._genericType() != null)
                    {
                        GenericType resolvedPropGT = _GenericType.makeAsConcreteAsPossible(prop._genericType(), classBindings, model);
                        meta.pure.metamodel.type.Type propRawType = _GenericType.type(resolvedPropGT);
                        meta.pure.metamodel.type.Type valRawType = _GenericType.type(keyValueVS._genericType());
                        String pName = propRawType instanceof meta.pure.metamodel.PackageableElement pe ? pe._name() : null;
                        String vName = valRawType instanceof meta.pure.metamodel.PackageableElement pe ? pe._name() : null;
                        // Skip check for unresolved/abstract types, type parameters, and
                        // meta-level types (ValueSpecification subtypes used in meta construction)
                        String valPath = valRawType instanceof meta.pure.metamodel.PackageableElement vpe
                                ? org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(vpe) : "";
                        String pPath = propRawType instanceof meta.pure.metamodel.PackageableElement ppe
                                ? org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(ppe) : "";
                        boolean sameRawType = (pName != null && pName.equals(vName)) || (!pPath.isEmpty() && pPath.equals(valPath));
                        // Skip when the value type is a subtype of the property type (linearization check)
                        boolean isSubtype = propRawType != null && valRawType != null
                                && propRawType != valRawType
                                && org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type.linearize(valRawType, model).contains(propRawType);
                        boolean skip = pName == null || vName == null
                                || sameRawType || isSubtype
                                || "Any".equals(pName) || "Any".equals(vName)
                                || "Nil".equals(vName) || "Unknown".equals(pName) || "Unknown".equals(vName)
                                || valRawType instanceof meta.pure.metamodel.type.generics.TypeParameter
                                || valPath.startsWith("meta::pure::metamodel::valuespecification::");
                        if (!skip && !_GenericType.isCompatible(resolvedPropGT, keyValueVS._genericType(), model))
                        {
                            context.addError(new CompilationError(
                                    "Type mismatch for property '" + propName + "': expected " + _GenericType.print(prop._genericType()) + ", got " + _GenericType.print(keyValueVS._genericType()),
                                    fe._sourceInformation()));
                        }
                    }
                }
            }
        });
    }

    /**
     * Validate a deep property path like "address.city" by traversing each segment.
     * For "address.city" on Person: find "address" on Person → get its type (Address) → find "city" on Address.
     */
    private static void validateDeepPropertyPath(
            meta.pure.metamodel.type.Class cls,
            String fullPath,
            FunctionExpression fe,
            MetadataAccess model,
            CompilationContext context)
    {
        String[] segments = fullPath.split("\\.");
        meta.pure.metamodel.type.Type currentType = cls;
        for (int i = 0; i < segments.length; i++)
        {
            String segment = segments[i];
            meta.pure.metamodel.function.property.Property prop = findProperty(currentType, segment);
            if (prop == null)
            {
                String currentTypeName = currentType instanceof meta.pure.metamodel.PackageableElement pe
                        ? org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe)
                        : "Unknown";
                context.addError(new CompilationError(
                        "Unknown deep property path '" + fullPath + "' in class '"
                                + org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(cls)
                                + "': property '" + segment + "' not found in class '" + currentTypeName + "'",
                        fe._sourceInformation()));
                return;
            }
            // Navigate to the property's type for the next segment
            if (i < segments.length - 1 && prop._genericType() != null)
            {
                currentType = _GenericType.type(prop._genericType());
                if (currentType == null)
                {
                    return; // Can't resolve further
                }
            }
        }
    }

    private static String formatMultiplicity(meta.pure.metamodel.multiplicity.Multiplicity m)
    {
        long lower = _Multiplicity.lowerBound(m);
        Long upper = _Multiplicity.upperBound(m);
        if (upper == null)
        {
            return lower == 0 ? "[*]" : "[" + lower + "..*]";
        }
        return lower == upper ? "[" + lower + "]" : "[" + lower + ".." + upper + "]";
    }

    private static meta.pure.metamodel.function.property.Property findProperty(meta.pure.metamodel.type.Type type, String name)
    {
        if (type instanceof meta.pure.metamodel.SimplePropertyOwner po && po._properties() != null)
        {
            var prop = po._properties().detect(p -> name.equals(p._name()));
            if (prop != null)
            {
                return prop;
            }
        }
        // Check association properties
        if (type instanceof meta.pure.metamodel.type.Class cls && cls._propertiesFromAssociations() != null)
        {
            var assocProp = cls._propertiesFromAssociations().detect(p -> name.equals(p._name()));
            if (assocProp != null)
            {
                return assocProp;
            }
        }
        if (type._generalizations() != null)
        {
            for (var gen : type._generalizations())
            {
                if (gen._general() != null && _GenericType.type(gen._general()) != null)
                {
                    var inherited = findProperty(_GenericType.type(gen._general()), name);
                    if (inherited != null)
                    {
                        return inherited;
                    }
                }
            }
        }
        return null;
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
                        "inferred".equals(s._value()) || "excluded".equals(s._value()) || "pointer".equals(s._value())))
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
                if (gen._general() != null && _GenericType.type(gen._general()) != null)
                {
                    checkMissingProperties(_GenericType.type(gen._general()), providedNames, fe, context);
                }
            });
        }
    }

}
