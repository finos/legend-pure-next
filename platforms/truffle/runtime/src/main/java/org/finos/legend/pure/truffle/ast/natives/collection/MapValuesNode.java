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
import org.finos.legend.pure.truffle.pdb.meta.pure.functions.collection.MapImpl;
import org.finos.legend.pure.truffle.pdb.meta.pure.functions.collection.PairImpl;
import org.finos.legend.pure.execution.PureMap;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * {@code values(Map[1]) : V[*]} -- extracts all values from a map.
 */
@NodeInfo(shortName = "values")
public final class MapValuesNode extends PureNode
{
    @Child
    private PureNode mapArg;

    public MapValuesNode(PureNode mapArg)
    {
        this.mapArg = mapArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object map = mapArg.executeGeneric(frame);
        return doValues(map);
    }

    @TruffleBoundary
    private static Object doValues(Object map)
    {
        if (map instanceof MapImpl mi)
        {
            Object[] values = mi.values().toArray();
            return values.length == 0 ? PureSequence.EMPTY : new ObjectSequence(values);
        }
        if (map instanceof PureMap pureMap)
        {
            Object[] values = pureMap.getMap().values().toArray();
            return values.length == 0 ? PureSequence.EMPTY : new ObjectSequence(values);
        }
        return PureSequence.EMPTY;
    }
}
