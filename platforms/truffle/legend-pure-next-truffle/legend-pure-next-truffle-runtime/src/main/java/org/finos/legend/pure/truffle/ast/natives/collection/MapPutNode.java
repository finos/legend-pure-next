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
 * {@code put(Map[1], U[1], V[1]) : Map[1]} -- inserts a key-value pair into a map.
 */
@NodeInfo(shortName = "mapPut")
public final class MapPutNode extends PureNode
{
    @Child
    private PureNode mapArg;

    @Child
    private PureNode keyArg;

    @Child
    private PureNode valueArg;

    public MapPutNode(PureNode mapArg, PureNode keyArg, PureNode valueArg)
    {
        this.mapArg = mapArg;
        this.keyArg = keyArg;
        this.valueArg = valueArg;
    }

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue cachedMapCgt;

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object map = mapArg.executeGeneric(frame);
        Object key = keyArg.executeGeneric(frame);
        Object value = valueArg.executeGeneric(frame);
        return doPut(map, key, value, lookupMapCgt());
    }

    private org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue lookupMapCgt()
    {
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue cgt = cachedMapCgt;
        if (cgt == null)
        {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
            cgt = populateMapCgt();
        }
        return cgt;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue populateMapCgt()
    {
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue cgt =
                getContext().cgtForType("meta::pure::functions::collection::Map");
        if (cgt == null)
        {
            throw new RuntimeException("[MapPutNode] Cannot resolve Map type from PDB");
        }
        cachedMapCgt = cgt;
        return cgt;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object doPut(Object map, Object key, Object value, org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue mapCGT)
    {
        MapImpl newMap = new MapImpl();
        // MapImpl is hand-written runtime infra, not Pure codegen — keep typed setter.
        newMap._classifierGenericType(mapCGT);
        if (map instanceof MapImpl mi)
        {
            newMap.putAll(mi);
        }
        newMap.put(key, value);
        return newMap;
    }
}
