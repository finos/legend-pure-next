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
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.ObjectSequence;

/**
 * {@code genericTypeHolder(T[m]) : GenericTypeAndMultiplicityHolder[1]}.
 * Creates a GenericTypeAndMultiplicityHolder that captures the type and multiplicity.
 */
@NodeInfo(shortName = "genericTypeHolder")
public final class GenericTypeHolderNode extends PureNode
{
    @Child
    private PureNode child;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public GenericTypeHolderNode(PureNode child, GenericType genericType, Multiplicity multiplicity)
    {
        this.child = child;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object result = child.executeGeneric(frame);
        return doGenericTypeHolder(result, genericType, multiplicity, getResolver());
    }

    private static Object doGenericTypeHolder(Object result, GenericType genericType, Multiplicity multiplicity, TruffleMetadataAccess resolver)
    {

        GenericType heldGT = MetaHelper.getRawGenericType(result, resolver);
        // Build classifier GT: GenericTypeAndMultiplicityHolder<heldGT|heldMul>
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type holderType = (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::valuespecification::UserDefinedGenericTypeAndMultiplicityHolder");
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl classifierGT = org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(holderType, resolver);
        if (heldGT != null)
        {
            classifierGT._typeArguments(new ObjectSequence(new Object[]{heldGT}));
        }

        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.UserDefinedGenericTypeAndMultiplicityHolderImpl holder =
                new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.UserDefinedGenericTypeAndMultiplicityHolderImpl()
                        ._classifierGenericType(classifierGT)
                        ._genericType(classifierGT)
                        ._multiplicity((Multiplicity) resolver.getElement("meta::pure::metamodel::multiplicity::PureOne"));
        return holder;
    }
}
