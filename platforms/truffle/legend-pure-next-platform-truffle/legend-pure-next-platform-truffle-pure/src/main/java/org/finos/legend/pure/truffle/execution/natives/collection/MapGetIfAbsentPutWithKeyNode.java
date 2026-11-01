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
package org.finos.legend.pure.truffle.execution.natives.collection;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.execution.ast.PureNode;
import org.finos.legend.pure.truffle.execution.ast.call.RawLambdaCallNode;
import org.finos.legend.pure.truffle.execution.types.MapImpl;

/**
 * {@code getIfAbsentPutWithKey(Map[1], U[1], Function[1]) : V[0..1]} — the key's value, or the function's of
 * the key when it has none. A Pure map is immutable, so the computed value is not stored.
 */
@NodeInfo(shortName = "getIfAbsentPutWithKey")
public final class MapGetIfAbsentPutWithKeyNode extends PureNode
{
    @Child
    private PureNode mapArg;

    @Child
    private PureNode keyArg;

    @Child
    private PureNode functionArg;

    @Child
    private RawLambdaCallNode callNode = new RawLambdaCallNode();

    public MapGetIfAbsentPutWithKeyNode(PureNode mapArg, PureNode keyArg, PureNode functionArg)
    {
        this.mapArg = mapArg;
        this.keyArg = keyArg;
        this.functionArg = functionArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object map = mapArg.executeGeneric(frame);
        Object key = keyArg.executeGeneric(frame);
        Object function = functionArg.executeGeneric(frame);
        Object present = present(map, key);
        return present != null ? present : callNode.call(function, key);
    }

    @TruffleBoundary
    private static Object present(Object map, Object key)
    {
        return map instanceof MapImpl mi ? mi.get(key) : null;
    }
}
