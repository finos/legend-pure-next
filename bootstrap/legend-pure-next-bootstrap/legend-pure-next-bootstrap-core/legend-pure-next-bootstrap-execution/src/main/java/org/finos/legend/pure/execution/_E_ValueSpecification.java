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
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.AtomicValueImpl;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.CollectionImpl;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type;

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
    public static Object unwrap(Object p)
    {
        if (p == null)
        {
            return null;
        }
        if (!(p instanceof ValueSpecification vs))
        {
            return p;
        }
        if (vs instanceof AtomicValue av)
        {
            return av._value();
        }
        // Collections — unwrap one level: each child unwrapped once
        if (vs instanceof Collection col)
        {
            List<Object> results = new ArrayList<>();
            for (Object v : col._values())
            {
                if (v instanceof AtomicValue childAv)
                {
                    results.add(childAv._value());
                }
                else
                {
                    results.add(v);
                }
            }
            return results;
        }
        return vs;
    }

    /**
     * Ensure a value is a ValueSpecification.
     * - null → empty Collection
     * - List → Collection (each element wrapped recursively)
     * - raw Java value → AtomicValue
     */
    public static ValueSpecification wrap(Object value,
                                   meta.pure.metamodel.type.generics.GenericType genericType,
                                   meta.pure.metamodel.multiplicity.Multiplicity multiplicity,
                                   MetadataAccess resolver)
    {
        if (value == null)
        {
            return new meta.pure.metamodel.valuespecification.CollectionImpl(resolver)
                    ._values(org.eclipse.collections.api.factory.Lists.mutable.empty())
                    ._genericType(genericType != null ? genericType
                            : (meta.pure.metamodel.type.generics.GenericType) resolver.getElement(
                                    "meta::pure::metamodel::type::generics::optimization::GenericType_meta_pure_metamodel_type_Nil"))
                    ._multiplicity((meta.pure.metamodel.multiplicity.Multiplicity)
                            resolver.getElement("meta::pure::metamodel::multiplicity::PureZero"));
        }
        if (value instanceof List<?> list)
        {
            java.util.List<ValueSpecification> items = new java.util.ArrayList<>(list.size());
            for (Object item : list)
            {
                items.add(wrap(item, genericType, null, resolver));
            }
            return org.finos.legend.pure.execution.natives.collection.CollectionNatives.makeCollection(items, resolver);
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
        Object value = (vs instanceof AtomicValue av) ? av._value() : vs;
        // Collection — compute common type from actual elements
        if (vs instanceof Collection col)
        {
            if (col._values().isEmpty())
            {
                return resolver != null ? (Type) resolver.getElement("meta::pure::metamodel::type::Nil") : null;
            }
            org.eclipse.collections.api.list.MutableList<GenericType> elementTypes =
                    col._values().collect(ValueSpecification::_genericType).select(gt -> gt != null);
            if (elementTypes.notEmpty() && resolver != null)
            {
                return _GenericType.type(_GenericType.findCommonGenericType(elementTypes, resolver));
            }
            return col._genericType() != null ? _GenericType.type(col._genericType()) : null;
        }
        // DynamicInstance — use classifierGenericType
        if (value instanceof DynamicInstance di)
        {
            return _GenericType.type(di.getClassifierGenericType());
        }
        // TempCompilerPointer — `_classifierGenericType()` is guarded on
        // pointer impls and throws under strict mode (the slot isn't
        // populated on a pointer). Derive the type from the Java class name
        // instead: every pointer impl in {@code meta.pure.metamodel.pointer}
        // maps 1:1 to a Pure type at the same package path. The match-arm
        // dispatcher only needs the runtime type to walk generalizations,
        // and the pointer's typed interface chain already encodes the right
        // ancestry (e.g. PackageableFunctionPointer → PackageableFunction →
        // Function → Any, and also → PackageableElement).
        if (value instanceof meta.pure.metamodel.pointer.TempCompilerPointer && resolver != null)
        {
            String simpleName = value.getClass().getSimpleName();
            if (simpleName.endsWith("Impl"))
            {
                simpleName = simpleName.substring(0, simpleName.length() - 4);
            }
            Object pointerType = resolver.getElement("meta::pure::metamodel::pointer::" + simpleName);
            if (pointerType instanceof Type t)
            {
                return t;
            }
        }
        if (value instanceof meta.pure.metamodel.type.Any any)
        {
            GenericType cgt = any._classifierGenericType();
            if (cgt == null)
            {
                throw new RuntimeException("classifierGenericType is null for " + value.getClass().getName()
                        + (value instanceof meta.pure.metamodel.PackageableElement pe ? " name='" + pe._name() + "'" : "")
                        + " — this is a constructor bug.");
            }
            return _GenericType.type(cgt);
        }
        // Reconcile two views of the runtime type:
        //   - VS's declared genericType: from the AtomicValue / Collection /
        //     property accessor; carries any specialization the parser or
        //     compiler put there (e.g. `StrictDate` for `%2015-03-14`).
        //   - Runtime Java class: maps Java primitive boxes to Pure primitives
        //     (Long→Integer, String→String, etc.).
        // Prefer whichever is the subtype (more specific). When the runtime
        // Java class produces a type narrower than the VS's declared type
        // (e.g. `Number[1]` property holding an Integer; `Any[*]` property
        // holding a String), use the narrower runtime type. When the VS type
        // is already narrower (e.g. `StrictDate` for a date literal whose Java
        // representation is a plain String), keep the VS type.
        // Pinned by `meta::pure::functions::meta::tests::instanceOf::testInstanceOfFromAnyProperty`.
        Type vsType = vs != null && vs._genericType() != null ? _GenericType.type(vs._genericType()) : null;
        Type primitiveType = primitiveTypeForJavaValue(value, resolver);
        if (vsType != null && primitiveType != null && resolver != null)
        {
            if (_Type.subtypeOf(primitiveType, vsType, resolver))
            {
                return primitiveType;
            }
            return vsType;
        }
        if (primitiveType != null)
        {
            return primitiveType;
        }
        if (vsType != null)
        {
            return vsType;
        }
        throw new RuntimeException("Cannot determine type of value: " + value.getClass().getName());
    }

    private static Type primitiveTypeForJavaValue(Object value, MetadataAccess resolver)
    {
        if (resolver == null || value == null)
        {
            return null;
        }
        String pureTypePath = null;
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
        {
            pureTypePath = "meta::pure::metamodel::type::primitives::Integer";
        }
        else if (value instanceof Double || value instanceof Float)
        {
            pureTypePath = "meta::pure::metamodel::type::primitives::Float";
        }
        else if (value instanceof java.math.BigDecimal)
        {
            pureTypePath = "meta::pure::metamodel::type::primitives::Decimal";
        }
        else if (value instanceof Boolean)
        {
            pureTypePath = "meta::pure::metamodel::type::primitives::Boolean";
        }
        else if (value instanceof String)
        {
            pureTypePath = "meta::pure::metamodel::type::primitives::String";
        }
        if (pureTypePath == null)
        {
            return null;
        }
        Object element = resolver.getElement(pureTypePath);
        return element instanceof Type t ? t : null;
    }
}

