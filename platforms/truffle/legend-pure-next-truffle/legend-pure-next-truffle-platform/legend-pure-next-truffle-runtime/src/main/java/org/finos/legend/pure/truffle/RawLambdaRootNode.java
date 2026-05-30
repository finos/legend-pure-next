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
import org.finos.legend.pure.truffle.ast.RawClosure;

/**
 * RootNode for lambda bodies — binds parameters and captured variables
 * from {@code frame.getArguments()} before executing the body.
 *
 * <p>Arguments layout: {@code [lambdaOrClosure, arg0, arg1, ...]}.</p>
 *
 * <p>No ValueSpecification anywhere — all values are raw.</p>
 */
public final class RawLambdaRootNode extends RootNode
{
    @CompilationFinal
    private final String name;

    @CompilationFinal
    private final FrameLayout layout;

    @CompilationFinal(dimensions = 1)
    private final int[] paramSlots;

    @CompilationFinal(dimensions = 1)
    private final String[] openVarNames;

    @CompilationFinal(dimensions = 1)
    private final int[] openVarSlots;

    /** Per-param declared multiplicity bounds — see {@link PureFunctionRootNode}. */
    @CompilationFinal(dimensions = 1)
    private final long[] paramUpperBound;
    @CompilationFinal(dimensions = 1)
    private final long[] paramLowerBound;

    @Children
    private final Node[] body;

    private static final long[] EMPTY_LONG = new long[0];

    public RawLambdaRootNode(PureLanguage language, String name, FrameLayout layout,
                             int[] paramSlots, String[] openVarNames, PureNode[] body)
    {
        this(language, name, layout, paramSlots, openVarNames, body, EMPTY_LONG, EMPTY_LONG);
    }

    public RawLambdaRootNode(PureLanguage language, String name, FrameLayout layout,
                             int[] paramSlots, String[] openVarNames, PureNode[] body,
                             long[] paramUpperBound, long[] paramLowerBound)
    {
        super(language, layout.descriptor());
        this.name = name;
        this.layout = layout;
        this.paramSlots = paramSlots;
        this.openVarNames = openVarNames;
        int[] slots = new int[openVarNames.length];
        for (int i = 0; i < openVarNames.length; i++)
        {
            Integer s = layout.slotFor(openVarNames[i]);
            slots[i] = s == null ? -1 : s;
        }
        this.openVarSlots = slots;
        this.body = java.util.Arrays.copyOf(body, body.length, Node[].class);
        this.paramUpperBound = paramUpperBound != null ? paramUpperBound : EMPTY_LONG;
        this.paramLowerBound = paramLowerBound != null ? paramLowerBound : EMPTY_LONG;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame)
    {
        org.finos.legend.pure.truffle.profiler.PureProfiler.enter(name);

        Object[] arguments = frame.getArguments();
        Object closureOrLambda = arguments[0];

        // Bind params from arguments[1..] with caller-driven shape coercion
        // (see PureFunctionRootNode#coerceArgToParamShape).
        boolean haveShapeInfo = paramUpperBound.length > 0;
        for (int i = 0; i < paramSlots.length; i++)
        {
            Object arg = arguments[i + 1];
            if (haveShapeInfo && i < paramUpperBound.length && arg != null)
            {
                arg = PureFunctionRootNode.coerceArgToParamShape(arg, paramUpperBound[i], paramLowerBound[i], i);
            }
            frame.setObject(paramSlots[i], arg);
        }
        // Bind open vars from RawClosure captured values. Iterate
        // openVarSlots.length (compilation-final) so @ExplodeLoop above
        // can fully unroll this loop and Graal can virtualise the frame
        // — using captured.length would leave a runtime-bounded loop and
        // force the frame to materialise (Truffle frame-escape bailout).
        // RawLambdaCaptureNode sizes capturedValues from openVarNames.length,
        // which equals openVarSlots.length, so the indices stay in bounds.
        if (closureOrLambda instanceof RawClosure rc)
        {
            Object[] captured = rc.capturedValues();
            for (int i = 0; i < openVarSlots.length; i++)
            {
                if (openVarSlots[i] >= 0)
                {
                    frame.setObject(openVarSlots[i], captured[i]);
                }
            }
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

    /**
     * Truffle's compilation listener logs `target.toString()` (which falls back
     * to `RootNode.toString()` → `Node.toString()` → "ClassName@hashCode").
     * Include the source-located {@code name} so Graal failure messages name
     * the offending lambda's file:line:col instead of an opaque identity hash.
     */
    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "[" + name + "]";
    }

    @Override
    protected com.oracle.truffle.api.nodes.ExecutionSignature prepareForAOT()
    {
        return com.oracle.truffle.api.nodes.ExecutionSignature.create(Object.class, new Class<?>[]{Object[].class});
    }
}
