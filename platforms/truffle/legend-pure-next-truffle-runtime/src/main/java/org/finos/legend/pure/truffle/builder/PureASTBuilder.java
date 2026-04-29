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

    private PureNode lowerFunctionExpression(FunctionExpression fe)
    {
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.Function func = fe._func();
        if (func == null)
        {
            throw new RuntimeException("_func() returned null for: " + fe._functionName() + " [" + fe.getClass().getSimpleName() + "]");
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
        return switch (resolvedFunc)
        {
            case NativeFunction nf -> lowerNativeCall(nf, fe);
            case FunctionDefinition fd -> new RawUserFunctionCallNode(fd, lowerArgs(fe));
            case AbstractProperty prop -> new RawPropertyAccessNode(fe, lowerArgs(fe));
            default -> throw new RuntimeException(
                    "Unsupported function type: " + resolvedFunc.getClass().getName() + " for: " + fe._functionName());
        };
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
