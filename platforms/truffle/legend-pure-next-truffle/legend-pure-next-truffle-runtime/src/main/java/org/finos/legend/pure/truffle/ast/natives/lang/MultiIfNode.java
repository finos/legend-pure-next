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

package org.finos.legend.pure.truffle.ast.natives.lang;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;

/**
 * Native implementation of Pure's multi-clause {@code if} —
 * {@code if(condList: Pair<Function<{->Boolean[1]}>, Function<{->T[m]}>>[*], last: ...)}.
 *
 * <p>Two operating modes, picked by the AST builder:</p>
 *
 * <h3>Static (literal-pair) mode</h3>
 * When the AST builder sees a literal pair list — {@code if([pair(|c1,|b1),
 * pair(|c2,|b2), ...], |default)} — and the lambdas are parameter-free
 * single-expression bodies, it pre-resolves every condition and branch
 * expression at build time. At runtime: no {@code Pair} allocation, no
 * list allocation, no per-clause closure-call. Conditions are evaluated
 * via primitive {@link PureNode#executeBoolean}, only the matching
 * branch runs.
 *
 * <h3>Runtime (sequence) mode</h3>
 * When {@code condList} is a runtime-built sequence (variable, function
 * result, etc.), the AST builder constructs the node with a single child
 * each for {@code condList} and {@code last}. At runtime we walk the
 * sequence directly in Java, calling each pair's {@code .first()}
 * condition lambda and stopping at the first that returns true. Same
 * semantics as the previous Pure {@code find}-based body, but without
 * going through Pure's {@code find} closure dispatch.
 *
 * <p>Both modes preserve short-circuit evaluation — only conditions up to
 * (and including) the first true one run. The Pure body using {@code find}
 * already short-circuits, so semantics are unchanged.</p>
 */
@NodeInfo(shortName = "multiIf")
public final class MultiIfNode extends PureNode
{
    // --- Static mode fields ------------------------------------------------

    /** Inlined condition expressions; null in runtime mode. */
    @Children
    private PureNode[] conditions;

    /** Inlined branch expressions; null in runtime mode. */
    @Children
    private PureNode[] bodies;

    // --- Runtime mode fields ----------------------------------------------

    /** condList child (a {@code Pair<...>[*]} producer); null in static mode. */
    @Child
    private PureNode condListNode;

    @Child
    private RawLambdaCallNode condCallNode;

    @Child
    private RawLambdaCallNode bodyCallNode;

    // --- Always present ---------------------------------------------------

    /** Default branch (last argument). Always present. */
    @Child
    private PureNode defaultBody;

    /** Static-mode constructor: caller pre-resolved each clause's condition + body. */
    public MultiIfNode(PureNode[] conditions, PureNode[] bodies, PureNode defaultBody)
    {
        assert conditions.length == bodies.length :
                "conditions and bodies must be parallel arrays";
        this.conditions = conditions;
        this.bodies = bodies;
        this.defaultBody = defaultBody;
    }

    /** Runtime-mode constructor: condList is evaluated at runtime to a Pair[*]. */
    public MultiIfNode(PureNode condListNode, PureNode defaultBody)
    {
        this.condListNode = condListNode;
        this.defaultBody = defaultBody;
        this.condCallNode = new RawLambdaCallNode();
        this.bodyCallNode = new RawLambdaCallNode();
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        if (conditions != null)
        {
            return executeStatic(frame);
        }
        return executeRuntime(frame);
    }

    @ExplodeLoop
    private Object executeStatic(VirtualFrame frame)
    {
        for (int i = 0; i < conditions.length; i++)
        {
            if (conditions[i].executeBoolean(frame))
            {
                return bodies[i].executeGeneric(frame);
            }
        }
        return defaultBody.executeGeneric(frame);
    }

    private Object executeRuntime(VirtualFrame frame)
    {
        Object condList = condListNode.executeGeneric(frame);
        int n = CollectionHelper.size(condList);
        for (int i = 0; i < n; i++)
        {
            Object item = CollectionHelper.at(condList, i);
            if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(item,
                    "meta::pure::functions::collection::Pair"))
            {
                continue;
            }
            Object condFn = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(item, "first");
            Object condResult = condCallNode.call(condFn);
            if (Boolean.TRUE.equals(condResult))
            {
                Object bodyFn = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(item, "second");
                return bodyCallNode.call(bodyFn);
            }
        }
        Object defaultFn = defaultBody.executeGeneric(frame);
        return bodyCallNode != null ? bodyCallNode.call(defaultFn) : defaultFn;
    }
}
