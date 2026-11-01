// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.execution.natives.lang;

import platform.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.natives.NativeRegistry;
import org.finos.legend.pure.execution.natives.NativeRegistry.LazyNativeImpl;
import org.finos.legend.pure.execution.natives.NativeRegistry.NativeImpl;
import org.finos.legend.pure.execution.natives.NativeRegistry.PureAssertionError;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.util.List;
import java.util.Map;

public class AssertNatives
{
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        // assert(Boolean[1], Function<{->String[1]}>[1]) : Boolean[1]
        natives.put("platform::pure::functions::asserts::assert_Boolean_1__Function_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
        {
            boolean condition = (Boolean) _E_ValueSpecification.unwrap(args.get(0));
            if (!condition)
            {
                ValueSpecification msgResult = eval.executeFunction(args.get(1), List.of());
                String message = (String) _E_ValueSpecification.unwrap(msgResult);
                throw new PureAssertionError(message);
            }
            return _E_ValueSpecification.wrap(true, genericType, multiplicity, resolver);
        });

        // assert(Boolean[1]) : Boolean[1]

        // assertError(Function<{->Any[*]}>[1], String[1], Integer[0..1], Integer[0..1]) : Boolean[1]
        natives.put("platform::pure::functions::asserts::assertError_Function_1__String_1__Integer_$0_1$__Integer_$0_1$__Boolean_1_", (args, eval, genericType, multiplicity) ->
        {
            String expectedMessage = (String) _E_ValueSpecification.unwrap(args.get(1));
            try
            {
                eval.executeFunction(args.get(0), List.of());
            }
            catch (Exception e)
            {
                String actualMessage = e.getMessage();
                if (actualMessage == null || !actualMessage.contains(expectedMessage))
                {
                    throw new PureAssertionError(
                            "Expected error message containing: '" + expectedMessage
                                    + "' but got: '" + actualMessage + "'");
                }
                return _E_ValueSpecification.wrap(true, genericType, multiplicity, resolver);
            }
            // outside the try: this failure is not the error the function was expected to raise
            throw new PureAssertionError("Expected error with message containing: '" + expectedMessage + "' but no error was thrown");
        });

        // assertEqual(Any[*], Any[*]) : Boolean[1]

        // assertFalse(Boolean[1]) : Boolean[1]

        // assertFalse(Boolean[1], String[1]) : Boolean[1]
    }
}
