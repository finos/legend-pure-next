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
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.List;

/**
 * {@code put(Map[1], U[1], V[1]) : Map[1]} — inserts a key-value pair into a map.
 * Delegates to the bridged native for PureMap manipulation.
 */
@NodeInfo(shortName = "mapPut")
public final class MapPutNode extends PureNode
{
    private static final String SIG = "put_Map_1__U_1__V_1__Map_1_";

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

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object map = mapArg.executeGeneric(frame);
        Object key = keyArg.executeGeneric(frame);
        Object value = valueArg.executeGeneric(frame);
        return doPut(map, key, value);
    }

    @TruffleBoundary
    private static ValueSpecification doPut(Object map, Object key, Object value)
    {
        ValueSpecification mapVS = ValueAdapter.ensureVS(map);
        ValueSpecification keyVS = ValueAdapter.ensureVS(key);
        ValueSpecification valueVS = ValueAdapter.ensureVS(value);
        return EvaluatorHolder.current().natives().execute(
                SIG,
                List.of(mapVS, keyVS, valueVS),
                EvaluatorHolder.current(),
                null,
                null);
    }
}
