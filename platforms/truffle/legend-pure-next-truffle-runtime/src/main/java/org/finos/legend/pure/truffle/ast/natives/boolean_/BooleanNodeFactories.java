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

package org.finos.legend.pure.truffle.ast.natives.boolean_;

import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;

/**
 * Registers specialized Truffle nodes for boolean natives. Short-circuit
 * {@code and}/{@code or} route through {@code LazyNativeCallNode}
 * (unchanged in Phase 1); {@code equal}, {@code is}, and {@code compare}
 * are now specialized.
 */
public final class BooleanNodeFactories
{
    private BooleanNodeFactories()
    {
    }

    public static void registerAll(NativeNodeRegistry registry)
    {
        registry.register("not_Boolean_1__Boolean_1_",
                (args, gt, mul, fe) -> new NotBooleanNode(args[0]));
        registry.register("equal_Any_MANY__Any_MANY__Boolean_1_",
                (args, gt, mul, fe) -> new EqualNode(args[0], args[1]));
        registry.register("is_Any_1__Any_1__Boolean_1_",
                (args, gt, mul, fe) -> new IsNode(args[0], args[1]));
        registry.register("compare_T_1__T_1__Integer_1_",
                (args, gt, mul, fe) -> new CompareNode(args[0], args[1], gt, mul));
    }
}
