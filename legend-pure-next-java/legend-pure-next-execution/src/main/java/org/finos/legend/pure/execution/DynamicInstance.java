// Copyright 2024 Goldman Sachs
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

import meta.pure.metamodel.valuespecification.ValueSpecification;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A dynamic instance of a Pure Class created by the {@code new} function.
 *
 * <p>Since user-defined Pure classes are not generated as Java classes,
 * this serves as a generic runtime representation that holds property
 * values in a map and supports property access by name.</p>
 *
 * <p>All property values are stored as {@link ValueSpecification} instances,
 * preserving Pure type metadata throughout evaluation. Multi-valued properties
 * are stored as {@code CollectionImpl}; single-valued as {@code AtomicValueImpl}.</p>
 */
public class DynamicInstance
{
    private meta.pure.metamodel.type.generics.GenericType classifierGenericType;

    private static final ThreadLocal<Set<DynamicInstance>> PRINTING = ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final AtomicLong ID_COUNTER = new AtomicLong(0);

    private final String classPath;
    private final Map<String, Object> values;
    private final String id;

    public DynamicInstance(String classPath)
    {
        this.classPath = classPath;
        this.values = new HashMap<>();
        this.id = "Anonymous_" + ID_COUNTER.incrementAndGet();
    }

    public meta.pure.metamodel.type.generics.GenericType getClassifierGenericType()
    {
        return classifierGenericType;
    }

    public void setClassifierGenericType(meta.pure.metamodel.type.generics.GenericType classifierGenericType)
    {
        this.classifierGenericType = classifierGenericType;
    }

    public String getClassPath()
    {
        return classPath;
    }

    public void put(String propertyName, Object value)
    {
        values.put(propertyName, value);
    }

    public Object get(String propertyName)
    {
        return values.get(propertyName);
    }

    public boolean hasProperty(String propertyName)
    {
        return values.containsKey(propertyName);
    }

    public String getId()
    {
        return id;
    }

    public Map<String, Object> getValues()
    {
        return values;
    }

    @Override
    public String toString()
    {
        return id;
    }
}
