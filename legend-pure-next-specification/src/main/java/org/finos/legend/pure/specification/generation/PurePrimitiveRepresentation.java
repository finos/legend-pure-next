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

package org.finos.legend.pure.specification.generation;

/**
 * Formal mapping from Pure primitive type names to their Pure source representations.
 *
 * <p>This class is the single source of truth for how primitive values stored
 * as raw strings in the {@code :data} slot are rendered as Pure literals.
 * It also provides the canonical {@code GenericType_X} resource name for each primitive.</p>
 */
public final class PurePrimitiveRepresentation
{
    private PurePrimitiveRepresentation()
    {
    }

    /**
     * Returns {@code true} if the given name is a known Pure primitive type.
     */
    public static boolean isPrimitiveType(String name)
    {
        return switch (name)
        {
            case "String", "Boolean", "Integer", "Float", "Decimal",
                 "Date", "DateTime", "StrictDate", "LatestDate", "StrictTime",
                 "Number", "Byte", "Binary" -> true;
            default -> false;
        };
    }

    /**
     * Returns the canonical GenericType resource name for a Pure primitive type.
     * For example, {@code "String"} → {@code "GenericType_String"}.
     *
     * @param pureName the Pure primitive type name
     * @return the GenericType resource name, or {@code null} if not a known primitive
     */
    public static String toGenericTypeName(String pureName)
    {
        if (isPrimitiveType(pureName))
        {
            return "GenericType_" + pureName;
        }
        return null;
    }

    /**
     * Render a raw data string as a Pure source literal for the given primitive type.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code ("String", "hello")} → {@code "\"hello\""}</li>
     *   <li>{@code ("Boolean", "true")} → {@code "true"}</li>
     *   <li>{@code ("Integer", "42")} → {@code "42"}</li>
     *   <li>{@code ("Float", "3.14")} → {@code "3.14f"}</li>
     *   <li>{@code ("Decimal", "3.14")} → {@code "3.14D"}</li>
     *   <li>{@code ("Date", "2024-01-15")} → {@code "%2024-01-15"}</li>
     * </ul>
     *
     * @param pureName the Pure primitive type name
     * @param data     the raw string from the {@code :data} slot
     * @return the Pure source literal representation
     */
    public static String toPureLiteral(String pureName, String data)
    {
        return switch (pureName)
        {
            case "String"     -> "'" + data + "'";
            case "Boolean"    -> data;                  // true / false
            case "Integer"    -> data;                  // 42
            case "Float"      -> data + "f";            // 3.14f
            case "Decimal"    -> data + "D";            // 3.14D
            case "Date"       -> "%" + data;            // %2024-01-15
            case "DateTime"   -> "%" + data;            // %2024-01-15T10:30:00
            case "StrictDate" -> "%" + data;            // %2024-01-15
            case "LatestDate" -> "%latest";             // %latest
            case "StrictTime" -> "%" + data;            // %10:30:00
            case "Number"     -> data;                  // abstract numeric
            case "Byte"       -> data;                  // raw byte value
            case "Binary"     -> data;                  // base64 or hex
            default           -> data;
        };
    }

    /**
     * Extract the Pure primitive type name from a GenericType resource name.
     * For example, {@code "GenericType_String"} → {@code "String"}.
     *
     * @param genericTypeName the GenericType resource name (e.g., {@code "GenericType_String"})
     * @return the Pure primitive type name, or {@code null} if not a primitive GenericType
     */
    public static String fromGenericTypeName(String genericTypeName)
    {
        if (genericTypeName != null && genericTypeName.startsWith("GenericType_"))
        {
            String candidate = genericTypeName.substring("GenericType_".length());
            if (isPrimitiveType(candidate))
            {
                return candidate;
            }
        }
        return null;
    }
}
