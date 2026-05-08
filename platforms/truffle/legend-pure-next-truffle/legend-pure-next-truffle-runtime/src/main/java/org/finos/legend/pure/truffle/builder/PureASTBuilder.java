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

package org.finos.legend.pure.truffle.builder;

import org.finos.legend.pure.truffle.ast.FrameLetFunctionNode;
import org.finos.legend.pure.truffle.ast.PureSourceHelper;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.LambdaFunction;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.NativeFunction;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.AbstractProperty;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.Collection;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.FunctionExpression;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.VariableExpression;
import org.finos.legend.pure.truffle.types.PureDate;
import org.finos.legend.pure.truffle.types.PureSequence;
import org.finos.legend.pure.truffle.ast.AtomicValueNode;
import org.finos.legend.pure.truffle.ast.FrameVariableReadNode;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawCollectionNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCaptureNode;
import org.finos.legend.pure.truffle.ast.RawPropertyAccessNode;
import org.finos.legend.pure.truffle.ast.RawUserFunctionCallNode;
import org.finos.legend.pure.truffle.frame.FrameLayout;

/**
 * Lowers a Pure {@link ValueSpecification} tree into a Truffle {@link PureNode}
 * AST. Mirrors {@code ValueSpecificationEvaluator.evaluate}'s top-level switch.
 *
 * <p>For each native call, the builder consults {@link NativeNodeRegistry}
 * first — a specialized node operates on raw values and is inlineable by
 * Graal. All native signatures must have a registered specialization.</p>
 */
public final class PureASTBuilder
{
    private static final String LET_FUNCTION_SIGNATURE = "letFunction_String_1__T_m__T_m_";
    private static final String MULTI_IF_SIGNATURE = "if_Pair_MANY__Function_1__T_m_";
    private static final String PAIR_SIGNATURE = "pair_U_1__V_1__Pair_1_";

    private final NativeNodeRegistry specialized;

    // Current enclosing FunctionDefinition's frame layout. Set by
    // {@link #lowerBody} and consulted when lowering variable reads /
    // letFunction calls. Null means "no frame in scope".
    private FrameLayout currentLayout;

    public PureASTBuilder(Object nativesFallback, NativeNodeRegistry specialized)
    {
        this.specialized = specialized;
    }

    public NativeNodeRegistry specialized()
    {
        return specialized;
    }

    /**
     * Lower each expression in a FunctionDefinition's body under the given
     * frame layout. Variable reads and {@code letFunction} calls for names
     * in the layout lower to slot-based nodes; anything else (captured
     * lambda vars, etc.) falls back to HashMap-scope nodes.
     */
    public PureNode[] lowerBody(Object exprs, FrameLayout layout)
    {
        FrameLayout previous = pushLayout(layout);
        try
        {
            PureSequence seq = (PureSequence) exprs;
            PureNode[] nodes = new PureNode[seq.size()];
            for (int i = 0; i < seq.size(); i++)
            {
                nodes[i] = lower(seq.getBoxed(i));
            }
            return nodes;
        }
        finally
        {
            popLayout(previous);
        }
    }

    /**
     * Push a layout onto the current-layout context for ad-hoc lowering.
     * Returns the previous layout, which the caller must restore via
     * {@link #popLayout}.
     *
     * <p>Used by {@link org.finos.legend.pure.truffle.TruffleEvaluator}
     * while executing a frame-eligible FunctionDefinition: any sub-expression
     * re-lowered through {@code evaluate(vs)} (e.g. from a bridged native)
     * then sees slot-based variable reads.</p>
     */
    public FrameLayout pushLayout(FrameLayout layout)
    {
        FrameLayout prev = this.currentLayout;
        this.currentLayout = layout;
        return prev;
    }

    public void popLayout(FrameLayout previous)
    {
        this.currentLayout = previous;
    }

    /**
     * Lower a single {@link ValueSpecification} into an executable Truffle node.
     */
    public PureNode lower(Object vs)
    {
        PureNode node = switch (vs)
        {
            case AtomicValue av -> lowerAtomicValue(av);
            case VariableExpression ve -> lowerVariableRead(ve);
            case Collection col ->
            {
                org.finos.legend.pure.truffle.types.PureSequence values = col._values();
                PureNode[] children = new PureNode[values.size()];
                for (int i = 0; i < values.size(); i++)
                {
                    children[i] = lower(values.getBoxed(i));
                }
                yield new RawCollectionNode(children);
            }
            case GenericTypeAndMultiplicityHolder gmh -> new AtomicValueNode(gmh);
            case FunctionExpression fe -> lowerFunctionExpression(fe);
            default -> throw new RuntimeException(
                    "Unsupported ValueSpecification type: " + vs.getClass().getName());
        };
        // Attach Pure source location for stack traces
        PureSourceHelper.withSource(node, vs);
        return node;
    }

