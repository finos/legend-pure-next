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

package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Replaces the bridged {@code letFunction_String_1__T_m__T_m_} native for
 * frame-eligible FunctionDefinitions. Evaluates the value expression,
 * writes it into the target's pre-allocated frame slot, and returns the
 * value so the enclosing expression sequence can continue to observe it.
 *
 * <p>Matches the Java reference semantics ({@code LangNatives.letFunction}):
 * the let expression evaluates to the bound value.</p>
 */
@NodeInfo(shortName = "frameLet")
public final class FrameLetFunctionNode extends PureNode
{
    @CompilationFinal
    private final int slot;

    @Child
    private PureNode valueNode;

    public FrameLetFunctionNode(int slot, PureNode valueNode)
    {
        this.slot = slot;
        this.valueNode = valueNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object value = valueNode.executeGeneric(frame);
        // In Pure, null is not a valid value — it represents the empty set.
        // Store EMPTY instead of null so FrameVariableReadNode can distinguish
        // "set to empty" from "not yet set" (Java-null default).
        if (value == null)
        {
            value = org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        }
        frame.setObject(slot, value);
        return value;
    }
}
