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

package org.finos.legend.pure.execution;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable JSON value tree backing {@link PureVariant}.
 *
 * <p>Self-contained parser and compact serializer — no external JSON
 * dependency. Numbers preserve their source token so that integer vs
 * decimal distinction and literal text survive a round-trip
 * ({@code fromJson('1.25')->toJson() == '1.25'}).</p>
 *
 * <p>{@link #typeName()} values (NULL, BOOLEAN, NUMBER, STRING, ARRAY,
 * OBJECT) are part of the Variant error-message contract shared by all
 * execution platforms.</p>
 */
public abstract class JsonValue
{
    JsonValue()
    {
    }

    /**
     * The JSON node kind name used in Variant error messages.
     */
    public abstract String typeName();

    public final String toJson()
    {
        StringBuilder sb = new StringBuilder();
        writeTo(sb);
        return sb.toString();
    }

    abstract void writeTo(StringBuilder sb);

    @Override
    public final String toString()
    {
        return toJson();
    }

    // =========================================================================
    // Node types
    // =========================================================================

    public static final class JsonNull extends JsonValue
    {
        public static final JsonNull INSTANCE = new JsonNull();

        private JsonNull()
        {
        }

        @Override
        public String typeName()
        {
            return "NULL";
        }

        @Override
        void writeTo(StringBuilder sb)
        {
            sb.append("null");
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof JsonNull;
        }

        @Override
        public int hashCode()
        {
            return 0;
        }
    }

    public static final class JsonBoolean extends JsonValue
    {
        public static final JsonBoolean TRUE = new JsonBoolean(true);
        public static final JsonBoolean FALSE = new JsonBoolean(false);

        private final boolean value;

        private JsonBoolean(boolean value)
        {
            this.value = value;
        }

        public static JsonBoolean of(boolean value)
        {
            return value ? TRUE : FALSE;
        }

        public boolean value()
        {
            return value;
        }

        @Override
        public String typeName()
        {
            return "BOOLEAN";
        }

        @Override
        void writeTo(StringBuilder sb)
        {
            sb.append(value);
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof JsonBoolean other && other.value == value;
        }

        @Override
        public int hashCode()
        {
            return Boolean.hashCode(value);
        }
    }

    /**
     * A JSON number. Keeps the source literal for lossless serialization;
     * {@code integral} is true when the token has no fraction or exponent
     * part (the JSON-level Integer vs Float distinction).
     */
    public static final class JsonNumber extends JsonValue
    {
        private final String literal;
        private final boolean integral;

        private JsonNumber(String literal, boolean integral)
        {
            this.literal = literal;
            this.integral = integral;
        }

        public static JsonNumber ofIntegral(long value)
        {
            return new JsonNumber(Long.toString(value), true);
        }

        public static JsonNumber ofDecimal(double value)
        {
            return new JsonNumber(Double.toString(value), false);
        }

        public static JsonNumber ofDecimal(BigDecimal value)
        {
            return new JsonNumber(value.toPlainString(), false);
        }

        static JsonNumber ofLiteral(String literal)
        {
            boolean integral = literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0;
            return new JsonNumber(literal, integral);
        }

        public boolean isIntegral()
        {
            return integral;
        }

        public String literal()
        {
            return literal;
        }

        public long longValue()
        {
            return Long.parseLong(literal);
        }

        public double doubleValue()
        {
            return Double.parseDouble(literal);
        }

        @Override
        public String typeName()
        {
            return "NUMBER";
        }

        @Override
        void writeTo(StringBuilder sb)
        {
            sb.append(literal);
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof JsonNumber other
                    && other.integral == integral
                    && new BigDecimal(other.literal).compareTo(new BigDecimal(literal)) == 0;
        }

        @Override
        public int hashCode()
        {
            return new BigDecimal(literal).stripTrailingZeros().hashCode();
        }
    }

    public static final class JsonString extends JsonValue
    {
        private final String value;

        public JsonString(String value)
        {
            this.value = Objects.requireNonNull(value);
        }

        public String value()
        {
            return value;
        }

        @Override
        public String typeName()
        {
            return "STRING";
        }

        @Override
        void writeTo(StringBuilder sb)
        {
            writeEscapedString(value, sb);
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof JsonString other && other.value.equals(value);
        }

        @Override
        public int hashCode()
        {
            return value.hashCode();
        }
    }

    public static final class JsonArray extends JsonValue
    {
        private final List<JsonValue> values;

        public JsonArray(List<JsonValue> values)
        {
            this.values = List.copyOf(values);
        }

        public List<JsonValue> values()
        {
            return values;
        }

        @Override
        public String typeName()
        {
            return "ARRAY";
        }

        @Override
        void writeTo(StringBuilder sb)
        {
            sb.append('[');
            for (int i = 0; i < values.size(); i++)
            {
                if (i > 0)
                {
                    sb.append(',');
                }
                values.get(i).writeTo(sb);
            }
            sb.append(']');
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof JsonArray other && other.values.equals(values);
        }

