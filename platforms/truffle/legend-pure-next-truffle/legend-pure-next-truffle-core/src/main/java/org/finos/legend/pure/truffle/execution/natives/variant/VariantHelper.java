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

package org.finos.legend.pure.truffle.execution.natives.variant;

import org.finos.legend.pure.execution.JsonValue;
import org.finos.legend.pure.execution.PureVariant;
import org.finos.legend.pure.truffle.execution.TruffleInstanceFactory;
import org.finos.legend.pure.truffle.compiler.module.MetadataAccess;
import org.finos.legend.pure.truffle.compiler.helper._Any;
import org.finos.legend.pure.truffle.compiler.helper._GenericType;
import org.finos.legend.pure.truffle.compiler.helper._PackageableElement;
import org.finos.legend.pure.truffle.compiler.module.PureClassRegistry;
import org.finos.legend.pure.truffle.execution.types.MapImpl;
import org.finos.legend.pure.truffle.execution.types.ObjectSequence;
import org.finos.legend.pure.truffle.execution.types.PureDate;
import org.finos.legend.pure.truffle.execution.types.PureSequence;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shared logic for the Variant natives (fromJson/toJson/toVariant/to/toMany).
 *
 * <p>The JSON tree and its parser ({@link JsonValue}, {@link PureVariant})
 * are reused from bootstrap-execution; this class adapts the coercions to the
 * Truffle value model (raw scalars, {@link PureDate}, {@link MapImpl},
 * PDO List instances, {@link PureSequence} collections). Coercion rules and
 * error messages match the bootstrap {@code VariantNatives} exactly — they
 * are part of the cross-platform PCT contract.</p>
 */
final class VariantHelper
{
    static final String LIST_PATH = "meta::pure::functions::collection::List";
    static final String MAP_PATH = "meta::pure::functions::collection::Map";
    static final String PRIMITIVES = "meta::pure::metamodel::type::primitives::";

    private static final int SLOT_GENERIC_TYPE = PureClassRegistry.globalSlot("genericType");
    private static final int SLOT_VALUES = PureClassRegistry.globalSlot("values");

    private VariantHelper()
    {
    }

    /**
     * Normalize a [0..1] argument: empty sequence → null, single-element
     * sequence → its element, raw value → itself.
     */
    static Object scalarOf(Object value)
    {
        if (value instanceof PureSequence seq)
        {
            return seq.isEmpty() ? null : seq.getBoxed(0);
        }
        return value;
    }

    /**
     * Extract the target T GenericType from a GenericTypeAndMultiplicityHolder
     * runtime value (same access pattern as CastNode: the holder's
     * classifierGenericType carries T as typeArguments[0]).
     */
    static Object targetGenericType(Object holder)
    {
        Object holderGT = holder != null ? _Any.readBySlot(holder, SLOT_GENERIC_TYPE) : null;
        PureSequence typeArgs = holderGT != null ? _GenericType.typeArguments(holderGT) : null;
        return typeArgs != null && typeArgs.size() > 0 ? typeArgs.getBoxed(0) : null;
    }

    // =========================================================================
    // Pure value -> JSON (toVariant)
    // =========================================================================

