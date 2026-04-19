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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import org.finos.legend.pure.execution.natives.meta.MetaNatives;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;

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
        return doCast(inputResult, targetResult);
    }

    @TruffleBoundary
    private static Object doCast(Object inputResult, Object targetResult)
    {
        TruffleMetadataAccess resolver = StandaloneEvaluatorHolder.current().resolver();

        // Resolve the target GenericType from the GenericTypeAndMultiplicityHolder
        GenericType targetGT = null;
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type targetType = null;
        if (targetResult instanceof GenericTypeAndMultiplicityHolder gtmh
                && gtmh._genericType() != null
                && org.finos.legend.pure.truffle.runtime.helper._GenericType.typeArguments(gtmh._genericType()) != null
                && org.finos.legend.pure.truffle.runtime.helper._GenericType.typeArguments(gtmh._genericType()).size() > 0)
        {
            Object rawTargetGT = org.finos.legend.pure.truffle.runtime.helper._GenericType.typeArguments(gtmh._genericType()).getBoxed(0);
            if (rawTargetGT instanceof GenericType gt)
            {
                targetGT = gt;
            }
            targetType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(rawTargetGT);
        }

        // Validate type compatibility
        if (inputResult != null
                && !(inputResult instanceof org.finos.legend.pure.truffle.types.PureNull)
                && targetType instanceof PackageableElement targetPe)
        {
            String targetPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(targetPe);
            if (!"meta::pure::metamodel::type::Any".equals(targetPath)
                    && !targetPath.startsWith("meta::pure::metamodel::valuespecification::"))
            {
                org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type sourceType = MetaHelper.getRawValueType(inputResult, resolver);
                if (sourceType instanceof PackageableElement sourcePe)
                {
                    String sourcePath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(sourcePe);
                    if (!"meta::pure::metamodel::type::Nil".equals(sourcePath))
                    {
                        boolean related = false;
                        try
                        {
                            related = org.finos.legend.pure.truffle.runtime.helper._Type.subtypeOf(sourceType, targetType, resolver)
                                    || org.finos.legend.pure.truffle.runtime.helper._Type.subtypeOf(targetType, sourceType, resolver);
                        }
                        catch (Exception ignored)
                        {
                            // Type hierarchy check can fail for raw values — allow cast
                            related = true;
                        }
                        if (!related)
                        {
                            throw new RuntimeException("Cast exception: " + sourcePe._name() + " cannot be cast to " + targetPe._name());
                        }
                    }
                }
            }
        }

        // Constraint validation
        if (targetType != null && inputResult != null
                && !(inputResult instanceof org.finos.legend.pure.truffle.types.PureNull))
        {
            validateConstraints(targetType, targetGT, inputResult, resolver);
        }

        // Cast is a type assertion — it does NOT change the runtime type (CGT)
        // of the object. The object retains its original classifierGenericType
        // so that polymorphic QP dispatch resolves to the correct override.
        return inputResult;
    }

    private static void validateConstraints(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type type,
                                            GenericType targetGT,
                                            Object value,
                                            TruffleMetadataAccess resolver)
    {
        validateConstraintsOnType(type, targetGT, value, resolver);
        // Walk up the type hierarchy
        Object gens = type._generalizations();
        if (gens instanceof org.finos.legend.pure.truffle.types.PureSequence genSeq)
        {
            for (Object gen : genSeq.toBoxedArray())
            {
                if (gen instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.relationship.Generalization g
                        && g._general() != null && org.finos.legend.pure.truffle.runtime.helper._GenericType.type(g._general()) != null)
                {
                    validateConstraints(org.finos.legend.pure.truffle.runtime.helper._GenericType.type(g._general()), g._general(), value, resolver);
                }
            }
        }
    }

    private static void validateConstraintsOnType(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type type,
                                                   GenericType targetGT,
                                                   Object value,
                                                   TruffleMetadataAccess resolver)
    {
        if (!(type instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.extension.ElementWithConstraints ewc))
        {
            return;
        }
        var constraints = ewc._constraints();
        if (constraints == null || constraints.isEmpty())
        {
            return;
        }
        String typeName = (type instanceof PackageableElement pe) ? pe._name() : "Unknown";

        // Resolve type variable bindings (e.g., x=8 for P(8))
        java.util.Map<String, Object> typeVarBindings = new java.util.HashMap<>();
        if (targetGT instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue gtv
                && gtv._typeVariableValues() != null && gtv._typeVariableValues().size() > 0)
        {
            org.finos.legend.pure.truffle.types.PureSequence typeVars = null;
            if (type instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.PrimitiveType pt)
            {
                typeVars = pt._typeVariables();
            }
            else if (type instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class cls)
            {
                typeVars = cls._typeVariables();
            }
            if (typeVars != null)
            {
                org.finos.legend.pure.truffle.types.PureSequence typeVarVals = gtv._typeVariableValues();
                int count = Math.min(typeVars.size(), typeVarVals.size());
                for (int i = 0; i < count; i++)
                {
                    Object rawVal = typeVarVals.getBoxed(i);
                    // PDB metadata values may be AtomicValue FlatBuffer wrappers — unwrap
                    if (rawVal instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue av && av._value() != null)
                    {
                        rawVal = av._value();
                    }
                    Object rawTypeVar = typeVars.getBoxed(i);
                    String tvName = null;
                    if (rawTypeVar instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.TypeParameter tp)
                    {
                        tvName = tp._name();
                    }
                    else if (rawTypeVar instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.VariableExpression ve)
                    {
                        tvName = ve._name();
                    }
                    if (tvName != null)
                    {
                        typeVarBindings.put(tvName, rawVal);
                    }
                }
            }
        }

        var eval = StandaloneEvaluatorHolder.current();
        for (int idx = 0; idx < constraints.size(); idx++)
        {
            if (!(constraints.getBoxed(idx) instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.constraint.Constraint c))
            {
                continue;
            }
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition funcDef = c._functionDefinition();
            if (funcDef == null)
            {
                continue;
            }

            // Build args: [this, typeVarValues...]
            java.util.List<Object> constraintArgs = new java.util.ArrayList<>();
            constraintArgs.add(value);
            org.finos.legend.pure.truffle.types.PureSequence funcParams = funcDef._parameters();
            if (funcParams != null && funcParams.size() > 1)
            {
                for (int p = 1; p < funcParams.size(); p++)
                {
                    Object rawParam = funcParams.getBoxed(p);
                    if (rawParam instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.VariableExpression ve)
                    {
                        Object tvVal = typeVarBindings.get(ve._name());
                        if (tvVal != null)
                        {
                            constraintArgs.add(tvVal);
                        }
                    }
                }
            }

            Object result = eval.executeFunction(funcDef, constraintArgs.toArray());
            if (Boolean.FALSE.equals(result))
            {
                String constraintName = c._name() != null ? c._name() : String.valueOf(idx);
                String message = "Constraint :[" + constraintName + "] violated in the Class " + typeName;

                if (c._messageFunction() != null)
                {
                    try
                    {
                        java.util.List<Object> msgArgs = new java.util.ArrayList<>();
                        msgArgs.add(value);
                        org.finos.legend.pure.truffle.types.PureSequence msgParams = c._messageFunction()._parameters();
                        if (msgParams != null && msgParams.size() > 1)
                        {
                            for (int p = 1; p < msgParams.size(); p++)
                            {
                                Object rawParam = msgParams.getBoxed(p);
                                if (rawParam instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.VariableExpression ve)
                                {
                                    Object tvVal = typeVarBindings.get(ve._name());
                                    if (tvVal != null)
                                    {
                                        msgArgs.add(tvVal);
                                    }
                                }
                            }
                        }
                        Object msgResult = eval.executeFunction(c._messageFunction(), msgArgs.toArray());
                        if (msgResult != null)
                        {
                            message += ", Message: " + msgResult;
                        }
                    }
                    catch (Exception ignored)
                    {
                    }
                }
                throw new RuntimeException(message);
            }
        }
    }
}
