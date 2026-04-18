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
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code type(Any[*]) : Type[1]}.
 *
 * <p>Returns the Pure Type of the value. Uses
 * {@link _E_ValueSpecification#getValueOriginalType} and wraps the result
 * as a VS (matching MetaNatives behavior).</p>
 */
@NodeInfo(shortName = "type")
public final class TypeNode extends PureNode
{
    @Child
    private PureNode child;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public TypeNode(PureNode child, GenericType genericType, Multiplicity multiplicity)
    {
        this.child = child;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object result = child.executeGeneric(frame);
        return doType(result, genericType, multiplicity);
    }

    @TruffleBoundary
    private static ValueSpecification doType(Object result, GenericType genericType, Multiplicity multiplicity)
    {
        ValueSpecification vs = ValueAdapter.ensureVS(result);
        MetadataAccess resolver = EvaluatorHolder.current().natives().resolver();

        Type type = _E_ValueSpecification.getValueOriginalType(vs);
        // If the resolved type is itself a TypeParameter (unresolved generic like T),
        // fall back to the VS's genericType which has the concrete binding from the compiler
        if (type instanceof meta.pure.metamodel.type.generics.TypeParameter
                && vs._genericType() != null)
        {
            type = _GenericType.type(vs._genericType());
        }
        return _E_ValueSpecification.wrap(type, genericType, multiplicity, resolver);
    }
}
