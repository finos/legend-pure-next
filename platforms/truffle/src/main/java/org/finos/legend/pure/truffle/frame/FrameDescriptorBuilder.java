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

package org.finos.legend.pure.truffle.frame;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.function.NativeFunction;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.list.MutableList;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static analysis that builds a {@link FrameLayout} from a {@link
 * FunctionDefinition}.
 *
 * <p>Eligibility: any {@link FunctionDefinition} that is not itself a
 * {@link LambdaFunction} is frame-eligible. A FD that contains lambdas in
 * its body is still eligible — the lambda's
 * {@link org.finos.legend.pure.truffle.ast.LambdaCaptureNode} reads its
 * open variables from the enclosing frame's slots at capture time (see
 * Phase 6). LambdaFunctions themselves stay on the HashMap / Closure
 * path in the Java evaluator because their invocation model relies on
 * {@code closure.capturedScope()} being consulted in
 * {@link org.finos.legend.pure.execution.ValueSpecificationEvaluator#evaluateFunctionDefinition}.</p>
 *
 * <p>Slot allocation: one Object-typed slot per parameter and per
 * {@code letFunction} target name. Control flow inside the FD may
 * reassign the same name; a single slot per name suffices since Pure's
 * {@code let} is a rebind rather than an SSA value.</p>
 */
public final class FrameDescriptorBuilder
{
    private static final String LET_FUNCTION_SIGNATURE = "letFunction_String_1__T_m__T_m_";

    private FrameDescriptorBuilder()
    {
    }

    /**
     * Analyze {@code fd}. Returns a {@link FrameLayout} if the FD is
     * frame-eligible, {@code null} otherwise (caller falls back to HashMap
     * scope).
     */
    public static FrameLayout analyze(FunctionDefinition fd)
    {
        if (fd instanceof LambdaFunction lambda)
        {
            return analyzeLambda(lambda);
        }

        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        Map<String, Integer> slots = new LinkedHashMap<>();
        MutableList<VariableExpression> params = fd._parameters();
        int[] paramSlots = new int[params == null ? 0 : params.size()];

        if (params != null)
        {
            for (int i = 0; i < params.size(); i++)
            {
                String name = params.get(i)._name();
                int slot = allocateSlot(builder, slots, name);
                paramSlots[i] = slot;
            }
        }

        collectLetTargets(fd._expressionSequence(), builder, slots);
        // Safety net: scan for variable reads to catch let targets missed
        // by collectLetTargets (e.g. when FlatBuffer _func() resolution fails)
        collectVariableReads(fd._expressionSequence(), builder, slots);

        return new FrameLayout(builder.build(), slots, paramSlots);
    }

    /**
     * Minimal layout with only parameter slots — used by
     * {@code StandaloneEvaluator} when {@link #analyze} returns null
     * (should not happen once all FDs are frame-eligible, but provides
     * a safe fallback during the transition).
     */
    public static FrameLayout analyzeMinimal(FunctionDefinition fd)
    {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        Map<String, Integer> slots = new LinkedHashMap<>();
        MutableList<VariableExpression> params = fd._parameters();
        int[] paramSlots = new int[params == null ? 0 : params.size()];
        if (params != null)
        {
            for (int i = 0; i < params.size(); i++)
            {
                paramSlots[i] = allocateSlot(builder, slots, params.get(i)._name());
            }
        }
        return new FrameLayout(builder.build(), slots, paramSlots);
    }

    /**
     * Build a layout for a {@link LambdaFunction}: one slot per parameter,
     * one slot per open variable (captured from the enclosing scope), and
     * one slot per {@code let} target inside the lambda body.
     *
     * <p>The caller binds param slots from the invocation's arguments and
     * open-var slots from the
     * {@link org.finos.legend.pure.execution.Closure#capturedScope()} map
     * before executing the body. Reads of open vars inside the body then
     * resolve via {@link org.finos.legend.pure.truffle.ast.FrameVariableReadNode}
     * instead of falling through to the HashMap scope.</p>
     */
    private static FrameLayout analyzeLambda(LambdaFunction lambda)
    {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        Map<String, Integer> slots = new LinkedHashMap<>();

        MutableList<VariableExpression> params = lambda._parameters();
        int[] paramSlots = new int[params == null ? 0 : params.size()];
        if (params != null)
        {
            for (int i = 0; i < params.size(); i++)
            {
                paramSlots[i] = allocateSlot(builder, slots, params.get(i)._name());
            }
        }

        MutableList<VariableExpression> openVars = lambda._openVariables();
        if (openVars != null)
        {
            for (VariableExpression ov : openVars)
            {
                allocateSlot(builder, slots, ov._name());
            }
        }

        collectLetTargets(lambda._expressionSequence(), builder, slots);

        return new FrameLayout(builder.build(), slots, paramSlots);
    }

    private static int allocateSlot(FrameDescriptor.Builder builder, Map<String, Integer> slots, String name)
    {
        Integer existing = slots.get(name);
        if (existing != null)
        {
            return existing;
        }
        int slot = builder.addSlot(FrameSlotKind.Object, name, null);
        slots.put(name, slot);
        return slot;
    }

    private static void collectLetTargets(Iterable<ValueSpecification> exprs,
                                          FrameDescriptor.Builder builder,
                                          Map<String, Integer> slots)
    {
        if (exprs == null)
        {
            return;
        }
        // Pure's `let` is a top-level statement form inside a function body
        // (`let x = ...;`). We only scan the expression-sequence roots; let
        // never appears nested inside another expression. Walking deeper
        // also triggers FlatBuffer wrappers to resolve QP chains eagerly,
        // which can fail with out-of-bounds reads on not-yet-executed
        // references — the top-level-only scan avoids that hazard too.
        for (ValueSpecification expr : exprs)
        {
            collectLetTargets(expr, builder, slots);
        }
    }

    private static void collectLetTargets(ValueSpecification vs,
                                          FrameDescriptor.Builder builder,
                                          Map<String, Integer> slots)
    {
        if (!(vs instanceof FunctionExpression fe))
        {
            return;
        }
        boolean isLet = false;
        try
        {
            if (fe._func() instanceof NativeFunction nf
                    && LET_FUNCTION_SIGNATURE.equals(nf._name()))
            {
                isLet = true;
            }
        }
        catch (RuntimeException ignored)
        {
            // FlatBuffer wrapper may fail to resolve _func() lazily.
            // Fall back to checking _functionName().
        }
        if (!isLet && "letFunction".equals(fe._functionName()))
        {
            isLet = true;
        }
        if (isLet)
        {
            String name = extractLetName(fe);
            if (name != null)
            {
                allocateSlot(builder, slots, name);
            }
        }
    }

    private static boolean containsLambda(Iterable<ValueSpecification> exprs)
    {
        if (exprs == null)
        {
            return false;
        }
        for (ValueSpecification expr : exprs)
        {
            if (containsLambdaIn(expr))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean containsLambdaIn(ValueSpecification vs)
    {
        if (vs instanceof AtomicValue av && av._value() instanceof LambdaFunction)
        {
            return true;
        }
        if (vs instanceof meta.pure.metamodel.valuespecification.Collection col)
        {
            for (ValueSpecification child : col._values())
            {
                if (containsLambdaIn(child))
                {
                    return true;
                }
            }
        }
        // Do not descend into FunctionExpression children: resolving a
        // nested FE's structure can touch lazy FlatBuffer wrappers (e.g.
        // DotApplication#_func) whose accessors throw when the target
        // class isn't bound. Top-level and Collection-level detection
        // catches lambdas that appear directly in the expression sequence.
        // Lambdas buried inside FE args (e.g. fold({s,acc|...}, seed))
        // are still safe: the fold itself stays on the bridge or uses
        // LambdaCallNode, and LambdaCaptureNode handles the capture
        // correctly from either frame or HashMap scope.
        return false;
    }

    private static String extractLetName(FunctionExpression fe)
    {
        MutableList<ValueSpecification> args = fe._parametersValues();
        if (args == null || args.isEmpty())
        {
            return null;
        }
        ValueSpecification first = args.get(0);
        if (first instanceof AtomicValue av && av._value() instanceof String s)
        {
            return s;
        }
        return null;
    }

    /**
     * Scan the expression sequence for VariableExpression reads and
     * allocate slots for any variable names not yet present in the layout.
     * This is a safety net: if {@link #collectLetTargets} missed a
     * {@code let} definition (e.g. because the FlatBuffer wrapper's
     * {@code _func()} could not be resolved), the corresponding
     * VariableExpression read will still have a slot. The slot starts
     * as null and will be populated by the {@code LetFunctionFallbackNode}
     * at runtime.
     */
    private static void collectVariableReads(Iterable<ValueSpecification> exprs,
                                             FrameDescriptor.Builder builder,
                                             Map<String, Integer> slots)
    {
        if (exprs == null)
        {
            return;
        }
        for (ValueSpecification vs : exprs)
        {
            scanForVariableReads(vs, builder, slots);
        }
    }

    private static void scanForVariableReads(ValueSpecification vs,
                                             FrameDescriptor.Builder builder,
                                             Map<String, Integer> slots)
    {
        if (vs instanceof VariableExpression ve)
        {
            String name = ve._name();
            if (name != null)
            {
                allocateSlot(builder, slots, name);
            }
            return;
        }
        if (vs instanceof AtomicValue av)
        {
            // Don't recurse into lambdas — they get their own layout
            return;
        }
        if (vs instanceof Collection col && col._values() != null)
        {
            for (ValueSpecification child : col._values())
            {
                scanForVariableReads(child, builder, slots);
            }
            return;
        }
        if (vs instanceof FunctionExpression fe)
        {
            try
            {
                MutableList<ValueSpecification> args = fe._parametersValues();
                if (args != null)
                {
                    for (ValueSpecification arg : args)
                    {
                        scanForVariableReads(arg, builder, slots);
                    }
                }
            }
            catch (RuntimeException ignored)
            {
                // FlatBuffer lazy resolution may fail — skip gracefully
            }
        }
    }

}
