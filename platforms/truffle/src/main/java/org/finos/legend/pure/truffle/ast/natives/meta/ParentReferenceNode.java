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

package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.math.IntegerHelper;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;

/**
 * {@code parentReference(Integer[1], String[1]) : Any[1]}.
 * Returns the parent instance from the construction stack at the given depth.
 */
@NodeInfo(shortName = "parentReference")
public final class ParentReferenceNode extends PureNode
{
    @Child
    private PureNode depthChild;

    @Child
    private PureNode propNameChild;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public ParentReferenceNode(PureNode depthChild, PureNode propNameChild, GenericType genericType, Multiplicity multiplicity)
    {
        this.depthChild = depthChild;
        this.propNameChild = propNameChild;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object depthResult = depthChild.executeGeneric(frame);
        Object propNameResult = propNameChild.executeGeneric(frame);
        return doParentReference(depthResult);
    }

    private static Object doParentReference(Object depthResult)
    {
        int depth = (int) IntegerHelper.asLong(depthResult, "parentReference");
        Object target = StandaloneEvaluatorHolder.current().peekConstruction(depth);
        if (target == null)
        {
            throw new RuntimeException("Parent reference ~ at depth " + depth
                    + " is out of bounds. Ensure ~ is used inside a ^Type(...) expression.");
        }
        return target;
    }
}
