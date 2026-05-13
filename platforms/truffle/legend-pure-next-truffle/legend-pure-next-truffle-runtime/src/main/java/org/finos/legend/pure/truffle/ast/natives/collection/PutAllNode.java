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

    private static final int SLOT_FIRST = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("first");
    private static final int SLOT_SECOND = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("second");
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

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private Object cachedMapCgt;

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object map = mapArg.executeGeneric(frame);
        Object other = otherArg.executeGeneric(frame);
        return doPutAll(map, other, lookupMapCgt());
    }

    private Object lookupMapCgt()
    {
        Object cgt = cachedMapCgt;
        if (cgt == null)
        {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
            cgt = populateMapCgt();
        }
        return cgt;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object populateMapCgt()
    {
        Object cgt = getContext().cgtForType("meta::pure::functions::collection::Map");
        if (cgt == null)
        {
            throw new RuntimeException("[PutAllNode] Cannot resolve Map type from PDB");
        }
        cachedMapCgt = cgt;
        return cgt;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object doPutAll(Object map, Object other, Object mapCGT)
    {
        MapImpl newMap = new MapImpl();
        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(newMap, "classifierGenericType", mapCGT);
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
                if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(pair,
                        "meta::pure::functions::collection::Pair"))
                {
                    newMap.put(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(pair, SLOT_FIRST),
                            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(pair, SLOT_SECOND));
                }
            }
        }
        return newMap;
    }
}
