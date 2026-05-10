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
import com.oracle.truffle.api.nodes.ExplodeLoop;
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

    /** Property name node (children[0] — typically a ConstantNode with a String). */
    public PureNode nameNode() { return children[0]; }

    /** Value expression node (children[1]). */
    public PureNode valueNode() { return children[1]; }

    /** Whether this is an add (+=) expression (children[2] if present). */
    public PureNode addNode() { return children.length > 2 ? children[2] : null; }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] values = evaluateChildren(frame);
        return doKeyExpression(values);
    }

    @ExplodeLoop
    private Object[] evaluateChildren(VirtualFrame frame)
    {
        Object[] values = new Object[children.length];
        for (int i = 0; i < children.length; i++)
        {
            values[i] = children[i].executeGeneric(frame);
        }
        return values;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object doKeyExpression(Object[] values)
    {
        KeyExpressionImpl keyExpr = new KeyExpressionImpl();
        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(keyExpr, "name", values[0]);
        Object expr = values[1];
        if (expr instanceof org.finos.legend.pure.truffle.types.PureSequence)
        {
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(keyExpr, "expression", expr);
        }
        else
        {
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(keyExpr, "expression",
                    new org.finos.legend.pure.truffle.types.ObjectSequence(new Object[]{expr}));
        }
        if (values.length > 2)
        {
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(keyExpr, "add", values[2]);
        }
        return keyExpr;
    }
}
