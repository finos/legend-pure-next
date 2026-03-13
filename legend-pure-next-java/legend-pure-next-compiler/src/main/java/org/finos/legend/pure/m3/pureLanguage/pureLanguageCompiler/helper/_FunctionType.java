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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.Inferred;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.FunctionTypeImpl;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Utility methods for {@link FunctionType}.
 */
public final class _FunctionType
{
    private _FunctionType()
    {
    }

    /**
     * Check whether {@code actual} FunctionType is compatible with {@code declared}.
     * Parameters are contravariant (input), return type is covariant (output).
     */
    public static boolean isCompatible(FunctionType declared, FunctionType actual, MetadataAccess model)
    {
        return isCompatible(declared, actual, false, model);
    }

    /**
     * Check whether {@code actual} FunctionType is compatible with {@code declared}.
     * Parameters are contravariant (input), return type is covariant (output).
     *
     * @param contravariant if true, the check is in a contravariant position (directions are flipped)
     */
    public static boolean isCompatible(FunctionType declared, FunctionType actual, boolean contravariant, MetadataAccess model)
    {
        int declaredParamCount = declared._parameters() != null ? declared._parameters().size() : 0;
        int actualParamCount = actual._parameters() != null ? actual._parameters().size() : 0;
        if (declaredParamCount != actualParamCount)
        {
            return false;
        }

        // Parameters are contravariant: flip direction
        if (declared._parameters() != null && actual._parameters() != null)
        {
            for (int i = 0; i < declaredParamCount; i++)
            {
                VariableExpression declaredParam = declared._parameters().get(i);
                VariableExpression actualParam = actual._parameters().get(i);
                if (declaredParam == null || actualParam == null)
                {
                    continue;
                }
                GenericType declaredParamGT = declaredParam._genericType();
                GenericType actualParamGT = actualParam._genericType();
                // Parameter type and multiplicity: contravariant
                if (!_GenericType.isCompatible(declaredParamGT, actualParamGT, !contravariant, model)
                        || !_Multiplicity.isCompatible(declaredParam._multiplicity(), actualParam._multiplicity(), !contravariant))
                {
                    return false;
                }
            }
        }

        // Return type and multiplicity: covariant
        return _GenericType.isCompatible(declared._returnType(), actual._returnType(), contravariant, model)
                && _Multiplicity.isCompatible(declared._returnMultiplicity(), actual._returnMultiplicity(), contravariant);
    }

    /**
     * Find the most common FunctionType for a collection of FunctionTypes.
     * Respects variance:
     * <ul>
     *   <li>Parameter types are <b>contravariant</b> (greatest lower bound)</li>
     *   <li>Return type is <b>covariant</b> (least common ancestor)</li>
     * </ul>
     *
     * @param functionTypes the function types to unify
     * @return the common FunctionType, or {@code null} if they cannot be unified
     */
    public static FunctionType findCommonFunctionType(MutableList<FunctionType> functionTypes, MetadataAccess model)
    {
        if (functionTypes == null || functionTypes.isEmpty())
        {
            return null;
        }
        if (functionTypes.size() == 1)
        {
            return functionTypes.getFirst();
        }

        // All must have the same parameter count
        int paramCount = functionTypes.getFirst()._parameters() != null
                ? functionTypes.getFirst()._parameters().size() : 0;
        boolean allSameParamCount = functionTypes.allSatisfy(ft ->
                (ft._parameters() != null ? ft._parameters().size() : 0) == paramCount);
        if (!allSameParamCount)
        {
            return null;
        }

        FunctionTypeImpl result = new FunctionTypeImpl();

        // Unify parameter types (contravariant: GLB)
        if (paramCount > 0)
        {
            MutableList<VariableExpression> commonParams = Lists.mutable.empty();
            for (int i = 0; i < paramCount; i++)
            {
                final int idx = i;
                MutableList<GenericType> paramTypesAtPosition = functionTypes
                        .select(ft -> ft._parameters().get(idx) != null && ft._parameters().get(idx)._genericType() != null)
                        .collect(ft -> ft._parameters().get(idx)._genericType());

                // Use contravariant (GLB) for parameter types
                GenericType commonParamType = paramTypesAtPosition.notEmpty()
                        ? _GenericType.findCommonGenericType(paramTypesAtPosition, true, model)
                        : null;

                // Use covariant for multiplicity (widest = LCA)
                MutableList<Multiplicity> paramMulsAtPosition = functionTypes
                        .select(ft -> ft._parameters().get(idx) != null && ft._parameters().get(idx)._multiplicity() != null)
                        .collect(ft -> ft._parameters().get(idx)._multiplicity());
                Multiplicity commonParamMul = paramMulsAtPosition.notEmpty()
                        ? _Multiplicity.findCommonMultiplicity(paramMulsAtPosition)
                        : null;

                VariableExpressionImpl param = new VariableExpressionImpl()
                        ._name(functionTypes.getFirst()._parameters().get(idx) != null
                                ? functionTypes.getFirst()._parameters().get(idx)._name()
                                : "p" + idx);
                if (commonParamType != null)
                {
                    param._genericType(commonParamType);
                }
                if (commonParamMul != null)
                {
                    param._multiplicity(commonParamMul);
                }
                commonParams.add(param);
            }
            result._parameters(commonParams);
        }

        // Unify return type (covariant: LCA)
        MutableList<GenericType> returnTypes = functionTypes
                .select(ft -> ft._returnType() != null)
                .collect(FunctionType::_returnType);
        if (returnTypes.notEmpty())
        {
            GenericType commonReturnType = _GenericType.findCommonGenericType(returnTypes, model);
            if (commonReturnType != null)
            {
                result._returnType(commonReturnType);
            }
        }

        // Unify return multiplicity
        MutableList<Multiplicity> returnMuls = functionTypes
                .select(ft -> ft._returnMultiplicity() != null)
                .collect(FunctionType::_returnMultiplicity);
        if (returnMuls.notEmpty())
        {
            result._returnMultiplicity(_Multiplicity.findCommonMultiplicity(returnMuls));
        }

        return result;
    }

