// Copyright 2026 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.execution;

import platform.pure.metamodel.type.generics.GenericType;

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
