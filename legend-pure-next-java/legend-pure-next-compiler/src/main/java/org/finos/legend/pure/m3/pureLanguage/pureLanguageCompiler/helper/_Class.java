package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.SimplePropertyOwner;
import meta.pure.metamodel.function.property.Property;
import meta.pure.metamodel.function.property.QualifiedProperty;
import meta.pure.metamodel.type.Class;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.impl.factory.Sets;

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
        ParametersBinding bindings = new ParametersBinding();
        if (ownerClass._typeParameters().notEmpty())
        {
            for (int i = 0; i < ownerClass._typeParameters().size(); i++)
            {
                String paramName = ownerClass._typeParameters().get(i)._name();
                bindings.typeBindings()
                        .computeIfAbsent(paramName, k -> Sets.mutable.empty())
                        .add(receiverType._typeArguments().get(i));
            }
        }
        if (ownerClass._multiplicityParameters().notEmpty())
        {
            for (int i = 0; i < ownerClass._multiplicityParameters().size(); i++)
            {
                String paramName = ownerClass._multiplicityParameters().get(i)._name();
                bindings.multiplicityBindings()
                        .computeIfAbsent(paramName, k -> Sets.mutable.empty())
                        .add(receiverType._multiplicityArguments().get(i));
            }
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
                if (gen._general() != null && gen._general()._rawType() instanceof SimplePropertyOwner superOwner)
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
     * Find a qualified property by name on a Class, searching own qualified properties,
     * then traversing up the class hierarchy.
     */
    public static QualifiedProperty findQualifiedProperty(Class cls, String name)
    {
        // Direct qualified properties
        if (cls._qualifiedProperties() != null)
        {
            QualifiedProperty match = cls._qualifiedProperties().detect(qp -> name.equals(qp._name()));
            if (match != null)
            {
                return match;
            }
        }
        // Inherited qualified properties via generalizations
        if (cls._generalizations() != null)
        {
            for (meta.pure.metamodel.relationship.Generalization gen : cls._generalizations())
            {
                if (gen._general() != null && gen._general()._rawType() instanceof Class superClass)
                {
                    QualifiedProperty match = findQualifiedProperty(superClass, name);
                    if (match != null)
                    {
                        return match;
                    }
                }
            }
        }
        return null;
    }
}
