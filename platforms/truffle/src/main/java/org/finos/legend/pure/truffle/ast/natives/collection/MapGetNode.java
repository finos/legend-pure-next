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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.functions.collection.MapImpl;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * {@code get(Map[1], U[1]) : V[0..1]}.
 */
@NodeInfo(shortName = "mapGet")
public final class MapGetNode extends PureNode
{
    @Child
    private PureNode mapArg;

    @Child
    private PureNode keyArg;

    public MapGetNode(PureNode mapArg, PureNode keyArg)
    {
        this.mapArg = mapArg;
        this.keyArg = keyArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object map = mapArg.executeGeneric(frame);
        Object key = keyArg.executeGeneric(frame);
        return doGet(map, key);
    }

    @TruffleBoundary
    private static Object doGet(Object map, Object key)
    {
        if (map instanceof MapImpl mi)
        {
            Object value = mi.get(key);
            return value != null ? value : PureSequence.EMPTY;
        }
        if (map instanceof org.finos.legend.pure.execution.PureMap pureMap)
        {
            Object value = pureMap.getMap().get(key);
            return value != null ? value : PureSequence.EMPTY;
        }
        return PureSequence.EMPTY;
    }
}
