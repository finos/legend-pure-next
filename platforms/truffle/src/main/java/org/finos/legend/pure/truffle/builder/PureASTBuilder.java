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

import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.function.NativeFunction;
import meta.pure.metamodel.function.property.AbstractProperty;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.truffle.ast.AtomicValueNode;
import org.finos.legend.pure.truffle.ast.FrameLetFunctionNode;
import org.finos.legend.pure.truffle.ast.FrameVariableReadNode;
import org.finos.legend.pure.truffle.ast.GenericTypeHolderNode;
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
    public PureNode[] lowerBody(Iterable<ValueSpecification> exprs, FrameLayout layout)
    {
        FrameLayout previous = pushLayout(layout);
        try
        {
            java.util.ArrayList<PureNode> nodes = new java.util.ArrayList<>();
            for (ValueSpecification expr : exprs)
            {
                nodes.add(lower(expr));
            }
            return nodes.toArray(new PureNode[0]);
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

    public boolean hasCurrentLayout()
    {
        return currentLayout != null;
    }

    /**
     * Lower a single {@link ValueSpecification} into an executable Truffle node.
     */
    public PureNode lower(ValueSpecification vs)
    {
        return switch (vs)
        {
            case AtomicValue av -> lowerAtomicValue(av);
            case VariableExpression ve -> lowerVariableRead(ve);
            case Collection col ->
            {
                MutableList<ValueSpecification> values = col._values();
                PureNode[] children = new PureNode[values.size()];
                for (int i = 0; i < values.size(); i++)
                {
                    children[i] = lower(values.get(i));
                }
                yield new RawCollectionNode(children);
            }
            case GenericTypeAndMultiplicityHolder gmh -> new GenericTypeHolderNode(gmh);
            case FunctionExpression fe -> lowerFunctionExpression(fe);
            default -> throw new RuntimeException(
                    "Unsupported ValueSpecification type: " + vs.getClass().getName());
        };
    }

    private PureNode lowerFunctionExpression(FunctionExpression fe)
    {
        meta.pure.metamodel.function.Function func;
        try
        {
            func = fe._func();
        }
        catch (RuntimeException e)
        {
            // _func() FlatBuffer lazy resolution failed. Try name+arity fallback.
            String functionName = fe._functionName();
            if (functionName != null)
            {
                int argCount = fe._parametersValues() != null ? fe._parametersValues().size() : 0;
                NativeNodeRegistry.Factory factory = specialized.lookupByNameAndArity(functionName, argCount);
                if (factory != null)
                {
                    return factory.create(lowerArgs(fe), fe._genericType(), fe._multiplicity(), fe);
                }
            }
            return new RawPropertyAccessNode(fe, lowerArgs(fe));
        }
        return switch (func)
        {
            case NativeFunction nf -> lowerNativeCall(nf, fe);
            case FunctionDefinition fd -> new RawUserFunctionCallNode(fd, lowerArgs(fe));
            case AbstractProperty prop -> new RawPropertyAccessNode(fe, lowerArgs(fe));
            case null -> new RawPropertyAccessNode(fe, lowerArgs(fe));
            default -> throw new RuntimeException(
                    "Unsupported function type: " + func.getClass().getName());
        };
    }

    private PureNode lowerNativeCall(NativeFunction nf, FunctionExpression fe)
    {
        String signature = nf._name();
        if (LET_FUNCTION_SIGNATURE.equals(signature))
        {
            PureNode slotNode = tryLowerFrameLet(fe);
            if (slotNode != null)
            {
                return slotNode;
            }
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

    private PureNode tryLowerFrameLet(FunctionExpression fe)
    {
        if (currentLayout == null)
        {
            return null;
        }
        MutableList<ValueSpecification> args = fe._parametersValues();
        if (args == null || args.size() < 2 || !(args.get(0) instanceof AtomicValue nameAv)
                || !(nameAv._value() instanceof String name))
        {
            return null;
        }
        Integer slot = currentLayout.slotFor(name);
        if (slot == null)
        {
            return null;
        }
        // If only one value arg, lower it directly
        if (args.size() == 2)
        {
            return new FrameLetFunctionNode(slot, lower(args.get(1)));
        }
        // Multiple value args: the T[m] parameter was flattened — wrap in collection
        PureNode[] valueNodes = new PureNode[args.size() - 1];
        for (int i = 1; i < args.size(); i++)
        {
            valueNodes[i - 1] = lower(args.get(i));
        }
        return new FrameLetFunctionNode(slot, new RawCollectionNode(valueNodes));
    }

    private PureNode lowerAtomicValue(AtomicValue av)
    {
        Object value = av._value();
        if (value instanceof LambdaFunction lambda)
        {
            MutableList<VariableExpression> openVars = lambda._openVariables();
            if (openVars != null && !openVars.isEmpty())
            {
                return new RawLambdaCaptureNode(lambda, openVars, currentLayout);
            }
            // Lambdas without open vars keep the AtomicValue wrapper for now —
            // eval/match dispatch code checks for AtomicValue wrapping LambdaFunction.
            // TODO: eliminate once all dispatch paths handle RawClosure directly.
            return new AtomicValueNode(av);
        }
        if (value == null)
        {
            return new AtomicValueNode(org.finos.legend.pure.truffle.types.PureNull.INSTANCE);
        }
        // Date strings are kept as AtomicValue to preserve type info —
        // downstream date nodes check genericType to distinguish StrictDate/DateTime.
        // TODO: eliminate once all date paths use java.time objects.
        if (value instanceof String s)
        {
            meta.pure.metamodel.type.generics.GenericType gt = av._genericType();
            if (gt != null)
            {
                meta.pure.metamodel.type.Type type =
                        org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(gt);
                if (type instanceof meta.pure.metamodel.PackageableElement pe)
                {
                    String typeName = pe._name();
                    if ("StrictDate".equals(typeName) || "Date".equals(typeName)
                            || "DateTime".equals(typeName) || "StrictTime".equals(typeName))
                    {
                        return new AtomicValueNode(av);
                    }
                }
            }
        }
        return new AtomicValueNode(value);
    }

    private PureNode[] lowerArgs(FunctionExpression fe)
    {
        MutableList<ValueSpecification> paramSpecs = fe._parametersValues();
        PureNode[] argNodes = new PureNode[paramSpecs.size()];
        for (int i = 0; i < paramSpecs.size(); i++)
        {
            argNodes[i] = lower(paramSpecs.get(i));
        }
        return argNodes;
    }
}
