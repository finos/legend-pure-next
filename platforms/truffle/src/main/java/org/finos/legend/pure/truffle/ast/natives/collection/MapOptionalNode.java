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

package org.finos.legend.pure.truffle.ast.natives.collection;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.PureNull;

/**
 * {@code map(T[0..1], Function<{T[1]->V[0..1]}>[1]) : V[0..1]} -- map on optional.
 * If the input is non-empty, invokes the function; otherwise returns empty.
 */
@NodeInfo(shortName = "mapOpt")
public final class MapOptionalNode extends PureNode
{
    @Child
    private PureNode valueArg;

    @Child
    private PureNode lambdaArg;

    @Child
    private org.finos.legend.pure.truffle.ast.RawLambdaCallNode callNode = new org.finos.legend.pure.truffle.ast.RawLambdaCallNode();

    public MapOptionalNode(PureNode valueArg, PureNode lambdaArg)
    {
        this.valueArg = valueArg;
        this.lambdaArg = lambdaArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object val = valueArg.executeGeneric(frame);
        if (CollectionHelper.isEmpty(val))
        {
            return PureNull.INSTANCE;
        }
        Object fn = lambdaArg.executeGeneric(frame);
        return callNode.call(fn, val);
    }
}
