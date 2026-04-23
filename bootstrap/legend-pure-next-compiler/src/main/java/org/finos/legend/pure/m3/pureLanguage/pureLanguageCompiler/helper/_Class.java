package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.SimplePropertyOwner;
import meta.pure.metamodel.function.property.Property;
import meta.pure.metamodel.function.property.QualifiedProperty;
import meta.pure.metamodel.type.Class;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.ParametersBinding;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PlainParametersBinding;

/**
 * Helpers for {@link Class} operations such as property lookup
 * across the inheritance hierarchy.
 */
public final class _Class
{
    private _Class()
    {
    }

    /**
     * Build a {@link ParametersBinding} by matching a class's declared type and
     * multiplicity parameters against the concrete type and multiplicity arguments
     * from a {@link GenericType}.
     * <p>
     * For example, given {@code Class<T>} and {@code Class<CC_Address>}, this
     * produces the binding {@code T → CC_Address}.
     */
    public static ParametersBinding buildBindingsFromGenericType(Class ownerClass, GenericType receiverType)
    {
        ParametersBinding bindings = PlainParametersBinding.empty();
        if (ownerClass._typeParameters().notEmpty())
        {
            ownerClass._typeParameters().zip(_GenericType.typeArguments(receiverType)).forEach(pair ->
                    bindings.typeBindings()
                            .computeIfAbsent(pair.getOne()._name(), k -> Lists.mutable.empty())
                            .add(pair.getTwo()));
        }
        if (ownerClass._multiplicityParameters().notEmpty())
        {
            ownerClass._multiplicityParameters().zip(_GenericType.multiplicityArguments(receiverType)).forEach(pair ->
                    bindings.multiplicityBindings()
                            .computeIfAbsent(pair.getOne()._name(), k -> Lists.mutable.empty())
                            .add(pair.getTwo()));
        }
        return bindings;
    }

    /**
     * Find a property by name on a PropertyOwner, searching own properties,
     * association properties, then traversing up the class hierarchy.
     */
    public static Property findProperty(SimplePropertyOwner owner, String name)
    {
        // Direct properties
        if (owner._properties() != null)
        {
            Property match = owner._properties().detect(p -> name.equals(p._name()));
            if (match != null)
            {
                return match;
            }
        }
        // Association properties
        if (owner instanceof Class cls && cls._propertiesFromAssociations() != null)
        {
            Property match = cls._propertiesFromAssociations().detect(p -> name.equals(p._name()));
            if (match != null)
            {
                return match;
            }
        }
        // Inherited properties via generalizations
        if (owner instanceof Type type && type._generalizations() != null)
        {
            for (meta.pure.metamodel.relationship.Generalization gen : type._generalizations())
            {
                if (gen._general() != null && _GenericType.type(gen._general()) instanceof SimplePropertyOwner superOwner)
                {
                    Property match = findProperty(superOwner, name);
                    if (match != null)
                    {
                        return match;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Find all qualified properties by name on a Class, searching own qualified properties,
     * then traversing up the class hierarchy.
     */
    public static MutableList<QualifiedProperty> findQualifiedProperties(Class cls, String name)
    {
        MutableList<QualifiedProperty> results = Lists.mutable.empty();
        collectQualifiedProperties(cls, name, results);
        return results;
    }

    private static void collectQualifiedProperties(Class cls, String name, MutableList<QualifiedProperty> results)
    {
        // Direct qualified properties
        if (cls._qualifiedProperties() != null)
        {
            cls._qualifiedProperties().select(qp -> name.equals(qp._name()), results);
        }
        // Inherited qualified properties via generalizations
        if (cls._generalizations() != null)
        {
            for (meta.pure.metamodel.relationship.Generalization gen : cls._generalizations())
            {
                if (gen._general() != null && _GenericType.type(gen._general()) instanceof Class superClass)
                {
                    collectQualifiedProperties(superClass, name, results);
                }
            }
        }
    }
}
