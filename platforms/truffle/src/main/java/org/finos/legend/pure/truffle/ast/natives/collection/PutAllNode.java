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
 * {@code putAll(Map[1], Pair[*]) : Map[1]} and
 * {@code putAll(Map[1], Map[1]) : Map[1]} — merge operations on maps.
 * Delegates to the bridged native for PureMap manipulation.
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
        return doPutAll(map, other);
    }

    @TruffleBoundary
    private ValueSpecification doPutAll(Object map, Object other)
    {
        ValueSpecification mapVS = ValueAdapter.ensureVS(map);
        ValueSpecification otherVS = ValueAdapter.ensureVS(other);
        return EvaluatorHolder.current().natives().execute(
                signature,
                List.of(mapVS, otherVS),
                EvaluatorHolder.current(),
                null,
                null);
    }
}