    /**
     * Print a FunctionType in Pure syntax, e.g. {@code {String[1], Integer[1]->Boolean[1]}}.
     */
    public static String print(FunctionType ft)
    {
        return print(ft, true);
    }

    /**
     * Print a FunctionType in Pure syntax, e.g. {@code {String[1], Integer[1]->Boolean[1]}}.
     *
     * @param ft       the function type to print
     * @param fullPath if true, use fully-qualified paths for types
     */
    public static String print(FunctionType ft, boolean fullPath)
    {
        StringBuilder sb = new StringBuilder("{");
        if (ft._parameters() != null && ft._parameters().notEmpty())
        {
            boolean first = true;
            for (var p : ft._parameters())
            {
                if (p == null)
                {
                    continue;
                }
                if (!first)
                {
                    sb.append(", ");
                }
                first = false;
                sb.append(_GenericType.print(p._genericType(), fullPath));
                sb.append(_Multiplicity.print(p._multiplicity()));
            }
        }
        sb.append("->");
        if (ft._returnType() != null)
        {
            sb.append(_GenericType.print(ft._returnType(), fullPath));
        }
        if (ft._returnMultiplicity() != null)
        {
            sb.append(_Multiplicity.print(ft._returnMultiplicity()));
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Check whether all parameter types and the return type of a FunctionType are concrete.
     */
    public static boolean isConcrete(FunctionType ft)
    {
        if (ft._parameters() != null)
        {
            for (VariableExpression p : ft._parameters())
            {
                if (!_GenericType.isConcrete(p._genericType()))
                {
                    return false;
                }
            }
        }
        return _GenericType.isConcrete(ft._returnType());
    }

    /**
     * Collect type parameter bindings by matching two FunctionTypes structurally.
     * Parameters are matched positionally, plus return type and multiplicities.
     */
    public static void collectTypeParameterBindings(FunctionType paramFT, FunctionType argFT, ParametersBinding bindings)
    {
        // Match parameters positionally
        if (paramFT._parameters() != null && argFT._parameters() != null)
        {
            int count = Math.min(paramFT._parameters().size(), argFT._parameters().size());
            for (int i = 0; i < count; i++)
            {
                GenericType pGT = paramFT._parameters().get(i)._genericType();
                GenericType aGT = argFT._parameters().get(i)._genericType();
                if (pGT != null && aGT != null)
                {
                    _GenericType.collectTypeParameterBindings(pGT, aGT, bindings);
                }
                _Multiplicity.collectMultiplicityParameterBindings(paramFT._parameters().get(i)._multiplicity(), argFT._parameters().get(i)._multiplicity(), bindings);
            }
        }
        // Match return types
        if (paramFT._returnType() != null && argFT._returnType() != null)
        {
            _GenericType.collectTypeParameterBindings(paramFT._returnType(), argFT._returnType(), bindings);
        }
        // Match return multiplicities
        _Multiplicity.collectMultiplicityParameterBindings(paramFT._returnMultiplicity(), argFT._returnMultiplicity(), bindings);
    }

    /**
     * Reconcile inferred types in FunctionType parameters and return type.
     * Widens inferred generic types and multiplicities where the expected type subsumes the actual.
     */
    public static void reconcileInferred(FunctionType expectedFT, FunctionType actualFT, MetadataAccess model)
    {
        if (expectedFT._parameters() != null && actualFT._parameters() != null)
        {
            int count = Math.min(expectedFT._parameters().size(), actualFT._parameters().size());
            for (int i = 0; i < count; i++)
            {
                var ep = expectedFT._parameters().get(i);
                var ap = actualFT._parameters().get(i);
                // Widen Inferred generic types
                if (ap._genericType() instanceof Inferred
                        && ep._genericType() != null
                        && _GenericType.isCompatible(ep._genericType(), ap._genericType(), model))
                {
                    ((meta.pure.metamodel.valuespecification.VariableExpressionImpl) ap)
                            ._genericType(_GenericType.asInferred(ep._genericType()));
                }
                // Widen Inferred multiplicities
                if (ap._multiplicity() instanceof Inferred
                        && ep._multiplicity() != null
                        && _Multiplicity.subsumes(ep._multiplicity(), ap._multiplicity()))
                {
                    ((meta.pure.metamodel.valuespecification.VariableExpressionImpl) ap)
                            ._multiplicity(_Multiplicity.asInferred(ep._multiplicity(), model));
                }
                // Recurse into param generic types
                _GenericType.reconcileInferred(ep._genericType(), ap._genericType(), model);
            }
        }
        // Reconcile return type
        _GenericType.reconcileInferred(expectedFT._returnType(), actualFT._returnType(), model);
    }

    /**
     * Resolve type and multiplicity parameter references inside a FunctionType.
     * Returns a new GenericType wrapping the resolved FunctionType if anything changed,
     * or {@code null} if nothing changed.
     *
     * @param genericType the GenericType whose rawType is the FunctionType
     * @param ft          the FunctionType to resolve
     * @param bindings    parameter bindings
     * @param model       the model
     * @return resolved GenericType, or null if unchanged
     */
    public static GenericType makeAsConcreteAsPossible(GenericType genericType, FunctionType ft, ParametersBinding bindings, MetadataAccess model)
    {
        boolean changed = false;

        // Resolve parameters
        MutableList<VariableExpression> resolvedParams = null;
        if (ft._parameters() != null)
        {
            resolvedParams = ft._parameters().collect(p ->
            {
                GenericType resolvedGT = p._genericType() != null ? _GenericType.makeAsConcreteAsPossible(p._genericType(), bindings, model) : p._genericType();
                Multiplicity resolvedMul = _Multiplicity.makeAsConcreteAsPossible(p._multiplicity(), bindings);
                if (resolvedGT != p._genericType() || resolvedMul != p._multiplicity())
                {
                    return (VariableExpression) new VariableExpressionImpl()._name(p._name())._genericType(resolvedGT)._multiplicity(resolvedMul);
                }
                return p;
            });
            changed = !resolvedParams.equals(ft._parameters());
        }

        // Resolve return type and multiplicity
        GenericType resolvedReturnType = ft._returnType() != null ? _GenericType.makeAsConcreteAsPossible(ft._returnType(), bindings, model) : ft._returnType();
        Multiplicity resolvedReturnMul = _Multiplicity.makeAsConcreteAsPossible(ft._returnMultiplicity(), bindings);
        changed = changed || resolvedReturnType != ft._returnType() || resolvedReturnMul != ft._returnMultiplicity();

        if (changed)
        {
            FunctionTypeImpl newFT = new FunctionTypeImpl();
            if (resolvedParams != null)
            {
                newFT._parameters(resolvedParams);
            }
            newFT._returnType(resolvedReturnType);
            newFT._returnMultiplicity(resolvedReturnMul);
            return new meta.pure.metamodel.type.generics.InferredGenericTypeImpl()._rawType(newFT)._typeArguments(genericType._typeArguments());
        }
        return null;
    }
}
