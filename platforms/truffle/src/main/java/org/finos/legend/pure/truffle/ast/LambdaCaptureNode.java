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
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.AtomicValueImpl;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.execution.Closure;
import org.finos.legend.pure.truffle.frame.FrameLayout;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;

import java.util.HashMap;
import java.util.Map;

/**
 * Wraps an {@link AtomicValue} that holds a {@link LambdaFunction} with
 * open variables. At execute time we snapshot the current scope bindings
 * for those open variables into a {@link Closure} so the lambda carries
 * its own captured bindings.
 *
 * <p>Snapshot source: for each open variable, we check the enclosing FD's
 * {@link FrameLayout} first — if the name has a slot, we read it from the
 * current {@link VirtualFrame}. Slot values take precedence over any
 * HashMap-scope binding because a frame-eligible enclosing FD only pushes
 * an empty placeholder into the HashMap. If the slot lookup misses (the
 * enclosing FD is non-frame-eligible, or the name is captured from an
 * even-outer closure), we fall back to the HashMap scope — mirroring the
 * Java evaluator's {@code captureLambdaClosureIfNeeded}.</p>
 *
 * <p>Only emitted when the AST builder detects a lambda with
 * {@code _openVariables()}; trivial (non-closure) lambdas and
 * non-lambda AtomicValues continue to use the fast {@link AtomicValueNode}.</p>
 */
@NodeInfo(shortName = "lambdaCapture")
public final class LambdaCaptureNode extends PureNode
{
    @CompilationFinal
    private final AtomicValue template;

    @CompilationFinal
    private final LambdaFunction lambda;

    @CompilationFinal(dimensions = 1)
    private final String[] openVarNames;

    // Slot indices for each open var in the enclosing FD's frame layout.
    // -1 means "not in the enclosing frame layout" — fall back to HashMap.
    // Populated at construction (lowering) time from the builder's
    // current layout. Null means the enclosing FD has no frame layout at
    // all (pre-Phase-3 HashMap path).
    @CompilationFinal(dimensions = 1)
    private final int[] openVarSlots;

    public LambdaCaptureNode(AtomicValue template, LambdaFunction lambda,
                             MutableList<VariableExpression> openVars,
                             FrameLayout enclosingLayout)
    {
        this.template = template;
        this.lambda = lambda;
        String[] names = new String[openVars.size()];
        int[] slots = enclosingLayout != null ? new int[openVars.size()] : null;
        for (int i = 0; i < openVars.size(); i++)
        {
            String name = openVars.get(i)._name();
            names[i] = name;
            if (slots != null)
            {
                Integer s = enclosingLayout.slotFor(name);
                slots[i] = s == null ? -1 : s;
            }
        }
        this.openVarNames = names;
        this.openVarSlots = slots;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        // Pre-read slot values out of the frame (non-boundary), then hand
        // the resolved VSes to the boundary method for HashMap construction.
        // @TruffleBoundary can't accept a VirtualFrame parameter.
        ValueSpecification[] slotValues = null;
        if (openVarSlots != null && frame != null)
        {
            slotValues = new ValueSpecification[openVarSlots.length];
            for (int i = 0; i < openVarSlots.length; i++)
            {
                if (openVarSlots[i] >= 0)
                {
                    Object slotVal = frame.getObject(openVarSlots[i]);
                    if (slotVal != null)
                    {
                        slotValues[i] = org.finos.legend.pure.truffle.types.ValueAdapter.ensureVS(slotVal);
                    }
                }
            }
        }
        return capture(slotValues);
    }

    @TruffleBoundary
    private AtomicValue capture(ValueSpecification[] slotValues)
    {
        Map<String, ValueSpecification> scope = EvaluatorHolder.current().currentScope();
        Map<String, ValueSpecification> captured = new HashMap<>();
        for (int i = 0; i < openVarNames.length; i++)
        {
            String name = openVarNames[i];
            ValueSpecification binding = slotValues != null ? slotValues[i] : null;
            if (binding == null)
            {
                binding = scope.get(name);
            }
            if (binding != null)
            {
                captured.put(name, binding);
            }
        }
        Closure closure = new Closure(lambda, captured);
        return (AtomicValue) ((AtomicValueImpl) template._copy())
                ._value(closure)
                ._genericType(template._genericType());
    }
}
