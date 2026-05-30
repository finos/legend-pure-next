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

package org.finos.legend.pure.truffle.extension.javacompile;

import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.finos.legend.pure.truffle.builder.TruffleNativesExtension;

/**
 * Registers specialized Truffle nodes for the Pure→Java translator's
 * compile-and-execute native. Discovered via the {@link TruffleNativesExtension}
 * SPI (META-INF/services entry alongside this class).
 */
public final class JavaCompileNodeFactories implements TruffleNativesExtension
{
    @Override
    public void registerAll(NativeNodeRegistry registry)
    {
        registry.register("compileAndExecute_String_1__String_1__String_1__Any_MANY__Any_MANY_",
                (args, gt, mul, fe) -> new CompileAndExecuteNode(args[0], args[1], args[2], args[3]));
    }
}
