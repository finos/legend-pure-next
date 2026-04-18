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

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.Closure;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.frame.FrameLayout;

import java.util.Map;

/**
 * Root node for a compiled {@link LambdaFunction}, wrapped in a
 * {@code CallTarget} so call sites can invoke it via
 * {@code com.oracle.truffle.api.nodes.DirectCallNode} and let Graal inline
 * the body into the enclosing hot loop.
 *
 * <p>Invocation arguments: {@code [closureVS, arg0, arg1, ...]}. The
 * closure is at index 0 so {@link #execute} can reach its
 * {@link Closure#capturedScope()} to fill open-var slots. Remaining
 * entries are the positional parameters (already wrapped as
 * {@link ValueSpecification} — no additional type coercion here; the
 * calling convention mirrors
 * {@link TruffleEvaluator#evaluateFunctionDefinition}).</p>
 */
public final class LambdaRootNode extends RootNode
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

    @Children
    private final Node[] body;

    public LambdaRootNode(PureLanguage language, String name, FrameLayout layout,
                          String[] openVarNames, PureNode[] body)
    {
        super(language, layout.descriptor());
        this.name = name;
        this.layout = layout;
        this.paramSlots = layout.paramSlots();
        this.openVarNames = openVarNames;
        int[] slots = new int[openVarNames.length];
        for (int i = 0; i < openVarNames.length; i++)
        {
            Integer s = layout.slotFor(openVarNames[i]);
            slots[i] = s == null ? -1 : s;
        }
        this.openVarSlots = slots;
        // Copy via Node[].class to avoid the @Children / specialized-array
        // cast issue we hit in native-image with concrete PureNode[] arrays.
        this.body = java.util.Arrays.copyOf(body, body.length, Node[].class);
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame)
    {
        Object[] arguments = frame.getArguments();
        Object closureOrLambda = arguments[0];

        // Bind param slots from arguments[1..].
        int paramCount = paramSlots.length;
        for (int i = 0; i < paramCount; i++)
        {
            frame.setObject(paramSlots[i], arguments[i + 1]);
        }

        // Bind open-var slots from Closure.capturedScope() + the current
        // HashMap scope (for dynamic bindings like QP type variables).
        bindOpenVars(frame, closureOrLambda);

        // Install this lambda's context (layout + current frame + placeholder
        // HashMap scope) so sub-expressions that re-enter the evaluator via
        // {@code eval.evaluate(subVS)} still resolve slot-based variable reads.
        // Mirrors {@link TruffleEvaluator#executeWithFrame}.
        TruffleEvaluator eval = currentEvaluator();
        return eval.runLambdaBody(this, frame);
    }

    @CompilerDirectives.TruffleBoundary
    private static TruffleEvaluator currentEvaluator()
    {
        return (TruffleEvaluator) org.finos.legend.pure.truffle.runtime.EvaluatorHolder.current();
    }

    /**
     * Executes the lambda body in the given frame. Called only from
     * {@link TruffleEvaluator#runLambdaBody} after that method has installed
     * the layout/frame/scope context.
     */
    @ExplodeLoop
    public Object executeBody(VirtualFrame frame)
    {
        Object result = null;
        for (int i = 0; i < body.length; i++)
        {
            result = ((PureNode) body[i]).executeGeneric(frame);
        }
        return org.finos.legend.pure.truffle.types.ValueAdapter.ensureVS(result);
    }

    public FrameLayout layout()
    {
        return layout;
    }

    @ExplodeLoop
    private void bindOpenVars(VirtualFrame frame, Object closureOrLambda)
    {
        if (openVarNames.length == 0)
        {
            return;
        }
        Map<String, ValueSpecification> captured =
                (closureOrLambda instanceof Closure c) ? c.capturedScope() : null;
        Map<String, ValueSpecification> dynamic = dynamicScope();
        for (int i = 0; i < openVarNames.length; i++)
        {
            int slot = openVarSlots[i];
            if (slot < 0)
            {
                continue;
            }
            Object value = null;
            if (captured != null)
            {
                ValueSpecification vs = captured.get(openVarNames[i]);
                if (vs != null)
                {
                    value = unwrapIfPrimitive(vs);
                }
            }
            if (value == null && dynamic != null)
            {
                ValueSpecification vs = dynamic.get(openVarNames[i]);
                if (vs != null)
                {
                    value = unwrapIfPrimitive(vs);
                }
            }
            if (value != null)
            {
                frame.setObject(slot, value);
            }
        }
    }

    private static Object unwrapIfPrimitive(ValueSpecification vs)
    {
        if (vs instanceof meta.pure.metamodel.valuespecification.AtomicValue av)
        {
            Object inner = av._value();
            if (inner instanceof Long || inner instanceof Double || inner instanceof Boolean)
            {
                return inner;
            }
            if (inner instanceof String)
            {
                meta.pure.metamodel.type.generics.GenericType gt = av._genericType();
                if (gt == null)
                {
                    return inner;
                }
                meta.pure.metamodel.type.Type type =
                        org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(gt);
                if (type instanceof meta.pure.metamodel.PackageableElement pe && "String".equals(pe._name()))
                {
                    return inner;
                }
            }
        }
        return vs;
    }

    @CompilerDirectives.TruffleBoundary
    private static Map<String, ValueSpecification> dynamicScope()
    {
        return org.finos.legend.pure.truffle.runtime.EvaluatorHolder.current().currentScope();
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public String toString()
    {
        return "LambdaRootNode[" + name + "]";
    }
}
