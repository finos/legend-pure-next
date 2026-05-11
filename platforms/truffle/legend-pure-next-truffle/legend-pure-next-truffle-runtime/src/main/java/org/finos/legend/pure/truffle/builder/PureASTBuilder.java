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
     *
     * <p>Each arm matches the typed XImpl form first (covers all subtype-of-X
     * cases for free via {@code instanceof}); a fallback resolver-driven
     * {@link org.finos.legend.pure.truffle.runtime.dynobj.PureObj#isType}
     * handles {@link org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject}
     * inputs, where subtyping is encoded in PDB metadata rather than the
     * Java class hierarchy.</p>
     */
    public PureNode lower(Object vs)
    {
        PureNode node = lowerImpl(vs);
        // Attach Pure source location for stack traces
        PureSourceHelper.withSource(node, vs);
        return node;
    }

    private PureNode lowerImpl(Object vs)
    {
        // Dispatch by Pure metaclass via the resolver-driven isType
        // (subtype-aware). Post-PDO-flip every Pure value reaching here is a
        // PureDynamicObject; the typed-fast-path arms that used to live here
        // (`vs instanceof AtomicValue` etc.) are dead.
        org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver =
                org.finos.legend.pure.truffle.PureLanguage.get(null).resolver();
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(vs,
                "meta::pure::metamodel::valuespecification::AtomicValue", resolver))
        {
            return lowerAtomicValue(vs);
        }
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(vs,
                "meta::pure::metamodel::valuespecification::VariableExpression", resolver))
        {
            return lowerVariableRead(vs);
        }
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(vs,
                "meta::pure::metamodel::valuespecification::Collection", resolver))
        {
            return lowerCollection(vs);
        }
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(vs,
                "meta::pure::metamodel::valuespecification::GenericTypeAndMultiplicityHolder", resolver))
        {
            return new AtomicValueNode(vs);
        }
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(vs,
                "meta::pure::metamodel::valuespecification::FunctionExpression", resolver))
        {
            return lowerFunctionExpression(vs);
        }
        throw new RuntimeException(
                "Unsupported ValueSpecification type: " + (vs == null ? "null" : vs.getClass().getName()));
    }

    private PureNode lowerCollection(Object col)
    {
        Object valuesObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(col, "values");
        org.finos.legend.pure.truffle.types.PureSequence values = valuesObj instanceof org.finos.legend.pure.truffle.types.PureSequence vs2
                ? vs2 : org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        PureNode[] children = new PureNode[values.size()];
        for (int i = 0; i < values.size(); i++)
        {
            children[i] = lower(values.getBoxed(i));
        }
        return new RawCollectionNode(children);
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
    private PureNode lowerPropertyAccess(Object prop, Object fe)
    {
        PureNode[] args = lowerArgs(fe);
        Object propName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(prop, "name");
        boolean isQp = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(prop,
                "meta::pure::metamodel::function::property::QualifiedProperty");
        if (args.length == 1
                && !isQp
                && propName instanceof String name
                && !canTargetBeEnumeration(prop))
        {
            return new org.finos.legend.pure.truffle.ast.DirectPropertyAccessNode(args[0], name);
        }
        return new RawPropertyAccessNode(fe, args);
    }

    /**
     * True if the property's owning type might be an Enumeration. Enum
     * targets have special semantics ({@code $enum.someValue} can
     * resolve to either a metaclass property OR an enum value), so the
     * direct-access node can't safely handle them.
     */
    private static boolean canTargetBeEnumeration(Object prop)
    {
        Object owner = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(prop, "owner");
        // Conservative: if owner is anything other than a non-Enumeration
        // PackageableElement Class, we don't know — fall back to the full
        // RawPropertyAccessNode path. Most properties have a non-Enumeration
        // owning class, so the direct path catches the vast majority.
        if (owner == null) return true;
        return org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(owner,
                "meta::pure::metamodel::type::Enumeration");
    }

    private PureNode lowerFunctionExpression(Object fe)
    {
        Object funcObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fe, "func");
        if (funcObj == null)
        {
            Object fnNameDbg = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fe, "functionName");
            throw new RuntimeException("_func() returned null for: " + fnNameDbg + " [" + fe.getClass().getName() + "]");
        }
        Object func = funcObj;
        // QP overload disambiguation: the PDB func path may resolve to the wrong
        // overload when multiple QPs share the same simple name (e.g. res() vs res(z)).
        // Fix by matching the QP's param count against the call's arg count.
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(func,
                "meta::pure::metamodel::function::property::QualifiedProperty"))
        {
            Object feParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fe, "parametersValues");
            int callArgCount = feParamsObj instanceof PureSequence feps ? feps.size() : 0;
            Object qpParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(func, "parameters");
            int qpParamCount = qpParamsObj instanceof PureSequence qpps ? qpps.size() : 0;
            if (qpParamCount != callArgCount)
            {
                // Wrong overload — find the right one from the owning class
                Object correct = findQpOverload(func, callArgCount);
                if (correct != null)
                {
                    func = correct;
                }
            }
        }
        final Object resolvedFunc = func;
        // Recognise the multi-clause if pattern at AST-build time and lower
        // it to a flat conditional chain — see {@link MultiIfNode}.
        // Static-form fast path for the multi-clause if — applies whether
        // the function is a NativeFunction or FunctionDefinition. Literal
        // pair-list patterns lower to a flat {@link MultiIfNode} with no
        // Pair allocation; non-literal patterns fall through to the
        // regular dispatch (where the native factory builds a runtime
        // {@code MultiIfNode}).
        Object resolvedFuncName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(resolvedFunc, "name");
        if (MULTI_IF_SIGNATURE.equals(resolvedFuncName))
        {
            PureNode multiIf = tryLowerMultiIf(fe);
            if (multiIf != null)
            {
                return multiIf;
            }
        }
        // Dispatch by Pure metaclass — works for both XImpl (legacy) and
        // PureDynamicObject (post-flip). NativeFunction is a leaf concrete
        // type (no subtypes), so pureTypeIs is enough; FunctionDefinition /
        // AbstractProperty are interface roots requiring isType (subtype check).
        //
        // Order matters: QualifiedProperty extends BOTH FunctionDefinition
        // and AbstractProperty (multiple-interface inheritance), so the
        // FunctionDefinition branch must come first to route QPs through
        // RawUserFunctionCallNode (which has the polymorphic-dispatch
        // logic). lowerPropertyAccess only handles plain Property reads.
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(resolvedFunc,
                "meta::pure::metamodel::function::NativeFunction"))
        {
            return lowerNativeCall(resolvedFunc, fe);
        }
        org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver =
                org.finos.legend.pure.truffle.PureLanguage.get(null).resolver();
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(resolvedFunc,
                "meta::pure::metamodel::function::FunctionDefinition", resolver))
        {
            return new RawUserFunctionCallNode(resolvedFunc, lowerArgs(fe));
        }
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(resolvedFunc,
                "meta::pure::metamodel::function::property::AbstractProperty", resolver))
        {
            return lowerPropertyAccess(resolvedFunc, fe);
        }
        throw new RuntimeException(
                "Unsupported function type: " + resolvedFunc.getClass().getName() + " for: "
                        + org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fe, "functionName"));
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
    private PureNode tryLowerMultiIf(Object fe)
    {
        Object paramsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fe, "parametersValues");
        if (!(paramsObj instanceof PureSequence params) || params.size() < 2)
        {
            return null;
        }
        Object pairsArg = params.getBoxed(0);
        if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(pairsArg,
                "meta::pure::metamodel::valuespecification::Collection"))
        {
            return null;
        }
        Object pairValuesObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(pairsArg, "values");
        if (!(pairValuesObj instanceof PureSequence pairValues))
        {
            return null;
        }
        int n = pairValues.size();
        PureNode[] conds = new PureNode[n];
        PureNode[] bodies = new PureNode[n];
        for (int i = 0; i < n; i++)
        {
            Object pairFe = pairValues.getBoxed(i);
            if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(pairFe,
                    "meta::pure::metamodel::valuespecification::FunctionExpression"))
            {
                return null;
            }
            Object pairFunc = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(pairFe, "func");
            if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(pairFunc,
                    "meta::pure::metamodel::function::FunctionDefinition",
                    org.finos.legend.pure.truffle.PureLanguage.get(null).resolver())
                    || !PAIR_SIGNATURE.equals(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(pairFunc, "name")))
            {
                return null;
            }
            Object pairArgsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(pairFe, "parametersValues");
            PureSequence pairArgs = pairArgsObj instanceof PureSequence pa ? pa : null;
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
        Object inner = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(vs,
                "meta::pure::metamodel::valuespecification::AtomicValue")
                ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(vs, "value") : null;
        // Widened from `instanceof LambdaFunction` so PDO lambdas
        // (post-loader-flip resolver returns) take the same fast path.
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(inner,
                "meta::pure::metamodel::function::LambdaFunction"))
        {
            Object lfParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(inner, "parameters");
            if (!(lfParamsObj instanceof PureSequence lfParams) || lfParams.isEmpty())
            {
                Object bodyObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(inner, "expressionSequence");
                if (bodyObj instanceof PureSequence body && body.size() == 1)
                {
                    return lower(body.getBoxed(0));
                }
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
    private Object findQpOverload(Object wrongQp, int expectedParamCount)
    {
        Object owner = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(wrongQp, "owner");
        if (owner == null) return null;
        Object qpsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(owner, "qualifiedProperties");
        if (!(qpsObj instanceof PureSequence qps)) return null;
        Object targetNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(wrongQp, "name");
        if (!(targetNameObj instanceof String targetName)) return null;
        for (int i = 0; i < qps.size(); i++)
        {
            Object candidate = qps.getBoxed(i);
            if (candidate == null) continue;
            Object cqpName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(candidate, "name");
            if (!targetName.equals(cqpName)) continue;
            Object cqpParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(candidate, "parameters");
            if (cqpParamsObj instanceof PureSequence cqpParams
                    && cqpParams.size() == expectedParamCount
                    && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(candidate,
                            "meta::pure::metamodel::function::property::QualifiedProperty"))
            {
                return candidate;
            }
        }
        return null;
    }

    private PureNode lowerNativeCall(Object nf, Object fe)
    {
        Object sigObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(nf, "name");
        String signature = sigObj instanceof String s ? s : null;
        if (LET_FUNCTION_SIGNATURE.equals(signature))
        {
            return lowerFrameLet(fe);
        }
        NativeNodeRegistry.Factory factory = specialized.lookup(signature);
        if (factory != null)
        {
            Object gt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fe, "genericType");
            Object mul = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fe, "multiplicity");
            return factory.create(lowerArgs(fe), gt, mul, fe);
        }
        // All signatures should be registered. If we reach here, it's a
        // new native added without a corresponding Truffle node.
        throw new RuntimeException("No specialized Truffle node for native: " + signature);
    }

    private PureNode lowerVariableRead(Object ve)
    {
        Object nameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(ve, "name");
        String veName = nameObj instanceof String s ? s : null;
        if (currentLayout != null)
        {
            Integer slot = currentLayout.slotFor(veName);
            if (slot != null)
            {
                return new FrameVariableReadNode(slot, veName);
            }
        }
        return new FrameVariableReadNode(-1, veName);
    }

    private PureNode lowerFrameLet(Object fe)
    {
        if (currentLayout == null)
        {
            throw new RuntimeException("letFunction lowered outside an enclosing function frame "
                    + "(no FrameLayout in scope) — letFunction has no meaningful semantics here");
        }
        Object argsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fe, "parametersValues");
        if (!(argsObj instanceof PureSequence args) || args.size() < 2)
        {
            throw new RuntimeException("letFunction requires at least (name, value); got "
                    + (argsObj == null ? "null args" : (argsObj instanceof PureSequence ps ? ps.size() : 0) + " args"));
        }
        Object nameArg = args.getBoxed(0);
        Object nameInner = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(nameArg,
                "meta::pure::metamodel::valuespecification::AtomicValue")
                ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(nameArg, "value") : null;
        if (!(nameInner instanceof String name))
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

    private PureNode lowerAtomicValue(Object av)
    {
        Object value = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(av, "value");
        // Widened from `instanceof LambdaFunction` so PDO lambdas
        // (post-loader-flip resolver returns) take the same path.
        if (value != null && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(value,
                "meta::pure::metamodel::function::LambdaFunction"))
        {
            Object openVarsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(value, "openVariables");
            if (openVarsObj instanceof PureSequence openVars && !openVars.isEmpty())
            {
                return new RawLambdaCaptureNode(value, openVars, currentLayout);
            }
            return new AtomicValueNode(value);
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

    private static String extractTypeName(Object av)
    {
        try
        {
            Object gt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(av, "genericType");
            if (gt == null)
            {
                return null;
            }
            Object type = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(gt);
            if (type != null)
            {
                Object n = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(type, "name");
                if (n instanceof String s) return s;
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("GenericType resolution failed", e);
        }
        return null;
    }

    private PureNode[] lowerArgs(Object fe)
    {
        Object paramSpecsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fe, "parametersValues");
        org.finos.legend.pure.truffle.types.PureSequence paramSpecs = paramSpecsObj instanceof PureSequence ps
                ? ps : org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        PureNode[] argNodes = new PureNode[paramSpecs.size()];
        for (int i = 0; i < paramSpecs.size(); i++)
        {
            argNodes[i] = lower(paramSpecs.getBoxed(i));
        }
        return argNodes;
    }
}
