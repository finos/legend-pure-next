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

    private static final int SLOT_EXPRESSION_SEQUENCE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("expressionSequence");
    private static final int SLOT_FUNC = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("func");
    private static final int SLOT_FUNCTION_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("functionName");
    private static final int SLOT_GENERIC_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("genericType");
    private static final int SLOT_MULTIPLICITY = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("multiplicity");
    private static final int SLOT_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");
    private static final int SLOT_OPEN_VARIABLES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("openVariables");
    private static final int SLOT_OWNER = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("owner");
    private static final int SLOT_PARAMETERS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("parameters");
    private static final int SLOT_PARAMETERS_VALUES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("parametersValues");
    private static final int SLOT_QUALIFIED_PROPERTIES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("qualifiedProperties");
    private static final int SLOT_VALUE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("value");
    private static final int SLOT_VALUES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("values");
    private static final int SLOT_LOWER_BOUND = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("lowerBound");
    private static final int SLOT_UPPER_BOUND = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("upperBound");
    private static final String LET_FUNCTION_SIGNATURE = "letFunction_String_1__T_m__T_m_";
    private static final String PAIR_SIGNATURE = "pair_U_1__V_1__Pair_1_";

    private final NativeNodeRegistry specialized;

    // Current enclosing FunctionDefinition's frame layout. Set by
    // {@link #lowerBody} and consulted when lowering variable reads /
    // letFunction calls. Null means "no frame in scope".
    private FrameLayout currentLayout;

    private Integer resolveSlot(String name)
    {
        return currentLayout == null ? null : currentLayout.slotFor(name);
    }

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
     * <p>Each arm matches the typed XPDBHelper form first (covers all subtype-of-X
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
        Object valuesObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(col, SLOT_VALUES);
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
        Object propName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(prop, SLOT_NAME);
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
        Object owner = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(prop, SLOT_OWNER);
        // Conservative: if owner is anything other than a non-Enumeration
        // PackageableElement Class, we don't know — fall back to the full
        // RawPropertyAccessNode path. Most properties have a non-Enumeration
        // owning class, so the direct path catches the vast majority.
        if (owner == null) return true;
        return org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(owner,
                "meta::pure::metamodel::type::Enumeration");
    }

    /** Set by TruffleBackend before execution — identityHashes of every
     *  FunctionApplication/Invocation/DotApplication reachable from the
     *  registered module-mem. If the failing FE's hash is NOT in this set,
     *  the runtime is reading an object that compileDir didn't produce. */
    public static java.util.Set<Integer> KNOWN_FE_HASHES;

    private PureNode lowerFunctionExpression(Object fe)
    {
        Object funcObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_FUNC);
        if (funcObj == null)
        {
            // Diagnostic: walk the parent chain to identify WHERE this
            // unresolved FE lives — typically a constraint lambda on a
            // class. Without the parent path, the user only sees `or` /
            // sourceInfo=null and has no way to find the broken element.
            Object funcByName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fe, "func");
            Object fnNameDbg = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_FUNCTION_NAME);
            if (fnNameDbg == null)
            {
                fnNameDbg = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fe, "functionName");
            }
            // Metamodel FE uses `sourceInformation`; the `p_sourceInformation`
            // prefix is the protocol-side variant. Read both for completeness.
            Object srcInfo = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fe, "sourceInformation");
            String srcStr;
            if (srcInfo == null)
            {
                srcStr = "null";
            }
            else
            {
                Object sid = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(srcInfo, "sourceId");
                Object sl = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(srcInfo, "startLine");
                Object sc = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(srcInfo, "startColumn");
                Object el = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(srcInfo, "endLine");
                Object ec = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(srcInfo, "endColumn");
                srcStr = sid + ":" + sl + "c" + sc + "-" + el + "c" + ec;
            }
            StringBuilder parents = new StringBuilder();
            Object cur = fe;
            int depth = 0;
            java.util.IdentityHashMap<Object, Boolean> visited = new java.util.IdentityHashMap<>();
            while (cur != null && depth < 12 && visited.put(cur, Boolean.TRUE) == null)
            {
                String t = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(cur);
                Object nm = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(cur, "name");
                Object fn = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(cur, "functionName");
                parents.append("\n      ").append(depth).append(": ")
                        .append(t == null ? cur.getClass().getName() : t)
                        .append(" name=").append(nm)
                        .append(" functionName=").append(fn);
                Object next = cur instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdo ? pdo.parent : null;
                if (next == null)
                {
                    next = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(cur, "owner");
                }
                cur = next;
                depth++;
            }
            // Full slot dump: print every property this FE's class declares
            // and its value. Lets us see what state IS set so we can identify
            // where this orphan PDO was synthesized.
            StringBuilder slots = new StringBuilder();
            if (fe instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdo)
            {
                String[] names = pdo.classInfo.nameBySlot();
                for (int s = 0; s < names.length; s++)
                {
                    String name = names[s];
                    if (name == null) continue;
                    Object v;
                    try { v = pdo.readSlot(s); } catch (Throwable t) { v = "<err:" + t + ">"; }
                    String vs;
                    if (v == null) vs = "null";
                    else if (v instanceof String) vs = "\"" + v + "\"";
                    else if (v instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject vp) vs = vp.classInfo.purePath + "@" + System.identityHashCode(vp);
                    else if (v instanceof org.finos.legend.pure.truffle.types.PureSequence vq) vs = "Seq[" + vq.size() + "]";
                    else vs = v.getClass().getName() + "@" + System.identityHashCode(v);
                    slots.append("\n      ").append(s).append(":").append(name).append("=").append(vs);
                }
            }
            // Look up Field from the live resolver. Compare the FE we got
            // here (from the runtime call chain) with the FE that the
            // resolver-registered Field's constraint actually contains.
            // Different identity → some other path is producing this FE,
            // typically a pass-2 captured reference.
            StringBuilder registryView = new StringBuilder();
            try
            {
                org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess r =
                        org.finos.legend.pure.truffle.PureLanguage.get(null).resolver();
                Object regField = r.getElement("meta::external::language::java::metamodel::Field");
                if (regField != null)
                {
                    registryView.append("\n  registry Field@").append(System.identityHashCode(regField));
                    Object cs = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(regField, "constraints");
                    if (cs instanceof org.finos.legend.pure.truffle.types.PureSequence csSeq && csSeq.size() > 0)
                    {
                        Object c0 = csSeq.getBoxed(0);
                        registryView.append("\n  registry Field.constraints[0]@").append(System.identityHashCode(c0));
                        Object lam = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(c0, "functionDefinition");
                        registryView.append("\n  registry .functionDefinition@").append(System.identityHashCode(lam));
                        Object es = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(lam, "expressionSequence");
                        if (es instanceof org.finos.legend.pure.truffle.types.PureSequence esSeq && esSeq.size() > 0)
                        {
                            Object regFe = esSeq.getBoxed(0);
                            Object regFunc = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(regFe, "func");
                            registryView.append("\n  registry .expressionSequence[0]@").append(System.identityHashCode(regFe))
                                    .append(" func=").append(regFunc == null ? "null" : org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(regFunc));
                            registryView.append("\n  SAME as failing FE? ").append(regFe == fe);
                        }
                    }
                }
                else
                {
                    registryView.append("\n  registry has no Field");
                }
            }
            catch (Throwable t)
            {
                registryView.append("\n  registry lookup failed: ").append(t);
            }
            throw new RuntimeException("_func() returned null for: " + fnNameDbg
                    + " [" + fe.getClass().getName() + "]"
                    + " (SLOT_FUNC=" + SLOT_FUNC + ")"
                    + " (read-by-name func=" + (funcByName == null ? "null" : funcByName.getClass().getName()) + ")"
                    + " (sourceInfo=" + srcStr + ")"
                    + " (identityHash=" + System.identityHashCode(fe) + ")"
                    + " (inModuleMem=" + (KNOWN_FE_HASHES != null && KNOWN_FE_HASHES.contains(System.identityHashCode(fe))) + ")"
                    + " slots:" + slots
                    + " parents:" + parents
                    + " registry-view:" + registryView);
        }
        Object func = funcObj;
        // Pointer dereference: compile-pure emits TempCompilerPointer-typed funcs
        // (PackageableFunctionPointer, PropertyPointer, QualifiedPropertyPointer)
        // to keep cross-element refs identity-stable across compile passes. The
        // AST builder needs the live target's metaclass (NativeFunction /
        // FunctionDefinition / AbstractProperty) to choose a dispatch arm, so
        // resolve through the registry here before the metaclass checks below.
        org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess pointerResolver =
                org.finos.legend.pure.truffle.PureLanguage.get(null).resolver();
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(func,
                "meta::pure::metamodel::pointer::TempCompilerPointer", pointerResolver))
        {
            func = dereferencePointer(func, pointerResolver);
            if (func == null)
            {
                throw new RuntimeException("Failed to dereference pointer func in: "
                        + org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_FUNCTION_NAME));
            }
        }
        // QP overload disambiguation: the PDB func path may resolve to the wrong
        // overload when multiple QPs share the same simple name (e.g. res() vs res(z)).
        // Fix by matching the QP's param count against the call's arg count.
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(func,
                "meta::pure::metamodel::function::property::QualifiedProperty"))
        {
            Object feParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_PARAMETERS_VALUES);
            int callArgCount = feParamsObj instanceof PureSequence feps ? feps.size() : 0;
            Object qpParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(func, SLOT_PARAMETERS);
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
        // Dispatch by Pure metaclass — works for both XPDBHelper (legacy) and
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
        String pureType = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(resolvedFunc);
        throw new RuntimeException(
                "Unsupported function type: " + (pureType != null ? pureType : resolvedFunc.getClass().getName())
                        + " for: " + org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_FUNCTION_NAME));
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
    public PureNode tryLowerMultiIf(Object fe)
    {
        Object paramsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_PARAMETERS_VALUES);
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
        Object pairValuesObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(pairsArg, SLOT_VALUES);
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
            // Subtype check — pair-list elements are concretely
            // {@code FunctionInvocation} (a subtype of {@code FunctionExpression}),
            // not the abstract supertype itself. Using {@code pureTypeIs}'s
            // exact-match check here silently dropped every multi-if site into
            // the runtime mode (35/35 → pair allocs per call) for the entire
            // pre-fix history of this code.
            if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(pairFe,
                    "meta::pure::metamodel::valuespecification::FunctionExpression",
                    org.finos.legend.pure.truffle.PureLanguage.get(null).resolver()))
            {
                return null;
            }
            Object pairFunc = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(pairFe, SLOT_FUNC);
            if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(pairFunc,
                    "meta::pure::metamodel::function::FunctionDefinition",
                    org.finos.legend.pure.truffle.PureLanguage.get(null).resolver())
                    || !PAIR_SIGNATURE.equals(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(pairFunc, SLOT_NAME)))
            {
                return null;
            }
            Object pairArgsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(pairFe, SLOT_PARAMETERS_VALUES);
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
     * Try to lower a call to {@code if(Boolean[1], Function<{->T[m]}>[1], Function<{->T[m]}>[1])}
     * — the plain 3-arg if — into a static-mode {@link org.finos.legend.pure.truffle.ast.natives.lang.IfNode}
     * with inlined then/else bodies.
     *
     * <p>Triggers when both branch arguments are literal 0-param closure-lambdas
     * (the overwhelming common case: {@code if($x, |body1, |body2)}). At AST
     * build, each branch's body becomes a {@code @Child PureNode} on the IfNode,
     * eliminating the {@code RawClosure} allocation + {@code RawLambdaCallNode}
     * dispatch per call. Falls back to {@code null} (generic mode) when a
     * branch is anything else (e.g. a variable holding a lambda).</p>
     */
    public PureNode tryLowerIf(Object fe)
    {
        Object paramsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_PARAMETERS_VALUES);
        if (!(paramsObj instanceof PureSequence params) || params.size() != 3)
        {
            return null;
        }
        PureNode thenBody = lambdaArgAsBranch(params.getBoxed(1));
        PureNode elseBody = lambdaArgAsBranch(params.getBoxed(2));
        if (thenBody == null || elseBody == null) return null;
        PureNode condition = lower(params.getBoxed(0));
        if (condition == null) return null;
        return new org.finos.legend.pure.truffle.ast.natives.lang.IfNode(
                condition, thenBody, elseBody, /*staticMode=*/ true);
    }

    /**
     * Try to lower a call to {@code match(value, [lambda1, lambda2, ...])}
     * (or the 3-arg variant with an extra parameter) into a
     * {@link org.finos.legend.pure.truffle.ast.natives.lang.SpecializedMatchNode}.
     *
     * <p>Triggers when the branch list is a literal {@code Collection} of
     * literal closure-lambdas (each an {@code AtomicValue<LambdaFunction>})
     * where every lambda has exactly one parameter typed {@code T[1]} for a
     * concretely-resolvable Pure type. At that point each branch's accepted
     * type is known at AST-build, so the runtime dispatch can use a
     * constant-folded {@code @ExplodeLoop} {@code isType} chain instead of
     * the generic {@code @TruffleBoundary matchesBranch} loop.</p>
     *
     * <p>Returns {@code null} when any of these conditions don't hold; the
     * caller falls back to the generic {@code MatchNode}.</p>
     */
    public PureNode tryLowerMatch(Object fe)
    {
        Object paramsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_PARAMETERS_VALUES);
        if (!(paramsObj instanceof PureSequence params) || params.size() < 2)
        {
            return null;
        }
        Object branchListVs = params.getBoxed(1);
        if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(branchListVs,
                "meta::pure::metamodel::valuespecification::Collection"))
        {
            return null;
        }
        Object branchValuesObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(branchListVs, SLOT_VALUES);
        if (!(branchValuesObj instanceof PureSequence branchValues) || branchValues.isEmpty())
        {
            return null;
        }
        int n = branchValues.size();
        Object[] branchTypeElements = new Object[n];
        for (int i = 0; i < n; i++)
        {
            Object branchVs = branchValues.getBoxed(i);
            // Each branch must be an AtomicValue wrapping a LambdaFunction.
            if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(branchVs,
                    "meta::pure::metamodel::valuespecification::AtomicValue"))
            {
                return null;
            }
            Object lambda = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(branchVs, SLOT_VALUE);
            if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(lambda,
                    "meta::pure::metamodel::function::LambdaFunction"))
            {
                return null;
            }
            // Single parameter, multiplicity [1], known concrete type.
            Object lambdaParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(lambda, SLOT_PARAMETERS);
            if (!(lambdaParamsObj instanceof PureSequence lambdaParams) || lambdaParams.size() != 1)
            {
                return null;
            }
            Object param = lambdaParams.getBoxed(0);
            Object paramGT = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(param, SLOT_GENERIC_TYPE);
            if (paramGT == null)
            {
                return null;
            }
            Object paramType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(paramGT);
            if (paramType == null)
            {
                return null;
            }
            // Only accept [1] multiplicity for now — keeps the runtime
            // dispatch a pure type check. Other multiplicities would need
            // a value-count check we don't yet emit.
            Object paramMul = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(param, SLOT_MULTIPLICITY);
            if (!isSingleMultiplicity(paramMul))
            {
                return null;
            }
            branchTypeElements[i] = paramType;
        }
        // Lower the args normally — the value, the branch-list (still
        // evaluated to produce real closures with their captured open
        // vars), and the optional 3rd arg.
        PureNode valueNode = lower(params.getBoxed(0));
        PureNode matchFnsNode = lower(branchListVs);
        PureNode extraNode = params.size() > 2 ? lower(params.getBoxed(2)) : null;
        if (valueNode == null || matchFnsNode == null) return null;
        return new org.finos.legend.pure.truffle.ast.natives.lang.SpecializedMatchNode(
                valueNode, matchFnsNode, extraNode, branchTypeElements);
    }

    /** {@code true} when the multiplicity is concretely {@code [1]} —
     *  lowerBound == upperBound == 1. */
    private static boolean isSingleMultiplicity(Object mul)
    {
        if (mul == null) return false;
        Object lb = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(mul, SLOT_LOWER_BOUND);
        Object ub = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(mul, SLOT_UPPER_BOUND);
        if (lb == null || ub == null) return false;
        Object lbVal = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(lb, SLOT_VALUE);
        Object ubVal = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(ub, SLOT_VALUE);
        return lbVal instanceof Number ln && ubVal instanceof Number un
                && ln.longValue() == 1 && un.longValue() == 1;
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
                ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(vs, SLOT_VALUE) : null;
        // Widened from `instanceof LambdaFunction` so PDO lambdas
        // (post-loader-flip resolver returns) take the same fast path.
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(inner,
                "meta::pure::metamodel::function::LambdaFunction"))
        {
            Object lfParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(inner, SLOT_PARAMETERS);
            if (!(lfParamsObj instanceof PureSequence lfParams) || lfParams.isEmpty())
            {
                Object bodyObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(inner, SLOT_EXPRESSION_SEQUENCE);
                if (bodyObj instanceof PureSequence body && body.size() == 1
                        && !isLetCall(body.getBoxed(0)))
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

    /** {@code true} when the value-spec is a {@code letFunction(name, value)} call.
     *  We refuse to inline such bodies into the caller's frame because the
     *  caller's {@link org.finos.legend.pure.truffle.frame.FrameDescriptorBuilder}
     *  pre-scan only walks top-level expressions of the enclosing function
     *  body — it doesn't see lets nested inside lambdas, so the let target
     *  has no pre-allocated slot in the parent layout. Falling back to
     *  {@link org.finos.legend.pure.truffle.ast.natives.lang.LambdaCallNoArgNode}
     *  keeps the lambda's own frame, where its let-target slot IS allocated. */
    private static boolean isLetCall(Object vs)
    {
        if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(vs,
                "meta::pure::metamodel::valuespecification::FunctionInvocation")
                && !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(vs,
                        "meta::pure::metamodel::valuespecification::DotApplication")
                && !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(vs,
                        "meta::pure::metamodel::valuespecification::ArrowInvocation"))
        {
            return false;
        }
        Object funcName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(vs, SLOT_FUNCTION_NAME);
        if ("letFunction".equals(funcName)) return true;
        Object func = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(vs, SLOT_FUNC);
        return func != null
                && LET_FUNCTION_SIGNATURE.equals(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(func, SLOT_NAME));
    }

    /**
     * Find the correct QP overload from the owning class by matching parameter count.
     */
    /**
     * Resolve a TempCompilerPointer subtype to its live target via the registry.
     *
     * <p>Mirrors the Pure-side {@code dereferencePointer} in {@code _Pointer.pure}:
     * PE-style pointers (PackageableFunctionPointer, ClassPointer, etc.) carry
     * only {@code .path}; member pointers (PropertyPointer, QualifiedPropertyPointer)
     * carry {@code .path} (owner) + {@code .element} (member name).</p>
     */
    private Object dereferencePointer(Object ptr,
            org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        Object pathObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(
                ptr, org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("path"));
        if (!(pathObj instanceof String path) || resolver == null)
        {
            return null;
        }
        Object owner = resolver.getElement(path);
        if (owner == null)
        {
            return null;
        }
        // Member pointers — find the member by element name on the owner.
        Object elementObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(
                ptr, org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("element"));
        if (elementObj instanceof String elementName)
        {
            if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(ptr,
                    "meta::pure::metamodel::pointer::QualifiedPropertyPointer"))
            {
                Object qps = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(owner, SLOT_QUALIFIED_PROPERTIES);
                if (qps instanceof PureSequence qpsSeq)
                {
                    for (int i = 0; i < qpsSeq.size(); i++)
                    {
                        Object qp = qpsSeq.getBoxed(i);
                        Object qpName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(qp, SLOT_NAME);
                        if (elementName.equals(qpName)) return qp;
                    }
                }
                return null;
            }
            // PropertyPointer (default member pointer arm).
            Object props = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(owner,
                    org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("properties"));
            if (props instanceof PureSequence propsSeq)
            {
                for (int i = 0; i < propsSeq.size(); i++)
                {
                    Object p = propsSeq.getBoxed(i);
                    Object pName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(p, SLOT_NAME);
                    if (elementName.equals(pName)) return p;
                }
            }
            return null;
        }
        // PE-style pointer — owner IS the target.
        return owner;
    }

    private Object findQpOverload(Object wrongQp, int expectedParamCount)
    {
        Object owner = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(wrongQp, SLOT_OWNER);
        if (owner == null) return null;
        Object qpsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(owner, SLOT_QUALIFIED_PROPERTIES);
        if (!(qpsObj instanceof PureSequence qps)) return null;
        Object targetNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(wrongQp, SLOT_NAME);
        if (!(targetNameObj instanceof String targetName)) return null;
        for (int i = 0; i < qps.size(); i++)
        {
            Object candidate = qps.getBoxed(i);
            if (candidate == null) continue;
            Object cqpName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(candidate, SLOT_NAME);
            if (!targetName.equals(cqpName)) continue;
            Object cqpParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(candidate, SLOT_PARAMETERS);
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
        Object sigObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(nf, SLOT_NAME);
        String signature = sigObj instanceof String s ? s : null;
        if (LET_FUNCTION_SIGNATURE.equals(signature))
        {
            return lowerFrameLet(fe);
        }
        // Inline-fold fast path — when {@link FrameDescriptorBuilder} pre-
        // scanned this fold's closure-literal lambda and pre-allocated
        // inline slots in the enclosing frame, re-lower the lambda body
        // here and emit an {@code InlineFoldNode}. The lambda body becomes
        // a @Child of the fold, so Truffle's PE compiles them as one
        // unit — no CallTarget, no per-iteration Object[] alloc, the JIT
        // inlines the body into the loop the way Eclipse-Collections
        // lambdas inline in plain Java.
        // Inline fast path for lambda-taking natives: when the lambda arg
        // is a literal closure, compile its body once + reuse it as a
        // @Child running in a Truffle sub-frame (escape-analyzable). No
        // CallTarget per iteration.
        if (currentLayout != null)
        {
            // Inline-fold: 2-param lambda (elem, acc).
            if ("fold_T_MANY__Function_1__V_MANY__V_MANY_".equals(signature)
                    || "fold_T_MANY__Function_1__V_1__V_1_".equals(signature))
            {
                PureNode inlined = lowerInlineFold(fe);
                if (inlined != null) return inlined;
            }
            // 1-param iteration natives (map/filter/exists/forAll/find).
            // Same subframe pattern as fold but with one fewer parameter
            // (just elem). Applied uniformly so user workloads that exercise
            // these heavily benefit even when our compile benchmark doesn't.
            else if ("map_T_MANY__Function_1__V_MANY_".equals(signature)
                    || "map_T_m__Function_1__V_m_".equals(signature))
            {
                PureNode inlined = lowerInlineMap(fe);
                if (inlined != null) return inlined;
            }
            else if ("filter_T_MANY__Function_1__T_MANY_".equals(signature))
            {
                PureNode inlined = lowerInlineFilter(fe);
                if (inlined != null) return inlined;
            }
            else if ("exists_T_MANY__Function_1__Boolean_1_".equals(signature))
            {
                PureNode inlined = lowerInlineExistsOrForAll(fe, /*forAll=*/ false);
                if (inlined != null) return inlined;
            }
            else if ("forAll_T_MANY__Function_1__Boolean_1_".equals(signature))
            {
                PureNode inlined = lowerInlineExistsOrForAll(fe, /*forAll=*/ true);
                if (inlined != null) return inlined;
            }
            else if ("find_T_MANY__Function_1__T_$0_1$_".equals(signature))
            {
                PureNode inlined = lowerInlineFind(fe);
                if (inlined != null) return inlined;
            }
            else if ("groupBy_X_MANY__Function_1__Map_1_".equals(signature))
            {
                PureNode inlined = lowerInlineGroupBy(fe);
                if (inlined != null) return inlined;
            }
        }
        NativeNodeRegistry.Factory factory = specialized.lookup(signature);
        if (factory != null)
        {
            Object gt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_GENERIC_TYPE);
            Object mul = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_MULTIPLICITY);
            return factory.create(lowerArgs(fe), gt, mul, fe);
        }
        // All signatures should be registered. If we reach here, it's a
        // new native added without a corresponding Truffle node.
        throw new RuntimeException("No specialized Truffle node for native: " + signature);
    }

    /**
     * Re-lower the lambda body of an inline-eligible fold with name
     * overrides routing the lambda's param names to the inline slots
     * pre-allocated by {@link org.finos.legend.pure.truffle.frame.FrameDescriptorBuilder}.
     * Returns null if anything unexpected happens — caller falls back to
     * the standard {@code FoldNode}.
     */
    /** Build the subframe meta common to all inline-lambda-native nodes:
     *  resolves the lambda PDO, its compiled body, FrameDescriptor, and
     *  the (caller-slot → lambda-slot) bindings for open variables. */
    private InlineSubframeMeta buildInlineSubframeMeta(Object fe, int lambdaArgIdx)
    {
        Object argsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_PARAMETERS_VALUES);
        if (!(argsObj instanceof PureSequence args) || args.size() <= lambdaArgIdx) return null;
        Object lambdaArg = args.getBoxed(lambdaArgIdx);
        if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(lambdaArg,
                "meta::pure::metamodel::valuespecification::AtomicValue")) return null;
        Object lambda = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(lambdaArg, SLOT_VALUE);
        if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(lambda,
                "meta::pure::metamodel::function::LambdaFunction")) return null;

        org.finos.legend.pure.truffle.frame.CompiledFunction cf =
                org.finos.legend.pure.truffle.PureLanguage.get(null).compileLambdaFunction(lambda);
        if (cf == null) return null;
        PureNode[] body = cf.body();
        if (body == null) return null;
        org.finos.legend.pure.truffle.frame.FrameLayout lambdaLayout = cf.layout();

        Object openVarsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(lambda, SLOT_OPEN_VARIABLES);
        int n = (openVarsObj instanceof PureSequence ov) ? ov.size() : 0;
        int[] callerSlots = new int[n];
        int[] lambdaSlots = new int[n];
        int boundCount = 0;
        if (n > 0)
        {
            PureSequence ov = (PureSequence) openVarsObj;
            for (int i = 0; i < n; i++)
            {
                Object o = ov.getBoxed(i);
                Object nm = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(o, SLOT_NAME);
                if (!(nm instanceof String name)) continue;
                Integer cSlot = currentLayout == null ? null : currentLayout.slotFor(name);
                Integer lSlot = lambdaLayout.slotFor(name);
                if (cSlot == null || lSlot == null) return null;
                callerSlots[boundCount] = cSlot;
                lambdaSlots[boundCount] = lSlot;
                boundCount++;
            }
        }
        if (boundCount < n)
        {
            int[] cTrim = new int[boundCount];
            int[] lTrim = new int[boundCount];
            System.arraycopy(callerSlots, 0, cTrim, 0, boundCount);
            System.arraycopy(lambdaSlots, 0, lTrim, 0, boundCount);
            callerSlots = cTrim;
            lambdaSlots = lTrim;
        }
        return new InlineSubframeMeta(args, body, lambdaLayout, callerSlots, lambdaSlots);
    }

    private record InlineSubframeMeta(PureSequence callArgs, PureNode[] body,
            org.finos.legend.pure.truffle.frame.FrameLayout lambdaLayout,
            int[] callerSlots, int[] lambdaSlots) {}

    private PureNode lowerInlineExists(Object fe, boolean forAll)
    {
        InlineSubframeMeta m = buildInlineSubframeMeta(fe, 1);
        if (m == null) return null;
        int[] paramSlots = m.lambdaLayout.paramSlots();
        if (paramSlots == null || paramSlots.length < 1) return null;
        int elemSlot = paramSlots[0];
        PureNode collectionNode = lower(m.callArgs.getBoxed(0));
        return new org.finos.legend.pure.truffle.ast.natives.collection.InlineExistsNode(
                collectionNode, m.body, m.lambdaLayout.descriptor(), elemSlot, forAll,
                m.callerSlots, m.lambdaSlots);
    }

    /**
     * Subframe-approach inline-fold. The lambda's body is compiled once
     * (in the lambda's own FrameDescriptor) and reused as a {@code @Child}
     * of {@code InlineFoldNode}. At runtime, {@code InlineFoldNode}
     * creates a fresh sub-frame per call using the lambda's descriptor
     * and runs the body in it. Truffle PE virtualizes the sub-frame
     * (it doesn't escape), so slot writes become Java locals in the
     * JIT'd hot loop — same machine code as Eclipse-Collections + Java
     * lambdas after JIT.
     */
    private PureNode lowerInlineFold(Object fe)
    {
        Object argsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_PARAMETERS_VALUES);
        if (!(argsObj instanceof PureSequence args) || args.size() < 3) return null;

        Object lambdaArg = args.getBoxed(1);
        if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(lambdaArg,
                "meta::pure::metamodel::valuespecification::AtomicValue")) return null;
        Object lambda = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(lambdaArg, SLOT_VALUE);
        if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(lambda,
                "meta::pure::metamodel::function::LambdaFunction")) return null;

        // Compile the lambda (idempotent; populates cache) and pull its
        // body + FrameLayout. We reuse the already-lowered body PureNodes
        // and FrameDescriptor — no re-lowering needed.
        org.finos.legend.pure.truffle.frame.CompiledFunction cf =
                org.finos.legend.pure.truffle.PureLanguage.get(null).compileLambdaFunction(lambda);
        if (cf == null) return null;
        PureNode[] body = cf.body();
        if (body == null) return null;
        org.finos.legend.pure.truffle.frame.FrameLayout lambdaLayout = cf.layout();
        int[] paramSlots = lambdaLayout.paramSlots();
        if (paramSlots == null || paramSlots.length < 2) return null;
        int elemSlot = paramSlots[0];
        int accSlot = paramSlots[1];

        // Open-var bindings: caller-frame slot → lambda-frame slot, by name.
        Object openVarsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(lambda, SLOT_OPEN_VARIABLES);
        int n = (openVarsObj instanceof PureSequence ov) ? ov.size() : 0;
        int[] callerSlots = new int[n];
        int[] lambdaSlots = new int[n];
        int boundCount = 0;
        if (n > 0)
        {
            PureSequence ov = (PureSequence) openVarsObj;
            for (int i = 0; i < n; i++)
            {
                Object o = ov.getBoxed(i);
                Object nm = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(o, SLOT_NAME);
                if (!(nm instanceof String name)) continue;
                Integer cSlot = currentLayout == null ? null : currentLayout.slotFor(name);
                Integer lSlot = lambdaLayout.slotFor(name);
                if (cSlot == null || lSlot == null)
                {
                    // Lambda references something not visible in caller's
                    // layout — fall back to FoldNode rather than guess.
                    return null;
                }
                callerSlots[boundCount] = cSlot;
                lambdaSlots[boundCount] = lSlot;
                boundCount++;
            }
        }
        if (boundCount < n)
        {
            int[] cTrim = new int[boundCount];
            int[] lTrim = new int[boundCount];
            System.arraycopy(callerSlots, 0, cTrim, 0, boundCount);
            System.arraycopy(lambdaSlots, 0, lTrim, 0, boundCount);
            callerSlots = cTrim;
            lambdaSlots = lTrim;
        }

        PureNode collectionNode = lower(args.getBoxed(0));
        PureNode seedNode = lower(args.getBoxed(2));
        return new org.finos.legend.pure.truffle.ast.natives.collection.InlineFoldNode(
                collectionNode, seedNode, body,
                lambdaLayout.descriptor(), elemSlot, accSlot,
                callerSlots, lambdaSlots);
    }

    /**
     * Subframe meta for 1-param iteration natives (map/filter/exists/forAll/find).
     * Resolves the lambda's compiled body, the element-param slot, and the
     * open-var → caller-slot binding. Returns {@code null} when the second
     * argument isn't a literal closure-lambda or any open var doesn't resolve
     * in the caller's frame layout.
     */
    private InlineSingleArgMeta resolveInlineSingleArg(Object fe)
    {
        Object argsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_PARAMETERS_VALUES);
        if (!(argsObj instanceof PureSequence args) || args.size() < 2) return null;
        Object lambdaArg = args.getBoxed(1);
        if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(lambdaArg,
                "meta::pure::metamodel::valuespecification::AtomicValue")) return null;
        Object lambda = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(lambdaArg, SLOT_VALUE);
        if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(lambda,
                "meta::pure::metamodel::function::LambdaFunction")) return null;
        org.finos.legend.pure.truffle.frame.CompiledFunction cf =
                org.finos.legend.pure.truffle.PureLanguage.get(null).compileLambdaFunction(lambda);
        if (cf == null) return null;
        PureNode[] body = cf.body();
        if (body == null) return null;
        org.finos.legend.pure.truffle.frame.FrameLayout lambdaLayout = cf.layout();
        int[] paramSlots = lambdaLayout.paramSlots();
        if (paramSlots == null || paramSlots.length < 1) return null;
        int elemSlot = paramSlots[0];

        Object openVarsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(lambda, SLOT_OPEN_VARIABLES);
        int n = (openVarsObj instanceof PureSequence ov) ? ov.size() : 0;
        int[] callerSlots = new int[n];
        int[] lambdaSlots = new int[n];
        int boundCount = 0;
        if (n > 0)
        {
            PureSequence ov = (PureSequence) openVarsObj;
            for (int i = 0; i < n; i++)
            {
                Object o = ov.getBoxed(i);
                Object nm = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(o, SLOT_NAME);
                if (!(nm instanceof String name)) continue;
                Integer cSlot = currentLayout == null ? null : currentLayout.slotFor(name);
                Integer lSlot = lambdaLayout.slotFor(name);
                if (cSlot == null || lSlot == null) return null;
                callerSlots[boundCount] = cSlot;
                lambdaSlots[boundCount] = lSlot;
                boundCount++;
            }
        }
        if (boundCount < n)
        {
            int[] cTrim = new int[boundCount];
            int[] lTrim = new int[boundCount];
            System.arraycopy(callerSlots, 0, cTrim, 0, boundCount);
            System.arraycopy(lambdaSlots, 0, lTrim, 0, boundCount);
            callerSlots = cTrim;
            lambdaSlots = lTrim;
        }
        PureNode collectionNode = lower(args.getBoxed(0));
        if (collectionNode == null) return null;
        return new InlineSingleArgMeta(collectionNode, body, lambdaLayout.descriptor(),
                elemSlot, callerSlots, lambdaSlots);
    }

    private record InlineSingleArgMeta(PureNode collection, PureNode[] body,
                                       com.oracle.truffle.api.frame.FrameDescriptor lambdaDescriptor,
                                       int elemSlot, int[] callerSlots, int[] lambdaSlots) {}

    private PureNode lowerInlineMap(Object fe)
    {
        InlineSingleArgMeta m = resolveInlineSingleArg(fe);
        if (m == null) return null;
        return new org.finos.legend.pure.truffle.ast.natives.collection.InlineMapNode(
                m.collection, m.body, m.lambdaDescriptor, m.elemSlot,
                m.callerSlots, m.lambdaSlots);
    }

    private PureNode lowerInlineFilter(Object fe)
    {
        InlineSingleArgMeta m = resolveInlineSingleArg(fe);
        if (m == null) return null;
        return new org.finos.legend.pure.truffle.ast.natives.collection.InlineFilterNode(
                m.collection, m.body, m.lambdaDescriptor, m.elemSlot,
                m.callerSlots, m.lambdaSlots);
    }

    private PureNode lowerInlineExistsOrForAll(Object fe, boolean forAll)
    {
        InlineSingleArgMeta m = resolveInlineSingleArg(fe);
        if (m == null) return null;
        return new org.finos.legend.pure.truffle.ast.natives.collection.InlineExistsNode(
                m.collection, m.body, m.lambdaDescriptor, m.elemSlot, forAll,
                m.callerSlots, m.lambdaSlots);
    }

    private PureNode lowerInlineFind(Object fe)
    {
        InlineSingleArgMeta m = resolveInlineSingleArg(fe);
        if (m == null) return null;
        return new org.finos.legend.pure.truffle.ast.natives.collection.InlineFindNode(
                m.collection, m.body, m.lambdaDescriptor, m.elemSlot,
                m.callerSlots, m.lambdaSlots);
    }

    private PureNode lowerInlineGroupBy(Object fe)
    {
        InlineSingleArgMeta m = resolveInlineSingleArg(fe);
        if (m == null) return null;
        return new org.finos.legend.pure.truffle.ast.natives.collection.InlineGroupByNode(
                m.collection, m.body, m.lambdaDescriptor, m.elemSlot,
                m.callerSlots, m.lambdaSlots);
    }

    private PureNode lowerVariableRead(Object ve)
    {
        Object nameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(ve, SLOT_NAME);
        String veName = nameObj instanceof String s ? s : null;
        Integer slot = resolveSlot(veName);
        if (slot != null)
        {
            return new FrameVariableReadNode(slot, veName);
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
        Object argsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_PARAMETERS_VALUES);
        if (!(argsObj instanceof PureSequence args) || args.size() < 2)
        {
            throw new RuntimeException("letFunction requires at least (name, value); got "
                    + (argsObj == null ? "null args" : (argsObj instanceof PureSequence ps ? ps.size() : 0) + " args"));
        }
        Object nameArg = args.getBoxed(0);
        Object nameInner = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(nameArg,
                "meta::pure::metamodel::valuespecification::AtomicValue")
                ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(nameArg, SLOT_VALUE) : null;
        if (!(nameInner instanceof String name))
        {
            throw new RuntimeException("letFunction's first argument must be a literal String AtomicValue; got: "
                    + (nameArg == null ? "null" : nameArg.getClass().getName()));
        }
        // resolveSlot consults name-overrides first so inlined-lambda let
        // targets (mangled) get the right slot.
        Integer slot = resolveSlot(name);
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
        Object value = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(av, SLOT_VALUE);
        // Widened from `instanceof LambdaFunction` so PDO lambdas
        // (post-loader-flip resolver returns) take the same path.
        if (value != null && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(value,
                "meta::pure::metamodel::function::LambdaFunction"))
        {
            Object openVarsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(value, SLOT_OPEN_VARIABLES);
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
            Object gt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(av, SLOT_GENERIC_TYPE);
            if (gt == null)
            {
                return null;
            }
            Object type = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(gt);
            if (type != null)
            {
                Object n = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(type, SLOT_NAME);
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
        Object paramSpecsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_PARAMETERS_VALUES);
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
