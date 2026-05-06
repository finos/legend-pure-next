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

package org.finos.legend.pure.specification.generation.fbs;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal {@code .fbs} parser. Only extracts the constructs downstream codegen
 * needs to stay aligned with the wire format: {@code union} declarations and
 * {@code table} fields whose type references a union (directly or as a vector).
 *
 * <p>Anything else — enums, namespace, root_type, attributes, primitive fields,
 * non-union table fields — is intentionally ignored.</p>
 */
public final class FbsSchemaParser
{
    // union Name { Member1, Member2, ... }
    private static final Pattern UNION_PATTERN = Pattern.compile(
            "union\\s+(\\w+)\\s*\\{\\s*([^}]*)\\}",
            Pattern.DOTALL);

    // table Name { ... }   — body captured separately to handle nested braces simply
    private static final Pattern TABLE_HEADER = Pattern.compile("table\\s+(\\w+)\\s*\\{");

    // field_name: TypeRef [= default] [(attrs...)] ;
    // Attribute clauses (e.g. {@code (id: N)} or {@code (deprecated, id: N)})
    // are tolerated — RdfFbsSchemaGenerator emits explicit IDs to keep wire
    // format stable across builds, so this parser must accept them.
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "(\\w+)\\s*:\\s*(\\[\\s*(\\w+)\\s*\\]|(\\w+))(?:\\s*=\\s*[^;()]+)?(?:\\s*\\([^)]*\\))?\\s*;");

    private FbsSchemaParser()
    {
    }

    public static FbsSchema parse(Path fbsPath) throws IOException
    {
        return parse(Files.readString(fbsPath, StandardCharsets.UTF_8));
    }

    public static FbsSchema parse(String fbsSource)
    {
        String stripped = stripComments(fbsSource);

        MutableMap<String, FbsSchema.FbsUnion> unions = Maps.mutable.empty();
        Matcher um = UNION_PATTERN.matcher(stripped);
        while (um.find())
        {
            String unionName = um.group(1);
            MutableList<String> members = Lists.mutable.empty();
            for (String raw : um.group(2).split(","))
            {
                String m = raw.trim();
                if (!m.isEmpty()) { members.add(m); }
            }
            unions.put(unionName, new FbsSchema.FbsUnion(unionName, members));
        }

        MutableMap<String, FbsSchema.FbsTable> tables = Maps.mutable.empty();
        Matcher th = TABLE_HEADER.matcher(stripped);
        while (th.find())
        {
            String tableName = th.group(1);
            int bodyStart = th.end();
            int bodyEnd = findMatchingBrace(stripped, bodyStart);
            if (bodyEnd < 0)
            {
                throw new IllegalStateException("Unterminated table body: " + tableName);
            }
            String body = stripped.substring(bodyStart, bodyEnd);
            tables.put(tableName, parseTable(tableName, body, unions));
        }

        return new FbsSchema(unions, tables);
    }

    private static FbsSchema.FbsTable parseTable(String tableName, String body, MutableMap<String, FbsSchema.FbsUnion> unions)
    {
        MutableMap<String, String> fieldUnion = Maps.mutable.empty();
        Matcher fm = FIELD_PATTERN.matcher(body);
        while (fm.find())
        {
            String fieldName = fm.group(1);
            String vecInner = fm.group(3);
            String scalar = fm.group(4);
            String typeName = vecInner != null ? vecInner : scalar;
            if (typeName != null && unions.containsKey(typeName))
            {
                fieldUnion.put(fieldName, typeName);
            }
        }
        return new FbsSchema.FbsTable(tableName, fieldUnion);
    }

    private static int findMatchingBrace(String s, int openAfter)
    {
        // openAfter is the position AFTER the opening '{'. Walk forward until
        // we find the matching '}'. .fbs has no nested braces inside tables in
        // practice, but we still count to be safe.
        int depth = 1;
        for (int i = openAfter; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '{') { depth++; }
            else if (c == '}')
            {
                depth--;
                if (depth == 0) { return i; }
            }
        }
        return -1;
    }

    private static String stripComments(String s)
    {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length())
        {
            if (i + 1 < s.length() && s.charAt(i) == '/' && s.charAt(i + 1) == '/')
            {
                int nl = s.indexOf('\n', i);
                if (nl < 0) { break; }
                out.append('\n');
                i = nl + 1;
            }
            else if (i + 1 < s.length() && s.charAt(i) == '/' && s.charAt(i + 1) == '*')
            {
                int end = s.indexOf("*/", i + 2);
                if (end < 0) { break; }
                i = end + 2;
            }
            else
            {
                out.append(s.charAt(i));
                i++;
            }
        }
        return out.toString();
    }
}
