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
import meta.pure.metamodel.type.Any;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code genericType(Any[*]) : GenericTypeValue[1]}.
 *
 * <p>Returns the GenericType of the value. For DynamicInstances, uses the
 * instance's classifierGenericType. For metamodel elements, uses
 * {@code _classifierGenericType()}. Falls back to the VS's genericType.</p>
 */
@NodeInfo(shortName = "genericType")
public final class GenericTypeNode extends PureNode
{
    @Child
    private PureNode child;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public GenericTypeNode(PureNode child, GenericType genericType, Multiplicity multiplicity)
    {
        this.child = child;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object result = child.executeGeneric(frame);
        return doGenericType(result, genericType, multiplicity);
    }

    @TruffleBoundary
    private static ValueSpecification doGenericType(Object result, GenericType genericType, Multiplicity multiplicity)
    {
        ValueSpecification vs = ValueAdapter.ensureVS(result);
        MetadataAccess resolver = EvaluatorHolder.current().natives().resolver();

        Object val = _E_ValueSpecification.unwrap(vs);
        // DynamicInstance may carry its own classifierGenericType (set by new/copy)
        if (val instanceof DynamicInstance di && di.getClassifierGenericType() != null)
        {
            return _E_ValueSpecification.wrap(di.getClassifierGenericType(), genericType, multiplicity, resolver);
        }
        // Fall back to the ValueSpecification's generic type
        if (val instanceof Any pe)
        {
            return _E_ValueSpecification.wrap(pe._classifierGenericType(), genericType, multiplicity, resolver);
        }
        return _E_ValueSpecification.wrap(vs._genericType(), genericType, multiplicity, resolver);
    }
}
