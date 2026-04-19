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
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.Multiplicity;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.truffle.pdb.meta.pure.functions.lang.KeyExpressionImpl;
import org.finos.legend.pure.truffle.ast.PureNode;

/**
 * {@code keyExpression(String[1], Any[*]) : KeyExpression[1]}.
 * Creates a KeyExpressionImpl representing a key-value pair.
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
        return doKeyExpression(values);
    }

    @TruffleBoundary
    private static Object doKeyExpression(Object[] values)
    {
        KeyExpressionImpl keyExpr = new KeyExpressionImpl();
        keyExpr._name((String) values[0]);
        keyExpr._expression(values[1]);
        if (values.length > 2)
        {
            keyExpr._add((Boolean) values[2]);
        }
        return keyExpr;
    }
}
