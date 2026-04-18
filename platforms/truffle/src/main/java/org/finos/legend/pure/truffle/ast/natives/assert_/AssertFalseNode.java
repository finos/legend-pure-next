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

package org.finos.legend.pure.truffle.ast.natives.assert_;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.valuespecification.AtomicValue;
import org.finos.legend.pure.execution.PureAssertionError;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.string.StringHelper;

/**
 * {@code assertFalse(Boolean[1]) : Boolean[1]} — asserts the value is false.
 * {@code assertFalse(Boolean[1], String[1]) : Boolean[1]} — asserts false with message.
 */
@NodeInfo(shortName = "assertFalse")
public final class AssertFalseNode extends PureNode
{
    @Child
    private PureNode valueArg;

    @Child
    private PureNode messageArg;

    public AssertFalseNode(PureNode valueArg, PureNode messageArg)
    {
        this.valueArg = valueArg;
        this.messageArg = messageArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = valueArg.executeGeneric(frame);
        boolean value = asBoolean(v);
        if (value)
        {
            if (messageArg != null)
            {
                String msg = StringHelper.asString(messageArg.executeGeneric(frame), "assertFalse");
                throw new PureAssertionError(msg);
            }
            throw new PureAssertionError("assertFalse failed: value was true");
        }
        return true;
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
        return false;
    }
}
