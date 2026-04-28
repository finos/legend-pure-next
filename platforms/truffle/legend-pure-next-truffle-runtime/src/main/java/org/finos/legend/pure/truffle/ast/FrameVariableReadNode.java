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

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Reads a local variable from a Truffle {@link VirtualFrame} slot.
 *
 * <p>Used for frame-eligible FunctionDefinitions (see {@link
 * org.finos.legend.pure.truffle.frame.FrameDescriptorBuilder}). The slot
 * index is resolved at AST-build time; runtime lookup is a direct array
 * access that Graal can inline.</p>
 *
 * <p>Variables not in the enclosing layout (captured by lambdas, dynamic
 * type bindings, etc.) lower to {@link VariableReadNode} instead, which
 * walks the HashMap scope.</p>
 */
@NodeInfo(shortName = "frameVarRead")
public final class FrameVariableReadNode extends PureNode
{
    @CompilationFinal
    private final int slot;

    @CompilationFinal
    private final String name;

    public FrameVariableReadNode(int slot, String name)
    {
        this.slot = slot;
        this.name = name;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        if (slot < 0)
        {
            CompilerDirectives.transferToInterpreter();
            int dynSlot = findSlotByName(frame.getFrameDescriptor());
            if (dynSlot >= 0)
            {
                Object v = frame.getObject(dynSlot);
                if (v != null) return v;
            }
            throw new org.finos.legend.pure.truffle.ast.PureException("Unknown variable: " + name, this);
        }
        Object value = frame.getObject(slot);
        if (value == null)
        {
            CompilerDirectives.transferToInterpreter();
            int dynSlot = findSlotByName(frame.getFrameDescriptor());
            if (dynSlot >= 0)
            {
                Object v = frame.getObject(dynSlot);
                if (v != null) return v;
            }
            throw new org.finos.legend.pure.truffle.ast.PureException("Unknown variable: " + name, this);
        }
        return value;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private int findSlotByName(com.oracle.truffle.api.frame.FrameDescriptor desc)
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
}
