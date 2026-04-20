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
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.Multiplicity;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.StandaloneEvaluator;

/**
 * {@code type(Any[*]) : Type[1]}.
 * Returns the Pure Type of the value as a raw Type object.
 */
@NodeInfo(shortName = "type")
public final class TypeNode extends PureNode
{
    @Child
    private PureNode child;

    private final GenericType genericType;
    private final Multiplicity multiplicity;
    private final TruffleMetadataAccess resolver;

    public TypeNode(PureNode child, GenericType genericType, Multiplicity multiplicity)
    {
        this.child = child;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
        this.resolver = StandaloneEvaluator.INSTANCE.resolver();
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object result = child.executeGeneric(frame);
        return doType(result, resolver);
    }

    private static Object doType(Object result, TruffleMetadataAccess resolver)
    {
        return MetaHelper.getRawValueType(result, resolver);
    }
}
