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

package org.finos.legend.pure.truffle.execution.natives.variant;

import org.finos.legend.pure.truffle.execution.natives.NativeRegistry;

/**
 * Registers specialized Truffle nodes for the Variant natives
 * ({@code meta::pure::functions::variant::convert}).
 */
public final class VariantNodeFactories
{
    private VariantNodeFactories()
    {
    }

    public static void registerAll(NativeRegistry registry)
    {
        registry.register("fromJson_String_1__Variant_1_",
                (args, gt, mul, fe) -> new FromJsonNode(args[0]));
        registry.register("toJson_Variant_1__String_1_",
                (args, gt, mul, fe) -> new ToJsonNode(args[0]));
        registry.register("toVariant_Any_MANY__Variant_1_",
                (args, gt, mul, fe) -> new ToVariantNode(args[0]));
        registry.register("to_Variant_$0_1$__GenericTypeAndMultiplicityHolder_1__T_$0_1$_",
                (args, gt, mul, fe) -> new ToNode(args[0], args[1]));
        registry.register("toMany_Variant_$0_1$__GenericTypeAndMultiplicityHolder_1__T_MANY_",
                (args, gt, mul, fe) -> new ToManyNode(args[0], args[1]));
    }
}