    /**
     * Lower a property access. For the common case (single-argument
     * non-enum non-QP property read), emit a {@link
     * org.finos.legend.pure.truffle.ast.DirectPropertyAccessNode} which
     * has the receiver as a single {@code @Child}, the property name
     * baked in {@code @CompilationFinal}, and a 2-entry class cache
     * inlined into {@code executeGeneric} — no helper indirection,
     * no {@code Object[] args} allocation per call. Falls back to
     * {@link RawPropertyAccessNode} when the receiver type might be
     * an Enumeration (which has special property-vs-enum-value
     * dispatch) or when the call has unusual shape.
     */
    private PureNode lowerPropertyAccess(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.AbstractProperty prop,
                                         FunctionExpression fe)
    {
        PureNode[] args = lowerArgs(fe);
        if (args.length == 1
                && !(prop instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty)
                && prop instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property property
                && property._name() != null
                && !canTargetBeEnumeration(prop))
        {
            return new org.finos.legend.pure.truffle.ast.DirectPropertyAccessNode(args[0], property._name());
        }
        return new RawPropertyAccessNode(fe, args);
    }

    /**
     * True if the property's owning type might be an Enumeration. Enum
     * targets have special semantics ({@code $enum.someValue} can
     * resolve to either a metaclass property OR an enum value), so the
     * direct-access node can't safely handle them.
     */
    private static boolean canTargetBeEnumeration(
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.AbstractProperty prop)
    {
        Object owner = prop._owner();
        // Conservative: if owner is anything other than a non-Enumeration
        // PackageableElement Class, we don't know — fall back to the full
        // RawPropertyAccessNode path. Most properties have a non-Enumeration
        // owning class, so the direct path catches the vast majority.
        if (owner == null) return true;
        if (owner instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enumeration) return true;
        return false;
    }

    private PureNode lowerFunctionExpression(FunctionExpression fe)
    {
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.Function func = fe._func();
        if (func == null)
        {
            throw new RuntimeException("_func() returned null for: " + fe._functionName() + " [" + fe.getClass().getName() + "]");
        }
        // QP overload disambiguation: the PDB func path may resolve to the wrong
        // overload when multiple QPs share the same simple name (e.g. res() vs res(z)).
        // Fix by matching the QP's param count against the call's arg count.
        if (func instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty qp)
        {
            int callArgCount = fe._parametersValues() != null ? fe._parametersValues().size() : 0;
            int qpParamCount = qp._parameters() != null ? qp._parameters().size() : 0;
            if (qpParamCount != callArgCount)
            {
                // Wrong overload — find the right one from the owning class
                org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty correct =
                        findQpOverload(qp, callArgCount);
                if (correct != null)
                {
                    func = correct;
                }
            }
        }
        final org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.Function resolvedFunc = func;
        // Recognise the multi-clause if pattern at AST-build time and lower
        // it to a flat conditional chain — see {@link MultiIfNode}.
        // Static-form fast path for the multi-clause if — applies whether
        // the function is a NativeFunction or FunctionDefinition. Literal
        // pair-list patterns lower to a flat {@link MultiIfNode} with no
        // Pair allocation; non-literal patterns fall through to the
        // regular dispatch (where the native factory builds a runtime
        // {@code MultiIfNode}).
        if (resolvedFunc instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe
                && MULTI_IF_SIGNATURE.equals(pe._name()))
        {
            PureNode multiIf = tryLowerMultiIf(fe);
            if (multiIf != null)
            {
                return multiIf;
            }
        }
        return switch (resolvedFunc)
        {
            case NativeFunction nf -> lowerNativeCall(nf, fe);
            case FunctionDefinition fd -> new RawUserFunctionCallNode(fd, lowerArgs(fe));
            case AbstractProperty prop -> lowerPropertyAccess(prop, fe);
            default -> throw new RuntimeException(
                    "Unsupported function type: " + resolvedFunc.getClass().getName() + " for: " + fe._functionName());
        };
    }

