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

package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;

/**
 * {@code cast(Any[m], GenericTypeAndMultiplicityHolder[1]) : T[m]}.
 * Validates type compatibility and constraints. Returns the value directly.
 */
@NodeInfo(shortName = "cast")
public final class CastNode extends PureNode
{

    private static final int SLOT_CONSTRAINTS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("constraints");
    private static final int SLOT_FUNCTION_DEFINITION = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("functionDefinition");
    private static final int SLOT_GENERAL = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("general");
    private static final int SLOT_GENERALIZATIONS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("generalizations");
    private static final int SLOT_GENERIC_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("genericType");
    private static final int SLOT_MESSAGE_FUNCTION = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("messageFunction");
    private static final int SLOT_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");
    private static final int SLOT_PARAMETERS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("parameters");
    private static final int SLOT_TYPE_VARIABLE_VALUES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("typeVariableValues");
    private static final int SLOT_TYPE_VARIABLES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("typeVariables");
    private static final int SLOT_VALUE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("value");
    @Child
    private PureNode inputChild;

    @Child
    private PureNode targetChild;

    @Child
    private RawLambdaCallNode constraintCallNode = new RawLambdaCallNode();

    public CastNode(PureNode inputChild, PureNode targetChild)
    {
        this.inputChild = inputChild;
        this.targetChild = targetChild;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object inputResult = inputChild.executeGeneric(frame);
        Object targetResult = targetChild.executeGeneric(frame);
        return doCast(inputResult, targetResult, getResolver(), constraintCallNode);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object doCast(Object inputResult, Object targetResult, TruffleMetadataAccess resolver, RawLambdaCallNode constraintCallNode)
    {

        // Resolve the target GenericType from the GenericTypeAndMultiplicityHolder.
        // Hoist typeArguments() — was called 3 times on the same GT before
        // (~22 JFR samples on the metamodel_factories.pure compile combined
        // across the three sites).
        Object targetGT = null;
        Object targetType = null;
        Object targetMul = null;
        Object hoistedGT = targetResult != null
                ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(targetResult, SLOT_GENERIC_TYPE) : null;
        if (hoistedGT != null)
        {
            org.finos.legend.pure.truffle.types.PureSequence hoistedTypeArgs =
                    org.finos.legend.pure.truffle.runtime.helper._GenericType.typeArguments(hoistedGT);
            if (hoistedTypeArgs != null && hoistedTypeArgs.size() > 0)
            {
                Object rawTargetGT = hoistedTypeArgs.getBoxed(0);
                // Hold onto rawTargetGT regardless of typed-XPDBHelper vs PDO so
                // downstream `read(targetGT, "typeVariableValues")` finds the
                // type-variable bindings (e.g. {x: 8} for `cast(@P(8))`).
                targetGT = rawTargetGT;
                targetType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(rawTargetGT);
            }
            // multiplicityArguments[0] = m for cast(@T|m). Lives on the
            // outer GT (the holder's classifierGenericType), not on the
            // T-side GT. May be null when the holder didn't capture an m.
            Object mulArgsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(hoistedGT,
                    org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("multiplicityArguments"));
            if (mulArgsObj instanceof org.finos.legend.pure.truffle.types.PureSequence mulArgs && mulArgs.size() > 0)
            {
                targetMul = mulArgs.getBoxed(0);
            }
        }

        // Variant-driven validation, mirroring the three cast<T|m> native
        // signatures:
        //   cast<T|m>(source:Any[m], holder<T|?>):T[m]    — check T,  skip m (m from source)
        //   cast<T|m>(source:T[*],   holder<?|m>):T[m]    — skip T,   check m
        //   cast<T|m>(source:Any[*], holder<T|m>):T[m]    — check both
        // The `?` markers are `UndefinedType` / `UndefinedMultiplicity` in
        // the metamodel — explicit "this side is unconstrained" signals.
        // Skipping them here lets the right validation fire per variant.
        boolean targetTypeUndefined = targetGT != null
                && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(targetGT,
                        "meta::pure::metamodel::type::generics::UndefinedGenericType");
        boolean targetMulUndefined = targetMul != null
                && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(targetMul,
                        "meta::pure::metamodel::multiplicity::UndefinedMultiplicity");

        // If the target type is a TypeParameter (e.g. `cast(@T)` inside `f<T>(...)`),
        // resolve T from the enclosing function's recorded `<T>`-bindings
        // (see PureFunctionRootNode.buildFuncTypeVarBindings). Replace
        // targetType with the resolved Type so the normal subtype check runs
        // against the concrete type. If the lookup misses (no binding at all,
        // or a binding that resolved to null — empty sequence arg, no PDO
        // arg, etc.) throw rather than silently skipping: a `cast(@T)` whose
        // T can't be resolved at runtime is a real runtime error.
        // Use isType (subtype-aware), not pureTypeIs (exact-class) — runtime
        // class may be ResolvedTypeParameter which extends TypeParameter;
        // exact-class match would miss it. Matches the symmetric m-branch
        // below and computeFunctionTypeVarPlan's walker.
        org.finos.legend.pure.truffle.PureContext ctxForBindings = null;
        if (!targetTypeUndefined
                && targetType != null
                && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(targetType,
                        "meta::pure::metamodel::type::generics::TypeParameter", resolver))
        {
            Object tvNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(targetType, SLOT_NAME);
            String tvName = tvNameObj instanceof String s ? s : null;
            ctxForBindings = org.finos.legend.pure.truffle.PureLanguage.get(null);
            Object bound = tvName != null ? ctxForBindings.lookupFunctionTypeVarBinding(tvName) : null;
            if (bound == null)
            {
                throw new RuntimeException("Cast exception: type parameter '" + tvName
                        + "' could not be resolved at runtime. The enclosing generic function did"
                        + " not bind it from any scalar T[1] argument or non-empty T[*] sequence.");
            }
            targetType = bound;
        }

        // Symmetric resolution for `<T|m>`: if target multiplicity is a
        // MultiplicityParameter, resolve m from the recorded `<T|m>`
        // bindings (see PureFunctionRootNode.buildFuncMulVarBindings).
        // Validate the input value's count against m's bounds. Throw on
        // unresolved m — same rule as T.
        // NOTE: use isType (subtype-aware) not pureTypeIs (exact-class). The
        // actual runtime class is usually UserDefinedMultiplicityParameter or
        // ResolvedMultiplicityParameter — both extend MultiplicityParameter.
        if (!targetMulUndefined
                && targetMul != null
                && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(targetMul,
                        "meta::pure::metamodel::multiplicity::MultiplicityParameter", resolver))
        {
            Object mvNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(targetMul, SLOT_NAME);
            String mvName = mvNameObj instanceof String s ? s : null;
            if (ctxForBindings == null) ctxForBindings = org.finos.legend.pure.truffle.PureLanguage.get(null);
            Object bound = mvName != null ? ctxForBindings.lookupFunctionMulVarBinding(mvName) : null;
            if (bound == null)
            {
                throw new RuntimeException("Cast exception: multiplicity parameter '" + mvName
                        + "' could not be resolved at runtime. The enclosing generic function did"
                        + " not bind it from any argument's actual multiplicity.");
            }
            targetMul = bound;
        }
        if (!targetMulUndefined
                && targetMul != null
                && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(targetMul,
                        "meta::pure::metamodel::multiplicity::ConcreteMultiplicity", resolver))
        {
            int count = inputCount(inputResult);
            long[] bounds = multiplicityBounds(targetMul);
            long lower = bounds[0];
            long upper = bounds[1];
            if (count < lower || (upper != -1 && count > upper))
            {
                String mulStr = upper == -1 ? "[" + lower + "..*]" : "[" + lower + ".." + upper + "]";
                throw new RuntimeException("Cast exception: multiplicity violation — value count "
                        + count + " does not fit " + mulStr);
            }
        }

        // Validate type compatibility for scalar values.
        // Skip when:
        //   - target is UndefinedType (`<?|m>` variant — T inferred from source)
        //   - input is a collection (per-element check is the caller's job)
        //   - input is itself a TypeParameter/MultiplicityParameter (cast on
        //     a type symbol passes through)
        // targetType is guaranteed not to be a TypeParameter here — the
        // resolution block above either bound it or threw.
        if (!targetTypeUndefined
                && inputResult != null
                && !(inputResult instanceof org.finos.legend.pure.truffle.types.PureSequence)
                && !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(inputResult,
                        "meta::pure::metamodel::type::generics::TypeParameter")
                && !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(inputResult,
                        "meta::pure::metamodel::multiplicity::MultiplicityParameter")
                && targetType != null)
        {
            String targetPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(targetType, resolver);
            if (targetPath != null
                    && !"meta::pure::metamodel::type::Any".equals(targetPath)
                    && !targetPath.startsWith("meta::pure::metamodel::valuespecification::"))
            {
                Object sourceType = MetaHelper.getRawValueType(inputResult, resolver);
                if (sourceType != null)
                {
                    String sourcePath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(sourceType, resolver);
                    if (sourcePath != null && !"meta::pure::metamodel::type::Nil".equals(sourcePath))
                    {
                        boolean related = false;
                        try
                        {
                            related = org.finos.legend.pure.truffle.runtime.helper._Type.subtypeOf(sourceType, targetType, resolver)
                                    || org.finos.legend.pure.truffle.runtime.helper._Type.subtypeOf(targetType, sourceType, resolver);
                        }
                        catch (Exception e)
                        {
                            throw new RuntimeException("Type hierarchy check failed during cast", e);
                        }
                        if (!related)
                        {
                            Object srcName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(sourceType, SLOT_NAME);
                            Object tgtName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(targetType, SLOT_NAME);
                            throw new RuntimeException("Cast exception: " + srcName + " cannot be cast to " + tgtName);
                        }
                    }
                }
            }
        }

        // Constraint validation
        if (targetType != null && inputResult != null
                && !(inputResult instanceof org.finos.legend.pure.truffle.types.PureSequence ps2 && ps2.isEmpty()))
        {
            validateConstraints(targetType, targetGT, inputResult, resolver, constraintCallNode);
        }

        // Cast is a type assertion — it does NOT change the runtime type (CGT)
        // of the object. The object retains its original classifierGenericType
        // so that polymorphic QP dispatch resolves to the correct override.
        return inputResult;
    }

    /**
     * Identity-keyed cache: does {@code type}'s generalization hierarchy
     * declare any {@code ElementWithConstraints._constraints()} that are
     * non-empty? Most types in compiler-pure don't — the recursive walk
     * was burning CPU re-deriving "no" on every {@code ^Foo(...)}
     * construction. JFR identified {@code validateConstraints} at ~8% of
     * self-compile self-time before this cache.
     *
     * <p>Constraints (and generalizations) are immutable PDB metadata, so
     * the answer is a pure function of the type's structure.</p>
     */
    // Volatile copy-on-write IdentityHashMap. See _PackageableElement.PATH_CACHE
    // for the rationale: synchronizedMap.get goes through a monitor; a
    // volatile snapshot lets reads (the hot path) skip the monitor.
    private static volatile java.util.IdentityHashMap<Object, Boolean> NEEDS_VALIDATION_CACHE =
            new java.util.IdentityHashMap<>();
    private static final Object NEEDS_VALIDATION_CACHE_LOCK = new Object();

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean needsConstraintValidation(Object type)
    {
        Boolean cached = NEEDS_VALIDATION_CACHE.get(type);
        if (cached != null)
        {
            return cached;
        }
        boolean result = computeNeedsValidation(type,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        synchronized (NEEDS_VALIDATION_CACHE_LOCK)
        {
            if (!NEEDS_VALIDATION_CACHE.containsKey(type))
            {
                java.util.IdentityHashMap<Object, Boolean> next =
                        new java.util.IdentityHashMap<>(NEEDS_VALIDATION_CACHE);
                next.put(type, result);
                NEEDS_VALIDATION_CACHE = next;
            }
        }
        return result;
    }

    private static boolean computeNeedsValidation(
            Object type,
            java.util.Set<Object> visited)
    {
        if (type == null || !visited.add(type))
        {
            return false;
        }
        Object constraintsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(type, SLOT_CONSTRAINTS);
        if (constraintsObj instanceof org.finos.legend.pure.truffle.types.PureSequence constraints && !constraints.isEmpty())
        {
            return true;
        }
        Object gens = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(type, SLOT_GENERALIZATIONS);
        if (gens instanceof org.finos.legend.pure.truffle.types.PureSequence genSeq)
        {
            for (Object gen : genSeq.toBoxedArray())
            {
                Object general = gen != null
                        ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(gen, SLOT_GENERAL) : null;
                if (general != null)
                {
                    var superType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(general);
                    if (computeNeedsValidation(superType, visited))
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static void validateConstraints(Object type,
                                            Object targetGT,
                                            Object value,
                                            TruffleMetadataAccess resolver,
                                            RawLambdaCallNode constraintCallNode)
    {
        // Pointer-typed receiver carries no user constraints — `constraints`
        // is declared on {@code ElementWithConstraints}, which only {@code
        // Class<T>} and {@code PrimitiveType} extend. Enum / Enumeration /
        // their pointers can't have constraints by the metamodel. More
        // generally, a pointer is a stand-in: the live target will be
        // validated when something actually casts to it via the canonical
        // element. Skip — the alternative is walking `.generalizations` on
        // the pointer, which trips the strict guard.
        if (org.finos.legend.pure.truffle.runtime.helper._PackageableElement.pointerPath(type) != null)
        {
            return;
        }
        // Cache hit avoids the recursive generalization walk entirely for
        // the common "no constraints in hierarchy" case (~95% of types).
        if (!needsConstraintValidation(type))
        {
            return;
        }
        validateConstraintsOnType(type, targetGT, value, resolver, constraintCallNode);
        // Walk up the type hierarchy
        Object gens = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(type, SLOT_GENERALIZATIONS);
        if (gens instanceof org.finos.legend.pure.truffle.types.PureSequence genSeq)
        {
            for (Object gen : genSeq.toBoxedArray())
            {
                Object general = gen != null
                        ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(gen, SLOT_GENERAL) : null;
                Object genType =
                        general != null ? org.finos.legend.pure.truffle.runtime.helper._GenericType.type(general) : null;
                if (genType != null && general != null)
                {
                    // `general` is the (possibly PDO) GenericType for the
                    // superclass — pass it through as Object so the recursive
                    // constraint walk picks up its type-variable bindings.
                    validateConstraints(genType, general, value, resolver, constraintCallNode);
                }
            }
        }
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void validateConstraintsOnType(Object type,
                                                   Object targetGT,
                                                   Object value,
                                                   TruffleMetadataAccess resolver,
                                                   RawLambdaCallNode constraintCallNode)
    {
        Object constraintsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(type, SLOT_CONSTRAINTS);
        if (!(constraintsObj instanceof org.finos.legend.pure.truffle.types.PureSequence constraints) || constraints.isEmpty())
        {
            return;
        }
        Object typeNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(type, SLOT_NAME);
        String typeName = typeNameObj instanceof String s ? s : "Unknown";

        // Resolve type variable bindings (e.g., x=8 for P(8))
        java.util.Map<String, Object> typeVarBindings = new java.util.HashMap<>();
        Object typeVarValsObj = targetGT != null
                ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(targetGT, SLOT_TYPE_VARIABLE_VALUES) : null;
        if (typeVarValsObj instanceof org.finos.legend.pure.truffle.types.PureSequence typeVarVals
                && typeVarVals.size() > 0)
        {
            Object typeVarsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(type, SLOT_TYPE_VARIABLES);
            if (typeVarsObj instanceof org.finos.legend.pure.truffle.types.PureSequence typeVars)
            {
                int count = Math.min(typeVars.size(), typeVarVals.size());
                for (int i = 0; i < count; i++)
                {
                    Object rawVal = typeVarVals.getBoxed(i);
                    // PDB metadata values may be AtomicValue FlatBuffer wrappers — unwrap
                    if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(rawVal,
                            "meta::pure::metamodel::valuespecification::AtomicValue"))
                    {
                        Object inner = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(rawVal, SLOT_VALUE);
                        if (inner != null) rawVal = inner;
                    }
                    Object rawTypeVar = typeVars.getBoxed(i);
                    Object tvNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(rawTypeVar, SLOT_NAME);
                    if (tvNameObj instanceof String tvName)
                    {
                        typeVarBindings.put(tvName, rawVal);
                    }
                }
            }
        }

        for (int idx = 0; idx < constraints.size(); idx++)
        {
            Object c = constraints.getBoxed(idx);
            if (c == null)
            {
                continue;
            }
            Object funcDef = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(c, SLOT_FUNCTION_DEFINITION);
            if (funcDef == null)
            {
                continue;
            }

            // Build args: [this, typeVarValues...]
            java.util.List<Object> constraintArgs = new java.util.ArrayList<>();
            constraintArgs.add(value);
            Object funcParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(funcDef, SLOT_PARAMETERS);
            if (funcParamsObj instanceof org.finos.legend.pure.truffle.types.PureSequence funcParams && funcParams.size() > 1)
            {
                for (int p = 1; p < funcParams.size(); p++)
                {
                    Object rawParam = funcParams.getBoxed(p);
                    Object tvNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(rawParam, SLOT_NAME);
                    if (tvNameObj instanceof String tvName)
                    {
                        Object tvVal = typeVarBindings.get(tvName);
                        if (tvVal != null)
                        {
                            constraintArgs.add(tvVal);
                        }
                    }
                }
            }

            Object result = constraintCallNode.callWithArgs(funcDef, constraintArgs.toArray());
            if (Boolean.FALSE.equals(result))
            {
                Object cNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(c, SLOT_NAME);
                String constraintName = cNameObj instanceof String cn ? cn : String.valueOf(idx);
                String message = "Constraint :[" + constraintName + "] violated in the Class " + typeName;

                Object messageFn = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(c, SLOT_MESSAGE_FUNCTION);
                if (messageFn != null)
                {
                    try
                    {
                        java.util.List<Object> msgArgs = new java.util.ArrayList<>();
                        msgArgs.add(value);
                        Object msgParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(messageFn, SLOT_PARAMETERS);
                        if (msgParamsObj instanceof org.finos.legend.pure.truffle.types.PureSequence msgParams && msgParams.size() > 1)
                        {
                            for (int p = 1; p < msgParams.size(); p++)
                            {
                                Object rawParam = msgParams.getBoxed(p);
                                Object tvNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(rawParam, SLOT_NAME);
                                if (tvNameObj instanceof String tvName)
                                {
                                    Object tvVal = typeVarBindings.get(tvName);
                                    if (tvVal != null)
                                    {
                                        msgArgs.add(tvVal);
                                    }
                                }
                            }
                        }
                        Object msgResult = constraintCallNode.callWithArgs(messageFn, msgArgs.toArray());
                        if (msgResult != null)
                        {
                            message += ", Message: " + msgResult;
                        }
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException("Failed to execute constraint message function", e);
                    }
                }
                throw new RuntimeException(message);
            }
        }
    }

    private static int inputCount(Object value)
    {
        if (value == null) return 0;
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence seq) return seq.size();
        return 1;
    }

    /**
     * Extract [lower, upper] from a ConcreteMultiplicity. Upper == -1
     * encodes "unbounded" (matches the LangNatives.multiplicityAccepts
     * convention).
     */
    private static long[] multiplicityBounds(Object concreteMul)
    {
        Object lowerBound = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(concreteMul,
                org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("lowerBound"));
        Object upperBound = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(concreteMul,
                org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("upperBound"));
        long lower = readValue(lowerBound, 0L);
        long upper = upperBound != null ? readValue(upperBound, -1L) : -1L;
        return new long[]{lower, upper};
    }

    private static long readValue(Object boundObj, long def)
    {
        if (boundObj == null) return def;
        Object v = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(boundObj,
                org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("value"));
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        return def;
    }

}
