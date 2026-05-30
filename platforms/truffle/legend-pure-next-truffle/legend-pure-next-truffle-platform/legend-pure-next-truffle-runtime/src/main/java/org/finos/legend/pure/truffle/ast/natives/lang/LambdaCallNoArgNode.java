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
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;

/**
 * Evaluates a child to a lambda value and invokes it with no arguments.
 *
 * <p>Used by {@link MultiIfNode}'s static lowering path when a clause's
 * lambda body can't be inlined (multi-statement body, etc.) but the
 * surrounding pair list is literal — we still want to skip {@code Pair}
 * allocation and the {@code pair()} call, so we extract the lambda value
 * directly and call it here instead of going through the runtime walk.</p>
 */
@NodeInfo(shortName = "callLambda0")
public final class LambdaCallNoArgNode extends PureNode
{
    @Child
    private PureNode lambdaProducer;

    @Child
    private RawLambdaCallNode callNode = new RawLambdaCallNode();

    public LambdaCallNoArgNode(PureNode lambdaProducer)
    {
        this.lambdaProducer = lambdaProducer;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return callNode.call(lambdaProducer.executeGeneric(frame));
    }
}
