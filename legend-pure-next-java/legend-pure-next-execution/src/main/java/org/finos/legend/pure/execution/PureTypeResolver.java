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

import meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Recovers Pure type information ({@link GenericType}) from any Java runtime value.
 *
 * <p>This is the formal bridge between Java runtime values and Pure types,
 * enabling the execution layer to store unwrapped values while preserving
 * type information. It works for:</p>
 * <ul>
 *   <li><b>Raw Java primitives</b> (String, Long, Boolean, Double, etc.)
 *       — resolved via the bijective Java→Pure type mapping</li>
 *   <li><b>Metamodel instances</b> ({@code Any} subtypes)
 *       — read {@code _classifierGenericType()} directly</li>
 *   <li><b>DynamicInstance</b>
 *       — read {@code getClassifierGenericType()} directly</li>
 * </ul>
 */
public final class PureTypeResolver
{
    private PureTypeResolver()
    {
    }

    /**
     * Get the {@code classifierGenericType} for any runtime value.
     *
     * @param value    the runtime value (raw Java primitive, metamodel object, or DynamicInstance)
     * @param resolver metadata access for resolving GenericType instances by path
     * @return the classifierGenericType, or {@code null} if the type cannot be determined
     */
    public static GenericType getClassifierGenericType(Object value, MetadataAccess resolver)
    {
        if (value == null)
        {
            return null;
        }

        // DynamicInstance — carries its own classifierGenericType
        if (value instanceof DynamicInstance di)
        {
            return di.getClassifierGenericType();
        }

        // PrimitiveInstance — carries its own classifierGenericType for primitive subtypes
        if (value instanceof PrimitiveInstance pi)
        {
            return pi.getClassifierGenericType();
        }

        // Metamodel instances (Any subtypes) — read classifierGenericType directly
        if (value instanceof meta.pure.metamodel.type.Any any)
        {
            return any._classifierGenericType();
        }

        // Built-in Java primitive types — resolve to Pure GenericType via metadata
        if (resolver != null)
        {
            String pureName = getPureTypeName(value);
            if (pureName != null)
            {
                meta.pure.metamodel.type.Type primitiveType = (meta.pure.metamodel.type.Type) resolver.getElement(pureName);
                if (primitiveType != null)
                {
                    // For built-in types (e.g. String), instantiate an InferredGenericTypeImpl to wrap the type.
                    // This satisfies the GenericType interface without requiring a custom DynamicInstance wrapper.
                    return new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(resolver)
                            ._type(primitiveType);
                }
            }
        }

        return null;
    }

    /**
     * Map a Java runtime value to the Pure GenericType resource name.
     * Uses the bijective Java→Pure mapping defined in {@code PrimitiveJavaTypeMapping}.
     *
     * @param value the Java value
     * @return the GenericType resource name (e.g., "GenericType_String"), or null
     */
    private static String javaTypeToGenericTypeName(Object value)
    {
        return switch (value)
        {
            case String s                     -> "GenericType_String";
            case Boolean b                    -> "GenericType_Boolean";
            case Long l                       -> "GenericType_Integer";
            case Integer i                    -> "GenericType_Integer";   // widen to Long semantics
            case Double d                     -> "GenericType_Float";
            case java.math.BigDecimal bd      -> "GenericType_Decimal";
            case java.time.LocalDate ld       -> "GenericType_StrictDate";
            case java.time.ZonedDateTime zdt  -> "GenericType_DateTime";
            case java.time.LocalTime lt       -> "GenericType_StrictTime";
            case Byte b                       -> "GenericType_Byte";
            case Number n                     -> "GenericType_Number";    // fallback for Number subtypes
            default                           -> null;
        };
    }

    /**
     * Determine the Pure type name from a Java runtime value.
     *
     * @param value the Java value
     * @return the Pure type name (e.g., "String", "Integer"), or null
     */
    public static String getPureTypeName(Object value)
    {
        if (value == null) return null;
        if (value instanceof DynamicInstance di)
        {
            GenericType cgt = di.getClassifierGenericType();
            if (cgt != null)
            {
                meta.pure.metamodel.type.Type type = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(cgt);
                if (type instanceof meta.pure.metamodel.PackageableElement pe) return pe._name();
            }
            return null;
        }
        if (value instanceof PrimitiveInstance pi)
        {
            GenericType cgt = pi.getClassifierGenericType();
            if (cgt != null)
            {
                meta.pure.metamodel.type.Type type = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(cgt);
                if (type instanceof meta.pure.metamodel.PackageableElement pe) return pe._name();
            }
            return null;
        }
        if (value instanceof meta.pure.metamodel.type.Any any)
        {
            GenericType cgt = any._classifierGenericType();
            if (cgt != null)
            {
                meta.pure.metamodel.type.Type type = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(cgt);
                if (type instanceof meta.pure.metamodel.PackageableElement pe) return pe._name();
            }
            return null;
        }
        return switch (value)
        {
            case String s                     -> "String";
            case Boolean b                    -> "Boolean";
            case Long l                       -> "Integer";
            case Integer i                    -> "Integer";
            case Double d                     -> "Float";
            case java.math.BigDecimal bd      -> "Decimal";
            case java.time.LocalDate ld       -> "StrictDate";
            case java.time.ZonedDateTime zdt  -> "DateTime";
            case java.time.LocalTime lt       -> "StrictTime";
            case Byte b                       -> "Byte";
            case Number n                     -> "Number";
            default                           -> null;
        };
    }
}
