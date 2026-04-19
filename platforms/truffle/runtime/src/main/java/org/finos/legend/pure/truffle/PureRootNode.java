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

package org.finos.legend.pure.truffle;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.finos.legend.pure.truffle.ast.PureNode;

/**
 * Root node wrapping a Pure AST body. One instance per compiled
 * {@code FunctionDefinition} (or standalone expression).
 */
public final class PureRootNode extends RootNode
{
    @Child
    private PureNode body;

    private final String name;

    public PureRootNode(PureLanguage language, String name, FrameDescriptor frameDescriptor, PureNode body)
    {
        super(language, frameDescriptor);
        this.name = name;
        this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame)
    {
        return body.executeGeneric(frame);
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public String toString()
    {
        return "PureRootNode[" + name + "]";
    }
}
