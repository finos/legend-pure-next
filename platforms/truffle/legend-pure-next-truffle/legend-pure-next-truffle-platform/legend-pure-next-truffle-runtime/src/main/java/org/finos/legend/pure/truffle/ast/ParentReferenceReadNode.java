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
 * Reads a parent-reference variable (`~`, `~.~`, `~.~.~`, …) from the
 * PureLanguage construction stack. The depth is encoded by the number of
 * tildes in the variable name minus one — a compile-time constant resolved
 * by {@code PureASTBuilder.lowerVariableRead} when the name matches the
 * tilde pattern.
 *
 * <p>The construction stack is pushed/popped by the {@code new} and {@code copy}
 * natives. Depth 0 = innermost `^X(...)` (the construction being built right now),
 * depth N = N levels out.</p>
 */
@NodeInfo(shortName = "parentRefRead")
public final class ParentReferenceReadNode extends PureNode
{
    @CompilationFinal
    private final int depth;

    @CompilationFinal
    private final String name;

    public ParentReferenceReadNode(int depth, String name)
    {
        this.depth = depth;
        this.name = name;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object target = org.finos.legend.pure.truffle.PureLanguage.get(this).peekConstruction(depth);
        if (target == null)
        {
            throw new RuntimeException("Parent reference '" + name
                    + "' is out of bounds — no enclosing `^X(...)` construction at depth " + depth + ".");
        }
        return target;
    }
}
