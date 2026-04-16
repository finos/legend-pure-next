package org.finos.legend.pure.execution;

import meta.pure.metamodel.type.generics.GenericType;

/**
 * A container for user-defined primitive subtypes (e.g. Varchar(x:Integer[1]) extends String).
 * Since raw Java types (e.g., String) cannot hold subtype identity or parameterizations,
 * this wrapper carries both the raw primitive value and its specific classifierGenericType.
 */
public class PrimitiveInstance
{
    private final Object value;
    private final GenericType classifierGenericType;

    public PrimitiveInstance(Object value, GenericType classifierGenericType)
    {
        if (value == null)
        {
            throw new IllegalArgumentException("PrimitiveInstance value cannot be null");
        }
        if (classifierGenericType == null)
        {
            throw new IllegalArgumentException("PrimitiveInstance classifierGenericType cannot be null");
        }
        this.value = value;
        this.classifierGenericType = classifierGenericType;
    }

    public Object getValue()
    {
        return value;
    }

    public GenericType getClassifierGenericType()
    {
        return classifierGenericType;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PrimitiveInstance that = (PrimitiveInstance) o;
        return value.equals(that.value);
    }

    @Override
    public int hashCode()
    {
        return value.hashCode();
    }

    @Override
    public String toString()
    {
        return String.valueOf(value);
    }
}
