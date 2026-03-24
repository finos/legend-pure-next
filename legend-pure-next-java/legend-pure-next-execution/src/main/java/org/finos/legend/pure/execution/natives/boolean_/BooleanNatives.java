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

package org.finos.legend.pure.execution.natives.boolean_;

import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.NativeRepository;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.util.Map;

public class BooleanNatives
{
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        natives.put("equal_Any_MANY__Any_MANY__Boolean_1_", (args, eval, fe) ->
        {
            Object a = _E_ValueSpecification.unwrap(args.get(0));
            Object b = _E_ValueSpecification.unwrap(args.get(1));
            return _E_ValueSpecification.wrap(NativeRepository.pureEquals(a, b), fe._genericType(), fe._multiplicity());
        });

        natives.put("not_Boolean_1__Boolean_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap(!((Boolean) _E_ValueSpecification.unwrap(args.get(0))), fe._genericType(), fe._multiplicity()));

        // and/or — short-circuit: registered as lazy natives
        lazyNatives.put("and_Boolean_1__Boolean_1__Boolean_1_", (fe, eval) ->
        {
            boolean first = (Boolean) _E_ValueSpecification.unwrap(eval.evaluate(fe._parametersValues().get(0)));
            if (!first)
            {
                return _E_ValueSpecification.wrap(false, fe._genericType(), fe._multiplicity());
            }
            boolean second = (Boolean) _E_ValueSpecification.unwrap(eval.evaluate(fe._parametersValues().get(1)));
            return _E_ValueSpecification.wrap(second, fe._genericType(), fe._multiplicity());
        });

        lazyNatives.put("or_Boolean_1__Boolean_1__Boolean_1_", (fe, eval) ->
        {
            boolean first = (Boolean) _E_ValueSpecification.unwrap(eval.evaluate(fe._parametersValues().get(0)));
            if (first)
            {
                return _E_ValueSpecification.wrap(true, fe._genericType(), fe._multiplicity());
            }
            boolean second = (Boolean) _E_ValueSpecification.unwrap(eval.evaluate(fe._parametersValues().get(1)));
            return _E_ValueSpecification.wrap(second, fe._genericType(), fe._multiplicity());
        });

        // is — identity comparison
        natives.put("is_Any_1__Any_1__Boolean_1_", (args, eval, fe) ->
        {
            Object a = _E_ValueSpecification.unwrap(args.get(0));
            Object b = _E_ValueSpecification.unwrap(args.get(1));
            if (a instanceof DynamicInstance || b instanceof DynamicInstance)
            {
                return _E_ValueSpecification.wrap(a == b, fe._genericType(), fe._multiplicity());
            }
            return _E_ValueSpecification.wrap(NativeRepository.pureEquals(a, b), fe._genericType(), fe._multiplicity());
        });
    }
}
