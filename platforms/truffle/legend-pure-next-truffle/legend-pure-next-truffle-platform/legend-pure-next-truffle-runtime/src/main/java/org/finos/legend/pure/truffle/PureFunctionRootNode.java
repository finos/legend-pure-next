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

    /**
     * Parallel arrays computed at compile time: {@code funcTypeVarArgIdx[i]}
     * is the parameter index whose declared type is the TypeParameter named
     * {@code funcTypeVarNames[i]}. Empty when the function has no top-level
     * `<T>`-parameter bindings (most cases). Held final so PE folds the
     * branch out when empty.
     */
    @CompilationFinal(dimensions = 1)
    private final int[] funcTypeVarArgIdx;
    @CompilationFinal(dimensions = 1)
    private final String[] funcTypeVarNames;
    @CompilationFinal(dimensions = 1)
    private final int[] funcMulVarArgIdx;
    @CompilationFinal(dimensions = 1)
    private final String[] funcMulVarNames;

    /**
     * Per-param declared multiplicity bounds (parallel-indexed with paramSlots).
     * {@code paramUpperBound[i] == -1} means "skip shape coercion" (parametric
     * multiplicity like `[m]`, or the bounds couldn't be read). Otherwise the
     * binding code enforces {@code lower <= argSize <= upper} and unwraps a
     * sequence-of-1 to its bare element when upper bound is 1.
     */
    @CompilationFinal(dimensions = 1)
    private final long[] paramUpperBound;
    @CompilationFinal(dimensions = 1)
    private final long[] paramLowerBound;

    public PureFunctionRootNode(PureLanguage language, String name,
                                FrameLayout layout, PureNode[] body)
    {
        this(language, name, layout, body, null, false, EMPTY_INT, EMPTY_STR, EMPTY_INT, EMPTY_STR, EMPTY_LONG, EMPTY_LONG);
    }

    public PureFunctionRootNode(PureLanguage language, String name,
                                FrameLayout layout, PureNode[] body,
                                com.oracle.truffle.api.source.SourceSection sourceSection)
    {
        this(language, name, layout, body, sourceSection, false, EMPTY_INT, EMPTY_STR, EMPTY_INT, EMPTY_STR, EMPTY_LONG, EMPTY_LONG);
    }

    public PureFunctionRootNode(PureLanguage language, String name,
                                FrameLayout layout, PureNode[] body,
                                com.oracle.truffle.api.source.SourceSection sourceSection,
                                boolean mayBindTypeVars)
    {
        this(language, name, layout, body, sourceSection, mayBindTypeVars, EMPTY_INT, EMPTY_STR, EMPTY_INT, EMPTY_STR, EMPTY_LONG, EMPTY_LONG);
    }

    public PureFunctionRootNode(PureLanguage language, String name,
                                FrameLayout layout, PureNode[] body,
                                com.oracle.truffle.api.source.SourceSection sourceSection,
                                boolean mayBindTypeVars,
                                int[] funcTypeVarArgIdx, String[] funcTypeVarNames,
                                int[] funcMulVarArgIdx, String[] funcMulVarNames)
    {
        this(language, name, layout, body, sourceSection, mayBindTypeVars,
                funcTypeVarArgIdx, funcTypeVarNames, funcMulVarArgIdx, funcMulVarNames,
                EMPTY_LONG, EMPTY_LONG);
    }

    public PureFunctionRootNode(PureLanguage language, String name,
                                FrameLayout layout, PureNode[] body,
                                com.oracle.truffle.api.source.SourceSection sourceSection,
                                boolean mayBindTypeVars,
                                int[] funcTypeVarArgIdx, String[] funcTypeVarNames,
                                int[] funcMulVarArgIdx, String[] funcMulVarNames,
                                long[] paramUpperBound, long[] paramLowerBound)
    {
        super(language, layout.descriptor());
        this.name = name;
        this.layout = layout;
        this.paramSlots = layout.paramSlots();
        this.body = java.util.Arrays.copyOf(body, body.length, Node[].class);
        this.rootSourceSection = sourceSection;
        this.mayBindTypeVars = mayBindTypeVars;
        this.funcTypeVarArgIdx = funcTypeVarArgIdx != null ? funcTypeVarArgIdx : EMPTY_INT;
        this.funcTypeVarNames = funcTypeVarNames != null ? funcTypeVarNames : EMPTY_STR;
        this.funcMulVarArgIdx = funcMulVarArgIdx != null ? funcMulVarArgIdx : EMPTY_INT;
        this.funcMulVarNames = funcMulVarNames != null ? funcMulVarNames : EMPTY_STR;
        this.paramUpperBound = paramUpperBound != null ? paramUpperBound : EMPTY_LONG;
        this.paramLowerBound = paramLowerBound != null ? paramLowerBound : EMPTY_LONG;
    }

    private static final int[] EMPTY_INT = new int[0];
    private static final String[] EMPTY_STR = new String[0];
    private static final long[] EMPTY_LONG = new long[0];

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

        // Bind params from arguments[0..]. Caller-driven shape coercion
        // (Pure semantics: a 1-element collection equals its single value):
        // when the declared param has upperBound=1 but the arg arrived as a
        // PureSequence-of-1, unwrap to the bare element so downstream
        // property access / function dispatch see a scalar receiver.
        // {@code paramUpperBound[i] == -1} means "skip coercion" (parametric
        // multiplicity or no bound info available).
        boolean haveShapeInfo = paramUpperBound.length > 0;
        for (int i = 0; i < paramSlots.length && i < arguments.length; i++)
        {
            Object arg = arguments[i] != null ? arguments[i] : PureSequence.EMPTY;
            if (haveShapeInfo && i < paramUpperBound.length)
            {
                arg = coerceArgToParamShape(arg, paramUpperBound[i], paramLowerBound[i], i);
            }
            frame.setObject(paramSlots[i], arg);
        }


        // Bind type variables from the target's CGT (for QPs with type parameters).
        // mayBindTypeVars is @CompilationFinal so PE folds this whole branch out
        // for plain FunctionDefinitions — which is most of compiler-pure.
        if (mayBindTypeVars && arguments.length > 0 && arguments[0] != null)
        {
            PureContext.bindQpTypeVariablesStatic(arguments[0], frame, layout);
        }

        // Function-level `<T>` and `<T|m>` bindings: build name→resolved-Type
        // and name→resolved-Multiplicity maps from pre-planned arguments,
        // push onto the context stacks for the body (so `cast(@T|m)` can
        // resolve T and m), pop after. Empty-array branches fold out under
        // PE for the common case (functions with no parametric `<T>`/`<T|m>`).
        boolean hasFuncTypeVars = funcTypeVarArgIdx.length > 0;
        boolean hasFuncMulVars = funcMulVarArgIdx.length > 0;
        org.finos.legend.pure.truffle.PureContext ctx =
                (hasFuncTypeVars || hasFuncMulVars) ? org.finos.legend.pure.truffle.PureLanguage.get(this) : null;
        if (hasFuncTypeVars)
        {
            ctx.pushFunctionTypeVarBindings(buildFuncTypeVarBindings(arguments));
        }
        if (hasFuncMulVars)
        {
            ctx.pushFunctionMulVarBindings(buildFuncMulVarBindings(arguments, ctx));
        }

        Object result = PureSequence.EMPTY;
        try
        {
            for (int i = 0; i < body.length; i++)
            {
                result = ((PureNode) body[i]).executeGeneric(frame);
            }
        }
        finally
        {
            if (hasFuncMulVars)
            {
                ctx.popFunctionMulVarBindings();
            }
            if (hasFuncTypeVars)
            {
                ctx.popFunctionTypeVarBindings();
            }
        }

        org.finos.legend.pure.truffle.profiler.PureProfiler.exit();
        return result;
    }

    /**
     * Pure-semantic shape coercion: a 1-element collection equals its single
     * value. When the param declares upperBound=1 and the arg arrived as a
     * PureSequence-of-1, unwrap to the bare singleton. Throws when the arg's
     * actual size doesn't fit the declared bounds — type checker normally
     * catches this, but match's [1]-arm path (which passes the raw input
     * collection straight to the arm lambda) can leak size mismatches.
     */
    static Object coerceArgToParamShape(Object arg, long upperBound, long lowerBound, int paramIdx)
    {
        if (upperBound < 0) return arg; // parametric / unknown — skip
        int actualSize;
        if (arg instanceof PureSequence ps)
        {
            actualSize = ps.size();
        }
        else
        {
            actualSize = 1;
        }
        if (actualSize > upperBound)
        {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new RuntimeException("Argument multiplicity [" + actualSize
                    + "] exceeds parameter " + paramIdx + " declared upper bound " + upperBound);
        }
        if (actualSize < lowerBound)
        {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new RuntimeException("Argument multiplicity [" + actualSize
                    + "] below parameter " + paramIdx + " declared lower bound " + lowerBound);
        }
        // upper == 1 and we have a singleton sequence → unwrap to bare element.
        if (upperBound == 1 && actualSize == 1 && arg instanceof PureSequence ps2)
        {
            return ps2.getBoxed(0);
        }
        return arg;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private java.util.Map<String, Object> buildFuncTypeVarBindings(Object[] arguments)
    {
        java.util.Map<String, Object> bindings = new java.util.HashMap<>(funcTypeVarArgIdx.length);
        org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver =
                org.finos.legend.pure.truffle.PureLanguage.get(this).resolver();
        for (int i = 0; i < funcTypeVarArgIdx.length; i++)
        {
            int argIdx = funcTypeVarArgIdx[i];
            if (argIdx >= arguments.length) continue;
            Object arg = arguments[argIdx];
            if (arg == null) continue;
            Object resolvedType = resolveTypeFromArgument(arg, resolver);
            if (resolvedType != null)
            {
                bindings.put(funcTypeVarNames[i], resolvedType);
            }
        }
        return bindings;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private java.util.Map<String, Object> buildFuncMulVarBindings(Object[] arguments,
                                                                   org.finos.legend.pure.truffle.PureContext ctx)
    {
        java.util.Map<String, Object> bindings = new java.util.HashMap<>(funcMulVarArgIdx.length);
        for (int i = 0; i < funcMulVarArgIdx.length; i++)
        {
            int argIdx = funcMulVarArgIdx[i];
            if (argIdx >= arguments.length) continue;
            Object arg = arguments[argIdx];
            int count = countOfArg(arg);
            Object mul = standardMultiplicityForCount(count, ctx.resolver());
            if (mul != null)
            {
                bindings.put(funcMulVarNames[i], mul);
            }
        }
        return bindings;
    }

    private static int countOfArg(Object arg)
    {
        if (arg == null) return 0;
        if (arg instanceof org.finos.legend.pure.truffle.types.PureSequence seq) return seq.size();
        return 1;
    }

    /**
     * Bind m to a canonical Multiplicity based on the arg's count:
     *   0 → ZeroOne
     *   1 → PureOne
     *  >1 → PureMany ([1..*])
     * Loses precision for specific counts (e.g. a 2-element witness binds
     * PureMany rather than [2..2]), but matches Pure's own static
     * multiplicity inference. Tighter bounds could be derived by
     * constructing a fresh ConcreteMultiplicity, deferred until needed.
     */
    private static Object standardMultiplicityForCount(int count,
                                                       org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        String path = switch (count)
        {
            case 0 -> "meta::pure::metamodel::multiplicity::PureZero";
            case 1 -> "meta::pure::metamodel::multiplicity::PureOne";
            default -> "meta::pure::metamodel::multiplicity::OneMany";
        };
        return resolver.getElement(path);
    }

    /**
     * Derive T's binding from an argument value:
     *  - scalar PDO  → arg.classifierGenericType.type
     *  - PureSequence → common supertype of every non-null element's CGT type
     *                   (homogeneous fast path; falls back to findCommonType)
     *  - primitives / empty seq → null (caller will not bind, and CastNode
     *                                    will throw at lookup time)
     */
    private static Object resolveTypeFromArgument(Object arg,
                                                  org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (arg instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject)
        {
            Object cgt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(arg,
                    org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("classifierGenericType"));
            return cgt != null ? org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt) : null;
        }
        if (arg instanceof org.finos.legend.pure.truffle.types.PureSequence seq)
        {
            if (seq.isEmpty()) return null;
            Object first = null;
            java.util.ArrayList<Object> distinct = null;
            for (int i = 0; i < seq.size(); i++)
            {
                Object e = seq.getBoxed(i);
                if (e == null) continue;
                Object t = resolveTypeFromArgument(e, resolver);
                if (t == null) continue;
                if (first == null)
                {
                    first = t;
                }
                else if (t != first)
                {
                    if (distinct == null)
                    {
                        distinct = new java.util.ArrayList<>();
                        distinct.add(first);
                    }
                    if (!distinct.contains(t)) distinct.add(t);
                }
            }
            if (first == null) return null;
            if (distinct == null) return first;
            return org.finos.legend.pure.truffle.runtime.helper._Type.findCommonType(distinct, false, resolver);
        }
        // Primitives: no useful CGT type for T-resolution.
        return null;
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
