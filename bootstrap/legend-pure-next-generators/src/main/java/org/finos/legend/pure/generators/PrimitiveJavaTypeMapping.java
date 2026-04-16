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

package org.finos.legend.pure.generators;

/**
 * Formal mapping from Pure primitive type names to Java types.
 *
 * <p>This class is the single source of truth for the Pure → Java type mapping.
 * It replaces the inline switch in {@code JavaGeneratorUtils.mapPrimitiveType()}
 * and the implicit conversions in {@code M3BootstrapReader}.</p>
 *
 * <h3>Runtime Value Representation Strategy</h3>
 * <ul>
 *   <li><b>Built-in primitives</b> ({@link #isBuiltInPrimitive}): Use raw Java types directly.
 *       The Java type alone is sufficient to recover the Pure type — no wrapper needed.</li>
 *   <li><b>User-defined primitive subtypes</b> (e.g., {@code Varchar(x:Integer[1]) extends String}):
 *       Must be wrapped in a {@code PrimitiveInstance} container that carries the
 *       {@code classifierGenericType}, since the raw Java type (String) would lose
 *       the subtype identity and any parameterization.</li>
 * </ul>
 *
 * <p>This class lives in the Java generators layer because Java type mapping
 * is a platform-specific concern, not a Pure specification concern.</p>
 */
public final class PrimitiveJavaTypeMapping
{
    private PrimitiveJavaTypeMapping()
    {
    }

    /**
     * Returns {@code true} if the given Pure type is a built-in primitive with
     * a unique, bijective Java type mapping. For these types, the raw Java value
     * alone is sufficient — no wrapper is needed at runtime.
     *
     * <p>Returns {@code false} for user-defined primitive subtypes, which require
     * a {@code PrimitiveInstance} container to preserve their type identity.</p>
     *
     * @param pureName the Pure type name
     * @return {@code true} if the type can be represented as a raw Java value
     */
    public static boolean isBuiltInPrimitive(String pureName)
    {
        return toJavaType(pureName) != null;
    }

    /**
     * Map a Pure primitive type name to its Java type name (boxed form).
     * Each built-in primitive maps to a distinct Java type for lossless round-tripping.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "String"} → {@code "String"}</li>
     *   <li>{@code "Boolean"} → {@code "Boolean"}</li>
     *   <li>{@code "Integer"} → {@code "Long"}</li>
     *   <li>{@code "Float"} → {@code "Double"}</li>
     *   <li>{@code "StrictDate"} → {@code "java.time.LocalDate"}</li>
     *   <li>{@code "DateTime"} → {@code "java.time.ZonedDateTime"}</li>
     *   <li>{@code "StrictTime"} → {@code "java.time.LocalTime"}</li>
     * </ul>
     *
     * @param pureName the Pure primitive type name
     * @return the Java type name, or {@code null} if not a known built-in primitive
     */
    public static String toJavaType(String pureName)
    {
        return switch (pureName)
        {
            case "String"     -> "String";
            case "Boolean"    -> "Boolean";
            case "Integer"    -> "Long";
            case "Float"      -> "Double";
            case "Decimal"    -> "java.math.BigDecimal";
            case "Date"       -> "java.time.temporal.Temporal";  // abstract temporal supertype
            case "DateTime"   -> "java.time.ZonedDateTime";
            case "StrictDate" -> "java.time.LocalDate";
            case "LatestDate" -> "java.time.temporal.Temporal";  // sentinel — may need dedicated type
            case "StrictTime" -> "java.time.LocalTime";
            case "Number"     -> "Number";
            case "Byte"       -> "Byte";
            case "Binary"     -> "byte[]";
            case "Any"        -> "Object";
            default           -> null;
        };
    }

    /**
     * Parse a raw string from the {@code :data} slot into the corresponding
     * Java object for the given Pure primitive type.
     *
     * <p>This formalizes the implicit conversions that were previously scattered
     * across {@code M3BootstrapReader} as {@code Long.parseLong()},
     * {@code "true".equals()}, etc.</p>
     *
     * @param pureName the Pure primitive type name
     * @param data     the raw string from the {@code :data} slot
     * @return the typed Java value
     */
    public static Object parseFromString(String pureName, String data)
    {
        return switch (pureName)
        {
            case "String"     -> data;
            case "Boolean"    -> Boolean.parseBoolean(data);
            case "Integer"    -> Long.parseLong(data);
            case "Float"      -> Double.parseDouble(data);
            case "Decimal"    -> new java.math.BigDecimal(data);
            case "DateTime"   -> java.time.ZonedDateTime.parse(data);
            case "StrictDate" -> java.time.LocalDate.parse(data);
            case "StrictTime" -> java.time.LocalTime.parse(data);
            case "Number"     -> Double.parseDouble(data);
            case "Byte"       -> Byte.parseByte(data);
            case "Date", "LatestDate" -> data;  // abstract/sentinel — kept as string
            case "Binary"     -> data;          // base64/hex — kept as string
            default           -> data;
        };
    }
}
