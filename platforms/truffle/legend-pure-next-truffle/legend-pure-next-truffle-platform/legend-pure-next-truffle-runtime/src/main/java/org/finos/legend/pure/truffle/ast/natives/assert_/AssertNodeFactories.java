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

package org.finos.legend.pure.truffle.ast.natives.assert_;

import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;

/**
 * Registers specialized Truffle nodes for assert natives:
 * assert (with/without message), assertError, assertEqual, and assertFalse.
 */
public final class AssertNodeFactories
{
    private AssertNodeFactories()
    {
    }

    public static void registerAll(NativeNodeRegistry registry)
    {
        registry.register("assert_Boolean_1__Function_1__Boolean_1_",
                (args, gt, mul, fe) -> new AssertNode(args[0], args[1]));

        registry.register("assert_Boolean_1__Boolean_1_",
                (args, gt, mul, fe) -> new AssertNode(args[0], null));

        registry.register("assertError_Function_1__String_1__Integer_$0_1$__Integer_$0_1$__Boolean_1_",
                (args, gt, mul, fe) -> new AssertErrorNode(args[0], args[1],
                        args.length > 2 ? args[2] : null,
                        args.length > 3 ? args[3] : null));

        registry.register("assertEqual_Any_MANY__Any_MANY__Boolean_1_",
                (args, gt, mul, fe) -> new AssertEqualNode(args[0], args[1]));

        registry.register("assertFalse_Boolean_1__Boolean_1_",
                (args, gt, mul, fe) -> new AssertFalseNode(args[0], null));

        registry.register("assertFalse_Boolean_1__String_1__Boolean_1_",
                (args, gt, mul, fe) -> new AssertFalseNode(args[0], args[1]));
    }
}
