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
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code keyExpression(String[1], Any[*]) : KeyExpression[1]} and
 * {@code keyExpression(String[1], Any[*], Boolean[1]) : KeyExpression[1]}.
 *
 * <p>Creates a DynamicInstance representing a key-value pair with {name, expression}
 * and optionally an {add} flag.</p>
 */
@NodeInfo(shortName = "keyExpression")
public final class KeyExpressionNode extends PureNode
{
    @Children
    private PureNode[] children;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public KeyExpressionNode(PureNode[] children, GenericType genericType, Multiplicity multiplicity)
    {
        this.children = children;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] values = new Object[children.length];
        for (int i = 0; i < children.length; i++)
        {
            values[i] = children[i].executeGeneric(frame);
        }
        return doKeyExpression(values, genericType, multiplicity);
    }

    @TruffleBoundary
    private static ValueSpecification doKeyExpression(Object[] values, GenericType genericType, Multiplicity multiplicity)
    {
        MetadataAccess resolver = EvaluatorHolder.current().natives().resolver();

        ValueSpecification keyVS = ValueAdapter.ensureVS(values[0]);
        ValueSpecification valueVS = ValueAdapter.ensureVS(values[1]);

        DynamicInstance keyExpr = new DynamicInstance("meta::pure::functions::lang::KeyExpression");
        keyExpr.put("name", keyVS);
        keyExpr.put("expression", valueVS);
        if (values.length > 2)
        {
            keyExpr.put("add", ValueAdapter.ensureVS(values[2]));
        }
        return _E_ValueSpecification.wrap(keyExpr, genericType, multiplicity, resolver);
    }
}