    static JsonValue valueToJson(Object value, MetadataAccess resolver)
    {
        if (value == null)
        {
            return JsonValue.JsonNull.INSTANCE;
        }
        if (value instanceof PureVariant variant)
        {
            return variant.getValue();
        }
        if (value instanceof PureSequence seq)
        {
            List<JsonValue> elements = new ArrayList<>(seq.size());
            for (int i = 0; i < seq.size(); i++)
            {
                elements.add(valueToJson(seq.getBoxed(i), resolver));
            }
            return new JsonValue.JsonArray(elements);
        }
        if (_Any.pureTypeIs(value, LIST_PATH))
        {
            Object valuesObj = _Any.readBySlot(value, SLOT_VALUES);
            List<JsonValue> elements = new ArrayList<>();
            if (valuesObj instanceof PureSequence values)
            {
                for (int i = 0; i < values.size(); i++)
                {
                    elements.add(valueToJson(values.getBoxed(i), resolver));
                }
            }
            else if (valuesObj != null)
            {
                elements.add(valueToJson(valuesObj, resolver));
            }
            return new JsonValue.JsonArray(elements);
        }
        if (value instanceof MapImpl map)
        {
            // Object keys are rendered in sorted order for deterministic output
            TreeMap<String, JsonValue> sorted = new TreeMap<>();
            for (Map.Entry<Object, Object> entry : map.getMap().entrySet())
            {
                if (!(entry.getKey() instanceof String stringKey))
                {
                    throw new RuntimeException("Only maps with String keys can be converted to Variant, got key: " + entry.getKey());
                }
                sorted.put(stringKey, valueToJson(entry.getValue(), resolver));
            }
            return new JsonValue.JsonObject(new LinkedHashMap<>(sorted));
        }
        if (value instanceof Long longValue)
        {
            return JsonValue.JsonNumber.ofIntegral(longValue);
        }
        if (value instanceof Integer intValue)
        {
            return JsonValue.JsonNumber.ofIntegral(intValue);
        }
        if (value instanceof BigInteger bigInteger)
        {
            return JsonValue.JsonNumber.ofIntegral(bigInteger.longValueExact());
        }
        if (value instanceof Double doubleValue)
        {
            return JsonValue.JsonNumber.ofDecimal(doubleValue);
        }
        if (value instanceof BigDecimal bigDecimal)
        {
            return JsonValue.JsonNumber.ofDecimal(bigDecimal);
        }
        if (value instanceof Boolean booleanValue)
        {
            return JsonValue.JsonBoolean.of(booleanValue);
        }
        if (value instanceof PureDate date)
        {
            return new JsonValue.JsonString(date.dateString());
        }
        if (value instanceof String stringValue)
        {
            return new JsonValue.JsonString(stringValue);
        }
        String typeName = _Any.pureTypeOf(value);
        throw new RuntimeException((typeName != null ? typeName : value.getClass().getSimpleName()) + " - not supported!");
    }

    // =========================================================================
    // JSON -> Pure value (to / toMany)
    // =========================================================================

