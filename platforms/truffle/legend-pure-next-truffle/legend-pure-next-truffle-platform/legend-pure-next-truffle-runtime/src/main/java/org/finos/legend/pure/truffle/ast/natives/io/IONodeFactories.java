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

package org.finos.legend.pure.truffle.ast.natives.io;

import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;

/**
 * Registers specialized Truffle nodes for IO natives:
 * print, println, readFile, and directoryTree.
 */
public final class IONodeFactories
{
    private IONodeFactories()
    {
    }

    public static void registerAll(NativeNodeRegistry registry)
    {
        // print returns String[1] now (writes AND returns the formatted
        // value) so println can compose without a separate formatter native.
        registry.register("print_Any_MANY__Integer_1__String_1_",
                (args, gt, mul, fe) -> new PrintNode(args[0], args[1]));

        // println is a Pure function (`print(v) + print('\n')`) — no
        // Truffle-native override; the bytecode from the Pure body works on
        // both backends.

        // withSilencedPrint pushes a thread-local flag that suppresses
        // print's stdout side effect for the duration of the closure.
        // Used by test runners to silence individual test bodies while
        // keeping the progress UI visible.
        registry.register("withSilencedPrint_Function_1__T_m_",
                (args, gt, mul, fe) -> new WithSilencedPrintNode(args[0]));

        registry.register("readFile_String_1__String_1_",
                (args, gt, mul, fe) -> new ReadFileNode(args[0]));

        registry.register("directoryTree_String_1__String_MANY_",
                (args, gt, mul, fe) -> new DirectoryTreeNode(args[0]));
    }
}
