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
 * Registers the specialized Truffle nodes for
 * {@code meta::pure::functions::binary} — the Binary primitive and the
 * byte-level operations backing the self-hosted PDB reader and writer.
 */
public final class BinaryNodeFactories
{
    private BinaryNodeFactories()
    {
    }

    public static void registerAll(NativeRegistry registry)
    {
        registry.register("binarySize_Binary_1__Integer_1_",
                (args, gt, mul, fe) -> new BinaryNodes.Size(args[0]));

        registry.register("binaryAt_Binary_1__Integer_1__Byte_1_",
                (args, gt, mul, fe) -> new BinaryNodes.At(args[0], args[1]));

        registry.register("binaryLeUInt_Binary_1__Integer_1__Integer_1__Integer_1_",
                (args, gt, mul, fe) -> new BinaryNodes.LeInt(args[0], args[1], args[2], false));

        registry.register("binaryLeInt_Binary_1__Integer_1__Integer_1__Integer_1_",
                (args, gt, mul, fe) -> new BinaryNodes.LeInt(args[0], args[1], args[2], true));

        registry.register("binaryUtf8_Binary_1__Integer_1__Integer_1__String_1_",
                (args, gt, mul, fe) -> new BinaryNodes.Utf8Range(args[0], args[1], args[2]));

        registry.register("binarySlice_Binary_1__Integer_1__Integer_1__Binary_1_",
                (args, gt, mul, fe) -> new BinaryNodes.Slice(args[0], args[1], args[2]));

        registry.register("binaryConcat_Binary_MANY__Binary_1_",
                (args, gt, mul, fe) -> new BinaryNodes.Concat(args[0]));

        registry.register("binaryZeros_Integer_1__Binary_1_",
                (args, gt, mul, fe) -> new BinaryNodes.Zeros(args[0]));

        registry.register("binaryEquals_Binary_1__Binary_1__Boolean_1_",
                (args, gt, mul, fe) -> new BinaryNodes.Equals(args[0], args[1]));

        registry.register("binaryToHex_Binary_1__String_1_",
                (args, gt, mul, fe) -> new BinaryNodes.ToHex(args[0]));

        registry.register("intToLeBytes_Integer_1__Integer_1__Binary_1_",
                (args, gt, mul, fe) -> new BinaryNodes.IntToLe(args[0], args[1]));

        registry.register("floatToLeBytes_Float_1__Binary_1_",
                (args, gt, mul, fe) -> new BinaryNodes.FloatToLe(args[0]));

        registry.register("stringToUtf8Bytes_String_1__Binary_1_",
                (args, gt, mul, fe) -> new BinaryNodes.StringToUtf8(args[0]));

        registry.register("utf8BytesToString_Binary_1__String_1_",
                (args, gt, mul, fe) -> new BinaryNodes.Utf8ToString(args[0]));

        registry.register("leBytesToFloat_Binary_1__Float_1_",
                (args, gt, mul, fe) -> new BinaryNodes.LeBytesToFloat(args[0]));
    }
}
