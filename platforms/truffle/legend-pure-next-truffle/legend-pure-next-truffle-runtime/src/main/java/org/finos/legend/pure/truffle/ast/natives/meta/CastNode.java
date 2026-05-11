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
        Object hoistedGT = targetResult != null
                ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(targetResult, "genericType") : null;
        if (hoistedGT != null)
        {
            org.finos.legend.pure.truffle.types.PureSequence hoistedTypeArgs =
                    org.finos.legend.pure.truffle.runtime.helper._GenericType.typeArguments(hoistedGT);
            if (hoistedTypeArgs != null && hoistedTypeArgs.size() > 0)
            {
                Object rawTargetGT = hoistedTypeArgs.getBoxed(0);
                // Hold onto rawTargetGT regardless of typed-XImpl vs PDO so
                // downstream `read(targetGT, "typeVariableValues")` finds the
                // type-variable bindings (e.g. {x: 8} for `cast(@P(8))`).
                targetGT = rawTargetGT;
                targetType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(rawTargetGT);
            }
        }

        // Validate type compatibility for scalar values.
        // Skip collections (common element type is lossy), TypeParameters and MultiplicityParameters.
        // Use pureTypeIs because post loader-flip these may be PDOs, not typed XImpls.
        if (inputResult != null
                && !(inputResult instanceof org.finos.legend.pure.truffle.types.PureSequence)
                && !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(inputResult,
                        "meta::pure::metamodel::type::generics::TypeParameter")
                && !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(inputResult,
                        "meta::pure::metamodel::multiplicity::MultiplicityParameter")
                && targetType != null)
        {
            String targetPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(targetType);
            if (targetPath != null
                    && !"meta::pure::metamodel::type::Any".equals(targetPath)
                    && !targetPath.startsWith("meta::pure::metamodel::valuespecification::"))
            {
                Object sourceType = MetaHelper.getRawValueType(inputResult, resolver);
                if (sourceType != null)
                {
                    String sourcePath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(sourceType);
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
                            Object srcName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(sourceType, "name");
                            Object tgtName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(targetType, "name");
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
        Object constraintsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(type, "constraints");
        if (constraintsObj instanceof org.finos.legend.pure.truffle.types.PureSequence constraints && !constraints.isEmpty())
        {
            return true;
        }
        Object gens = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(type, "generalizations");
        if (gens instanceof org.finos.legend.pure.truffle.types.PureSequence genSeq)
        {
            for (Object gen : genSeq.toBoxedArray())
            {
                Object general = gen != null
                        ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(gen, "general") : null;
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
        // Cache hit avoids the recursive generalization walk entirely for
        // the common "no constraints in hierarchy" case (~95% of types).
        if (!needsConstraintValidation(type))
        {
            return;
        }
        validateConstraintsOnType(type, targetGT, value, resolver, constraintCallNode);
        // Walk up the type hierarchy
        Object gens = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(type, "generalizations");
        if (gens instanceof org.finos.legend.pure.truffle.types.PureSequence genSeq)
        {
            for (Object gen : genSeq.toBoxedArray())
            {
                Object general = gen != null
                        ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(gen, "general") : null;
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
        Object constraintsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(type, "constraints");
        if (!(constraintsObj instanceof org.finos.legend.pure.truffle.types.PureSequence constraints) || constraints.isEmpty())
        {
            return;
        }
        Object typeNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(type, "name");
        String typeName = typeNameObj instanceof String s ? s : "Unknown";

        // Resolve type variable bindings (e.g., x=8 for P(8))
        java.util.Map<String, Object> typeVarBindings = new java.util.HashMap<>();
        Object typeVarValsObj = targetGT != null
                ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(targetGT, "typeVariableValues") : null;
        if (typeVarValsObj instanceof org.finos.legend.pure.truffle.types.PureSequence typeVarVals
                && typeVarVals.size() > 0)
        {
            Object typeVarsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(type, "typeVariables");
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
                        Object inner = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(rawVal, "value");
                        if (inner != null) rawVal = inner;
                    }
                    Object rawTypeVar = typeVars.getBoxed(i);
                    Object tvNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(rawTypeVar, "name");
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
            Object funcDef = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(c, "functionDefinition");
            if (funcDef == null)
            {
                continue;
            }

            // Build args: [this, typeVarValues...]
            java.util.List<Object> constraintArgs = new java.util.ArrayList<>();
            constraintArgs.add(value);
            Object funcParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(funcDef, "parameters");
            if (funcParamsObj instanceof org.finos.legend.pure.truffle.types.PureSequence funcParams && funcParams.size() > 1)
            {
                for (int p = 1; p < funcParams.size(); p++)
                {
                    Object rawParam = funcParams.getBoxed(p);
                    Object tvNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(rawParam, "name");
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
                Object cNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(c, "name");
                String constraintName = cNameObj instanceof String cn ? cn : String.valueOf(idx);
                String message = "Constraint :[" + constraintName + "] violated in the Class " + typeName;

                Object messageFn = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(c, "messageFunction");
                if (messageFn != null)
                {
                    try
                    {
                        java.util.List<Object> msgArgs = new java.util.ArrayList<>();
                        msgArgs.add(value);
                        Object msgParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(messageFn, "parameters");
                        if (msgParamsObj instanceof org.finos.legend.pure.truffle.types.PureSequence msgParams && msgParams.size() > 1)
                        {
                            for (int p = 1; p < msgParams.size(); p++)
                            {
                                Object rawParam = msgParams.getBoxed(p);
                                Object tvNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(rawParam, "name");
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
}
