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

package org.finos.legend.pure.truffle.frame;

/**
 * Cached frame-path analysis of a single {@link
 * org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition}: just the
 * {@link FrameLayout} (slot allocation for parameters and
 * {@code letFunction} targets).
 *
 * <p>The body is <em>not</em> pre-lowered. Each top-level expression of
 * the FD is lowered lazily on the first call via
 * {@code TruffleEvaluator.evaluate(vs)} under the active layout, then
 * cached per-expression in the evaluator's own VS→PureNode map. Lazy
 * lowering avoids touching FunctionExpressions that are never reached
 * (dead branches, parser-internal {@code ~} placeholders) and side-steps
 * FlatBuffer wrapper hazards where {@code FunctionExpression#_func()}
 * resolves lazily against runtime-bound target types.</p>
 */
public final class CompiledFunction
{
    private final FrameLayout layout;
    private volatile org.finos.legend.pure.truffle.ast.PureNode[] body;
    private volatile com.oracle.truffle.api.RootCallTarget callTarget;

    public CompiledFunction(FrameLayout layout)
    {
        this.layout = layout;
    }

    public FrameLayout layout()
    {
        return layout;
    }

    public org.finos.legend.pure.truffle.ast.PureNode[] body()
    {
        return body;
    }

    public void setBody(org.finos.legend.pure.truffle.ast.PureNode[] body)
    {
        this.body = body;
    }

    public com.oracle.truffle.api.RootCallTarget callTarget()
    {
        return callTarget;
    }

    public void setCallTarget(com.oracle.truffle.api.RootCallTarget callTarget)
    {
        this.callTarget = callTarget;
    }
}
