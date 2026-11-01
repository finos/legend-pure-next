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
package org.finos.legend.pure.truffle.extensions.relation;

import org.finos.legend.pure.truffle.execution.natives.NativeRegistry;

/**
 * The relation module's natives on Truffle, keyed by full id. The runtime checks them against what the module
 * declares when it is built: one missing, or one the module does not declare, fails it.
 */
final class RelationNatives
{
    private RelationNatives()
    {
    }

    static void registerAll(NativeRegistry registry)
    {
        registry.register("platform::pure::functions::relation::size_Relation_1__Integer_1_", (args, gt, mul, fe) -> new SizeNode(args[0]));
    }
}
