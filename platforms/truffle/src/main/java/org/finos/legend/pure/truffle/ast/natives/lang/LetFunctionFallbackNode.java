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

package org.finos.legend.pure.truffle.ast.natives.lang;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.StandaloneEvaluator;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.frame.FrameLayout;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;

/**
 * Fallback node for {@code letFunction_String_1__T_m__T_m_} when the
 * slot-based {@code FrameLetFunctionNode} path could not be used at
 * lowering time (e.g. because the variable name was not in the layout).
 *
 * <p>At runtime, this node evaluates the variable name and value, then
 * writes the value into the current frame using the evaluator's active
 * layout. Returns the value so the enclosing expression sequence can
 * continue to observe it.</p>
 */
@NodeInfo(shortName = "letFallback")
public final class LetFunctionFallbackNode extends PureNode
{
    @Child
    private PureNode nameNode;

    @Child
    private PureNode valueNode;

    public LetFunctionFallbackNode(PureNode nameNode, PureNode valueNode)
    {
        this.nameNode = nameNode;
        this.valueNode = valueNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object nameObj = nameNode.executeGeneric(frame);
        Object value = valueNode.executeGeneric(frame);
        String name = nameObj instanceof String s ? s : String.valueOf(nameObj);
        // Look up the slot in the frame's own descriptor first
        int slot = resolveSlotInFrame(name, frame.getFrameDescriptor());
        if (slot < 0)
        {
            slot = resolveSlotInLayout(name);
        }
        if (slot >= 0)
        {
            frame.setObject(slot, value);
        }
        return value;
    }

    private static int resolveSlotInFrame(String name, com.oracle.truffle.api.frame.FrameDescriptor desc)
    {
        for (int i = 0; i < desc.getNumberOfSlots(); i++)
        {
            if (name.equals(desc.getSlotName(i)))
            {
                return i;
            }
        }
        return -1;
    }

    private static int resolveSlotInLayout(String name)
    {
        StandaloneEvaluator eval = StandaloneEvaluatorHolder.current();
        if (eval != null && eval.currentLayout() != null)
        {
            Integer slot = eval.currentLayout().slotFor(name);
            if (slot != null)
            {
                return slot;
            }
        }
        return -1;
    }
}
