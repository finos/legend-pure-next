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

package org.finos.legend.pure.truffle.ast.natives.string;

import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;

public final class StringNodeFactories
{
    private StringNodeFactories()
    {
    }

    public static void registerAll(NativeNodeRegistry registry)
    {
        registry.register("plus_String_1__String_1__String_1_",
                (args, gt, mul, fe) -> new PlusStringNode(args[0], args[1]));

        registry.register("length_String_1__Integer_1_",
                (args, gt, mul, fe) -> new LengthStringNode(args[0]));

        registry.register("substring_String_1__Integer_1__String_1_",
                (args, gt, mul, fe) -> new SubstringNode(args[0], args[1], null,
                        "substring_String_1__Integer_1__String_1_"));
        registry.register("substring_String_1__Integer_1__Integer_1__String_1_",
                (args, gt, mul, fe) -> new SubstringNode(args[0], args[1], args[2],
                        "substring_String_1__Integer_1__Integer_1__String_1_"));

        registry.register("startsWith_String_1__String_1__Boolean_1_",
                (args, gt, mul, fe) -> new StartsWithNode(args[0], args[1]));

        registry.register("endsWith_String_1__String_1__Boolean_1_",
                (args, gt, mul, fe) -> new EndsWithNode(args[0], args[1]));

        registry.register("contains_String_1__String_1__Boolean_1_",
                (args, gt, mul, fe) -> new ContainsStringNode(args[0], args[1]));

        // toString, joinStrings, format, toRepresentation stay on the bridge —
        // they have complex DynamicInstance/Date/Float formatting that the
        // simple nodes don't replicate correctly.

        registry.register("split_String_1__String_1__String_MANY_",
                (args, gt, mul, fe) -> new SplitNode(args[0], args[1], gt, mul));

        registry.register("toUpper_String_1__String_1_",
                (args, gt, mul, fe) -> new ToUpperNode(args[0]));

        registry.register("toLower_String_1__String_1_",
                (args, gt, mul, fe) -> new ToLowerNode(args[0]));

        registry.register("trim_String_1__String_1_",
                (args, gt, mul, fe) -> new TrimNode(args[0]));

        registry.register("ltrim_String_1__String_1_",
                (args, gt, mul, fe) -> new LTrimNode(args[0]));

        registry.register("rtrim_String_1__String_1_",
                (args, gt, mul, fe) -> new RTrimNode(args[0]));

        registry.register("indexOf_String_1__String_1__Integer_1_",
                (args, gt, mul, fe) -> new IndexOfNode(args[0], args[1], null,
                        "indexOf_String_1__String_1__Integer_1_"));
        registry.register("indexOf_String_1__String_1__Integer_1__Integer_1_",
                (args, gt, mul, fe) -> new IndexOfNode(args[0], args[1], args[2],
                        "indexOf_String_1__String_1__Integer_1__Integer_1_"));

        registry.register("replace_String_1__String_1__String_1__String_1_",
                (args, gt, mul, fe) -> new ReplaceNode(args[0], args[1], args[2]));

        registry.register("reverseString_String_1__String_1_",
                (args, gt, mul, fe) -> new ReverseStringNode(args[0]));

        registry.register("parseInteger_String_1__Integer_1_",
                (args, gt, mul, fe) -> new ParseIntegerNode(args[0]));

        registry.register("parseFloat_String_1__Float_1_",
                (args, gt, mul, fe) -> new ParseFloatNode(args[0]));

        registry.register("parseBoolean_String_1__Boolean_1_",
                (args, gt, mul, fe) -> new ParseBooleanNode(args[0]));

        registry.register("parseDate_String_1__Date_1_",
                (args, gt, mul, fe) -> new ParseDateNode(args[0], gt, mul));

        registry.register("parseDecimal_String_1__Decimal_1_",
                (args, gt, mul, fe) -> new ParseDecimalNode(args[0], null, null));

        registry.register("parseDecimal_String_1__Integer_1__Integer_1__Decimal_1_",
                (args, gt, mul, fe) -> new ParseDecimalNode(args[0], args[1], args[2]));
    }
}
