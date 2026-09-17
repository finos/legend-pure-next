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

package org.finos.legend.pure.truffle.execution.natives.pdb;

import org.finos.legend.pure.truffle.execution.natives.NativeRegistry;

/**
 * Registers specialized Truffle nodes for the meta::pure::functions::binary
 * byte-level primitives backing binary serialization (the self-hosted PDB
 * writer): intToLeBytes, floatToLeBytes, and stringToUtf8Bytes.
 */
public final class BinaryNodeFactories
{
    private BinaryNodeFactories()
    {
    }

    public static void registerAll(NativeRegistry registry)
    {
        registry.register("intToLeBytes_Integer_1__Integer_1__Integer_MANY_",
                (args, gt, mul, fe) -> new IntToLeBytesNode(args[0], args[1]));

        registry.register("floatToLeBytes_Float_1__Integer_MANY_",
                (args, gt, mul, fe) -> new FloatToLeBytesNode(args[0]));

        registry.register("stringToUtf8Bytes_String_1__Integer_MANY_",
                (args, gt, mul, fe) -> new StringToUtf8BytesNode(args[0]));

        registry.register("utf8BytesToString_Integer_MANY__String_1_",
                (args, gt, mul, fe) -> new Utf8BytesToStringNode(args[0]));

        registry.register("leBytesToFloat_Integer_MANY__Float_1_",
                (args, gt, mul, fe) -> new LeBytesToFloatNode(args[0]));
    }
}
