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
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import org.finos.legend.pure.execution.natives.meta.MetaNatives;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type;
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

    private static Object doCast(Object inputResult, Object targetResult)
    {
        MetadataAccess resolver = StandaloneEvaluatorHolder.current().resolver();

        // Resolve the target GenericType from the GenericTypeAndMultiplicityHolder
        GenericType targetGT = null;
        meta.pure.metamodel.type.Type targetType = null;
        if (targetResult instanceof GenericTypeAndMultiplicityHolder gtmh
                && gtmh._genericType() != null
                && _GenericType.typeArguments(gtmh._genericType()) != null
                && _GenericType.typeArguments(gtmh._genericType()).notEmpty())
        {
            targetGT = _GenericType.typeArguments(gtmh._genericType()).getFirst();
            targetType = _GenericType.type(targetGT);
        }

        // Validate type compatibility
        if (inputResult != null
                && !(inputResult instanceof org.finos.legend.pure.truffle.types.PureSequence _ps1 && _ps1.isEmpty())
                && targetType instanceof PackageableElement targetPe)
        {
            String targetPath = _PackageableElement.path(targetPe);
            if (!"meta::pure::metamodel::type::Any".equals(targetPath)
                    && !targetPath.startsWith("meta::pure::metamodel::valuespecification::"))
            {
                meta.pure.metamodel.type.Type sourceType = MetaHelper.getRawValueType(inputResult, resolver);
                if (sourceType instanceof PackageableElement sourcePe)
                {
                    String sourcePath = _PackageableElement.path(sourcePe);
                    if (!"meta::pure::metamodel::type::Nil".equals(sourcePath))
                    {
                        boolean related = false;
                        try
                        {
                            related = _Type.subtypeOf(sourceType, targetType, resolver)
                                    || _Type.subtypeOf(targetType, sourceType, resolver);
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
                && !(inputResult instanceof org.finos.legend.pure.truffle.types.PureSequence _ps2 && _ps2.isEmpty()))
        {
            validateConstraints(targetType, targetGT, inputResult, resolver);
        }

        // Cast is a type assertion — it does NOT change the runtime type (CGT)
        // of the object. The object retains its original classifierGenericType
        // so that polymorphic QP dispatch resolves to the correct override.
        return inputResult;
    }

    private static void validateConstraints(meta.pure.metamodel.type.Type type,
                                            GenericType targetGT,
                                            Object value,
                                            MetadataAccess resolver)
    {
        validateConstraintsOnType(type, targetGT, value, resolver);
        // Walk up the type hierarchy
        if (type._generalizations() != null)
        {
            for (var gen : type._generalizations())
            {
                if (gen._general() != null && _GenericType.type(gen._general()) != null)
                {
                    validateConstraints(_GenericType.type(gen._general()), gen._general(), value, resolver);
                }
            }
        }
    }

    private static void validateConstraintsOnType(meta.pure.metamodel.type.Type type,
                                                   GenericType targetGT,
                                                   Object value,
                                                   MetadataAccess resolver)
    {
        if (!(type instanceof meta.pure.metamodel.extension.ElementWithConstraints ewc))
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
        if (targetGT instanceof meta.pure.metamodel.type.generics.GenericTypeValue gtv
                && gtv._typeVariableValues() != null && gtv._typeVariableValues().notEmpty())
        {
            org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.valuespecification.VariableExpression> typeVars = null;
            if (type instanceof meta.pure.metamodel.type.PrimitiveType pt)
            {
                typeVars = pt._typeVariables();
            }
            else if (type instanceof meta.pure.metamodel.type.Class cls)
            {
                typeVars = cls._typeVariables();
            }
            if (typeVars != null)
            {
                var typeVarVals = gtv._typeVariableValues();
                int count = Math.min(typeVars.size(), typeVarVals.size());
                for (int i = 0; i < count; i++)
                {
                    Object rawVal = typeVarVals.get(i);
                    if (rawVal instanceof meta.pure.metamodel.valuespecification.AtomicValue av)
                    {
                        rawVal = av._value();
                    }
                    typeVarBindings.put(typeVars.get(i)._name(), rawVal);
                }
            }
        }

        var eval = StandaloneEvaluatorHolder.current();
        for (int idx = 0; idx < constraints.size(); idx++)
        {
            meta.pure.metamodel.constraint.Constraint c = constraints.get(idx);
            meta.pure.metamodel.function.FunctionDefinition funcDef = c._functionDefinition();
            if (funcDef == null)
            {
                continue;
            }

            // Build args: [this, typeVarValues...]
            java.util.List<Object> constraintArgs = new java.util.ArrayList<>();
            constraintArgs.add(value);
            if (funcDef._parameters() != null && funcDef._parameters().size() > 1)
            {
                for (int p = 1; p < funcDef._parameters().size(); p++)
                {
                    String paramName = funcDef._parameters().get(p)._name();
                    Object tvVal = typeVarBindings.get(paramName);
                    if (tvVal != null)
                    {
                        constraintArgs.add(tvVal);
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
                        if (c._messageFunction()._parameters() != null && c._messageFunction()._parameters().size() > 1)
                        {
                            for (int p = 1; p < c._messageFunction()._parameters().size(); p++)
                            {
                                String paramName = c._messageFunction()._parameters().get(p)._name();
                                Object tvVal = typeVarBindings.get(paramName);
                                if (tvVal != null)
                                {
                                    msgArgs.add(tvVal);
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
