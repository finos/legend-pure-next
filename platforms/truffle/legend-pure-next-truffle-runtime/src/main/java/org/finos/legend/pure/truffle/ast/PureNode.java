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
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Base class for all Pure Truffle AST nodes.
 */
@NodeInfo(language = "pure")
public abstract class PureNode extends Node
{
    @CompilationFinal
    private SourceSection pureSourceSection;

    public void setPureSourceSection(SourceSection section)
    {
        this.pureSourceSection = section;
    }

    @Override
    public SourceSection getSourceSection()
    {
        return pureSourceSection;
    }

    public abstract Object executeGeneric(VirtualFrame frame);

    /**
     * Specialised entry point for nodes whose Pure type is {@code Boolean[1]}.
     * Producers (e.g. {@link org.finos.legend.pure.truffle.ast.natives.boolean_.NotBooleanNode},
     * {@link org.finos.legend.pure.truffle.ast.natives.lang.AndNode}) override
     * this to return a primitive {@code boolean} directly — letting consumers
     * (e.g. {@link org.finos.legend.pure.truffle.ast.natives.lang.IfNode}'s
     * condition) skip the box / unbox pair and keep the value on the JVM
     * stack.
     *
     * <p>The default implementation unboxes from {@link #executeGeneric}; a
     * non-Boolean result deopts to the interpreter and throws — Pure's type
     * system guarantees this branch is unreachable for well-typed code.</p>
     */
    public boolean executeBoolean(VirtualFrame frame)
    {
        Object value = executeGeneric(frame);
        if (value instanceof Boolean b)
        {
            return b;
        }
        com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new ClassCastException("expected Boolean, got: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    protected final org.finos.legend.pure.truffle.PureContext getContext()
    {
        return org.finos.legend.pure.truffle.PureLanguage.get(this);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    protected final org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess getResolver()
    {
        return getContext().resolver();
    }
}
