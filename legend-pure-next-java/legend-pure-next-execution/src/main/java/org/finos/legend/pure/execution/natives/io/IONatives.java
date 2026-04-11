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

package org.finos.legend.pure.execution.natives.io;

import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution.PureValuePrinter;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.util.Map;

public class IONatives
{
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        // print(Any[*], Integer[1]) : Nil[0]
        natives.put("print_Any_MANY__Integer_1__Nil_0_", (args, eval, genericType, multiplicity) ->
        {
            Object val = _E_ValueSpecification.unwrap(args.get(0));
            System.out.print(PureValuePrinter.print(val));
            return _E_ValueSpecification.wrap(null, genericType, multiplicity, resolver);
        });

        // println(Any[*]) : Nil[0]
        natives.put("println_Any_MANY__Nil_0_", (args, eval, genericType, multiplicity) ->
        {
            Object val = _E_ValueSpecification.unwrap(args.get(0));
            System.out.println(PureValuePrinter.print(val));
            return _E_ValueSpecification.wrap(null, genericType, multiplicity, resolver);
        });
    }
}
