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
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;

/**
 * {@code putAll(Map[1], Pair[*]) : Map[1]} and
 * {@code putAll(Map[1], Map[1]) : Map[1]} -- merge operations on maps.
 */
@NodeInfo(shortName = "putAll")
public final class PutAllNode extends PureNode
{
    private final String signature;

    @Child
    private PureNode mapArg;

    @Child
    private PureNode otherArg;

    public PutAllNode(String signature, PureNode mapArg, PureNode otherArg)
    {
        this.signature = signature;
        this.mapArg = mapArg;
        this.otherArg = otherArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object map = mapArg.executeGeneric(frame);
        Object other = otherArg.executeGeneric(frame);
        var cgt = getContext().cgtForType("meta::pure::functions::collection::Map");
        if (cgt == null)
        {
            throw new RuntimeException("[PutAllNode] Cannot resolve Map type from PDB");
        }
        return doPutAll(map, other, cgt);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object doPutAll(Object map, Object other, org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue mapCGT)
    {
        MapImpl newMap = new MapImpl();
        newMap._classifierGenericType(mapCGT);
        if (map instanceof MapImpl mi)
        {
            newMap.putAll(mi);
        }
        if (other instanceof MapImpl otherMap)
        {
            newMap.putAll(otherMap);
        }
        else
        {
            // other is a collection of Pairs
            int sz = CollectionHelper.size(other);
            for (int i = 0; i < sz; i++)
            {
                Object pair = CollectionHelper.at(other, i);
                if (pair instanceof org.finos.legend.pure.truffle.pdb.meta.pure.functions.collection.PairImpl p)
                {
                    newMap.put(p._first(), p._second());
                }
            }
        }
        return newMap;
    }
}
