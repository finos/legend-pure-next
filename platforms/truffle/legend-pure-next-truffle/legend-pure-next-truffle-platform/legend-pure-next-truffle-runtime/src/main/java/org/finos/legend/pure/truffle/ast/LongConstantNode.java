// Copyright 2026 Goldman Sachs
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
 * Compile-time {@code Integer[1]} constant. Mirrors {@link AtomicValueNode}
 * but exposes the primitive {@code long} via {@link #executeLong} so a
 * consumer like {@link org.finos.legend.pure.truffle.ast.natives.math.PlusIntegerNode}
 * never has to unbox.
 */
@NodeInfo(shortName = "longConst")
public final class LongConstantNode extends AtomicValueNode
{
    @CompilationFinal
    private final long primitive;

    public LongConstantNode(long value)
    {
        super(value);
        this.primitive = value;
    }

    @Override
    public long executeLong(VirtualFrame frame)
    {
        return primitive;
    }
}
