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
import org.finos.legend.pure.truffle.pdb.meta.pure.functions.collection.PairImpl;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;

/**
 * {@code newMap(Pair[*]) : Map[1]} and
 * {@code newMap(Pair[*], Property[*]) : Map[1]}.
 */
@NodeInfo(shortName = "newMap")
public final class NewMapNode extends PureNode
{
    private final String signature;

    @Child
    private PureNode pairsArg;

    @Child
    private PureNode propertiesArg;

    public NewMapNode(String signature, PureNode pairsArg, PureNode propertiesArg)
    {
        this.signature = signature;
        this.pairsArg = pairsArg;
        this.propertiesArg = propertiesArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object pairs = pairsArg.executeGeneric(frame);
        if (propertiesArg != null)
        {
            propertiesArg.executeGeneric(frame);
        }
        var cgt = getContext().cgtForType("meta::pure::functions::collection::Map");
        if (cgt == null)
        {
            throw new RuntimeException("[NewMapNode] Cannot resolve Map type from PDB");
        }
        return buildMap(pairs, cgt);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object buildMap(Object pairs, org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue mapCGT)
    {
        MapImpl map = new MapImpl();
        map._classifierGenericType(mapCGT);
        int sz = CollectionHelper.size(pairs);
        for (int i = 0; i < sz; i++)
        {
            Object pair = CollectionHelper.at(pairs, i);
            if (pair instanceof PairImpl p)
            {
                map.put(p._first(), p._second());
            }
        }
        return map;
    }
}
