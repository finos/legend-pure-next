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
        natives.put("plus_Integer_1__Integer_1__Integer_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap((Long) _E_ValueSpecification.unwrap(args.get(0)) + (Long) _E_ValueSpecification.unwrap(args.get(1)),
                        fe._genericType(), fe._multiplicity()));

        natives.put("plus_Float_1__Float_1__Float_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap((Double) _E_ValueSpecification.unwrap(args.get(0)) + (Double) _E_ValueSpecification.unwrap(args.get(1)),
                        fe._genericType(), fe._multiplicity()));

        natives.put("plus_Number_1__Number_1__Number_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                + ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        fe._genericType(), fe._multiplicity()));

        // minus
        natives.put("minus_Integer_1__Integer_1__Integer_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap((Long) _E_ValueSpecification.unwrap(args.get(0)) - (Long) _E_ValueSpecification.unwrap(args.get(1)),
                        fe._genericType(), fe._multiplicity()));

        natives.put("minus_Number_1__Number_1__Number_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                - ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        fe._genericType(), fe._multiplicity()));

        // times
        natives.put("times_Integer_1__Integer_1__Integer_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap((Long) _E_ValueSpecification.unwrap(args.get(0)) * (Long) _E_ValueSpecification.unwrap(args.get(1)),
                        fe._genericType(), fe._multiplicity()));

        // lessThan, greaterThan, lessThanEqual, greaterThanEqual
        natives.put("lessThan_Number_1__Number_1__Boolean_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                < ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        fe._genericType(), fe._multiplicity()));

        natives.put("greaterThan_Number_1__Number_1__Boolean_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                > ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        fe._genericType(), fe._multiplicity()));

        natives.put("lessThanEqual_Number_1__Number_1__Boolean_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                <= ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        fe._genericType(), fe._multiplicity()));

        natives.put("greaterThanEqual_Number_1__Number_1__Boolean_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                >= ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        fe._genericType(), fe._multiplicity()));

        // abs(Number[1]) : Number[1]
        natives.put("abs_Number_1__Number_1_", (args, eval, fe) ->
        {
            Number n = (Number) _E_ValueSpecification.unwrap(args.get(0));
            if (_Type.subtypeOf(_GenericType.type(args.get(0)._genericType()), (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Integer"), resolver))
            {
                return _E_ValueSpecification.wrap(Math.abs(n.longValue()), fe._genericType(), fe._multiplicity());
            }
            return _E_ValueSpecification.wrap(Math.abs(n.doubleValue()), fe._genericType(), fe._multiplicity());
        });

        // divide(Number[1], Number[1]) : Float[1]
        natives.put("divide_Number_1__Number_1__Float_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap(((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue()
                                / ((Number) _E_ValueSpecification.unwrap(args.get(1))).doubleValue(),
                        fe._genericType(), fe._multiplicity()));

        // minus(Number[1]) : Number[1] — single-arg negate
        natives.put("minus_Number_1__Number_1_", (args, eval, fe) ->
        {
            Number n = (Number) _E_ValueSpecification.unwrap(args.get(0));
            if (_Type.subtypeOf(_GenericType.type(args.get(0)._genericType()), (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Integer"), resolver))
            {
                return _E_ValueSpecification.wrap(-n.longValue(), fe._genericType(), fe._multiplicity());
            }
            return _E_ValueSpecification.wrap(-n.doubleValue(), fe._genericType(), fe._multiplicity());
        });
    }
}
