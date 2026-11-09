// Copyright 2024 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.truffle.execution;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import org.finos.legend.pure.truffle.execution.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.execution.ast.root.FrameLayout;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static analysis that builds a {@link FrameLayout} from a {@link
 * FunctionDefinition}.
 *
 * <p>Eligibility: any {@link FunctionDefinition} that is not itself a
 * {@link LambdaFunction} is frame-eligible. A FD that contains lambdas in
 * its body is still eligible — the lambda's
 * {@link org.finos.legend.pure.truffle.execution.ast.variable.RawLambdaCaptureNode} reads its
 * open variables from the enclosing frame's slots at capture time (see
 * Phase 6). LambdaFunctions themselves stay on the HashMap / Closure
 * path in the Java evaluator because their invocation model relies on
 * {@code closure.capturedScope()} being consulted in
 * the bootstrap evaluator's {@code evaluateFunctionDefinition}.</p>
 *
 * <p>Slot allocation: one Object-typed slot per parameter and per
 * {@code letFunction} target name. Control flow inside the FD may
 * reassign the same name; a single slot per name suffices since Pure's
 * {@code let} is a rebind rather than an SSA value.</p>
 */
public final class FrameDescriptorBuilder
{

    private static final int SLOT_EXPRESSION_SEQUENCE = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("expressionSequence");
    private static final int SLOT_FUNC = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("func");
    private static final int SLOT_FUNCTION_NAME = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("functionName");
    private static final int SLOT_NAME = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("name");
    private static final int SLOT_OPEN_VARIABLES = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("openVariables");
    private static final int SLOT_PARAMETERS = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("parameters");
    private static final int SLOT_PARAMETERS_VALUES = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("parametersValues");
    private static final int SLOT_VALUE = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("value");
    private static final int SLOT_VALUES = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("values");
    private static final String LET_FUNCTION_SIGNATURE = "letFunction_String_1__T_m__T_m_";

    private FrameDescriptorBuilder()
    {
    }

    /**
     * Analyze {@code fd}. Returns a {@link FrameLayout} if the FD is
     * frame-eligible, {@code null} otherwise (caller falls back to HashMap
     * scope).
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static FrameLayout analyze(Object fd)
    {
        // LambdaFunction is a leaf concrete Pure type — pureTypeIs is exact-
        // match and class-keyed-cached, so this is a single CHM.get(Class)
        // post-warmup. After the loader flip the receiver is a
        // PureDynamicObject whose Shape's dynamic type is the Pure path.
        if (org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(fd,
                "platform::pure::metamodel::function::LambdaFunction"))
        {
            return analyzeLambda(fd);
        }

        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        Map<String, Integer> slots = new LinkedHashMap<>();
        org.finos.legend.pure.truffle.execution.types.PureSequence params = (org.finos.legend.pure.truffle.execution.types.PureSequence) org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(fd, SLOT_PARAMETERS);
        int[] paramSlots = new int[params == null ? 0 : params.size()];

        if (params != null)
        {
            for (int i = 0; i < params.size(); i++)
            {
                String name = (String) org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(params.getBoxed(i), SLOT_NAME);
                int slot = allocateSlot(builder, slots, name);
                paramSlots[i] = slot;
            }
        }

        Object bodyExprs = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(fd, SLOT_EXPRESSION_SEQUENCE);
        collectUpdatableInPlaceFlags(bodyExprs, parameterNames(params), builder, slots);
        collectLetTargets(bodyExprs, builder, slots);
        collectVariableReads(bodyExprs, builder, slots);

        return new FrameLayout(builder.build(), slots, paramSlots);
    }

    /** Native signatures whose lambda arg is a candidate for inlining.
     *  Only fold benefits — other natives regress (parent-frame
     *  materialization defeats Truffle escape analysis; see notes in
     *  PureASTBuilder.lowerInlineLambdaNative). */

