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

package org.finos.legend.pure.truffle.ast.natives.lang;

import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;

/**
 * Registers specialized Truffle nodes for core language natives:
 * {@code if}, {@code eval} (all overloads), and {@code match}.
 *
 * <p>Short-circuit {@code and}/{@code or} stay on {@code LazyNativeCallNode}
 * for Phase 1 because the {@code isLazy} check in {@code PureASTBuilder}
 * precedes the registry lookup. They will move here in Phase 2 when the
 * lowering order is reworked.</p>
 */
public final class LangNodeFactories
{
    private LangNodeFactories()
    {
    }

    public static void registerIfOnly(NativeNodeRegistry registry)
    {
        registry.register("if_Boolean_1__Function_1__Function_1__T_m_",
                (args, gt, mul, fe) -> new IfNode(args[0], args[1], args[2]));
    }

    public static void registerAll(NativeNodeRegistry registry)
    {
        registry.register("if_Boolean_1__Function_1__Function_1__T_m_",
                (args, gt, mul, fe) -> new IfNode(args[0], args[1], args[2]));

        // eval — all four overloads use the same variable-arity EvalNode
        registry.register("eval_Function_1__V_m_",
                (args, gt, mul, fe) -> new EvalNode(args));
        registry.register("eval_Function_1__T_n__V_m_",
                (args, gt, mul, fe) -> new EvalNode(args));
        registry.register("eval_Function_1__T_n__U_p__V_m_",
                (args, gt, mul, fe) -> new EvalNode(args));
        registry.register("eval_Function_1__T_n__U_p__W_q__V_m_",
                (args, gt, mul, fe) -> new EvalNode(args));

        // and/or — short-circuit boolean. Registry check now preempts
        // the isLazy check in PureASTBuilder, so these get wired.
        registry.register("and_Boolean_1__Boolean_1__Boolean_1_",
                (args, gt, mul, fe) -> new AndNode(args[0], args[1]));
        registry.register("or_Boolean_1__Boolean_1__Boolean_1_",
                (args, gt, mul, fe) -> new OrNode(args[0], args[1]));

        // match — both overloads (with and without extra parameter)
        registry.register("match_Any_MANY__Function_$1_MANY$__T_m_",
                (args, gt, mul, fe) -> new MatchNode(args));
        registry.register("match_Any_MANY__Function_$1_MANY$__P_o__T_m_",
                (args, gt, mul, fe) -> new MatchNode(args));

        // evaluate(Function[1], List[*]) — unwraps List values before dispatch
        registry.register("evaluate_Function_1__List_MANY__Any_MANY_",
                (args, gt, mul, fe) -> new EvaluateNode(args));

        // letFunction is handled exclusively by PureASTBuilder.lowerFrameLet;
        // no factory registration — eval(letFunction, ...) is not supported.
    }
}
