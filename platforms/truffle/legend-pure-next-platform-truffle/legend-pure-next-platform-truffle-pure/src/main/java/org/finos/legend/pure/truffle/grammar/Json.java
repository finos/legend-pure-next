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

package org.finos.legend.pure.truffle.grammar;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.map.MutableMap;

/**
 * A JSON reader, enough for a language manifest and no more.
 *
 * <p>Hand-rolled rather than Gson, following bootstrap's {@code ModuleManifest.parse}, which reads
 * the sibling {@code module.json} with its own cursor. The manifests are a dozen lines of flat JSON
 * this repository owns, this tree declares no dependencies of its own, and Gson appears here only as
 * a pinned transitive that {@code legend-pure-next-truffle-core} excludes outright.</p>
 *
 * <p>Values come back as {@link MapIterable}, {@link MutableList}, {@link String}, {@link Boolean},
 * {@link Double} or null. Key order is NOT preserved and nothing reads it: a manifest is read by key,
 * and registration order comes from the order a caller lists its manifests in.</p>
 */
final class Json
{
    private final String text;
    private int at;

    private Json(String text)
    {
        this.text = text;
    }

    /** Parse one JSON document. Throws on anything malformed, naming the offset. */
    static Object parse(String text)
    {
        Json json = new Json(text);
        json.skipWhitespace();
        Object value = json.value();
        json.skipWhitespace();
        if (json.at < json.text.length())
        {
            throw json.error("trailing content");
        }
        return value;
    }

    /** {@link #parse}, requiring an object — which every manifest is. */
    @SuppressWarnings("unchecked")
    static MapIterable<String, Object> parseObject(String text)
    {
        Object value = parse(text);
        if (!(value instanceof MutableMap))
        {
            throw new IllegalArgumentException("expected a JSON object, got " + describe(value));
        }
        return (MapIterable<String, Object>) value;
    }

    // --- reading ------------------------------------------------------------

    private Object value()
    {
        char c = peek();
        switch (c)
        {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9'))
                {
                    return number();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private MutableMap<String, Object> object()
    {
        MutableMap<String, Object> out = Maps.mutable.empty();
        expect('{');
        skipWhitespace();
        if (peek() == '}')
        {
            at++;
            return out;
        }
        while (true)
        {
            skipWhitespace();
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            out.put(key, value());
            skipWhitespace();
            char c = peek();
            at++;
            if (c == '}')
            {
                return out;
            }
            if (c != ',')
            {
                throw error("expected ',' or '}' in object");
            }
        }
    }

    private MutableList<Object> array()
    {
        MutableList<Object> out = Lists.mutable.empty();
        expect('[');
        skipWhitespace();
        if (peek() == ']')
        {
            at++;
            return out;
        }
        while (true)
        {
            skipWhitespace();
            out.add(value());
            skipWhitespace();
            char c = peek();
            at++;
            if (c == ']')
            {
                return out;
            }
            if (c != ',')
            {
                throw error("expected ',' or ']' in array");
            }
        }
    }

    private String string()
    {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true)
        {
            if (at >= text.length())
            {
                throw error("unterminated string");
            }
            char c = text.charAt(at++);
            if (c == '"')
            {
                return out.toString();
            }
            if (c != '\\')
            {
                out.append(c);
                continue;
            }
            if (at >= text.length())
            {
                throw error("unterminated escape");
            }
            char escaped = text.charAt(at++);
            switch (escaped)
            {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (at + 4 > text.length())
                    {
                        throw error("truncated \\u escape");
                    }
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                    break;
                default:
                    throw error("unknown escape '\\" + escaped + "'");
            }
        }
    }

    private Double number()
    {
        int start = at;
        if (peek() == '-')
        {
            at++;
        }
        while (at < text.length() && isNumberChar(text.charAt(at)))
        {
            at++;
        }
        try
        {
            return Double.valueOf(text.substring(start, at));
        }
        catch (NumberFormatException e)
        {
            throw error("malformed number '" + text.substring(start, at) + "'");
        }
    }

    private static boolean isNumberChar(char c)
    {
        return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
    }

    private Object literal(String word, Object value)
    {
        if (!text.startsWith(word, at))
        {
            throw error("expected '" + word + "'");
        }
        at += word.length();
        return value;
    }

    // --- cursor -------------------------------------------------------------

    private char peek()
    {
        if (at >= text.length())
        {
            throw error("unexpected end of input");
        }
        return text.charAt(at);
    }

    private void expect(char c)
    {
        if (peek() != c)
        {
            throw error("expected '" + c + "'");
        }
        at++;
    }

    private void skipWhitespace()
    {
        while (at < text.length() && Character.isWhitespace(text.charAt(at)))
        {
            at++;
        }
    }

    private IllegalArgumentException error(String message)
    {
        return new IllegalArgumentException("invalid JSON at offset " + at + ": " + message);
    }

    private static String describe(Object value)
    {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    // --- typed access, so callers do not cast ------------------------------

    /** The string at {@code key}, or null when absent. Throws when present but not a string. */
    static String string(MapIterable<String, Object> object, String key)
    {
        Object value = object.get(key);
        if (value == null)
        {
            return null;
        }
        if (!(value instanceof String))
        {
            throw new IllegalArgumentException("'" + key + "' must be a string, got " + describe(value));
        }
        return (String) value;
    }

    /** The boolean at {@code key}, false when absent. */
    static boolean bool(MapIterable<String, Object> object, String key)
    {
        Object value = object.get(key);
        if (value == null)
        {
            return false;
        }
        if (!(value instanceof Boolean))
        {
            throw new IllegalArgumentException("'" + key + "' must be a boolean, got " + describe(value));
        }
        return (Boolean) value;
    }

    /** The strings at {@code key}, or an empty list when absent. */
    static ImmutableList<String> strings(MapIterable<String, Object> object, String key)
    {
        Object value = object.get(key);
        if (value == null)
        {
            return Lists.immutable.empty();
        }
        if (!(value instanceof MutableList))
        {
            throw new IllegalArgumentException("'" + key + "' must be an array, got " + describe(value));
        }
        MutableList<String> out = Lists.mutable.empty();
        for (Object element : (MutableList<?>) value)
        {
            if (!(element instanceof String))
            {
                throw new IllegalArgumentException("'" + key + "' must hold strings, got " + describe(element));
            }
            out.add((String) element);
        }
        return out.toImmutable();
    }

    /** The nested object at {@code key}, or an empty map when absent. */
    @SuppressWarnings("unchecked")
    static MapIterable<String, Object> object(MapIterable<String, Object> object, String key)
    {
        Object value = object.get(key);
        if (value == null)
        {
            return Maps.mutable.empty();
        }
        if (!(value instanceof MapIterable))
        {
            throw new IllegalArgumentException("'" + key + "' must be an object, got " + describe(value));
        }
        return (MapIterable<String, Object>) value;
    }
}