    /**
     * Minimal layout with only parameter slots — used by
     * {@code StandaloneEvaluator} when {@link #analyze} returns null
     * (should not happen once all FDs are frame-eligible, but provides
     * a safe fallback during the transition).
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static FrameLayout analyzeMinimal(Object fd)
    {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        Map<String, Integer> slots = new LinkedHashMap<>();
        org.finos.legend.pure.truffle.execution.types.PureSequence params = (org.finos.legend.pure.truffle.execution.types.PureSequence) org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(fd, SLOT_PARAMETERS);
        int[] paramSlots = new int[params == null ? 0 : params.size()];
        if (params != null)
        {
            for (int i = 0; i < params.size(); i++)
            {
                paramSlots[i] = allocateSlot(builder, slots, (String) org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(params.getBoxed(i), SLOT_NAME));
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
     * the closure's captured scope map
     * before executing the body. Reads of open vars inside the body then
     * resolve via {@link org.finos.legend.pure.truffle.execution.ast.variable.FrameVariableReadNode}
     * instead of falling through to the HashMap scope.</p>
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static FrameLayout analyzeLambda(Object lambda)
    {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        Map<String, Integer> slots = new LinkedHashMap<>();

        org.finos.legend.pure.truffle.execution.types.PureSequence params = (org.finos.legend.pure.truffle.execution.types.PureSequence) org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(lambda, SLOT_PARAMETERS);
        int[] paramSlots = new int[params == null ? 0 : params.size()];
        if (params != null)
        {
            for (int i = 0; i < params.size(); i++)
            {
                paramSlots[i] = allocateSlot(builder, slots, (String) org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(params.getBoxed(i), SLOT_NAME));
            }
        }

        org.finos.legend.pure.truffle.execution.types.PureSequence openVars = (org.finos.legend.pure.truffle.execution.types.PureSequence) org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(lambda, SLOT_OPEN_VARIABLES);
        if (openVars != null)
        {
            for (int i = 0; i < openVars.size(); i++)
            {
                allocateSlot(builder, slots, (String) org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(openVars.getBoxed(i), SLOT_NAME));
            }
        }

        collectUpdatableInPlaceFlags(org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(lambda, SLOT_EXPRESSION_SEQUENCE), parameterNames(params), builder, slots);
        collectLetTargets(org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(lambda, SLOT_EXPRESSION_SEQUENCE), builder, slots);
        // Safety net: scan for variable reads to catch let targets missed
        collectVariableReads(org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(lambda, SLOT_EXPRESSION_SEQUENCE), builder, slots);

        return new FrameLayout(builder.build(), slots, paramSlots);
    }

    // Inline-fold support: the lambda has its own FrameDescriptor (built
    // by {@link #analyzeLambda}); the inline AST node uses that descriptor
    // directly and creates a fresh frame per iter (escape analysis elides
    // it when the frame doesn't escape). No pre-scan in this builder is
    // needed — detection happens in {@link
    // org.finos.legend.pure.truffle.execution.PureASTBuilder#lowerNativeCall}
    // on the AST level where the lambda literal is visible.


    private static final int SLOT_UPDATABLE_IN_PLACE_IF_PARAMETER = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("updatableInPlaceIfParameter");

    /**
     * The name of the frame slot holding whether parameter {@code name} was passed updatable in place
     * (pure/specification/IN_PLACE_UPDATE_ANALYSIS.md): no Pure variable can be called that.
     */
    public static String updatableInPlaceFlagName(String name)
    {
        return "\u0001updatableInPlace " + name;
    }

    private static final java.util.Map<Object, Boolean> READS_UPDATABILITY_FLAGS = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    /** Whether a function's body reads the updatability flag of one of its parameters (its layout has a flag slot). */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static boolean readsUpdatableInPlaceFlags(Object fd)
    {
        Boolean known = READS_UPDATABILITY_FLAGS.get(fd);
        if (known != null)
        {
            return known;
        }
        // Not known while computed: a recursive call reads no flag through itself.
        READS_UPDATABILITY_FLAGS.put(fd, false);
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        Map<String, Integer> slots = new LinkedHashMap<>();
        org.finos.legend.pure.truffle.execution.types.PureSequence params = (org.finos.legend.pure.truffle.execution.types.PureSequence) org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(fd, SLOT_PARAMETERS);
        collectUpdatableInPlaceFlags(org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(fd, SLOT_EXPRESSION_SEQUENCE), parameterNames(params), builder, slots);
        READS_UPDATABILITY_FLAGS.put(fd, !slots.isEmpty());
        return !slots.isEmpty();
    }

    private static java.util.Set<String> parameterNames(org.finos.legend.pure.truffle.execution.types.PureSequence params)
    {
        java.util.Set<String> names = new java.util.HashSet<>();
        if (params != null)
        {
            for (int i = 0; i < params.size(); i++)
            {
                Object name = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(params.getBoxed(i), SLOT_NAME);
                if (name instanceof String n)
                {
                    names.add(n);
                }
            }
        }
        return names;
    }

