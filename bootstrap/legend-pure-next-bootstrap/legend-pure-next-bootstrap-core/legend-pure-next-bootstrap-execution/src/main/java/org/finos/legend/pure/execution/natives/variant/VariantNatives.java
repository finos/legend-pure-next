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

package org.finos.legend.pure.execution.natives.variant;

import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.GenericTypeValue;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.JsonValue;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution.PureMap;
import org.finos.legend.pure.execution.PureTypeResolver;
import org.finos.legend.pure.execution.PureVariant;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.execution.natives.collection.CollectionNatives;
import org.finos.legend.pure.execution.natives.string.StringNatives;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Natives for {@code meta::pure::functions::variant::convert}: fromJson,
 * toJson, toVariant, to, toMany.
 *
 * <p>The {@code to}/{@code toMany} target type is the call-site resolved
 * return {@code genericType} (the type parameter {@code T}), which the
 * dispatch layer already passes in — no special type-argument plumbing is
 * needed. Coercion rules and error messages follow the reference
 * implementation in legend-pure (Jackson node-type names in messages:
 * NULL, BOOLEAN, NUMBER, STRING, ARRAY, OBJECT).</p>
 */
public class VariantNatives
{
    private static final String LIST_PATH = "meta::pure::functions::collection::List";
    private static final String MAP_PATH = "meta::pure::functions::collection::Map";
    private static final String PRIMITIVES = "meta::pure::metamodel::type::primitives::";

    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        // fromJson(String[1]) : Variant[1]
        natives.put("fromJson_String_1__Variant_1_", (args, eval, genericType, multiplicity) ->
        {
            String json = (String) _E_ValueSpecification.unwrap(args.get(0));
            return _E_ValueSpecification.wrap(new PureVariant(JsonValue.parse(json)), genericType, multiplicity, resolver);
        });

        // toJson(Variant[1]) : String[1]
        natives.put("toJson_Variant_1__String_1_", (args, eval, genericType, multiplicity) ->
        {
            PureVariant variant = (PureVariant) _E_ValueSpecification.unwrap(args.get(0));
            return _E_ValueSpecification.wrap(variant.getValue().toJson(), genericType, multiplicity, resolver);
        });

        // toVariant(Any[*]) : Variant[1]
        natives.put("toVariant_Any_MANY__Variant_1_", (args, eval, genericType, multiplicity) ->
        {
            List<? extends ValueSpecification> values = _E_ValueSpecification.toCollection(args.get(0), resolver)._values();
            JsonValue json;
            if (values.isEmpty())
            {
                json = JsonValue.JsonNull.INSTANCE;
            }
            else if (values.size() == 1)
            {
                json = valueToJson(values.get(0), resolver);
            }
            else
            {
                List<JsonValue> elements = new ArrayList<>(values.size());
                for (ValueSpecification vs : values)
                {
                    elements.add(valueToJson(vs, resolver));
                }
                json = new JsonValue.JsonArray(elements);
            }
            return _E_ValueSpecification.wrap(new PureVariant(json), genericType, multiplicity, resolver);
        });

        // to(Variant[0..1], GenericTypeAndMultiplicityHolder<T|?>[1]) : T[0..1] — genericType is the resolved T
        natives.put("to_Variant_$0_1$__GenericTypeAndMultiplicityHolder_1__T_$0_1$_", (args, eval, genericType, multiplicity) ->
        {
            PureVariant variant = unwrapVariant(args.get(0));
            if (variant == null)
            {
                return _E_ValueSpecification.wrap(null, genericType, multiplicity, resolver);
            }
            Object result = jsonToPure(variant.getValue(), genericType, resolver);
            return _E_ValueSpecification.wrap(result, genericType, multiplicity, resolver);
        });

