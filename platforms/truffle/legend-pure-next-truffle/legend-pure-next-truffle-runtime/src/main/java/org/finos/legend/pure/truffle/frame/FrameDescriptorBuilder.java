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
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;

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
 * the bootstrap evaluator's {@code evaluateFunctionDefinition}.</p>
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
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static FrameLayout analyze(Object fd)
    {
        // LambdaFunction is a leaf concrete Pure type — pureTypeIs is exact-
        // match and class-keyed-cached, so this is a single CHM.get(Class)
        // post-warmup. After the loader flip the receiver is a
        // PureDynamicObject whose Shape's dynamic type is the Pure path.
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(fd,
                "meta::pure::metamodel::function::LambdaFunction"))
        {
            return analyzeLambda(fd);
        }

        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        Map<String, Integer> slots = new LinkedHashMap<>();
        org.finos.legend.pure.truffle.types.PureSequence params = (org.finos.legend.pure.truffle.types.PureSequence) org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fd, "parameters");
        int[] paramSlots = new int[params == null ? 0 : params.size()];

        if (params != null)
        {
            for (int i = 0; i < params.size(); i++)
            {
                String name = (String) org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(params.getBoxed(i), "name");
                int slot = allocateSlot(builder, slots, name);
                paramSlots[i] = slot;
            }
        }

        collectLetTargets(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fd, "expressionSequence"), builder, slots);
        // Safety net: scan for variable reads to catch let targets missed
        // by collectLetTargets (e.g. when FlatBuffer _func() resolution fails)
        collectVariableReads(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fd, "expressionSequence"), builder, slots);

        return new FrameLayout(builder.build(), slots, paramSlots);
    }

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
        org.finos.legend.pure.truffle.types.PureSequence params = (org.finos.legend.pure.truffle.types.PureSequence) org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fd, "parameters");
        int[] paramSlots = new int[params == null ? 0 : params.size()];
        if (params != null)
        {
            for (int i = 0; i < params.size(); i++)
            {
                paramSlots[i] = allocateSlot(builder, slots, (String) org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(params.getBoxed(i), "name"));
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
     * resolve via {@link org.finos.legend.pure.truffle.ast.FrameVariableReadNode}
     * instead of falling through to the HashMap scope.</p>
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static FrameLayout analyzeLambda(Object lambda)
    {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        Map<String, Integer> slots = new LinkedHashMap<>();

        org.finos.legend.pure.truffle.types.PureSequence params = (org.finos.legend.pure.truffle.types.PureSequence) org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(lambda, "parameters");
        int[] paramSlots = new int[params == null ? 0 : params.size()];
        if (params != null)
        {
            for (int i = 0; i < params.size(); i++)
            {
                paramSlots[i] = allocateSlot(builder, slots, (String) org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(params.getBoxed(i), "name"));
            }
        }

        org.finos.legend.pure.truffle.types.PureSequence openVars = (org.finos.legend.pure.truffle.types.PureSequence) org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(lambda, "openVariables");
        if (openVars != null)
        {
            for (int i = 0; i < openVars.size(); i++)
            {
                allocateSlot(builder, slots, (String) org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(openVars.getBoxed(i), "name"));
            }
        }

        collectLetTargets(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(lambda, "expressionSequence"), builder, slots);
        // Safety net: scan for variable reads to catch let targets missed
        collectVariableReads(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(lambda, "expressionSequence"), builder, slots);

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
        if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(vs,
                "meta::pure::metamodel::valuespecification::FunctionInvocation")
                && !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(vs,
                        "meta::pure::metamodel::valuespecification::DotApplication")
                && !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(vs,
                        "meta::pure::metamodel::valuespecification::ArrowInvocation"))
        {
            return;
        }
        boolean isLet = false;
        try
        {
            Object func = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(vs, "func");
            if (func != null
                    && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(func,
                            "meta::pure::metamodel::function::NativeFunction"))
            {
                Object nfName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(func, "name");
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
        if (!isLet && "letFunction".equals(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(vs, "functionName")))
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
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(vs,
                "meta::pure::metamodel::valuespecification::AtomicValue"))
        {
            Object inner = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(vs, "value");
            if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(inner,
                    "meta::pure::metamodel::function::LambdaFunction"))
            {
                return true;
            }
        }
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(vs,
                "meta::pure::metamodel::valuespecification::Collection"))
        {
            Object valsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(vs, "values");
            if (valsObj instanceof org.finos.legend.pure.truffle.types.PureSequence vals)
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
        Object argsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fe, "parametersValues");
        if (!(argsObj instanceof org.finos.legend.pure.truffle.types.PureSequence args) || args.isEmpty())
        {
            return null;
        }
        Object first = args.getBoxed(0);
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(first,
                "meta::pure::metamodel::valuespecification::AtomicValue"))
        {
            Object inner = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(first, "value");
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
        String pureType = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(vs);
        if ("meta::pure::metamodel::valuespecification::VariableExpression".equals(pureType))
        {
            Object n = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(vs, "name");
            if (n instanceof String name)
            {
                allocateSlot(builder, slots, name);
            }
            return;
        }
        if ("meta::pure::metamodel::valuespecification::AtomicValue".equals(pureType))
        {
            // Don't recurse into lambdas — they get their own layout
            return;
        }
        if ("meta::pure::metamodel::valuespecification::Collection".equals(pureType))
        {
            Object valsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(vs, "values");
            if (valsObj instanceof org.finos.legend.pure.truffle.types.PureSequence vals)
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
                Object argsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(vs, "parametersValues");
                if (argsObj instanceof org.finos.legend.pure.truffle.types.PureSequence args)
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
