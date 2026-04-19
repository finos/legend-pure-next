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

package org.finos.legend.pure.truffle.ast.natives.boolean_;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue;
import org.finos.legend.pure.truffle.ast.PureNode;

@NodeInfo(shortName = "notBool")
public final class NotBooleanNode extends PureNode
{
    private static final String SIG = "not_Boolean_1__Boolean_1_";

    @Child
    private PureNode operand;

    public NotBooleanNode(PureNode operand)
    {
        this.operand = operand;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        boolean b = asBoolean(operand.executeGeneric(frame));
        return !b;
    }

    private static boolean asBoolean(Object v)
    {
        if (v instanceof Boolean b)
        {
            return b;
        }
        if (v instanceof AtomicValue av && av._value() instanceof Boolean b)
        {
            return b;
        }
        throw new ClassCastException(SIG + " expected Boolean, got: "
                + (v == null ? "null" : v.getClass().getName()));
    }
}
