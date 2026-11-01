// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.truffle.execution.natives.string;

import org.finos.legend.pure.truffle.execution.natives.NativeRegistry;

public final class StringNodeFactories
{
    private StringNodeFactories()
    {
    }

    public static void registerAll(NativeRegistry registry)
    {
        registry.register("platform::pure::functions::string::plus_String_1__String_1__String_1_",
                (args, gt, mul, fe) -> new PlusStringNode(args[0], args[1]));

        registry.register("platform::pure::functions::string::length_String_1__Integer_1_",
                (args, gt, mul, fe) -> new LengthStringNode(args[0]));

        registry.register("platform::pure::functions::string::substring_String_1__Integer_1__String_1_",
                (args, gt, mul, fe) -> new SubstringNode(args[0], args[1], null,
                        "substring_String_1__Integer_1__String_1_"));
        registry.register("platform::pure::functions::string::substring_String_1__Integer_1__Integer_1__String_1_",
                (args, gt, mul, fe) -> new SubstringNode(args[0], args[1], args[2],
                        "substring_String_1__Integer_1__Integer_1__String_1_"));

        registry.register("platform::pure::functions::string::startsWith_String_1__String_1__Boolean_1_",
                (args, gt, mul, fe) -> new StartsWithNode(args[0], args[1]));

        registry.register("platform::pure::functions::string::endsWith_String_1__String_1__Boolean_1_",
                (args, gt, mul, fe) -> new EndsWithNode(args[0], args[1]));

        registry.register("platform::pure::functions::string::contains_String_1__String_1__Boolean_1_",
                (args, gt, mul, fe) -> new ContainsStringNode(args[0], args[1]));

        registry.register("platform::pure::functions::string::toString_Any_1__String_1_",
                (args, gt, mul, fe) -> new ToStringNode(args[0], gt, mul));
        registry.register("platform::pure::functions::string::joinStrings_String_MANY__String_1__String_1__String_1__String_1_",
                (args, gt, mul, fe) -> new JoinStringsNode(args[0], args[1], args[2], args[3], gt, mul));
        registry.register("platform::pure::functions::string::format_String_1__Any_MANY__String_1_",
                (args, gt, mul, fe) -> new FormatNode(args[0], args[1], gt, mul));

        registry.register("platform::pure::functions::string::split_String_1__String_1__String_MANY_",
                (args, gt, mul, fe) -> new SplitNode(args[0], args[1], gt, mul));
        registry.register("platform::pure::functions::string::chunk_String_1__Integer_1__String_MANY_",
                (args, gt, mul, fe) -> new ChunkNode(args[0], args[1]));

        registry.register("platform::pure::functions::string::toUpper_String_1__String_1_",
                (args, gt, mul, fe) -> new ToUpperNode(args[0]));

        registry.register("platform::pure::functions::string::toLower_String_1__String_1_",
                (args, gt, mul, fe) -> new ToLowerNode(args[0]));

        registry.register("platform::pure::functions::string::trim_String_1__String_1_",
                (args, gt, mul, fe) -> new TrimNode(args[0]));

        registry.register("platform::pure::functions::string::ltrim_String_1__String_1_",
                (args, gt, mul, fe) -> new LTrimNode(args[0]));

        registry.register("platform::pure::functions::string::rtrim_String_1__String_1_",
                (args, gt, mul, fe) -> new RTrimNode(args[0]));

        registry.register("platform::pure::functions::string::indexOf_String_1__String_1__Integer_1_",
                (args, gt, mul, fe) -> new IndexOfNode(args[0], args[1], null,
                        "indexOf_String_1__String_1__Integer_1_"));
        registry.register("platform::pure::functions::string::indexOf_String_1__String_1__Integer_1__Integer_1_",
                (args, gt, mul, fe) -> new IndexOfNode(args[0], args[1], args[2],
                        "indexOf_String_1__String_1__Integer_1__Integer_1_"));
        registry.register("platform::pure::functions::string::lastIndexOf_String_1__String_1__Integer_1_",
                (args, gt, mul, fe) -> new LastIndexOfNode(args[0], args[1],
                        "lastIndexOf_String_1__String_1__Integer_1_"));

        registry.register("platform::pure::functions::string::replace_String_1__String_1__String_1__String_1_",
                (args, gt, mul, fe) -> new ReplaceNode(args[0], args[1], args[2]));

        // Regex natives backed by java.util.regex.Pattern. The Pure spec
        // defines overloads with default-flag bodies that delegate to the
        // 5-arg variants registered here.
        registry.register("platform::pure::functions::string::matches_String_1__String_1__Boolean_1_",
                (args, gt, mul, fe) -> new MatchesNode(args[0], args[1]));
        registry.register("platform::pure::functions::string::regexpLike_String_1__String_1__RegexpParameter_$1_MANY$__Boolean_1_",
                (args, gt, mul, fe) -> new RegexpLikeNode(args[0], args[1], args[2]));
        registry.register("platform::pure::functions::string::regexpCount_String_1__String_1__RegexpParameter_$1_MANY$__Integer_1_",
                (args, gt, mul, fe) -> new RegexpCountNode(args[0], args[1], args[2]));
        registry.register("platform::pure::functions::string::regexpIndexOf_String_1__String_1__Integer_1__RegexpParameter_$1_MANY$__Integer_1_",
                (args, gt, mul, fe) -> new RegexpIndexOfNode(args[0], args[1], args[2], args[3]));
        registry.register("platform::pure::functions::string::regexpReplace_String_1__String_1__String_1__Boolean_1__RegexpParameter_$1_MANY$__String_1_",
                (args, gt, mul, fe) -> new RegexpReplaceNode(args[0], args[1], args[2], args[3], args[4]));
        registry.register("platform::pure::functions::string::regexpExtract_String_1__String_1__Boolean_1__Integer_1__RegexpParameter_$1_MANY$__String_MANY_",
                (args, gt, mul, fe) -> new RegexpExtractNode(args[0], args[1], args[2], args[3], args[4]));

        registry.register("platform::pure::functions::string::reverseString_String_1__String_1_",
                (args, gt, mul, fe) -> new ReverseStringNode(args[0]));

        registry.register("platform::pure::functions::string::inferType_String_MANY__Type_1_",
                (args, gt, mul, fe) -> new InferTypeNode(args[0]));

        registry.register("platform::pure::functions::string::parseInteger_String_1__Integer_1_",
                (args, gt, mul, fe) -> new ParseIntegerNode(args[0]));

        registry.register("platform::pure::functions::string::parseFloat_String_1__Float_1_",
                (args, gt, mul, fe) -> new ParseFloatNode(args[0]));

        registry.register("platform::pure::functions::string::parseBoolean_String_1__Boolean_1_",
                (args, gt, mul, fe) -> new ParseBooleanNode(args[0]));

        registry.register("platform::pure::functions::string::parseDate_String_1__Date_1_",
                (args, gt, mul, fe) -> new ParseDateNode(args[0], gt, mul));

        registry.register("platform::pure::functions::string::parseDecimal_String_1__Decimal_1_",
                (args, gt, mul, fe) -> new ParseDecimalNode(args[0], null, null));

        registry.register("platform::pure::functions::string::parseDecimal_String_1__Integer_1__Integer_1__Decimal_1_",
                (args, gt, mul, fe) -> new ParseDecimalNode(args[0], args[1], args[2]));

        // Compiler-internal native: normalizes date literal strings
        registry.register("platform::pure::compiler::structural::valueSpecificationCompiler::normalizeDateString_String_1__String_1_",
                (args, gt, mul, fe) -> new NormalizeDateStringNode(args[0]));

        // Compiler-internal native: char-by-char unescape of Pure string-literal
        // escape sequences. See UnescapePureStringNode for why this is a native
        // rather than a Pure chained-replace.
        registry.register("platform::pure::compiler::structural::valueSpecificationCompiler::unescapePureString_String_1__String_1_",
                (args, gt, mul, fe) -> new UnescapePureStringNode(args[0]));
    }
}
