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

/**
 * {@code remove(Map[1], U[1]) : Map[1]} -- returns a new map with the key removed.
 */
@NodeInfo(shortName = "mapRemove")
public final class MapRemoveNode extends PureNode
{
    @Child
    private PureNode mapArg;

    @Child
    private PureNode keyArg;

    public MapRemoveNode(PureNode mapArg, PureNode keyArg)
    {
        this.mapArg = mapArg;
        this.keyArg = keyArg;
    }

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue cachedMapCgt;

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object map = mapArg.executeGeneric(frame);
        Object key = keyArg.executeGeneric(frame);
        return doRemove(map, key, lookupMapCgt());
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
            throw new RuntimeException("[MapRemoveNode] Cannot resolve Map type from PDB");
        }
        cachedMapCgt = cgt;
        return cgt;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object doRemove(Object map, Object key, org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue mapCGT)
    {
        MapImpl newMap = new MapImpl();
        newMap._classifierGenericType(mapCGT);
        if (map instanceof MapImpl mi)
        {
            newMap.putAll(mi);
            newMap.getMap().remove(key);
        }
        return newMap;
    }
}
