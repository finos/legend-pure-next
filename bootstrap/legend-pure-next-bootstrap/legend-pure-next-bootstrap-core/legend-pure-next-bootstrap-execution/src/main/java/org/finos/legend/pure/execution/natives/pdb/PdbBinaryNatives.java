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

package org.finos.legend.pure.execution.natives.pdb;

import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The byte-level primitives backing the self-hosted Pure PDB writer
 * ({@code meta::pure::compiler::pdb::binary}). Pure has no byte/char/bit access,
 * so exactly these are native; all encoding logic lives in Pure.
 */
public class PdbBinaryNatives
{
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        // intToLeBytes(value, width): little-endian two's-complement bytes (0..255).
        natives.put("intToLeBytes_Integer_1__Integer_1__Integer_MANY_", (args, eval, genericType, multiplicity) ->
        {
            long value = ((Number) _E_ValueSpecification.unwrap(args.get(0))).longValue();
            int width = ((Number) _E_ValueSpecification.unwrap(args.get(1))).intValue();
            List<Long> bytes = new ArrayList<>(width);
            long v = value;
            for (int i = 0; i < width; i++)
            {
                bytes.add(v & 0xFFL);
                v >>= 8;
            }
            return wrapIntegerList(bytes, resolver);
        });

        // floatToLeBytes(value): IEEE-754 double bits, little-endian (0..255) —
        // exactly what FlatBufferBuilder.addDouble writes for an fbs `double`.
        natives.put("floatToLeBytes_Float_1__Integer_MANY_", (args, eval, genericType, multiplicity) ->
        {
            double value = ((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue();
            long bits = Double.doubleToLongBits(value);
            List<Long> bytes = new ArrayList<>(8);
            for (int i = 0; i < 8; i++)
            {
                bytes.add((bits >>> (8 * i)) & 0xFFL);
            }
            return wrapIntegerList(bytes, resolver);
        });

        // stringToUtf8Bytes(s): UTF-8 bytes (0..255).
        natives.put("stringToUtf8Bytes_String_1__Integer_MANY_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            List<Long> bytes = new ArrayList<>(utf8.length);
            for (byte b : utf8)
            {
                bytes.add((long) (b & 0xFF));
            }
            return wrapIntegerList(bytes, resolver);
        });

        // utf8BytesToString(bytes): decode UTF-8 bytes (0..255) — inverse of
        // stringToUtf8Bytes; backs the self-hosted PDB reader.
        natives.put("utf8BytesToString_Integer_MANY__String_1_", (args, eval, genericType, multiplicity) ->
        {
            List<Long> values = unwrapIntegerList(args.get(0));
            byte[] bytes = new byte[values.size()];
            for (int i = 0; i < values.size(); i++)
            {
                bytes[i] = (byte) (values.get(i) & 0xFFL);
            }
            return _E_ValueSpecification.wrap(new String(bytes, StandardCharsets.UTF_8), null, null, resolver);
        });

        // leBytesToFloat(bytes): 8 little-endian bytes -> IEEE-754 double —
        // inverse of floatToLeBytes; backs the self-hosted PDB reader.
        natives.put("leBytesToFloat_Integer_MANY__Float_1_", (args, eval, genericType, multiplicity) ->
        {
            List<Long> values = unwrapIntegerList(args.get(0));
            if (values.size() != 8)
            {
                throw new IllegalArgumentException("leBytesToFloat expects exactly 8 bytes, got " + values.size());
            }
            long bits = 0L;
            for (int i = 7; i >= 0; i--)
            {
                bits = (bits << 8) | (values.get(i) & 0xFFL);
            }
            return _E_ValueSpecification.wrap(Double.longBitsToDouble(bits), null, null, resolver);
        });
    }

    private static List<Long> unwrapIntegerList(ValueSpecification arg)
    {
        Object unwrapped = _E_ValueSpecification.unwrap(arg);
        List<Long> values = new ArrayList<>();
        if (unwrapped instanceof List<?> list)
        {
            for (Object o : list)
            {
                values.add(((Number) (o instanceof ValueSpecification vs ? _E_ValueSpecification.unwrap(vs) : o)).longValue());
            }
        }
        else if (unwrapped != null)
        {
            values.add(((Number) unwrapped).longValue());
        }
        return values;
    }

    private static ValueSpecification wrapIntegerList(List<Long> values, MetadataAccess resolver)
    {
        List<ValueSpecification> result = new ArrayList<>(values.size());
        for (Long v : values)
        {
            result.add(_E_ValueSpecification.wrap(v, null, null, resolver));
        }
        meta.pure.metamodel.type.generics.GenericType gt = result.isEmpty() ? null : result.get(0)._genericType();
        return new meta.pure.metamodel.valuespecification.CollectionImpl(resolver)
                ._values(org.eclipse.collections.api.factory.Lists.mutable.withAll(result))
                ._genericType(gt)
                ._multiplicity(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity.concreteMultiplicity(values.size(), values.size(), resolver));
    }
}
