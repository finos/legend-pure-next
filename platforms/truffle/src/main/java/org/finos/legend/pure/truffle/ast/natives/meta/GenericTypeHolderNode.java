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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code genericTypeHolder(T[m]) : GenericTypeAndMultiplicityHolder[1]}.
 *
 * <p>Creates a {@code GenericTypeAndMultiplicityHolder} that captures the
 * type and multiplicity of the argument. This holder is then passed to
 * {@code new} or {@code cast} to drive instantiation or type-narrowing.</p>
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
        return doGenericTypeHolder(result, genericType, multiplicity);
    }

    @TruffleBoundary
    private static ValueSpecification doGenericTypeHolder(Object result, GenericType genericType, Multiplicity multiplicity)
    {
        ValueSpecification vs = ValueAdapter.ensureVS(result);
        MetadataAccess resolver = EvaluatorHolder.current().natives().resolver();

        GenericType heldGT = vs._genericType();
        Multiplicity heldMul = vs._multiplicity();

        // Build classifier GT: GenericTypeAndMultiplicityHolder<heldGT|heldMul>
        meta.pure.metamodel.type.Type holderType = (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::valuespecification::GenericTypeAndMultiplicityHolder");
        meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl classifierGT = _GenericType.buildUserDefinedGenericType(holderType, resolver);
        if (heldGT != null)
        {
            classifierGT._typeArguments(Lists.mutable.with(heldGT));
        }
        if (heldMul != null)
        {
            classifierGT._multiplicityArguments(Lists.mutable.with(heldMul));
        }

        meta.pure.metamodel.valuespecification.UserDefinedGenericTypeAndMultiplicityHolderImpl holder =
                new meta.pure.metamodel.valuespecification.UserDefinedGenericTypeAndMultiplicityHolderImpl()
                        ._classifierGenericType(classifierGT)
                        ._genericType(classifierGT)
                        ._multiplicity((Multiplicity) resolver.getElement("meta::pure::metamodel::multiplicity::PureOne"));
        return _E_ValueSpecification.wrap(holder, genericType, multiplicity, resolver);
    }
}
