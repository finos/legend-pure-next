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
import org.finos.legend.pure.truffle.types.PureSequence;

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

    @CompilationFinal
    private final com.oracle.truffle.api.source.SourceSection rootSourceSection;

    /**
     * {@code true} iff this function may need {@link
     * PureContext#bindQpTypeVariablesStatic} to install type-variable values
     * from {@code arguments[0]}'s classifier generic type — i.e., it is a
     * {@code QualifiedProperty} whose owner has type variables. The vast
     * majority of compiler-pure user functions are plain
     * {@code FunctionDefinition}s with no type-variable binding to do, so
     * baking this in compilation-final lets PE drop the QP path entirely
     * (saving a property read + instanceof chain + slot-name lookup on every
     * call). Currently set conservatively from {@code fd instanceof
     * QualifiedProperty}.
     */
    @CompilationFinal
    private final boolean mayBindTypeVars;

    public PureFunctionRootNode(PureLanguage language, String name,
                                FrameLayout layout, PureNode[] body)
    {
        this(language, name, layout, body, null, false);
    }

    public PureFunctionRootNode(PureLanguage language, String name,
                                FrameLayout layout, PureNode[] body,
                                com.oracle.truffle.api.source.SourceSection sourceSection)
    {
        this(language, name, layout, body, sourceSection, false);
    }

    public PureFunctionRootNode(PureLanguage language, String name,
                                FrameLayout layout, PureNode[] body,
                                com.oracle.truffle.api.source.SourceSection sourceSection,
                                boolean mayBindTypeVars)
    {
        super(language, layout.descriptor());
        this.name = name;
        this.layout = layout;
        this.paramSlots = layout.paramSlots();
        this.body = java.util.Arrays.copyOf(body, body.length, Node[].class);
        this.rootSourceSection = sourceSection;
        this.mayBindTypeVars = mayBindTypeVars;
    }

    @Override
    public com.oracle.truffle.api.source.SourceSection getSourceSection()
    {
        return rootSourceSection;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame)
    {
        // Profiler hook — short-circuits to a single field load when
        // disabled. Skipped on exceptional exit (uncommon — propagates as
        // a Pure runtime error and the profiler frame stays on the
        // stack until the next enter prunes it).
        org.finos.legend.pure.truffle.profiler.PureProfiler.enter(name);

        Object[] arguments = frame.getArguments();

        // Bind params from arguments[0..]
        for (int i = 0; i < paramSlots.length && i < arguments.length; i++)
        {
            frame.setObject(paramSlots[i], arguments[i] != null ? arguments[i] : PureSequence.EMPTY);
        }


        // Bind type variables from the target's CGT (for QPs with type parameters).
        // mayBindTypeVars is @CompilationFinal so PE folds this whole branch out
        // for plain FunctionDefinitions — which is most of compiler-pure.
        if (mayBindTypeVars && arguments.length > 0 && arguments[0] != null)
        {
            PureContext.bindQpTypeVariablesStatic(arguments[0], frame, layout);
        }

        Object result = PureSequence.EMPTY;
        for (int i = 0; i < body.length; i++)
        {
            result = ((PureNode) body[i]).executeGeneric(frame);
        }

        org.finos.legend.pure.truffle.profiler.PureProfiler.exit();
        return result;
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

    @Override
    protected com.oracle.truffle.api.nodes.ExecutionSignature prepareForAOT()
    {
        return com.oracle.truffle.api.nodes.ExecutionSignature.create(Object.class, new Class<?>[]{Object[].class});
    }
}
