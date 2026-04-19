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

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.frame.FrameLayout;
import org.finos.legend.pure.truffle.types.PureNull;

/**
 * RootNode for user-defined FunctionDefinition bodies — binds parameters
 * from {@code frame.getArguments()} before executing the pre-compiled body.
 *
 * <p>Arguments layout: {@code [arg0, arg1, ...]}.</p>
 *
 * <p>Body nodes are adopted via {@code @Children}, ensuring proper Truffle
 * node tree management. The body is lowered once at compile time and
 * reused across all invocations.</p>
 */
public final class PureFunctionRootNode extends RootNode
{
    @CompilationFinal
    private final String name;

    @CompilationFinal
    private final FrameLayout layout;

    @CompilationFinal(dimensions = 1)
    private final int[] paramSlots;

    @Children
    private final Node[] body;

    public PureFunctionRootNode(PureLanguage language, String name,
                                FrameLayout layout, PureNode[] body)
    {
        super(language, layout.descriptor());
        this.name = name;
        this.layout = layout;
        this.paramSlots = layout.paramSlots();
        this.body = java.util.Arrays.copyOf(body, body.length, Node[].class);
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame)
    {
        Object[] arguments = frame.getArguments();

        // Bind params from arguments[0..]
        for (int i = 0; i < paramSlots.length && i < arguments.length; i++)
        {
            frame.setObject(paramSlots[i], arguments[i] != null ? arguments[i] : PureNull.INSTANCE);
        }


        // Set evaluator context for nodes that need dynamic layout lookup
        StandaloneEvaluator eval = org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder.current();
        FrameLayout prevLayout = eval.currentLayout();
        VirtualFrame prevFrame = eval.currentFrame();
        eval.setCurrentLayout(layout);
        eval.setCurrentFrame(frame);
        FrameLayout prevBuilderLayout = eval.astBuilder().pushLayout(layout);
        try
        {
            Object result = PureNull.INSTANCE;
            for (int i = 0; i < body.length; i++)
            {
                result = ((PureNode) body[i]).executeGeneric(frame);
            }
            return result;
        }
        finally
        {
            eval.astBuilder().popLayout(prevBuilderLayout);
            eval.setCurrentLayout(prevLayout);
            eval.setCurrentFrame(prevFrame);
        }
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public String toString()
    {
        return "PureFunctionRootNode[" + name + "]";
    }

}