    /**
     * Try to lower a call to {@code if(Pair<...>[*], Function[1])} into a
     * {@link org.finos.legend.pure.truffle.ast.natives.lang.MultiIfNode}.
     *
     * <p>Triggers when the pairs argument is a literal {@link Collection}
     * of literal {@code pair(...)} calls — at that point we know the two
     * lambda values for each clause statically, so we never need to run
     * {@code pair()} or allocate a {@code Pair} object. Per-clause we
     * prefer body-inlining (no closure call at all); when the lambda
     * body isn't inlinable we extract the lambda value and call it via
     * {@link org.finos.legend.pure.truffle.ast.natives.lang.LambdaCallNoArgNode}.
     * Either way the {@code pair()} call site is bypassed entirely.</p>
     */
    private PureNode tryLowerMultiIf(FunctionExpression fe)
    {
        PureSequence params = fe._parametersValues();
        if (params == null || params.size() < 2)
        {
            return null;
        }
        Object pairsArg = params.getBoxed(0);
        if (!(pairsArg instanceof Collection col))
        {
            return null;
        }
        PureSequence pairValues = col._values();
        if (pairValues == null)
        {
            return null;
        }
        int n = pairValues.size();
        PureNode[] conds = new PureNode[n];
        PureNode[] bodies = new PureNode[n];
        for (int i = 0; i < n; i++)
        {
            Object pairExpr = pairValues.getBoxed(i);
            if (!(pairExpr instanceof FunctionExpression pairFe))
            {
                return null;
            }
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.Function pairFunc = pairFe._func();
            if (!(pairFunc instanceof FunctionDefinition)
                    || !(pairFunc instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pairPe)
                    || !PAIR_SIGNATURE.equals(pairPe._name()))
            {
                return null;
            }
            PureSequence pairArgs = pairFe._parametersValues();
            if (pairArgs == null || pairArgs.size() != 2)
            {
                return null;
            }
            PureNode condBody = lambdaArgAsBranch(pairArgs.getBoxed(0));
            PureNode bodyBody = lambdaArgAsBranch(pairArgs.getBoxed(1));
            if (condBody == null || bodyBody == null)
            {
                return null;
            }
            conds[i] = condBody;
            bodies[i] = bodyBody;
        }
        PureNode defaultBody = lambdaArgAsBranch(params.getBoxed(1));
        if (defaultBody == null)
        {
            return null;
        }
        return new org.finos.legend.pure.truffle.ast.natives.lang.MultiIfNode(conds, bodies, defaultBody);
    }

    /**
     * Lower a lambda argument (the {@code |expr} ValueSpecification) so
     * the result, when executed, produces the value the lambda would have
     * returned when called with no arguments. Two shapes:
     *
     * <ul>
     *   <li>{@code AtomicValue<LambdaFunction>} with no parameters and a
     *       single-expression body — inlined: lower the body directly so
     *       no closure call happens at all.</li>
     *   <li>Anything else — lowered as a value-producing expression and
     *       wrapped in a {@link org.finos.legend.pure.truffle.ast.natives.lang.LambdaCallNoArgNode}
     *       that calls the resulting lambda. Still skips the {@code pair()}
     *       call and {@code Pair} allocation that the runtime fallback
     *       mode would need.</li>
     * </ul>
     */
    private PureNode lambdaArgAsBranch(Object vs)
    {
        if (vs instanceof AtomicValue av && av._value() instanceof LambdaFunction lf
                && (lf._parameters() == null || lf._parameters().isEmpty()))
        {
            PureSequence body = lf._expressionSequence();
            if (body != null && body.size() == 1)
            {
                return lower(body.getBoxed(0));
            }
        }
        PureNode lowered = lower(vs);
        if (lowered == null)
        {
            return null;
        }
        return new org.finos.legend.pure.truffle.ast.natives.lang.LambdaCallNoArgNode(lowered);
    }

    /**
     * Find the correct QP overload from the owning class by matching parameter count.
     */
    private org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty findQpOverload(
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty wrongQp, int expectedParamCount)
    {
        var owner = wrongQp._owner();
        if (!(owner instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class cls))
        {
            return null;
        }
        var qps = cls._qualifiedProperties();
        if (qps == null) return null;
        String targetName = wrongQp._name();
        for (int i = 0; i < qps.size(); i++)
        {
            Object candidate = qps.getBoxed(i);
            if (candidate instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty cqp
                    && targetName.equals(cqp._name())
                    && cqp._parameters() != null
                    && cqp._parameters().size() == expectedParamCount)
            {
                return cqp;
            }
        }
        return null;
    }

    private PureNode lowerNativeCall(NativeFunction nf, FunctionExpression fe)
    {
        String signature = nf._name();
        if (LET_FUNCTION_SIGNATURE.equals(signature))
        {
            return lowerFrameLet(fe);
        }
        NativeNodeRegistry.Factory factory = specialized.lookup(signature);
        if (factory != null)
        {
            return factory.create(lowerArgs(fe), fe._genericType(), fe._multiplicity(), fe);
        }
        // All signatures should be registered. If we reach here, it's a
        // new native added without a corresponding Truffle node.
        throw new RuntimeException("No specialized Truffle node for native: " + signature);
    }

