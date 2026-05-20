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

import org.finos.legend.pure.truffle.ast.PureNode;
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
        registry.register("if_Boolean_1__Function_1__Function_1__T_m_", LangNodeFactories::buildIfNode);
    }

    /** Static-mode (inlined then/else bodies) when both branches are literal
     *  0-param closure-lambdas; otherwise the generic lambda-dispatch form. */
    private static PureNode buildIfNode(PureNode[] args, Object gt, Object mul, Object fe)
    {
        PureNode spec = org.finos.legend.pure.truffle.PureLanguage.get(null)
                .astBuilder().tryLowerIf(fe);
        return spec != null ? spec : new IfNode(args[0], args[1], args[2]);
    }

    public static void registerAll(NativeNodeRegistry registry)
    {
        registry.register("if_Boolean_1__Function_1__Function_1__T_m_", LangNodeFactories::buildIfNode);

        // Multi-clause if — declared `native` in if.pure. Two operating modes:
        //   - Static: the args are a literal pair-list ({@code [pair(|c1, |b1),
        //     pair(|c2, |b2), ...]}). The AST builder's
        //     {@link PureASTBuilder#tryLowerMultiIf} inspects the
        //     FunctionExpression, pulls each pair's cond+body lambdas, and
        //     emits a {@link MultiIfNode} with pre-lowered branches — no
        //     {@code Pair} allocation, no per-clause closure call.
        //   - Runtime: anything else (a variable, a function-built sequence
        //     of pairs, etc.). The {@link MultiIfNode} runtime constructor
        //     walks the {@code Pair[*]} at execution time, reading each pair's
        //     {@code first}/{@code second} lambdas via slot.
        registry.register("if_Pair_MANY__Function_1__T_m_",
                (args, gt, mul, fe) ->
                {
                    PureNode staticForm = org.finos.legend.pure.truffle.PureLanguage.get(null)
                            .astBuilder().tryLowerMultiIf(fe);
                    return staticForm != null ? staticForm : new MultiIfNode(args[0], args[1]);
                });

        // eval — all four overloads use the same variable-arity EvalNode
        registry.register("eval_Function_1__V_m_",
                (args, gt, mul, fe) -> new EvalNode(args));
        registry.register("eval_Function_1__T_n__V_m_",
                (args, gt, mul, fe) -> new EvalNode(args));
        registry.register("eval_Function_1__T_n__U_p__V_m_",
                (args, gt, mul, fe) -> new EvalNode(args));
        registry.register("eval_Function_1__T_n__U_p__W_q__V_m_",
                (args, gt, mul, fe) -> new EvalNode(args));

        // tryEval — run closure, return TryResult<V|m> (value or Error).
        registry.register("tryEval_Function_1__TryResult_1_",
                (args, gt, mul, fe) -> new TryEvalNode(args[0]));

        // and/or — short-circuit boolean. Registry check now preempts
        // the isLazy check in PureASTBuilder, so these get wired.
        registry.register("and_Boolean_1__Boolean_1__Boolean_1_",
                (args, gt, mul, fe) -> new AndNode(args[0], args[1]));
        registry.register("or_Boolean_1__Boolean_1__Boolean_1_",
                (args, gt, mul, fe) -> new OrNode(args[0], args[1]));

        // match — both overloads (with and without extra parameter).
        // Specialised at AST-build when the branch list is a literal
        // {@code Collection} of literal closure-lambdas with single
        // {@code T[1]} params: pre-resolves branch type paths and emits a
        // {@link SpecializedMatchNode} that PE-folds the dispatch into a
        // constant-string-compare {@code @ExplodeLoop}. Otherwise falls
        // back to the generic {@link MatchNode} that walks branches via
        // the {@code @TruffleBoundary} {@code matchesBranch}.
        registry.register("match_Any_MANY__Function_$1_MANY$__T_m_",
                (args, gt, mul, fe) ->
                {
                    PureNode spec = org.finos.legend.pure.truffle.PureLanguage.get(null)
                            .astBuilder().tryLowerMatch(fe);
                    return spec != null ? spec : new MatchNode(args);
                });
        registry.register("match_Any_MANY__Function_$1_MANY$__P_o__T_m_",
                (args, gt, mul, fe) ->
                {
                    PureNode spec = org.finos.legend.pure.truffle.PureLanguage.get(null)
                            .astBuilder().tryLowerMatch(fe);
                    return spec != null ? spec : new MatchNode(args);
                });

        // evaluate(Function[1], List[*]) — unwraps List values before dispatch
        registry.register("evaluate_Function_1__List_MANY__Any_MANY_",
                (args, gt, mul, fe) -> new EvaluateNode(args));

        // letFunction is handled exclusively by PureASTBuilder.lowerFrameLet;
        // no factory registration — eval(letFunction, ...) is not supported.
    }
}
