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

package org.finos.legend.pure.platform.java.runtime;

import org.finos.legend.pure.m3.platform.pure.functions.collection.ListImpl;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.LazyObject;

import java.math.BigDecimal;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * A Pure {@code Variant}: a JSON value. {@code type} is NULL, BOOLEAN, NUMBER (its text, and whether it is
 * {@code integral}), STRING, ARRAY (a List of Variants) or OBJECT (a Map from String to Variant). It prints as
 * compact JSON, parses from JSON text, and converts to the Pure types {@code to}/{@code toMany} ask for.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class Variant
{
    private static final String LIST = "platform::pure::functions::collection::List";

    public String type;
    public Object value;
    public boolean integral;

    public static Variant make(String type, Object value, boolean integral)
    {
        Variant v = new Variant();
        v.type = type;
        v.value = value;
        v.integral = integral;
        return v;
    }

    public String _purePath()
    {
        return "platform::pure::metamodel::variant::Variant";
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        write(this, sb);
        return sb.toString();
    }

    /** Numbers compare by value (1.0 and 1.00 are equal), but an integral number never equals a decimal one. */
    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof Variant))
        {
            return false;
        }
        Variant other = (Variant) o;
        if (!type.equals(other.type))
        {
            return false;
        }
        if ("NUMBER".equals(type))
        {
            return integral == other.integral && new BigDecimal((String) value).compareTo(new BigDecimal((String) other.value)) == 0;
        }
        return Objects.equals(value, other.value);
    }

    @Override
    public int hashCode()
    {
        return "NUMBER".equals(type) ? new BigDecimal((String) value).stripTrailingZeros().hashCode() : Objects.hash(type, value);
    }

    // --- printing -------------------------------------------------------------------------------------------

    static void write(Variant v, StringBuilder sb)
    {
        switch (v.type)
        {
            case "NULL":
                sb.append("null");
                break;
            case "BOOLEAN":
            case "NUMBER":
                sb.append(v.value);
                break;
            case "STRING":
                quote((String) v.value, sb);
                break;
            case "ARRAY":
                writeArray((List<?>) v.value, sb);
                break;
            default:
                writeObject((Map<?, ?>) v.value, sb);
        }
    }

    private static void writeArray(List<?> elements, StringBuilder sb)
    {
        sb.append('[');
        for (int i = 0; i < elements.size(); i++)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            write((Variant) elements.get(i), sb);
        }
        sb.append(']');
    }

    private static void writeObject(Map<?, ?> fields, StringBuilder sb)
    {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : fields.entrySet())
        {
            if (!first)
            {
                sb.append(',');
            }
            first = false;
            quote((String) e.getKey(), sb);
            sb.append(':');
            write((Variant) e.getValue(), sb);
        }
        sb.append('}');
    }

    static void quote(String s, StringBuilder sb)
    {
        sb.append('"');
        for (int i = 0; i < s.length(); i++)
        {
            appendEscaped(s.charAt(i), sb);
        }
        sb.append('"');
    }

    private static void appendEscaped(char c, StringBuilder sb)
    {
        switch (c)
        {
            case '"':
                sb.append("\\\"");
                break;
            case '\\':
                sb.append("\\\\");
                break;
            case '\n':
                sb.append("\\n");
                break;
            case '\r':
                sb.append("\\r");
                break;
            case '\t':
                sb.append("\\t");
                break;
            case '\b':
                sb.append("\\b");
                break;
            case '\f':
                sb.append("\\f");
                break;
            default:
                if (c < 0x20)
                {
                    sb.append(String.format("\\u%04x", (int) c));
                }
                else
                {
                    sb.append(c);
                }
        }
    }

    // --- parsing (`p` is the position, shared by the recursive descent) ---------------------------------------

    public static Variant fromJson(String text)
    {
        int[] p = {0};
        skip(text, p);
        Variant v = parse(text, p);
        skip(text, p);
        if (p[0] < text.length())
        {
            throw error(p, "Unexpected trailing content");
        }
        return v;
    }

    static RuntimeException error(int[] p, String message)
    {
        return new RuntimeException("Invalid JSON (at offset " + p[0] + "): " + message);
    }

    static void skip(String t, int[] p)
    {
        while (p[0] < t.length() && " \t\n\r".indexOf(t.charAt(p[0])) >= 0)
        {
            p[0]++;
        }
    }

    static char peek(String t, int[] p)
    {
        if (p[0] >= t.length())
        {
            throw error(p, "Unexpected end of input");
        }
        return t.charAt(p[0]);
    }

    static void expect(String t, int[] p, char c)
    {
        if (p[0] >= t.length() || t.charAt(p[0]) != c)
        {
            throw error(p, "Expected '" + c + "'");
        }
        p[0]++;
    }

    static Variant parse(String t, int[] p)
    {
        char c = peek(t, p);
        if (c == '{')
        {
            return parseObject(t, p);
        }
        if (c == '[')
        {
            return parseArray(t, p);
        }
        if (c == '"')
        {
            return make("STRING", parseString(t, p), false);
        }
        if (c == 't' || c == 'f' || c == 'n')
        {
            return parseKeyword(t, p);
        }
        return parseNumber(t, p);
    }

    private static Variant parseObject(String t, int[] p)
    {
        expect(t, p, '{');
        Map<String, Object> fields = new LinkedHashMap<>();
        skip(t, p);
        if (peek(t, p) == '}')
        {
            p[0]++;
            return make("OBJECT", fields, false);
        }
        while (true)
        {
            skip(t, p);
            String key = parseString(t, p);
            skip(t, p);
            expect(t, p, ':');
            skip(t, p);
            fields.put(key, parse(t, p));
            skip(t, p);
            char d = peek(t, p);
            p[0]++;
            if (d == '}')
            {
                return make("OBJECT", fields, false);
            }
            if (d != ',')
            {
                p[0]--;
                throw error(p, "Expected ',' or '}'");
            }
        }
    }

    private static Variant parseArray(String t, int[] p)
    {
        expect(t, p, '[');
        List<Object> values = new ArrayList<>();
        skip(t, p);
        if (peek(t, p) == ']')
        {
            p[0]++;
            return make("ARRAY", values, false);
        }
        while (true)
        {
            skip(t, p);
            values.add(parse(t, p));
            skip(t, p);
            char d = peek(t, p);
            p[0]++;
            if (d == ']')
            {
                return make("ARRAY", values, false);
            }
            if (d != ',')
            {
                p[0]--;
                throw error(p, "Expected ',' or ']'");
            }
        }
    }

    private static Variant parseKeyword(String t, int[] p)
    {
        for (String keyword : new String[]{"true", "false", "null"})
        {
            if (t.startsWith(keyword, p[0]))
            {
                p[0] += keyword.length();
                return keyword.equals("null") ? make("NULL", null, false) : make("BOOLEAN", Boolean.valueOf(keyword), false);
            }
        }
        throw error(p, "Unexpected token");
    }

    private static Variant parseNumber(String t, int[] p)
    {
        int start = p[0];
        if (p[0] < t.length() && t.charAt(p[0]) == '-')
        {
            p[0]++;
        }
        skipDigits(t, p);
        if (p[0] < t.length() && t.charAt(p[0]) == '.')
        {
            p[0]++;
            skipDigits(t, p);
        }
        if (p[0] < t.length() && (t.charAt(p[0]) == 'e' || t.charAt(p[0]) == 'E'))
        {
            p[0]++;
            if (p[0] < t.length() && (t.charAt(p[0]) == '+' || t.charAt(p[0]) == '-'))
            {
                p[0]++;
            }
            skipDigits(t, p);
        }
        String literal = t.substring(start, p[0]);
        if (literal.isEmpty() || literal.equals("-"))
        {
            throw error(p, "Unexpected token");
        }
        return make("NUMBER", literal, literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0);
    }

    private static void skipDigits(String t, int[] p)
    {
        while (p[0] < t.length() && Character.isDigit(t.charAt(p[0])))
        {
            p[0]++;
        }
    }

    static String parseString(String t, int[] p)
    {
        expect(t, p, '"');
        StringBuilder sb = new StringBuilder();
        while (true)
        {
            if (p[0] >= t.length())
            {
                throw error(p, "Unterminated string");
            }
            char c = t.charAt(p[0]++);
            if (c == '"')
            {
                return sb.toString();
            }
            if (c != '\\')
            {
                sb.append(c);
                continue;
            }
            if (p[0] >= t.length())
            {
                throw error(p, "Unterminated escape");
            }
            appendUnescaped(t, p, t.charAt(p[0]++), sb);
        }
    }

    private static void appendUnescaped(String t, int[] p, char esc, StringBuilder sb)
    {
        switch (esc)
        {
            case '"':
                sb.append('"');
                break;
            case '\\':
                sb.append('\\');
                break;
            case '/':
                sb.append('/');
                break;
            case 'b':
                sb.append('\b');
                break;
            case 'f':
                sb.append('\f');
                break;
            case 'n':
                sb.append('\n');
                break;
            case 'r':
                sb.append('\r');
                break;
            case 't':
                sb.append('\t');
                break;
            case 'u':
                if (p[0] + 4 > t.length())
                {
                    throw error(p, "Invalid unicode escape");
                }
                sb.append((char) Integer.parseInt(t.substring(p[0], p[0] + 4), 16));
                p[0] += 4;
                break;
            default:
                throw error(p, "Invalid escape character '\\" + esc + "'");
        }
    }

    // --- conversions -------------------------------------------------------------------------------------------

    public static String toJson(Variant v)
    {
        return v.toString();
    }

    /** A Pure value as a Variant: [] is null, one value is that value, several an array. */
    public static Variant toVariant(Object value)
    {
        if (value instanceof List)
        {
            List<?> values = (List<?>) value;
            if (values.isEmpty())
            {
                return make("NULL", null, false);
            }
            if (values.size() == 1)
            {
                return of(values.get(0));
            }
        }
        return of(value);
    }

    static Variant of(Object value)
    {
        if (value == null)
        {
            return make("NULL", null, false);
        }
        if (value instanceof Variant)
        {
            return (Variant) value;
        }
        if (value instanceof List || isPureList(value))
        {
            return make("ARRAY", elementsOf(value instanceof List ? value : ((LazyObject) value).__values("values")), false);
        }
        if (value instanceof Map)
        {
            return make("OBJECT", sortedFields((Map<?, ?>) value), false);
        }
        if (value instanceof Long || value instanceof Integer)
        {
            return make("NUMBER", value.toString(), true);
        }
        if (value instanceof Double)
        {
            return make("NUMBER", Double.toString((Double) value), false);
        }
        if (value instanceof BigDecimal)
        {
            return make("NUMBER", ((BigDecimal) value).toPlainString(), false);
        }
        if (value instanceof Boolean)
        {
            return make("BOOLEAN", value, false);
        }
        if (value instanceof String || value instanceof Temporal || value instanceof PureDate)
        {
            return make("STRING", value.toString(), false);
        }
        throw new RuntimeException(value.getClass().getSimpleName() + " - not supported!");
    }

    private static boolean isPureList(Object value)
    {
        return value instanceof LazyObject && ((LazyObject) value)._purePath().equals(LIST);
    }

    private static List<Object> elementsOf(Object values)
    {
        List<Object> elements = new ArrayList<>();
        if (values instanceof List)
        {
            for (Object e : (List<?>) values)
            {
                elements.add(of(e));
            }
        }
        else if (values != null)
        {
            elements.add(of(values));
        }
        return elements;
    }

    /** An object's fields sorted by key, as every host prints them. */
    private static Map<String, Object> sortedFields(Map<?, ?> map)
    {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : map.entrySet())
        {
            if (!(e.getKey() instanceof String))
            {
                throw new RuntimeException("Only maps with String keys can be converted to Variant, got key: " + e.getKey());
            }
            sorted.put((String) e.getKey(), of(e.getValue()));
        }
        return new LinkedHashMap<>(sorted);
    }

    static RuntimeException wrong(Variant v, String target)
    {
        return new RuntimeException("Variant of type '" + v.type + "' cannot be converted to " + target);
    }

    public static Object to(Variant v, Function convert)
    {
        return v == null ? null : convert.apply(v);
    }

    public static List toMany(Variant v, Function convert)
    {
        List<Object> out = new ArrayList<>();
        if (v == null)
        {
            return out;
        }
        if (!"ARRAY".equals(v.type))
        {
            throw new RuntimeException("Expect variant that contains an 'ARRAY', but got '" + v.type + "'");
        }
        return converted((List<?>) v.value, convert);
    }

    /** Each element converted, the ones converting to nothing (a null) left out. */
    private static List<Object> converted(List<?> elements, Function convert)
    {
        List<Object> out = new ArrayList<>();
        for (Object e : elements)
        {
            Object r = convert.apply(e);
            if (r != null)
            {
                out.add(r);
            }
        }
        return out;
    }

    public static Object asVariant(Object x)
    {
        return (Variant) x;
    }

    public static Object asInteger(Object x)
    {
        Variant v = (Variant) x;
        if ("NULL".equals(v.type))
        {
            return null;
        }
        if (("NUMBER".equals(v.type) && v.integral) || "STRING".equals(v.type))
        {
            return Long.parseLong((String) v.value);
        }
        throw wrong(v, "Integer");
    }

    public static Object asFloat(Object x)
    {
        Variant v = (Variant) x;
        if ("NULL".equals(v.type))
        {
            return null;
        }
        if ("NUMBER".equals(v.type) || "STRING".equals(v.type))
        {
            return Double.parseDouble((String) v.value);
        }
        throw wrong(v, "Float");
    }

    public static Object asBoolean(Object x)
    {
        Variant v = (Variant) x;
        if ("NULL".equals(v.type))
        {
            return null;
        }
        if ("BOOLEAN".equals(v.type))
        {
            return v.value;
        }
        if ("STRING".equals(v.type))
        {
            if ("true".equals(v.value))
            {
                return true;
            }
            if ("false".equals(v.value))
            {
                return false;
            }
            throw new RuntimeException("Invalid Pure Boolean: '" + v.value + "'");
        }
        throw wrong(v, "Boolean");
    }

    public static Object asString(Object x)
    {
        Variant v = (Variant) x;
        if ("NULL".equals(v.type))
        {
            return null;
        }
        if ("STRING".equals(v.type) || "NUMBER".equals(v.type) || "BOOLEAN".equals(v.type))
        {
            return v.value.toString();
        }
        throw wrong(v, "String");
    }

    public static Object asStrictDate(Object x)
    {
        Variant v = (Variant) x;
        if ("NULL".equals(v.type))
        {
            return null;
        }
        if ("STRING".equals(v.type))
        {
            String s = (String) v.value;
            if (!s.matches("\\d{4}-\\d{1,2}-\\d{1,2}"))
            {
                throw new RuntimeException("StrictDate must be a calendar day, got: " + s);
            }
            return PureDate.of(s);
        }
        throw wrong(v, "StrictDate");
    }

    public static Object asDateTime(Object x)
    {
        Variant v = (Variant) x;
        if ("NULL".equals(v.type))
        {
            return null;
        }
        if ("STRING".equals(v.type))
        {
            String s = (String) v.value;
            if (s.indexOf('T') < 0)
            {
                throw new RuntimeException("DateTime must include time information, got: " + s);
            }
            return PureDate.of(s);
        }
        throw wrong(v, "DateTime");
    }

    /** A converter to a Pure List of what `element` converts each array element to. */
    public static Function asList(Function element, String target)
    {
        return (Function<Object, Object>) x ->
        {
            Variant v = (Variant) x;
            if ("NULL".equals(v.type))
            {
                return null;
            }
            if (!"ARRAY".equals(v.type))
            {
                throw wrong(v, target);
            }
            return new ListImpl()._values(converted((List<?>) v.value, element));
        };
    }

    /** A converter to a Map of what `valueConverter` converts each field value to. */
    public static Function asMap(Function valueConverter, String target)
    {
        return (Function<Object, Object>) x ->
        {
            Variant v = (Variant) x;
            if ("NULL".equals(v.type))
            {
                return null;
            }
            if (!"OBJECT".equals(v.type))
            {
                throw wrong(v, target);
            }
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v.value).entrySet())
            {
                out.put(e.getKey(), valueConverter.apply(e.getValue()));
            }
            return out;
        };
    }

    public static Function unsupported(String target)
    {
        return (Function<Object, Object>) x ->
        {
            if ("NULL".equals(((Variant) x).type))
            {
                return null;
            }
            throw new RuntimeException(target + " is not managed yet!");
        };
    }

    public static Variant get(Variant v, String key)
    {
        Object map = to(v, asMap(Variant::asVariant, "Map<String, Variant>"));
        return map == null ? null : (Variant) ((Map<?, ?>) map).get(key);
    }

    public static Variant at(Variant v, long index)
    {
        return (Variant) toMany(v, Variant::asVariant).get((int) index);
    }
}
