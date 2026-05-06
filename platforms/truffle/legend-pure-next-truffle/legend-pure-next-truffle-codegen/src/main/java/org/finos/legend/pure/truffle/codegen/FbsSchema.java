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

package org.finos.legend.pure.truffle.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses m3.fbs to extract table definitions and union type mappings.
 * Used by PdbJavaGenerator to generate FlatBuffer wrappers.
 */
public final class FbsSchema
{
    /** Union name → ordered list of member Def names (1-based index = discriminator) */
    private final Map<String, List<String>> unions = new HashMap<>();

    /** Table name → ordered list of fields */
    private final Map<String, List<FbsField>> tables = new HashMap<>();

    public static FbsSchema parse(Path fbsPath) throws IOException
    {
        FbsSchema schema = new FbsSchema();
        String content = Files.readString(fbsPath);

        // Parse unions: union FooUnion { DefA, DefB, DefC }
        Pattern unionPattern = Pattern.compile("union\\s+(\\w+)\\s*\\{([^}]+)\\}");
        Matcher um = unionPattern.matcher(content);
        while (um.find())
        {
            String name = um.group(1);
            String[] members = um.group(2).split(",");
            List<String> memberList = new ArrayList<>();
            for (String m : members)
            {
                memberList.add(m.trim());
            }
            schema.unions.put(name, memberList);
        }

        // Parse tables: table FooDef { field1: Type; field2: [Type]; ... }
        Pattern tablePattern = Pattern.compile("table\\s+(\\w+)\\s*\\{([^}]+)\\}");
        Matcher tm = tablePattern.matcher(content);
        while (tm.find())
        {
            String name = tm.group(1);
            String body = tm.group(2);
            List<FbsField> fields = new ArrayList<>();
            for (String line : body.split(";"))
            {
                line = line.trim();
                if (line.isEmpty())
                {
                    continue;
                }
                // Field syntax: name: Type [(attr...)] [= default]
                // Split on the FIRST colon only — the type portion may
                // itself contain ":" (in attribute clauses like "(id: N)"
                // emitted by RdfFbsSchemaGenerator for wire-format stability).
                int colon = line.indexOf(':');
                if (colon < 0)
                {
                    continue;
                }
                String fieldName = line.substring(0, colon).trim();
                String rest = line.substring(colon + 1).trim();
                // Strip "(...)" attribute clause and "=..." default before
                // reading the type — neither affects the wire-level shape.
                int paren = rest.indexOf('(');
                if (paren >= 0) { rest = rest.substring(0, paren).trim(); }
                int eq = rest.indexOf('=');
                if (eq >= 0) { rest = rest.substring(0, eq).trim(); }
                String fieldType = rest;
                boolean isVector = fieldType.startsWith("[") && fieldType.endsWith("]");
                if (isVector)
                {
                    fieldType = fieldType.substring(1, fieldType.length() - 1).trim();
                }
                boolean isUnion = schema.unions.containsKey(fieldType);
                fields.add(new FbsField(fieldName, fieldType, isVector, isUnion));
            }
            schema.tables.put(name, fields);
        }

        return schema;
    }

    public List<String> getUnionMembers(String unionName)
    {
        return unions.get(unionName);
    }

    public List<FbsField> getTableFields(String tableName)
    {
        return tables.get(tableName);
    }

    public boolean hasTable(String tableName)
    {
        return tables.containsKey(tableName);
    }

    /**
     * Convert a FBS snake_case field name to Java camelCase method name.
     * e.g. "expression_sequence" → "expressionSequence"
     */
    private static final java.util.Set<String> JAVA_KEYWORDS = java.util.Set.of(
            "package", "class", "return", "default", "new", "import", "static",
            "public", "private", "protected", "abstract", "final", "native",
            "boolean", "int", "long", "double", "float", "char", "byte", "short",
            // FlatBuffer schema keywords (escaped with trailing _ in generated Java)
            "type", "table", "struct", "enum", "union", "namespace", "root_type",
            "include", "attribute", "rpc_service", "file_identifier", "file_extension");

    /**
     * Convert a FBS snake_case field name to Java camelCase method name.
     * FlatBuffers appends '_' to Java keywords (e.g. "package" → "package_").
     */
    public static String snakeToCamel(String snake)
    {
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = false;
        for (char c : snake.toCharArray())
        {
            if (c == '_')
            {
                capitalizeNext = true;
            }
            else if (capitalizeNext)
            {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            }
            else
            {
                sb.append(c);
            }
        }
        String result = sb.toString();
        // FlatBuffers escapes Java keywords with trailing underscore
        if (JAVA_KEYWORDS.contains(result))
        {
            return result + "_";
        }
        return result;
    }

    /**
     * Get the Pure property name from a FBS field name.
     * FBS uses snake_case, Pure uses camelCase.
     */
    public static String fbsFieldToPureProperty(String fbsField)
    {
        return snakeToCamel(fbsField);
    }

    /**
     * Derive the Def class simple name from a Pure class name.
     */
    public String findDefTableName(String pureClassName)
    {
        for (String candidate : List.of(
                pureClassName + "Def",
                "UserDefined" + pureClassName + "Def"))
        {
            if (tables.containsKey(candidate))
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Derive the Pure class name from a Def table name.
     * e.g. "UserDefinedFunctionDef" → "UserDefinedFunction",
     *      "ArrowInvocationDef" → "ArrowInvocation"
     */
    public static String defToPureClassName(String defName)
    {
        if (defName.endsWith("Def"))
        {
            return defName.substring(0, defName.length() - 3);
        }
        return defName;
    }

    /**
     * Check if a Def name is a special reference type (not a real class).
     */
    public static boolean isSpecialRef(String defName)
    {
        return "AncestorRef".equals(defName) || "PointerRef".equals(defName);
    }

    /**
     * Check if a Def name is a primitive value type.
     */
    public static boolean isPrimitiveValueDef(String defName)
    {
        return "IntegerValueDef".equals(defName) || "FloatValueDef".equals(defName)
                || "BooleanValueDef".equals(defName) || "StringValueDef".equals(defName)
                || "DecimalValueDef".equals(defName);
    }

    @Override
    public String toString()
    {
        return "FbsSchema[" + unions.size() + " unions, " + tables.size() + " tables]";
    }

    public record FbsField(String name, String type, boolean isVector, boolean isUnion)
    {
    }
}