    private PureNode lowerVariableRead(VariableExpression ve)
    {
        if (currentLayout != null)
        {
            Integer slot = currentLayout.slotFor(ve._name());
            if (slot != null)
            {
                return new FrameVariableReadNode(slot, ve._name());
            }
        }
        return new FrameVariableReadNode(-1, ve._name());
    }

    private PureNode lowerFrameLet(FunctionExpression fe)
    {
        if (currentLayout == null)
        {
            throw new RuntimeException("letFunction lowered outside an enclosing function frame "
                    + "(no FrameLayout in scope) — letFunction has no meaningful semantics here");
        }
        org.finos.legend.pure.truffle.types.PureSequence args = fe._parametersValues();
        if (args == null || args.size() < 2)
        {
            throw new RuntimeException("letFunction requires at least (name, value); got "
                    + (args == null ? "null args" : args.size() + " args"));
        }
        Object nameArg = args.getBoxed(0);
        if (!(nameArg instanceof AtomicValue nameAv) || !(nameAv._value() instanceof String name))
        {
            throw new RuntimeException("letFunction's first argument must be a literal String AtomicValue; got: "
                    + (nameArg == null ? "null" : nameArg.getClass().getName()));
        }
        Integer slot = currentLayout.slotFor(name);
        if (slot == null)
        {
            throw new RuntimeException("letFunction target '" + name + "' has no pre-allocated slot in the current frame layout — "
                    + "FrameDescriptorBuilder failed to collect this let target (likely a PDB resolution issue)");
        }
        // If only one value arg, lower it directly
        if (args.size() == 2)
        {
            return new FrameLetFunctionNode(slot, lower(args.getBoxed(1)));
        }
        // Multiple value args: the T[m] parameter was flattened — wrap in collection
        PureNode[] valueNodes = new PureNode[args.size() - 1];
        for (int i = 1; i < args.size(); i++)
        {
            valueNodes[i - 1] = lower(args.getBoxed(i));
        }
        return new FrameLetFunctionNode(slot, new RawCollectionNode(valueNodes));
    }

    private static final java.util.Set<String> DATE_TYPE_NAMES = java.util.Set.of(
            "Date", "StrictDate", "DateTime", "StrictTime", "LatestDate"
    );

    private PureNode lowerAtomicValue(AtomicValue av)
    {
        Object value = av._value();
        if (value instanceof LambdaFunction lambda)
        {
            org.finos.legend.pure.truffle.types.PureSequence openVars = lambda._openVariables();
            if (openVars != null && !openVars.isEmpty())
            {
                return new RawLambdaCaptureNode(lambda, openVars, currentLayout);
            }
            return new AtomicValueNode(lambda);
        }
        if (value == null)
        {
            return new AtomicValueNode(org.finos.legend.pure.truffle.types.PureSequence.EMPTY);
        }
        // Pure's primitive types map to JVM primitives — emit typed constant
        // nodes so consumers calling executeLong / executeDouble /
        // executeBoolean skip the unbox.
        if (value instanceof Long l)
        {
            return new org.finos.legend.pure.truffle.ast.LongConstantNode(l);
        }
        if (value instanceof Double d)
        {
            return new org.finos.legend.pure.truffle.ast.DoubleConstantNode(d);
        }
        if (value instanceof Boolean b)
        {
            return new org.finos.legend.pure.truffle.ast.BooleanConstantNode(b);
        }
        if (value instanceof String s)
        {
            String typeName = extractTypeName(av);
            if (typeName != null && DATE_TYPE_NAMES.contains(typeName))
            {
                return new AtomicValueNode(PureDate.of(s, typeName));
            }
        }
        return new AtomicValueNode(value);
    }

    private static String extractTypeName(AtomicValue av)
    {
        try
        {
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType gt = av._genericType();
            if (gt == null)
            {
                return null;
            }
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type type =
                    org.finos.legend.pure.truffle.runtime.helper._GenericType.type(gt);
            if (type instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe)
            {
                return pe._name();
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("GenericType resolution failed", e);
        }
        return null;
    }

    private PureNode[] lowerArgs(FunctionExpression fe)
    {
        org.finos.legend.pure.truffle.types.PureSequence paramSpecs = fe._parametersValues();
        PureNode[] argNodes = new PureNode[paramSpecs.size()];
        for (int i = 0; i < paramSpecs.size(); i++)
        {
            argNodes[i] = lower(paramSpecs.getBoxed(i));
        }
        return argNodes;
    }
}