        @Override
        public int hashCode()
        {
            return values.hashCode();
        }
    }

    public static final class JsonObject extends JsonValue
    {
        private final LinkedHashMap<String, JsonValue> fields;

        public JsonObject(LinkedHashMap<String, JsonValue> fields)
        {
            this.fields = new LinkedHashMap<>(fields);
        }

        public Map<String, JsonValue> fields()
        {
            return fields;
        }

        @Override
        public String typeName()
        {
            return "OBJECT";
        }

        @Override
        void writeTo(StringBuilder sb)
        {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonValue> entry : fields.entrySet())
            {
                if (!first)
                {
                    sb.append(',');
                }
                first = false;
                writeEscapedString(entry.getKey(), sb);
                sb.append(':');
                entry.getValue().writeTo(sb);
            }
            sb.append('}');
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof JsonObject other && other.fields.equals(fields);
        }

        @Override
        public int hashCode()
        {
            return fields.hashCode();
        }
    }

    static void writeEscapedString(String s, StringBuilder sb)
    {
        sb.append('"');
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch (c)
            {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default ->
                {
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
        }
        sb.append('"');
    }

    // =========================================================================
    // Parser
    // =========================================================================

    /**
     * Parse a complete JSON document. Throws {@link RuntimeException} on
     * malformed input or trailing content.
     */
    public static JsonValue parse(String text)
    {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        JsonValue value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd())
        {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    private static final class Parser
    {
        private final String text;
        private int pos;

        Parser(String text)
        {
            this.text = text;
        }

        boolean atEnd()
        {
            return pos >= text.length();
        }

        RuntimeException error(String message)
        {
            return new RuntimeException("Invalid JSON (at offset " + pos + "): " + message);
        }

        void skipWhitespace()
        {
            while (pos < text.length())
            {
                char c = text.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r')
                {
                    break;
                }
                pos++;
            }
        }

        char peek()
        {
            if (atEnd())
            {
                throw error("Unexpected end of input");
            }
            return text.charAt(pos);
        }

        void expect(char c)
        {
            if (atEnd() || text.charAt(pos) != c)
            {
                throw error("Expected '" + c + "'");
            }
            pos++;
        }

        JsonValue parseValue()
        {
            char c = peek();
            return switch (c)
            {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> new JsonString(parseString());
                case 't' -> parseKeyword("true", JsonBoolean.TRUE);
                case 'f' -> parseKeyword("false", JsonBoolean.FALSE);
                case 'n' -> parseKeyword("null", JsonNull.INSTANCE);
                default -> parseNumber();
            };
        }

        JsonValue parseKeyword(String keyword, JsonValue value)
        {
            if (!text.startsWith(keyword, pos))
            {
                throw error("Unexpected token");
            }
            pos += keyword.length();
            return value;
        }

        JsonValue parseObject()
        {
            expect('{');
            LinkedHashMap<String, JsonValue> fields = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}')
            {
                pos++;
                return new JsonObject(fields);
            }
            while (true)
            {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                fields.put(key, parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',')
                {
                    pos++;
                }
                else if (c == '}')
                {
                    pos++;
                    return new JsonObject(fields);
                }
                else
                {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        JsonValue parseArray()
        {
            expect('[');
            List<JsonValue> values = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']')
            {
                pos++;
                return new JsonArray(values);
            }
            while (true)
            {
                skipWhitespace();
                values.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',')
                {
                    pos++;
                }
                else if (c == ']')
                {
                    pos++;
                    return new JsonArray(values);
                }
                else
                {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        String parseString()
        {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true)
            {
                if (atEnd())
                {
                    throw error("Unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"')
                {
                    return sb.toString();
                }
                if (c != '\\')
                {
                    sb.append(c);
                    continue;
                }
                if (atEnd())
                {
                    throw error("Unterminated escape");
                }
                char esc = text.charAt(pos++);
                switch (esc)
                {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' ->
                    {
                        if (pos + 4 > text.length())
                        {
                            throw error("Invalid unicode escape");
                        }
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("Invalid escape character '\\" + esc + "'");
                }
            }
        }

        JsonValue parseNumber()
        {
            int start = pos;
            if (!atEnd() && text.charAt(pos) == '-')
            {
                pos++;
            }
            while (!atEnd() && Character.isDigit(text.charAt(pos)))
            {
                pos++;
            }
            if (!atEnd() && text.charAt(pos) == '.')
            {
                pos++;
                while (!atEnd() && Character.isDigit(text.charAt(pos)))
                {
                    pos++;
                }
            }
            if (!atEnd() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E'))
            {
                pos++;
                if (!atEnd() && (text.charAt(pos) == '+' || text.charAt(pos) == '-'))
                {
                    pos++;
                }
                while (!atEnd() && Character.isDigit(text.charAt(pos)))
                {
                    pos++;
                }
            }
            String literal = text.substring(start, pos);
            if (literal.isEmpty() || "-".equals(literal))
            {
                throw error("Unexpected token");
            }
            return JsonNumber.ofLiteral(literal);
        }
    }
}
