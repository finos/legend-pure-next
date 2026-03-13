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

import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.set.MutableSet;

/**
 * Java-specific utility methods for M3 model generators.
 *
 * <p>Provides Java type mapping, package conversion, and keyword escaping.
 * Non-Java-specific helpers (stereotypes, property hierarchy, FBS naming)
 * live in {@code org.finos.legend.pure.specification.generation.model.ModelUtils}.</p>
 */
public final class JavaGeneratorUtils
{
    // Java reserved keywords that cannot be used as identifiers
    static final MutableSet<String> JAVA_KEYWORDS = Sets.mutable.with(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "true", "false", "null", "_");

    private JavaGeneratorUtils()
    {
    }

    // =========================================================================
    // Type Mapping
    // =========================================================================

    /**
     * Map an M3 type name to a Java type, wrapping in MutableList if isMany.
     */
    public static String mapToJavaType(String m3Type, boolean isMany)
    {
        String baseType = mapPrimitiveType(m3Type);
        return isMany ? "MutableList<" + boxType(baseType) + ">" : baseType;
    }

    /**
     * Map an M3 primitive type name to the corresponding Java type.
     */
    public static String mapPrimitiveType(String m3Type)
    {
        return switch (m3Type)
        {
            case null -> "Object";
            case "String" -> "String";
            case "Boolean" -> "Boolean";
            case "Integer" -> "Long";
            case "Float" -> "Double";
            case "Decimal" -> "java.math.BigDecimal";
            case "Date", "DateTime", "StrictDate" -> "java.time.temporal.Temporal";
            case "Number" -> "Number";
            case "Byte" -> "Byte";
            case "Any" -> "Object";
            default -> m3Type;
        };
    }

    /**
     * Box a Java primitive type name.
     */
    public static String boxType(String type)
    {
        return switch (type)
        {
            case "boolean" -> "Boolean";
            case "long" -> "Long";
            case "double" -> "Double";
            case "byte" -> "Byte";
            default -> type;
        };
    }

    /**
     * Convert Pure package path (meta::pure::metamodel::type) to Java package.
     * Escapes Java reserved keywords by appending an underscore suffix.
     */
    public static String toJavaPackage(String purePackagePath, String outputPackage)
    {
        if (purePackagePath == null || purePackagePath.isEmpty())
        {
            return outputPackage;
        }
        String[] segments = purePackagePath.split("::");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < segments.length; i++)
        {
            if (i > 0)
            {
                result.append('.');
            }
            String segment = segments[i];
            result.append(JAVA_KEYWORDS.contains(segment) ? segment + '_' : segment);
        }
        return result.toString();
    }

    /**
     * Convert an FBS snake_case field name to the camelCase Java accessor name
     * that flatc generates. Trailing underscores are preserved.
     */
    public static String toJavaAccessorName(String fbsFieldName)
    {
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = false;
        for (int i = 0; i < fbsFieldName.length(); i++)
        {
            char c = fbsFieldName.charAt(i);
            if (c == '_')
            {
                if (i == fbsFieldName.length() - 1)
                {
                    sb.append('_');
                }
                else
                {
                    capitalizeNext = true;
                }
            }
            else
            {
                sb.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            }
        }
        return sb.toString();
    }

    /**
     * Escape Java reserved keywords when used as field names.
     */
    public static String escapeJavaKeyword(String name)
    {
        return JAVA_KEYWORDS.contains(name) ? name + "_" : name;
    }
}