    /**
     * Coerce a JSON value to the target generic type. Returns a raw Truffle
     * value — or null for JSON null (empty in Pure).
     */
    static Object jsonToPure(JsonValue json, Object targetGT, MetadataAccess resolver)
    {
        String targetPath = typePath(targetGT, resolver);

        if (PureVariant.TYPE_PATH.equals(targetPath))
        {
            return new PureVariant(json);
        }

        if (json instanceof JsonValue.JsonNull)
        {
            return null;
        }

        boolean supportedTypeButWrongJson = false;

        if (LIST_PATH.equals(targetPath))
        {
            if (json instanceof JsonValue.JsonArray array)
            {
                Object elementGT = typeArgumentAt(targetGT, 0);
                List<Object> elements = new ArrayList<>(array.values().size());
                for (JsonValue element : array.values())
                {
                    Object coerced = jsonToPure(element, elementGT, resolver);
                    if (coerced != null)
                    {
                        elements.add(coerced);
                    }
                }
                Object instance = TruffleInstanceFactory.createInstance(LIST_PATH, resolver);
                _Any.write(instance, "classifierGenericType", targetGT);
                _Any.write(instance, "values", new ObjectSequence(elements.toArray()));
                return instance;
            }
            supportedTypeButWrongJson = true;
        }
        else if (MAP_PATH.equals(targetPath)
                && (PRIMITIVES + "String").equals(typePath(typeArgumentAt(targetGT, 0), resolver)))
        {
            if (json instanceof JsonValue.JsonObject object)
            {
                Object valueGT = typeArgumentAt(targetGT, 1);
                MapImpl map = new MapImpl();
                for (Map.Entry<String, JsonValue> entry : object.fields().entrySet())
                {
                    map.put(entry.getKey(), jsonToPure(entry.getValue(), valueGT, resolver));
                }
                return map;
            }
            supportedTypeButWrongJson = true;
        }
        else if ((PRIMITIVES + "Integer").equals(targetPath))
        {
            if (json instanceof JsonValue.JsonNumber number && number.isIntegral())
            {
                return number.longValue();
            }
            if (json instanceof JsonValue.JsonString string)
            {
                return Long.parseLong(string.value());
            }
            supportedTypeButWrongJson = true;
        }
        else if ((PRIMITIVES + "Float").equals(targetPath))
        {
            if (json instanceof JsonValue.JsonNumber number)
            {
                return number.doubleValue();
            }
            if (json instanceof JsonValue.JsonString string)
            {
                return Double.parseDouble(string.value());
            }
            supportedTypeButWrongJson = true;
        }
        else if ((PRIMITIVES + "StrictDate").equals(targetPath))
        {
            if (json instanceof JsonValue.JsonString string)
            {
                if (!string.value().matches("\\d{4}-\\d{1,2}-\\d{1,2}"))
                {
                    throw new RuntimeException("StrictDate must be a calendar day, got: " + string.value());
                }
                return new PureDate.StrictDate(string.value());
            }
            supportedTypeButWrongJson = true;
        }
        else if ((PRIMITIVES + "DateTime").equals(targetPath))
        {
            if (json instanceof JsonValue.JsonString string)
            {
                if (string.value().indexOf('T') < 0)
                {
                    throw new RuntimeException("DateTime must include time information, got: " + string.value());
                }
                return new PureDate.DateTime(string.value());
            }
            supportedTypeButWrongJson = true;
        }
        else if ((PRIMITIVES + "String").equals(targetPath))
        {
            if (json instanceof JsonValue.JsonString string)
            {
                return string.value();
            }
            if (json instanceof JsonValue.JsonNumber number)
            {
                return number.literal();
            }
            if (json instanceof JsonValue.JsonBoolean bool)
            {
                return Boolean.toString(bool.value());
            }
            supportedTypeButWrongJson = true;
        }
        else if ((PRIMITIVES + "Boolean").equals(targetPath))
        {
            if (json instanceof JsonValue.JsonBoolean bool)
            {
                return bool.value();
            }
            if (json instanceof JsonValue.JsonString string)
            {
                if ("true".equals(string.value()))
                {
                    return true;
                }
                if ("false".equals(string.value()))
                {
                    return false;
                }
                throw new RuntimeException("Invalid Pure Boolean: '" + string.value() + "'");
            }
            supportedTypeButWrongJson = true;
        }

        if (supportedTypeButWrongJson)
        {
            throw new RuntimeException("Variant of type '" + json.typeName() + "' cannot be converted to " + printForMessage(targetGT, resolver));
        }

        throw new RuntimeException(printForMessage(targetGT, resolver) + " is not managed yet!");
    }

    private static String typePath(Object genericType, MetadataAccess resolver)
    {
        if (genericType == null)
        {
            return null;
        }
        Object rawType = _GenericType.type(genericType);
        return rawType != null ? _PackageableElement.path(rawType, resolver) : null;
    }

    private static Object typeArgumentAt(Object genericType, int index)
    {
        PureSequence typeArgs = genericType != null ? _GenericType.typeArguments(genericType) : null;
        return typeArgs != null && typeArgs.size() > index ? typeArgs.getBoxed(index) : null;
    }

    /**
     * Print a generic type for an error message using the same convention as
     * the bootstrap printer: simple names for well-known metamodel/functions
     * types (Integer, Month, Map&lt;String, Variant&gt;, ...), full paths otherwise.
     */
    static String printForMessage(Object genericType, MetadataAccess resolver)
    {
        if (genericType == null)
        {
            return "Unknown";
        }
        String path = typePath(genericType, resolver);
        StringBuilder sb = new StringBuilder(simpleName(path));
        PureSequence typeArgs = _GenericType.typeArguments(genericType);
        if (typeArgs != null && typeArgs.size() > 0)
        {
            sb.append('<');
            for (int i = 0; i < typeArgs.size(); i++)
            {
                if (i > 0)
                {
                    sb.append(", ");
                }
                sb.append(printForMessage(typeArgs.getBoxed(i), resolver));
            }
            sb.append('>');
        }
        return sb.toString();
    }

    private static String simpleName(String path)
    {
        if (path == null || path.isEmpty())
        {
            return "?";
        }
        if (path.startsWith("meta::pure::metamodel::") || path.startsWith("meta::pure::functions::"))
        {
            int lastSeparator = path.lastIndexOf("::");
            return lastSeparator < 0 ? path : path.substring(lastSeparator + 2);
        }
        return path;
    }
}
