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

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.ast.natives.meta.MetaHelper;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.helper._Type;

/**
 * Specialized match dispatch — skips the generic {@code MatchNode}'s
 * {@code @TruffleBoundary} {@code matchesBranch} loop when the AST builder
 * sees a closed-set match: a literal {@code Collection} of literal
 * closure-lambdas where each lambda has exactly one parameter typed
 * {@code T[1]} for a known Pure type.
 *
 * <p>Each branch's param type path is resolved at AST-build and baked here
 * as a {@code @CompilationFinal} string array, so the runtime dispatch
 * collapses to a constant-folded {@code @ExplodeLoop} of
 * {@link PureObj#isType isType} checks against compile-time constant
 * strings — no per-branch slot reads, no per-branch closure-shape walk,
 * no boundary-blocked Java method call.</p>
 *
 * <p>Branch invocation still goes through {@link RawLambdaCallNode}: the
 * branches are real closures that may capture outer-scope variables,
 * so this specialisation handles dispatch only, not body inlining. That's
 * enough to recover the {@code match}-heavy hot path in
 * {@code compiler-pure}'s type-handling code (e.g.
 * {@code typeEquals}/{@code subtypeOf}/{@code isConcreteGenericType})
 * without touching the Pure source.</p>
 */
@NodeInfo(shortName = "match*")
public final class SpecializedMatchNode extends PureNode
{
    @Child
    private PureNode valueNode;

    @Child
    private PureNode matchFnsNode;

    @Child
    private PureNode extraNode;

    /** Branch param Pure-type Type elements in declaration order. Resolved
     *  ONCE at AST-build from the lambda params; {@code @CompilationFinal} so
     *  PE folds the per-branch {@code subtypeOf} call against a constant
     *  type element. */
    @CompilationFinal(dimensions = 1)
    private final Object[] branchTypeElements;

    @Child
    private RawLambdaCallNode callNode = new RawLambdaCallNode();

    public SpecializedMatchNode(PureNode valueNode, PureNode matchFnsNode, PureNode extraNode, Object[] branchTypeElements)
    {
        this.valueNode = valueNode;
        this.matchFnsNode = matchFnsNode;
        this.extraNode = extraNode;
        this.branchTypeElements = branchTypeElements;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object value = valueNode.executeGeneric(frame);
        Object matchFns = matchFnsNode.executeGeneric(frame);
        Object extra = extraNode != null ? extraNode.executeGeneric(frame) : null;
        return dispatch(value, matchFns, extra, getResolver());
    }

    /** Behind {@code @TruffleBoundary} for the same reason as
     *  {@code MatchNode.invokeMatch}: {@code _Type.subtypeOf} chains into
     *  {@code TypeCache.ancestors} which recurses through generalisations,
     *  and the post-dispatch {@code callNode.call} inlines the branch body
     *  into the caller's compilation. Combined, both expand the inlining
     *  budget per match call site × per caller of the enclosing function
     *  (e.g. every user of {@code toRepresentation}/{@code typeEquals}).
     *  The boundary keeps the caller's compilation small. The speedup over
     *  the generic {@code MatchNode} comes from skipping the per-branch
     *  {@code matchesBranch} (param-shape walk + slot reads + multiplicity
     *  check) — we already know the branch types are concrete {@code T[1]}
     *  from AST-build. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object dispatch(Object value, Object matchFns, Object extra, TruffleMetadataAccess resolver)
    {
        Object valueType = MetaHelper.getRawValueType(value, resolver);
        if (valueType != null)
        {
            for (int i = 0; i < branchTypeElements.length; i++)
            {
                if (_Type.subtypeOf(valueType, branchTypeElements[i], resolver))
                {
                    Object branchFn = CollectionHelper.at(matchFns, i);
                    if (extraNode != null) return callNode.call(branchFn, value, extra);
                    return callNode.call(branchFn, value);
                }
            }
        }
        throw new RuntimeException("No match branch accepts value of Pure type "
                + (valueType == null ? "null" : valueType));
    }
}
