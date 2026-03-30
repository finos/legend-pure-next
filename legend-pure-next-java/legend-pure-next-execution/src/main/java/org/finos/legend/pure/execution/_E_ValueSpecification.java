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

import meta.pure.metamodel.multiplicity.PackageableMultiplicity;
import meta.pure.metamodel.type.Any;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.AtomicValueImpl;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.CollectionImpl;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for querying Pure types and multiplicities from {@link ValueSpecification}
 * instances, without relying on Java {@code instanceof} checks on unwrapped values.
 */
public class _E_ValueSpecification
{
    private _E_ValueSpecification()
    {
    }

    // =========================================================================
    // Multiplicity checks
    // =========================================================================

    /**
     * Ensure a ValueSpecification is a Collection.
     * If it already is, return it as-is. Otherwise wrap it in a single-element Collection.
     */
    public static Collection toCollection(ValueSpecification vs, MetadataAccess resolver)
    {
        if (vs instanceof Collection col)
        {
            return col;
        }
        else if (vs instanceof AtomicValue)
        {
            Object value = ((AtomicValue) vs)._value();
            return value == null ?
                    new CollectionImpl(resolver)
                            ._values(Lists.mutable.empty())
                            ._genericType(vs._genericType())
                            ._multiplicity((PackageableMultiplicity) resolver.getElement("meta::pure::metamodel::multiplicity::PureZero"))
                    :
                    new CollectionImpl(resolver)
                            ._values(Lists.mutable.with(vs))
                            ._genericType(vs._genericType())
                            ._multiplicity((PackageableMultiplicity) resolver.getElement("meta::pure::metamodel::multiplicity::PureOne"));
        }
        else
        {
            return new CollectionImpl(resolver)
                    ._values(Lists.mutable.with(vs))
                    ._genericType(vs._genericType())
                    ._multiplicity((PackageableMultiplicity) resolver.getElement("meta::pure::metamodel::multiplicity::PureOne"));
        }
    }

    /**
     * Extract the raw Java value from a ValueSpecification.
     * Returns null for null inputs or GenericTypeAndMultiplicityHolder values.
     */
    public static Object unwrap(ValueSpecification vs)
    {
        if (vs == null)
        {
            return null;
        }
        if (vs instanceof AtomicValue av)
        {
            return av._value();
        }
        // Collections — unwrap to a list of values
        if (vs instanceof Collection col)
        {
            List<Object> results = new ArrayList<>();
            for (ValueSpecification v : col._values())
            {
                results.add(unwrap(v));
            }
            return results;
        }
        return vs;
    }

    /**
     * Wrap a single raw Java scalar value in an AtomicValueImpl with the given type info.
     * Do NOT pass a List here — build a CollectionImpl directly instead.
     */
    public static ValueSpecification wrap(Object value,
                                   meta.pure.metamodel.type.generics.GenericType genericType,
                                   meta.pure.metamodel.multiplicity.Multiplicity multiplicity,
                                   MetadataAccess resolver)
    {
        if (value instanceof ValueSpecification vs)
        {
            return vs;
        }
        if (value instanceof List<?>)
        {
            throw new IllegalArgumentException(
                "wrap() does not accept raw List values — use makeCollection() or CollectionImpl directly "
                + "to preserve per-element ValueSpecification types.");
        }
        return new AtomicValueImpl(resolver)
                ._value(value)
                ._genericType(genericType)
                ._multiplicity(multiplicity);
    }

    /**
     * Determine the Type of a runtime value.
     * Uses classifierGenericType for metamodel elements and DynamicInstances,
     * and the ValueSpecification's genericType for Java primitives.
     * Throws if the type cannot be determined.
     */
    public static Type getValueOriginalType(ValueSpecification vs)
    {
        return getValueOriginalType(vs, null);
    }

    /**
     * Determine the Type of a runtime value, with optional resolver for
     * self-referential classifierGenericType fallback.
     */
    public static Type getValueOriginalType(ValueSpecification vs, MetadataAccess resolver)
    {
        Object value = unwrap(vs);
        // DynamicInstance — use classifierGenericType
        if (value instanceof DynamicInstance di)
        {
            return _GenericType.type(di.getClassifierGenericType());
        }
        // All metamodel elements implement Any which has _classifierGenericType()
        if (value instanceof meta.pure.metamodel.type.Any any)
        {
            GenericType cgt = any._classifierGenericType();
            if (cgt == null)
            {
                throw new RuntimeException("classifierGenericType is null for " + value.getClass().getName()
                        + (value instanceof meta.pure.metamodel.PackageableElement pe ? " name='" + pe._name() + "'" : ""));
            }
            // FlatBuffer GenericTypeValue wrappers return 'this' from _classifierGenericType()
            // when the classifier pointer is unset (uType==0 self-referential).
            // In that case cgt._type() returns the *inner* wrapped type (e.g., Number)
            // rather than the meta-class (UserDefinedGenericType). Detect and resolve properly.
            // IMPORTANT: Do NOT use vs._genericType() here — that gives the declared static type
            // (e.g., GenericType) which is too broad. We need the actual runtime type
            // (e.g., UserDefinedGenericType) resolved via the Java interface name.
            if (cgt == value
                    || (cgt instanceof meta.pure.metamodel.type.generics.GenericTypeValue cgtv
                            && cgtv._type() == value))
            {
                // Resolve the M3 type by Java interface name → Pure path
                if (resolver != null)
                {
                    return resolveTypeByJavaInterfaceName(value.getClass(), resolver);
                }
                return null;
            }
            return _GenericType.type(cgt);
        }
        // Java primitives (Long, Double, String, Boolean) — use the VS genericType
        if (vs != null && vs._genericType() != null)
        {
            return _GenericType.type(vs._genericType());
        }
        throw new RuntimeException("Cannot determine type of value: " + value.getClass().getName());
    }

    /**
     * Walk the value's Java interface hierarchy and find the first interface
     * whose canonical name maps to a known Pure M3 path via the resolver.
     * Java package {@code meta.pure.metamodel.X.Y} → Pure path
     * {@code meta::pure::metamodel::X::Y}.
     */
    private static Type resolveTypeByJavaInterfaceName(Class<?> javaClass, MetadataAccess resolver)
    {
        // Check direct interfaces of the concrete class and its supertypes
        for (Class<?> cls = javaClass; cls != null; cls = cls.getSuperclass())
        {
            for (Class<?> iface : cls.getInterfaces())
            {
                String javaName = iface.getCanonicalName();
                if (javaName != null && javaName.startsWith("meta.pure.metamodel"))
                {
                    // Java package uses single '.' so replace all '.' with '::'
                    // e.g. "meta.pure.metamodel.type.generics.UserDefinedGenericType"
                    //    → "meta::pure::metamodel::type::generics::UserDefinedGenericType"
                    String purePathFixed = javaName.replace(".", "::");
                    meta.pure.metamodel.PackageableElement element = resolver.getElement(purePathFixed);
                    if (element instanceof Type t)
                    {
                        return t;
                    }
                }
            }
        }
        return null;
    }
}
