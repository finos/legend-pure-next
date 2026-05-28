// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.extension.typescriptcompile;

import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.finos.legend.pure.truffle.builder.TruffleNativesExtension;

/**
 * Registers the {@code meta::external::language::typescript::compile} and
 * {@code meta::external::language::typescript::execute} natives. Discovered
 * via the {@link TruffleNativesExtension} SPI (META-INF/services entry
 * alongside this class).
 *
 * <p>{@code compileAndExecute} (the one-shot convenience) is implemented in
 * Pure as a wrapper around {@code compile()->execute()}; not registered
 * here as a native.</p>
 */
public final class TypeScriptCompileNodeFactories implements TruffleNativesExtension
{
    @Override
    public void registerAll(NativeNodeRegistry registry)
    {
        registry.register("compile_String_1__Any_1_",
                (args, gt, mul, fe) -> new CompileNode(args[0]));
        registry.register("execute_Any_1__String_1__Any_MANY__GenericType_$0_1$__Multiplicity_$0_1$__Any_$0_1$__Any_MANY_",
                (args, gt, mul, fe) -> new ExecuteNode(args[0], args[1], args[2], args[3], args[4], args[5]));
    }
}