    /**
     * A flag slot ({@link #updatableInPlaceFlagName}) for each parameter whose flag a node reads (pure/specification/
     * IN_PLACE_UPDATE_ANALYSIS.md): a use updatable in place if it was passed updatable in place that is the receiver of an add, concatenate or
     * reverse, the start value of a fold, or an argument of a call to a user function that itself reads a flag. Only
     * those: each slot enlarges the frame. Set by whoever runs the body knowing it (a fold updatable in place, a call passing an
     * updatability mask); never set, it reads as not updatable in place. Not into lambdas: each has its own layout.
     */
    private static void collectUpdatableInPlaceFlags(Object exprsObj, java.util.Set<String> params, FrameDescriptor.Builder builder, Map<String, Integer> slots)
    {
        if (params.isEmpty() || exprsObj == null)
        {
            return;
        }
        if (exprsObj instanceof org.finos.legend.pure.truffle.execution.types.PureSequence seq)
        {
            for (int i = 0; i < seq.size(); i++)
            {
                collectUpdatableInPlaceFlags(seq.getBoxed(i), params, builder, slots);
            }
            return;
        }
        if (org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(exprsObj, "platform::pure::metamodel::valuespecification::Collection"))
        {
            collectUpdatableInPlaceFlags(org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(exprsObj, SLOT_VALUES), params, builder, slots);
            return;
        }
        String pureType = org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeOf(exprsObj);
        if (pureType == null || !(pureType.endsWith("FunctionInvocation") || pureType.endsWith("DotApplication") || pureType.endsWith("ArrowInvocation")))
        {
            return;
        }
        Object argsObj = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(exprsObj, SLOT_PARAMETERS_VALUES);
        if (!(argsObj instanceof org.finos.legend.pure.truffle.execution.types.PureSequence args))
        {
            return;
        }
        boolean[] reading = flagReadingArguments(org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(exprsObj, SLOT_FUNC), args.size());
        for (int i = 0; i < args.size(); i++)
        {
            Object arg = args.getBoxed(i);
            if (reading[i] && org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(arg, "platform::pure::metamodel::valuespecification::VariableExpression"))
            {
                Object parameter = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(arg, SLOT_UPDATABLE_IN_PLACE_IF_PARAMETER);
                if (parameter instanceof String name && params.contains(name))
                {
                    allocateSlot(builder, slots, updatableInPlaceFlagName(name));
                }
            }
            collectUpdatableInPlaceFlags(arg, params, builder, slots);
        }
    }

