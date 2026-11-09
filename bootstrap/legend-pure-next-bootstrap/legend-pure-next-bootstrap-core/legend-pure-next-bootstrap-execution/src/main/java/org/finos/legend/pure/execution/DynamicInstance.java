// Copyright 2024 Goldman Sachs
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

import platform.pure.metamodel.type.generics.GenericTypeValue;

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
 * <p>All property values are stored as <b>raw unwrapped</b> Java objects
 * (e.g., {@code String}, {@code Long}, {@code DynamicInstance}, {@code List}).
 * The {@link #put} method automatically unwraps {@code ValueSpecification}
 * wrappers so that consumers reading via {@link #get} receive raw values.
 * Callers that need a {@code ValueSpecification} should re-wrap on read.</p>
 */
public class DynamicInstance
{
    private GenericTypeValue classifierGenericType;

    private static final ThreadLocal<Set<DynamicInstance>> PRINTING = ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final AtomicLong ID_COUNTER = new AtomicLong(0);

    private final String classPath;
    private final Map<String, Object> values;
    private final String id;

    public DynamicInstance(String classPath)
    {
        this.classPath = classPath;
        // LinkedHashMap preserves declaration order so printing / inspection
        // tools (PureValuePrinter, Truffle's IDE Compile tab, etc.) see
        // properties in the same order the class declared them. HashMap's
        // arbitrary iteration order made the rendered output non-deterministic
        // and divergent from Truffle's slot-indexed walk.
        this.values = new java.util.LinkedHashMap<>();
        this.id = "Anonymous_" + ID_COUNTER.incrementAndGet();
    }

    public GenericTypeValue getClassifierGenericType()
    {
        return classifierGenericType;
    }

    public void setClassifierGenericType(GenericTypeValue classifierGenericType)
    {
        this.classifierGenericType = classifierGenericType;
    }

    public String getClassPath()
    {
        return classPath;
    }

    /**
     * Store a property value, automatically unwrapping any ValueSpecification wrapper.
     * Collections are unwrapped to a {@code List<Object>} of raw values.
     * Scalars wrapped in AtomicValue are unwrapped to the raw Java value.
     */
    public void put(String propertyName, Object value)
    {
        if (own != null)
        {
            own.remove(propertyName);
        }
        values.put(propertyName, _E_ValueSpecification.unwrap(value));
    }

    // -- a property's own collection (pure/specification/IN_PLACE_UPDATE_ANALYSIS.md, Fields) --------------------------------
    // A collection stored updatable in place is kept as is, referenced by nothing but this instance, until any access other than
    // a read the compiler proved holds the only reference to it: then it becomes this instance's raw value, as any
    // other store would have made it.

    private static final Object OWN = new Object();
    private Map<String, platform.pure.metamodel.valuespecification.ValueSpecification> own;

    /** Store a collection the compiler proved updatable in place, one this interpreter built: kept as this instance's own. */
    public void putUpdatableInPlace(String propertyName, platform.pure.metamodel.valuespecification.ValueSpecification collection)
    {
        if (own == null)
        {
            own = new java.util.HashMap<>();
        }
        own.put(propertyName, collection);
        values.put(propertyName, OWN);
    }

    /** The collection this instance holds as its own for a property, for a read proved to hold the only reference to it. */
    public platform.pure.metamodel.valuespecification.ValueSpecification updatableInPlaceCollection(String propertyName)
    {
        return own == null ? null : own.get(propertyName);
    }

    private Object materialized(String propertyName)
    {
        Object v = values.get(propertyName);
        if (v == OWN)
        {
            v = _E_ValueSpecification.unwrap(own.remove(propertyName));
            values.put(propertyName, v);
        }
        return v;
    }

    /**
     * Retrieve a raw property value. The returned value is always unwrapped
     * (String, Long, DynamicInstance, List, etc. — never a ValueSpecification).
     */
    public Object get(String propertyName)
    {
        return materialized(propertyName);
    }

    /** The default values computed for this instance's unset properties, kept once read (a copy computes its own). */
    private final Map<String, Object> defaults = new java.util.HashMap<>();

    /** The default value computed for an unset property, once read; null before. */
    /** Forget the defaults kept: this instance is being changed by a copy expression, and a copy computes its own. */
    public void forgetKeptDefaults()
    {
        defaults.clear();
    }

    public Object keptDefault(String propertyName)
    {
        return defaults.get(propertyName);
    }

    /** Keep the default value computed for an unset property: every read is then the same (is.pure). */
    public void keepDefault(String propertyName, Object value)
    {
        defaults.put(propertyName, value);
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
        if (own != null)
        {
            for (String p : new java.util.ArrayList<>(own.keySet()))
            {
                materialized(p);
            }
        }
        return values;
    }

    @Override
    public String toString()
    {
        return id;
    }
}
