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
 * Formal mapping from Pure primitive type names to FlatBuffers schema types.
 *
 * <p>This class is the single source of truth for the Pure → FBS type mapping.
 * It replaces the inline switch in {@code RdfFbsSchemaGenerator.mapToFbsType()}.</p>
 */
public final class PrimitiveFbsTypeMapping
{
    private PrimitiveFbsTypeMapping()
    {
    }

    /**
     * Map a Pure primitive type name to its FlatBuffers schema type.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "String"} → {@code "string"}</li>
     *   <li>{@code "Boolean"} → {@code "bool"}</li>
     *   <li>{@code "Integer"} → {@code "long"}</li>
     *   <li>{@code "Float"} → {@code "double"}</li>
     * </ul>
     *
     * @param pureName the Pure primitive type name
     * @return the FBS scalar type, or {@code null} if not a known primitive
     */
    public static String toFbsType(String pureName)
    {
        return switch (pureName)
        {
            case "String"     -> "string";
            case "Boolean"    -> "bool";
            case "Integer"    -> "long";
            case "Float"      -> "double";
            case "Decimal"    -> "string";    // arbitrary precision — stored as string
            case "Date"       -> "string";    // ISO-8601 string
            case "DateTime"   -> "string";    // ISO-8601 string
            case "StrictDate" -> "string";    // ISO-8601 string
            case "LatestDate" -> "string";    // sentinel value
            case "StrictTime" -> "string";    // ISO-8601 time string
            case "Number"     -> "double";    // abstract numeric supertype
            case "Byte"       -> "ubyte";
            case "Binary"     -> "string";    // base64 or hex encoded
            case "Any"        -> "string";    // fallback for unresolved types
            default           -> null;
        };
    }

    /**
     * Returns {@code true} if the given Pure type name maps to a FBS scalar type
     * (as opposed to a table/struct reference).
     */
    public static boolean isFbsScalar(String pureName)
    {
        return toFbsType(pureName) != null;
    }
}
