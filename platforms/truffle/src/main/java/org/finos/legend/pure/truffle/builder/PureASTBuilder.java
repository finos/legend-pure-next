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
import org.finos.legend.pure.execution.NativeRepository;
import org.finos.legend.pure.truffle.ast.AtomicValueNode;
import org.finos.legend.pure.truffle.ast.BridgedNativeCallNode;
import org.finos.legend.pure.truffle.ast.CollectionNode;
import org.finos.legend.pure.truffle.ast.FrameLetFunctionNode;
import org.finos.legend.pure.truffle.ast.FrameVariableReadNode;
import org.finos.legend.pure.truffle.ast.GenericTypeHolderNode;
import org.finos.legend.pure.truffle.ast.LambdaCaptureNode;
import org.finos.legend.pure.truffle.ast.LazyNativeCallNode;
import org.finos.legend.pure.truffle.ast.PropertyAccessNode;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.UserFunctionCallNode;
import org.finos.legend.pure.truffle.ast.VariableReadNode;
import org.finos.legend.pure.truffle.frame.FrameLayout;

/**
 * Lowers a Pure {@link ValueSpecification} tree into a Truffle {@link PureNode}
 * AST. Mirrors {@code ValueSpecificationEvaluator.evaluate}'s top-level switch.
 *
 * <p>For each native call, the builder consults {@link NativeNodeRegistry}
 * first — a specialized node operates on raw values and is inlineable by
 * Graal. Signatures without a specialization fall back to {@link
 * BridgedNativeCallNode}, which crosses a {@code @TruffleBoundary} to the
 * legacy {@link NativeRepository}. Short-circuit / lazy natives always
 * route through {@link LazyNativeCallNode} since they control argument
 * evaluation.</p>
 */
public final class PureASTBuilder
{
    private static final String LET_FUNCTION_SIGNATURE = "letFunction_String_1__T_m__T_m_";

    private final NativeRepository natives;
    private final NativeNodeRegistry specialized;

    // Current enclosing FunctionDefinition's frame layout. Set by
    // {@link #lowerBody} and consulted when lowering variable reads /
    // letFunction calls. Null means "no frame in scope" — emit HashMap
    // VariableReadNode / BridgedNativeCallNode(letFunction).
    private FrameLayout currentLayout;

    public PureASTBuilder(NativeRepository natives)
    {
        this(natives, NativeNodeRegistry.empty());
    }

    public NativeNodeRegistry specialized()
    {
        return specialized;
    }

    public PureASTBuilder(NativeRepository natives, NativeNodeRegistry specialized)
    {
        this.natives = natives;
        this.specialized = specialized;
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
                yield new CollectionNode(children, col._genericType(), col._multiplicity());
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
            // Some FlatBuffer-backed DotApplications resolve their `_func()`
            // lazily against the target's runtime class, and touching that
            // resolution at lower time (before the target is evaluated) can
            // throw IndexOutOfBounds on unreachable references. Defer to
            // the Java evaluator, which evaluates the target first.
            return new org.finos.legend.pure.truffle.ast.DeferredLowerNode(fe);
        }
        // Note: QualifiedProperty implements both FunctionDefinition and
        // AbstractProperty. Matching FunctionDefinition first means QPs
        // dispatch through UserFunctionCallNode, which bypasses Java's
        // evaluatePropertyFunc type-variable binding. This is a known gap
        // — switching the order causes a different Truffle-only failure
        // (FlatBuffer IOBE when Java's evaluator re-reads the same FE via
        // javaEvaluate), so we leave the ordering and accept the missed
        // type-var binding for now. testNewWithTypeVariables is the only
        // test that exercises this; all other QPs have no type variables.
        return switch (func)
        {
            case NativeFunction nf -> lowerNativeCall(nf, fe);
            case FunctionDefinition fd -> new UserFunctionCallNode(fd, lowerArgs(fe));
            case AbstractProperty prop -> new PropertyAccessNode(fe);
            case null -> new org.finos.legend.pure.truffle.ast.DeferredLowerNode(fe);
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
        if (natives.isLazy(signature))
        {
            return new LazyNativeCallNode(signature, fe);
        }
        NativeNodeRegistry.Factory factory = specialized.lookup(signature);
        if (factory != null)
        {
            return factory.create(lowerArgs(fe), fe._genericType(), fe._multiplicity(), fe);
        }
        return new BridgedNativeCallNode(signature, lowerArgs(fe), fe._genericType(), fe._multiplicity());
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
        return new VariableReadNode(ve._name());
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
        return new FrameLetFunctionNode(slot, lower(args.get(1)));
    }

    private PureNode lowerAtomicValue(AtomicValue av)
    {
        Object value = av._value();
        if (value instanceof LambdaFunction lambda)
        {
            MutableList<VariableExpression> openVars = lambda._openVariables();
            if (openVars != null && !openVars.isEmpty())
            {
                return new LambdaCaptureNode(av, lambda, openVars, currentLayout);
            }
            // Lambdas without open vars keep the AtomicValue wrapper —
            // executeFunction/eval/match expect a VS wrapping a lambda.
            return new AtomicValueNode(av);
        }
        if (value == null)
        {
            return new AtomicValueNode(org.finos.legend.pure.truffle.types.PureNull.INSTANCE);
        }
        // Primitives → raw (consumers handle via IntegerHelper etc.)
        if (value instanceof Long || value instanceof Double || value instanceof Boolean)
        {
            return new AtomicValueNode(value);
        }
        // String: only unwrap if the type IS String (not Date/Enum stored as String)
        if (value instanceof String)
        {
            meta.pure.metamodel.type.generics.GenericType gt = av._genericType();
            if (gt != null)
            {
                meta.pure.metamodel.type.Type type =
                        org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(gt);
                if (type instanceof meta.pure.metamodel.PackageableElement pe && !"String".equals(pe._name()))
                {
                    return new AtomicValueNode(av);
                }
            }
            return new AtomicValueNode(value);
        }
        // Everything else (metamodel objects, DynamicInstance, etc.)
        // Keep the AV wrapper to preserve genericType metadata.
        return new AtomicValueNode(av);
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
