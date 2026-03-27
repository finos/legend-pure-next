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

        // minus(Decimal[1]) : Decimal[1] — single-arg negate
        natives.put("minus_Decimal_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(-((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue(), genericType, multiplicity, resolver));

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

        // abs(Decimal[1]) : Decimal[1] — Decimal represented as Double
        natives.put("abs_Decimal_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Math.abs(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()), genericType, multiplicity, resolver));

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
        {
            double a = ((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue();
            double b = ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue();
            double d = java.math.BigDecimal.valueOf(a).multiply(java.math.BigDecimal.valueOf(b)).doubleValue();
            return _E_ValueSpecification.wrap(d, genericType, multiplicity, resolver);
        });
        // plus(Decimal[1], Decimal[1]) : Decimal[1]
        natives.put("plus_Decimal_1__Decimal_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                + ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        genericType, multiplicity, resolver));

        // minus(Decimal[1], Decimal[1]) : Decimal[1]
        natives.put("minus_Decimal_1__Decimal_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                - ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        genericType, multiplicity, resolver));

        // divide(Decimal[1], Decimal[1], Integer[1]) : Decimal[1]
        natives.put("divide_Decimal_1__Decimal_1__Integer_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
        {
            double a = ((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue();
            double b = ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue();
            int scale = ((Number) _E_ValueSpecification.unwrap(args.get(2))).intValue();
            double d = java.math.BigDecimal.valueOf(a)
                    .divide(java.math.BigDecimal.valueOf(b), scale, java.math.RoundingMode.HALF_UP)
                    .doubleValue();
            return _E_ValueSpecification.wrap(d, genericType, multiplicity, resolver);
        });

    }
}