    /** The arguments of a call to {@code func} whose updatability a node reads (see {@link #collectUpdatableInPlaceFlags}). */
    private static boolean[] flagReadingArguments(Object func, int count)
    {
        boolean[] reading = new boolean[count];
        if (func == null)
        {
            return reading;
        }
        if (org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(func, "platform::pure::metamodel::function::NativeFunction"))
        {
            Object name = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(func, SLOT_NAME);
            if ("add_T_MANY__T_1__T_$1_MANY$_".equals(name) || "add_T_MANY__Integer_1__T_1__T_$1_MANY$_".equals(name)
                    || "concatenate_T_MANY__T_MANY__T_MANY_".equals(name) || "reverse_T_m__T_m_".equals(name)
                    || "copy_T_1__KeyExpression_MANY__T_1_".equals(name))
            {
                reading[0] = count > 0;
            }
            else if (("fold_T_MANY__Function_1__V_MANY__V_MANY_".equals(name) || "fold_T_MANY__Function_1__V_1__V_1_".equals(name)) && count > 2)
            {
                reading[2] = true;
            }
        }
        else if (org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(func, "platform::pure::metamodel::function::UserDefinedFunction")
                && readsUpdatableInPlaceFlags(func))
        {
            java.util.Arrays.fill(reading, true);
        }
        else if (org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(func, "platform::pure::metamodel::function::property::QualifiedProperty"))
        {
            // Its override is chosen at run time: any may read a flag (the receiver never: an instance is not changed).
            java.util.Arrays.fill(reading, 1, count, true);
        }
        return reading;
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

    private static void collectLetTargets(Object exprsObj,
                                          FrameDescriptor.Builder builder,
                                          Map<String, Integer> slots)
    {
        if (exprsObj == null)
        {
            return;
        }
        // Pure's `let` is a top-level statement form inside a function body
        // (`let x = ...;`). We only scan the expression-sequence roots; let
        // never appears nested inside another expression. Walking deeper
        // also triggers FlatBuffer wrappers to resolve QP chains eagerly,
        // which can fail with out-of-bounds reads on not-yet-executed
        // references — the top-level-only scan avoids that hazard too.
        for (int i = 0; i < CollectionHelper.size(exprsObj); i++)
        {
            collectLetTarget(CollectionHelper.at(exprsObj, i), builder, slots);
        }
    }

    private static void collectLetTarget(Object vs,
                                          FrameDescriptor.Builder builder,
                                          Map<String, Integer> slots)
    {
        if (!org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(vs,
                "platform::pure::metamodel::valuespecification::FunctionInvocation")
                && !org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(vs,
                        "platform::pure::metamodel::valuespecification::DotApplication")
                && !org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(vs,
                        "platform::pure::metamodel::valuespecification::ArrowInvocation"))
        {
            return;
        }
        boolean isLet = false;
        try
        {
            Object func = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(vs, SLOT_FUNC);
            if (func != null
                    && org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(func,
                            "platform::pure::metamodel::function::NativeFunction"))
            {
                Object nfName = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(func, SLOT_NAME);
                if (LET_FUNCTION_SIGNATURE.equals(nfName))
                {
                    isLet = true;
                }
            }
        }
        catch (RuntimeException ignored)
        {
            // FlatBuffer wrapper may fail to resolve _func() lazily.
            // Fall back to checking _functionName().
        }
        if (!isLet && "letFunction".equals(org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(vs, SLOT_FUNCTION_NAME)))
        {
            isLet = true;
        }
        if (isLet)
        {
            String name = extractLetName(vs);
            if (name != null)
            {
                allocateSlot(builder, slots, name);
            }
        }
    }

    private static boolean containsLambda(Object exprsObj)
    {
        if (exprsObj == null)
        {
            return false;
        }
        for (int i = 0; i < CollectionHelper.size(exprsObj); i++)
        {
            if (containsLambdaIn(CollectionHelper.at(exprsObj, i)))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean containsLambdaIn(Object vs)
    {
        if (org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(vs,
                "platform::pure::metamodel::valuespecification::AtomicValue"))
        {
            Object inner = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(vs, SLOT_VALUE);
            if (org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(inner,
                    "platform::pure::metamodel::function::LambdaFunction"))
            {
                return true;
            }
        }
        if (org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(vs,
                "platform::pure::metamodel::valuespecification::Collection"))
        {
            Object valsObj = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(vs, SLOT_VALUES);
            if (valsObj instanceof org.finos.legend.pure.truffle.execution.types.PureSequence vals)
            {
                for (Object child : vals.toBoxedArray())
                {
                    if (containsLambdaIn(child))
                    {
                        return true;
                    }
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

    private static String extractLetName(Object fe)
    {
        Object argsObj = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(fe, SLOT_PARAMETERS_VALUES);
        if (!(argsObj instanceof org.finos.legend.pure.truffle.execution.types.PureSequence args) || args.isEmpty())
        {
            return null;
        }
        Object first = args.getBoxed(0);
        if (org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(first,
                "platform::pure::metamodel::valuespecification::AtomicValue"))
        {
            Object inner = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(first, SLOT_VALUE);
            if (inner instanceof String s)
            {
                return s;
            }
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
    private static void collectVariableReads(Object exprsObj,
                                             FrameDescriptor.Builder builder,
                                             Map<String, Integer> slots)
    {
        if (exprsObj == null)
        {
            return;
        }
        for (int i = 0; i < CollectionHelper.size(exprsObj); i++)
        {
            Object vs = CollectionHelper.at(exprsObj, i);
            scanForVariableReads(vs, builder, slots);
        }
    }

    private static void scanForVariableReads(Object vs,
                                             FrameDescriptor.Builder builder,
                                             Map<String, Integer> slots)
    {
        String pureType = org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeOf(vs);
        if ("platform::pure::metamodel::valuespecification::VariableExpression".equals(pureType))
        {
            Object n = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(vs, SLOT_NAME);
            if (n instanceof String name)
            {
                allocateSlot(builder, slots, name);
            }
            return;
        }
        if ("platform::pure::metamodel::valuespecification::AtomicValue".equals(pureType))
        {
            // Don't recurse into lambdas — they get their own layout
            return;
        }
        if ("platform::pure::metamodel::valuespecification::Collection".equals(pureType))
        {
            Object valsObj = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(vs, SLOT_VALUES);
            if (valsObj instanceof org.finos.legend.pure.truffle.execution.types.PureSequence vals)
            {
                for (Object child : vals.toBoxedArray())
                {
                    scanForVariableReads(child, builder, slots);
                }
            }
            return;
        }
        // FunctionInvocation / DotApplication / ArrowInvocation share a
        // parametersValues slot — recurse into their args
        if (pureType != null
                && (pureType.endsWith("FunctionInvocation")
                    || pureType.endsWith("DotApplication")
                    || pureType.endsWith("ArrowInvocation")))
        {
            try
            {
                Object argsObj = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(vs, SLOT_PARAMETERS_VALUES);
                if (argsObj instanceof org.finos.legend.pure.truffle.execution.types.PureSequence args)
                {
                    for (Object arg : args.toBoxedArray())
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
