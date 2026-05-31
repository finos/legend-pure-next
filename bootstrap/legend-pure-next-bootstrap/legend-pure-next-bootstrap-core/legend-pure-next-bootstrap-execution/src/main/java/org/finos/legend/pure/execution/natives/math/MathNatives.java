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

package org.finos.legend.pure.execution.natives.math;

import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type;

import java.util.Map;

public class MathNatives
{
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        // plus
        natives.put("plus_Integer_1__Integer_1__Integer_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap((Long) _E_ValueSpecification.unwrap(args.get(0)) + (Long) _E_ValueSpecification.unwrap(args.get(1)),
                        genericType, multiplicity, resolver));

        natives.put("plus_Float_1__Float_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap((Double) _E_ValueSpecification.unwrap(args.get(0)) + (Double) _E_ValueSpecification.unwrap(args.get(1)),
                        genericType, multiplicity, resolver));

        natives.put("plus_Number_1__Number_1__Number_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                + ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        genericType, multiplicity, resolver));

        // minus
        natives.put("minus_Integer_1__Integer_1__Integer_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap((Long) _E_ValueSpecification.unwrap(args.get(0)) - (Long) _E_ValueSpecification.unwrap(args.get(1)),
                        genericType, multiplicity, resolver));

        natives.put("minus_Number_1__Number_1__Number_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                - ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        genericType, multiplicity, resolver));

        // times
        natives.put("times_Integer_1__Integer_1__Integer_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap((Long) _E_ValueSpecification.unwrap(args.get(0)) * (Long) _E_ValueSpecification.unwrap(args.get(1)),
                        genericType, multiplicity, resolver));

        // lessThan, greaterThan, lessThanEqual, greaterThanEqual
        natives.put("lessThan_Number_1__Number_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                < ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        genericType, multiplicity, resolver));

        natives.put("greaterThan_Number_1__Number_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                > ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        genericType, multiplicity, resolver));

        natives.put("lessThanEqual_Number_1__Number_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                <= ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        genericType, multiplicity, resolver));

        natives.put("greaterThanEqual_Number_1__Number_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                >= ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        genericType, multiplicity, resolver));

        // abs(Number[1]) : Number[1]
        natives.put("abs_Number_1__Number_1_", (args, eval, genericType, multiplicity) ->
        {
            Number n = (Number) _E_ValueSpecification.unwrap(args.get(0));
            if (_Type.subtypeOf(_GenericType.type(args.get(0)._genericType()), (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Integer"), resolver))
            {
                return _E_ValueSpecification.wrap(Math.abs(n.longValue()), genericType, multiplicity, resolver);
            }
            return _E_ValueSpecification.wrap(Math.abs(n.doubleValue()), genericType, multiplicity, resolver);
        });

        // divide(Number[1], Number[1]) : Float[1]
        natives.put("divide_Number_1__Number_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                / ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        genericType, multiplicity, resolver));

        // minus(Integer[1]) : Integer[1] — single-arg negate
        natives.put("minus_Integer_1__Integer_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(-((Number) _E_ValueSpecification.unwrap(args.get(0))).longValue(), genericType, multiplicity, resolver));

        // minus(Float[1]) : Float[1] — single-arg negate
        natives.put("minus_Float_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(-((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue(), genericType, multiplicity, resolver));

        // minus(Decimal[1]) : Decimal[1] — single-arg negate
        natives.put("minus_Decimal_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(asBigDecimal(args.get(0)).negate(), genericType, multiplicity, resolver));

        // minus(Number[1]) : Number[1] — single-arg negate
        natives.put("minus_Number_1__Number_1_", (args, eval, genericType, multiplicity) ->
        {
            Number n = (Number) _E_ValueSpecification.unwrap(args.get(0));
            if (_Type.subtypeOf(_GenericType.type(args.get(0)._genericType()), (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Integer"), resolver))
            {
                return _E_ValueSpecification.wrap(-n.longValue(), genericType, multiplicity, resolver);
            }
            return _E_ValueSpecification.wrap(-n.doubleValue(), genericType, multiplicity, resolver);
        });

        // abs(Integer[1]) : Integer[1]
        natives.put("abs_Integer_1__Integer_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Math.abs(((Long) _E_ValueSpecification.unwrap(args.get(0)))), genericType, multiplicity, resolver));

        // abs(Float[1]) : Float[1]
        natives.put("abs_Float_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Math.abs(((Double) _E_ValueSpecification.unwrap(args.get(0)))), genericType, multiplicity, resolver));

        // abs(Decimal[1]) : Decimal[1]
        natives.put("abs_Decimal_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(asBigDecimal(args.get(0)).abs(), genericType, multiplicity, resolver));

        // times(Float[1], Float[1]) : Float[1]
        natives.put("times_Float_1__Float_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap((Double) _E_ValueSpecification.unwrap(args.get(0)) * (Double) _E_ValueSpecification.unwrap(args.get(1)),
                        genericType, multiplicity, resolver));

        // times(Number[1], Number[1]) : Number[1]
        natives.put("times_Number_1__Number_1__Number_1_", (args, eval, genericType, multiplicity) ->
        {
            Number a = (Number) _E_ValueSpecification.unwrap(args.get(0));
            Number b = (Number) _E_ValueSpecification.unwrap(args.get(1));
            if (a instanceof Long la && b instanceof Long lb)
            {
                return _E_ValueSpecification.wrap(la * lb, genericType, multiplicity, resolver);
            }
            double d = java.math.BigDecimal.valueOf(a.doubleValue())
                    .multiply(java.math.BigDecimal.valueOf(b.doubleValue())).doubleValue();
            return _E_ValueSpecification.wrap(d, genericType, multiplicity, resolver);
        });

        // times(Decimal[1], Decimal[1]) : Decimal[1]
        natives.put("times_Decimal_1__Decimal_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(asBigDecimal(args.get(0)).multiply(asBigDecimal(args.get(1))),
                        genericType, multiplicity, resolver));

        // plus(Decimal[1], Decimal[1]) : Decimal[1]
        natives.put("plus_Decimal_1__Decimal_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(asBigDecimal(args.get(0)).add(asBigDecimal(args.get(1))),
                        genericType, multiplicity, resolver));

        // minus(Decimal[1], Decimal[1]) : Decimal[1]
        natives.put("minus_Decimal_1__Decimal_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(asBigDecimal(args.get(0)).subtract(asBigDecimal(args.get(1))),
                        genericType, multiplicity, resolver));

        // divide(Decimal[1], Decimal[1], Integer[1]) : Decimal[1]
        natives.put("divide_Decimal_1__Decimal_1__Integer_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
        {
            int scale = ((Number) _E_ValueSpecification.unwrap(args.get(2))).intValue();
            return _E_ValueSpecification.wrap(
                    asBigDecimal(args.get(0)).divide(asBigDecimal(args.get(1)), scale, java.math.RoundingMode.HALF_UP),
                    genericType, multiplicity, resolver);
        });

        // ceiling(Number[1]) : Integer[1]
        natives.put("ceiling_Number_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            double v = ((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue();
            return _E_ValueSpecification.wrap((long) Math.ceil(v), genericType, multiplicity, resolver);
        });

        // floor(Number[1]) : Integer[1]
        natives.put("floor_Number_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            double v = ((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue();
            return _E_ValueSpecification.wrap((long) Math.floor(v), genericType, multiplicity, resolver);
        });

        // round(Number[1]) : Integer[1]
        natives.put("round_Number_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            double v = ((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue();
            long result = java.math.BigDecimal.valueOf(v)
                    .setScale(0, java.math.RoundingMode.HALF_EVEN).longValue();
            return _E_ValueSpecification.wrap(result, genericType, multiplicity, resolver);
        });

        // round(Float[1], Integer[1]) : Float[1]
        natives.put("round_Float_1__Integer_1__Float_1_", (args, eval, genericType, multiplicity) ->
        {
            double v = ((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue();
            int scale = ((Number) _E_ValueSpecification.unwrap(args.get(1))).intValue();
            double result = java.math.BigDecimal.valueOf(v)
                    .setScale(scale, java.math.RoundingMode.HALF_UP).doubleValue();
            return _E_ValueSpecification.wrap(result, genericType, multiplicity, resolver);
        });

        // round(Decimal[1], Integer[1]) : Decimal[1]
        natives.put("round_Decimal_1__Integer_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
        {
            int scale = ((Number) _E_ValueSpecification.unwrap(args.get(1))).intValue();
            return _E_ValueSpecification.wrap(
                    asBigDecimal(args.get(0)).setScale(scale, java.math.RoundingMode.HALF_UP),
                    genericType, multiplicity, resolver);
        });

        // sqrt(Number[1]) : Float[1]
        natives.put("sqrt_Number_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Math.sqrt(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()),
                        genericType, multiplicity, resolver));

        // cbrt(Number[1]) : Float[1]
        natives.put("cbrt_Number_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Math.cbrt(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()),
                        genericType, multiplicity, resolver));

        // pow(Number[1], Number[1]) : Number[1]
        natives.put("pow_Number_1__Number_1__Number_1_", (args, eval, genericType, multiplicity) ->
        {
            double base = ((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue();
            double exp = ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue();
            return _E_ValueSpecification.wrap(Math.pow(base, exp), genericType, multiplicity, resolver);
        });

        // mod(Integer[1], Integer[1]) : Integer[1]
        natives.put("mod_Integer_1__Integer_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            long a = (Long) _E_ValueSpecification.unwrap(args.get(0));
            long b = (Long) _E_ValueSpecification.unwrap(args.get(1));
            long result = a % b;
            if (result < 0) { result += Math.abs(b); }
            return _E_ValueSpecification.wrap(result, genericType, multiplicity, resolver);
        });

        // rem(Number[1], Number[1]) : Number[1]
        natives.put("rem_Number_1__Number_1__Number_1_", (args, eval, genericType, multiplicity) ->
        {
            Number a = (Number) _E_ValueSpecification.unwrap(args.get(0));
            Number b = (Number) _E_ValueSpecification.unwrap(args.get(1));
            if (a instanceof Long la && b instanceof Long lb)
            {
                return _E_ValueSpecification.wrap(la % lb, genericType, multiplicity, resolver);
            }
            // Use BigDecimal to avoid float precision errors (e.g. 3.14 % 1.5)
            java.math.BigDecimal bdA = a instanceof java.math.BigDecimal bda ? bda : new java.math.BigDecimal(a.toString());
            java.math.BigDecimal bdB = b instanceof java.math.BigDecimal bdb ? bdb : new java.math.BigDecimal(b.toString());
            java.math.BigDecimal result = bdA.remainder(bdB);
            // Return as double if neither operand was a BigDecimal (pure Decimal literal)
            if (!(a instanceof java.math.BigDecimal) && !(b instanceof java.math.BigDecimal))
            {
                return _E_ValueSpecification.wrap(result.doubleValue(), genericType, multiplicity, resolver);
            }
            return _E_ValueSpecification.wrap(result.doubleValue(), genericType, multiplicity, resolver);
        });

        // sign(Number[1]) : Integer[1]
        natives.put("sign_Number_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            double v = ((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue();
            long s = v > 0 ? 1L : (v < 0 ? -1L : 0L);
            return _E_ValueSpecification.wrap(s, genericType, multiplicity, resolver);
        });

        // Trigonometric functions
        natives.put("sin_Number_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Math.sin(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()),
                        genericType, multiplicity, resolver));

        natives.put("cos_Number_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Math.cos(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()),
                        genericType, multiplicity, resolver));

        natives.put("tan_Number_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Math.tan(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()),
                        genericType, multiplicity, resolver));

        natives.put("cot_Number_1__Float_1_", (args, eval, genericType, multiplicity) ->
        {
            double v = ((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue();
            return _E_ValueSpecification.wrap(1.0 / Math.tan(v), genericType, multiplicity, resolver);
        });

        natives.put("asin_Number_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Math.asin(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()),
                        genericType, multiplicity, resolver));

        natives.put("acos_Number_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Math.acos(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()),
                        genericType, multiplicity, resolver));

        natives.put("atan_Number_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Math.atan(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()),
                        genericType, multiplicity, resolver));

        natives.put("atan2_Number_1__Number_1__Float_1_", (args, eval, genericType, multiplicity) ->
        {
            double y = ((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue();
            double x = ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue();
            return _E_ValueSpecification.wrap(Math.atan2(y, x), genericType, multiplicity, resolver);
        });

        // Exponential / logarithmic
        natives.put("log_Number_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Math.log(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()),
                        genericType, multiplicity, resolver));

        natives.put("log10_Number_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Math.log10(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()),
                        genericType, multiplicity, resolver));

        natives.put("exp_Number_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Math.exp(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()),
                        genericType, multiplicity, resolver));

    }

    /**
     * Coerce a Decimal-typed Pure value to {@link java.math.BigDecimal} without
     * routing through Double — preserves the exact decimal representation
     * carried by the input. Used by every Decimal arithmetic native so the
     * `0.1 + 0.2 == 0.3` family of identities holds.
     */
    private static java.math.BigDecimal asBigDecimal(ValueSpecification vs)
    {
        Object v = _E_ValueSpecification.unwrap(vs);
        if (v instanceof java.math.BigDecimal bd) return bd;
        if (v instanceof Number n) return new java.math.BigDecimal(n.toString());
        return new java.math.BigDecimal(v.toString());
    }
}
