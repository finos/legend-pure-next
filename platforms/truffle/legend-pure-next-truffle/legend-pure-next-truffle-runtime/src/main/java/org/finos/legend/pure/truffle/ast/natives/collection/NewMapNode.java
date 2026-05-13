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
 * {@code newMap(Pair[*]) : Map[1]} and
 * {@code newMap(Pair[*], Property[*]) : Map[1]}.
 */
@NodeInfo(shortName = "newMap")
public final class NewMapNode extends PureNode
{

    private static final int SLOT_FIRST = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("first");
    private static final int SLOT_SECOND = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("second");
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

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private Object cachedMapCgt;

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object pairs = pairsArg.executeGeneric(frame);
        if (propertiesArg != null)
        {
            propertiesArg.executeGeneric(frame);
        }
        return buildMap(pairs, lookupMapCgt());
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
            throw new RuntimeException("[NewMapNode] Cannot resolve Map type from PDB");
        }
        cachedMapCgt = cgt;
        return cgt;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object buildMap(Object pairs, Object mapCGT)
    {
        MapImpl map = new MapImpl();
        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(map, "classifierGenericType", mapCGT);
        int sz = CollectionHelper.size(pairs);
        for (int i = 0; i < sz; i++)
        {
            Object pair = CollectionHelper.at(pairs, i);
            if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(pair,
                    "meta::pure::functions::collection::Pair"))
            {
                map.put(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(pair, SLOT_FIRST),
                        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(pair, SLOT_SECOND));
            }
        }
        return map;
    }
}
