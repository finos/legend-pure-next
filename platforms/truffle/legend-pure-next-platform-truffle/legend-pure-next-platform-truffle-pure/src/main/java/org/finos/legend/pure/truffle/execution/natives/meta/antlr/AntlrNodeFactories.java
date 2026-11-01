// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.execution.natives.meta.antlr;

import org.finos.legend.pure.truffle.execution.natives.NativeRegistry;

/**
 * Registers the ANTLR bridge natives declared in
 * pure/specification/runtime/functions/meta/parser/antlr/antlr.pure.
 *
 * <p>Wired into {@link NativeRegistry#createDefault} alongside the other
 * core {@code XNodeFactories} — these are core natives, not parser-only.</p>
 */
public final class AntlrNodeFactories
{
    private AntlrNodeFactories() {}

    public static void registerAll(NativeRegistry registry)
    {
        registry.register("platform::pure::functions::meta::antlr::getText_AntlrContext_1__String_1_",
                (args, gt, mul, fe) -> new AntlrNodes.GetTextNode(args[0]));

        registry.register("platform::pure::functions::meta::antlr::grammarRuleName_AntlrContext_1__String_1_",
                (args, gt, mul, fe) -> new AntlrNodes.GrammarRuleNameNode(args[0]));

        registry.register("platform::pure::functions::meta::antlr::getTopLevelChildren_AntlrContext_1__AntlrContext_MANY_",
                (args, gt, mul, fe) -> new AntlrNodes.GetTopLevelChildrenNode(args[0]));

        registry.register("platform::pure::functions::meta::antlr::getChild_AntlrContext_1__String_1__AntlrContext_$0_1$_",
                (args, gt, mul, fe) -> new AntlrNodes.GetChildNode(args[0], args[1]));

        registry.register("platform::pure::functions::meta::antlr::getChildren_AntlrContext_1__String_1__AntlrContext_MANY_",
                (args, gt, mul, fe) -> new AntlrNodes.GetChildrenNode(args[0], args[1]));

        registry.register("platform::pure::functions::meta::antlr::getTokenText_AntlrContext_1__String_1__String_$0_1$_",
                (args, gt, mul, fe) -> new AntlrNodes.GetTokenTextNode(args[0], args[1]));

        registry.register("platform::pure::functions::meta::antlr::getTokenTexts_AntlrContext_1__String_1__String_MANY_",
                (args, gt, mul, fe) -> new AntlrNodes.GetTokenTextsNode(args[0], args[1]));

        registry.register("platform::pure::functions::meta::antlr::hasChild_AntlrContext_1__String_1__Boolean_1_",
                (args, gt, mul, fe) -> new AntlrNodes.HasChildNode(args[0], args[1]));

        registry.register("platform::pure::functions::meta::antlr::hasToken_AntlrContext_1__String_1__Boolean_1_",
                (args, gt, mul, fe) -> new AntlrNodes.HasTokenNode(args[0], args[1]));

        registry.register("platform::pure::functions::meta::antlr::getStartLine_AntlrContext_1__Integer_1_",
                (args, gt, mul, fe) -> new AntlrNodes.GetStartLineNode(args[0]));

        registry.register("platform::pure::functions::meta::antlr::getStartColumn_AntlrContext_1__Integer_1_",
                (args, gt, mul, fe) -> new AntlrNodes.GetStartColumnNode(args[0]));

        registry.register("platform::pure::functions::meta::antlr::getStopLine_AntlrContext_1__Integer_1_",
                (args, gt, mul, fe) -> new AntlrNodes.GetStopLineNode(args[0]));

        registry.register("platform::pure::functions::meta::antlr::getStopColumn_AntlrContext_1__Integer_1_",
                (args, gt, mul, fe) -> new AntlrNodes.GetStopColumnNode(args[0]));

        registry.register("platform::pure::functions::meta::antlr::getChildTextAt_AntlrContext_1__Integer_1__String_$0_1$_",
                (args, gt, mul, fe) -> new AntlrNodes.GetChildTextAtNode(args[0], args[1]));

        registry.register("platform::pure::functions::meta::antlr::stripTripleQuotesDedented_String_1__String_1_",
                (args, gt, mul, fe) -> new AntlrNodes.StripTripleQuotesDedentedNode(args[0]));

        registry.register("platform::pure::functions::meta::antlr::parseAntlr_String_1__String_1__String_1__Integer_1__AntlrContext_1_",
                (args, gt, mul, fe) -> new AntlrNodes.ParseAntlrNode(args[0], args[1], args[2], args[3]));

        registry.register(
                "platform::pure::functions::meta::antlr::computeFirstNonNewlineLine_AntlrContext_$0_1$__Integer_1_",
                (args, gt, mul, fe) -> new AntlrNodes.ComputeFirstNonNewlineLineNode(args[0]));
    }
}
