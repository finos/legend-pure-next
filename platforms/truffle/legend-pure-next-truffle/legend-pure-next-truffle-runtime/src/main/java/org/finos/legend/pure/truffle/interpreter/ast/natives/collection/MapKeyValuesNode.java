// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.interpreter.ast.natives.collection;

import org.finos.legend.pure.truffle.types.MapImpl;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.interpreter.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.module.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * {@code keyValues(Map<U,V>[1]) : Pair<U,V>[*]} -- extracts all key-value pairs from a map.
 */
@NodeInfo(shortName = "keyValues")
public final class MapKeyValuesNode extends PureNode
{
    @Child
    private PureNode mapArg;

    public MapKeyValuesNode(PureNode mapArg)
    {
        this.mapArg = mapArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object map = mapArg.executeGeneric(frame);
        return doKeyValues(map, getResolver());
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object doKeyValues(Object map, TruffleMetadataAccess resolver)
    {
        if (map instanceof MapImpl mi)
        {
            java.util.LinkedHashMap<Object, Object> raw = mi.getMap();
            if (raw.isEmpty())
            {
                return PureSequence.EMPTY;
            }
            Object[] pairs = new Object[raw.size()];
            int i = 0;
            for (java.util.Map.Entry<Object, Object> entry : raw.entrySet())
            {
                Object pair = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(
                        "meta::pure::functions::collection::Pair", resolver);
                org.finos.legend.pure.truffle.runtime.helper._Any.write(pair, "first", entry.getKey());
                org.finos.legend.pure.truffle.runtime.helper._Any.write(pair, "second", entry.getValue());
                pairs[i++] = pair;
            }
            return new ObjectSequence(pairs);
        }
        return PureSequence.EMPTY;
    }
}