        // toMany(Variant[0..1], GenericTypeAndMultiplicityHolder<T|?>[1]) : T[*] — genericType is the resolved T
        natives.put("toMany_Variant_$0_1$__GenericTypeAndMultiplicityHolder_1__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            PureVariant variant = unwrapVariant(args.get(0));
            if (variant == null)
            {
                return _E_ValueSpecification.wrap(null, genericType, multiplicity, resolver);
            }
            JsonValue json = variant.getValue();
            if (!(json instanceof JsonValue.JsonArray array))
            {
                throw new RuntimeException("Expect variant that contains an 'ARRAY', but got '" + json.typeName() + "'");
            }
            List<Object> results = new ArrayList<>(array.values().size());
            for (JsonValue element : array.values())
            {
                Object result = jsonToPure(element, genericType, resolver);
                if (result != null)
                {
                    results.add(result);
                }
            }
            return _E_ValueSpecification.wrap(results, genericType, multiplicity, resolver);
        });
    }

    /**
     * Unwrap a Variant[0..1] argument: null for empty, the PureVariant otherwise.
     */
    private static PureVariant unwrapVariant(ValueSpecification vs)
    {
        Object value = _E_ValueSpecification.unwrap(vs);
        if (value instanceof List<?> list)
        {
            value = list.isEmpty() ? null : list.get(0);
        }
        return (PureVariant) value;
    }

    // =========================================================================
    // Pure value -> JSON (toVariant)
    // =========================================================================

    private static JsonValue valueToJson(ValueSpecification vs, MetadataAccess resolver)
    {
        return rawToJson(_E_ValueSpecification.unwrap(vs), resolver);
    }

    private static JsonValue rawToJson(Object value, MetadataAccess resolver)
    {
        if (value == null)
        {
            return JsonValue.JsonNull.INSTANCE;
        }
        if (value instanceof ValueSpecification vs)
        {
            return valueToJson(vs, resolver);
        }
        if (value instanceof List<?> list)
        {
            List<JsonValue> elements = new ArrayList<>(list.size());
            for (Object element : list)
            {
                elements.add(rawToJson(element, resolver));
            }
            return new JsonValue.JsonArray(elements);
        }
        if (value instanceof PureVariant variant)
        {
            return variant.getValue();
        }
        if (value instanceof DynamicInstance di && LIST_PATH.equals(di.getClassPath()))
        {
            // DynamicInstance property storage is raw (put() auto-unwraps):
            // a single raw value, a List<Object> of raw values, or null
            Object valuesProperty = di.get("values");
            List<JsonValue> elements = new ArrayList<>();
            if (valuesProperty instanceof List<?> list)
            {
                for (Object element : list)
                {
                    elements.add(rawToJson(element, resolver));
                }
            }
            else if (valuesProperty != null)
            {
                elements.add(rawToJson(valuesProperty, resolver));
            }
            return new JsonValue.JsonArray(elements);
        }
        if (value instanceof PureMap map)
        {
            // Object keys are rendered in sorted order for deterministic output
            TreeMap<String, JsonValue> sorted = new TreeMap<>();
            for (Map.Entry<ValueSpecification, ValueSpecification> entry : map.getMap().entrySet())
            {
                Object key = _E_ValueSpecification.unwrap(entry.getKey());
                if (!(key instanceof String stringKey))
                {
                    throw new RuntimeException("Only maps with String keys can be converted to Variant, got key: " + key);
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
        if (value instanceof String stringValue)
        {
            // Covers String and the string-based date representations
            return new JsonValue.JsonString(stringValue);
        }
        throw new RuntimeException(PureTypeResolver.getPureTypeName(value) + " - not supported!");
    }

    // =========================================================================
    // JSON -> Pure value (to / toMany)
    // =========================================================================

    /**
     * Coerce a JSON value to the target generic type. Returns a raw value
     * suitable for {@code _E_ValueSpecification.wrap} — or null for JSON null
     * (empty in Pure).
     */
    private static Object jsonToPure(JsonValue json, GenericType targetGT, MetadataAccess resolver)
    {
        String targetPath = rawTypePath(targetGT);

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
                GenericType elementGT = typeArgument(targetGT, 0);
                List<ValueSpecification> elements = new ArrayList<>(array.values().size());
                for (JsonValue element : array.values())
                {
                    elements.add(_E_ValueSpecification.wrap(jsonToPure(element, elementGT, resolver), elementGT, null, resolver));
                }
                DynamicInstance list = new DynamicInstance(LIST_PATH);
                list.put("values", CollectionNatives.makeCollection(elements, resolver));
                if (targetGT instanceof GenericTypeValue gtv)
                {
                    list.setClassifierGenericType(gtv);
                }
                return list;
            }
            supportedTypeButWrongJson = true;
        }
        else if (MAP_PATH.equals(targetPath) && (PRIMITIVES + "String").equals(rawTypePath(typeArgument(targetGT, 0))))
        {
            if (json instanceof JsonValue.JsonObject object)
            {
                GenericType keyGT = typeArgument(targetGT, 0);
                GenericType valueGT = typeArgument(targetGT, 1);
                LinkedHashMap<ValueSpecification, ValueSpecification> map = new LinkedHashMap<>();
                for (Map.Entry<String, JsonValue> entry : object.fields().entrySet())
                {
                    map.put(
                            _E_ValueSpecification.wrap(entry.getKey(), keyGT, null, resolver),
                            _E_ValueSpecification.wrap(jsonToPure(entry.getValue(), valueGT, resolver), valueGT, null, resolver));
                }
                return new PureMap(map);
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
                return parseStrictDate(string.value());
            }
            supportedTypeButWrongJson = true;
        }
        else if ((PRIMITIVES + "DateTime").equals(targetPath))
        {
            if (json instanceof JsonValue.JsonString string)
            {
                return parseDateTime(string.value());
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
                return parsePureBoolean(string.value());
            }
            supportedTypeButWrongJson = true;
        }

        if (supportedTypeButWrongJson)
        {
            throw new RuntimeException("Variant of type '" + json.typeName() + "' cannot be converted to " + _GenericType.print(targetGT));
        }

        throw new RuntimeException(_GenericType.print(targetGT) + " is not managed yet!");
    }

    private static String rawTypePath(GenericType genericType)
    {
        if (genericType == null)
        {
            return null;
        }
        meta.pure.metamodel.type.Type rawType = _GenericType.type(genericType);
        return rawType instanceof meta.pure.metamodel.PackageableElement pe ? _PackageableElement.path(pe) : null;
    }

    private static GenericType typeArgument(GenericType genericType, int index)
    {
        if (genericType instanceof GenericTypeValue gtv
                && gtv._typeArguments() != null
                && gtv._typeArguments().size() > index)
        {
            return gtv._typeArguments().get(index);
        }
        return null;
    }

    private static String parseStrictDate(String text)
    {
        if (!text.matches("\\d{4}-\\d{1,2}-\\d{1,2}"))
        {
            throw new RuntimeException("StrictDate must be a calendar day, got: " + text);
        }
        return StringNatives.normalizePureDate(text);
    }

    private static String parseDateTime(String text)
    {
        if (text.indexOf('T') < 0)
        {
            throw new RuntimeException("DateTime must include time information, got: " + text);
        }
        return StringNatives.normalizePureDate(text);
    }

    private static boolean parsePureBoolean(String text)
    {
        if ("true".equals(text))
        {
            return true;
        }
        if ("false".equals(text))
        {
            return false;
        }
        throw new RuntimeException("Invalid Pure Boolean: '" + text + "'");
    }
}
