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

import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.ResolvedMultiplicityParameter;
import meta.pure.metamodel.type.generics.ResolvedMultiplicityParameterImpl;
import meta.pure.metamodel.type.generics.ResolvedTypeParameter;
import meta.pure.metamodel.type.generics.ResolvedTypeParameterImpl;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Sets;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.ParametersBinding;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PlainParametersBinding;

/**
 * Helper methods for {@link FunctionExpression}.
 */
public final class _FunctionExpression
{
    private _FunctionExpression()
    {
        // static utility
    }

    /**
     * Extract resolved type and multiplicity parameter bindings from a {@link FunctionExpression}.
     */
    public static ParametersBinding extractResolvedParametersBinding(FunctionExpression expr)
    {
        ParametersBinding bindings = PlainParametersBinding.empty();
        MutableList<? extends ResolvedTypeParameter> resolvedTypeParameters = expr._resolvedTypeParameters();
        if (resolvedTypeParameters != null && resolvedTypeParameters.notEmpty())
        {
            for (ResolvedTypeParameter rtp : resolvedTypeParameters)
            {
                bindings.typeBindings().computeIfAbsent(rtp._name(), k -> Sets.mutable.empty()).add(rtp._value());
            }
        }
        MutableList<? extends ResolvedMultiplicityParameter> resolvedMultiplicityParameters = expr._resolvedMultiplicityParameters();
        if (resolvedMultiplicityParameters != null && resolvedMultiplicityParameters.notEmpty())
        {
            for (ResolvedMultiplicityParameter rmp : resolvedMultiplicityParameters)
            {
                bindings.multiplicityBindings().computeIfAbsent(rmp._name(), k -> Sets.mutable.empty()).add(rmp._value());
            }
        }
        return bindings;
    }

    /**
     * Build and set resolved type and multiplicity parameters on the expression.
     * Overload that writes ALL bindings (no scope filtering).
     */
    public static FunctionExpression populateResolvedParameters(
            FunctionExpression expr,
            ParametersBinding bindings,
            MetadataAccess model)
    {
        return populateResolvedParameters(expr, bindings, null, null, model);
    }

    /**
     * Build and set resolved type and multiplicity parameters on the expression.
     * Only writes bindings for the function's own params (ownTypeParams/ownMulParams),
     * filtering out bindings from outer scopes (e.g., Z, y from enclosing function).
     */
    public static FunctionExpression populateResolvedParameters(
            FunctionExpression expr,
            ParametersBinding bindings,
            MutableSet<String> ownTypeParams,
            MutableSet<String> ownMulParams,
            MetadataAccess model)
    {
        // Resolve type parameters — only write this function's own params
        if (!bindings.typeBindings().isEmpty())
        {
            MutableList<ResolvedTypeParameter> resolvedTypes = Lists.mutable.empty();
            for (var entry : bindings.typeBindings().entrySet())
            {
                if (ownTypeParams != null && !ownTypeParams.contains(entry.getKey()))
                {
                    continue;
                }
                MutableSet<GenericType> boundTypes = entry.getValue();
                if (boundTypes != null && boundTypes.notEmpty())
                {
                    GenericType boundGT = boundTypes.size() == 1
                            ? boundTypes.getFirst()
                            : _GenericType.findCommonGenericType(Lists.mutable.withAll(boundTypes), model);
                    if (boundGT != null)
                    {
                        resolvedTypes.add(new ResolvedTypeParameterImpl(model)
                                ._name(entry.getKey())
                                ._value(boundGT));
                    }
                }
            }
            if (resolvedTypes.notEmpty())
            {
                expr._resolvedTypeParameters(resolvedTypes);
            }
        }

        // Resolve multiplicity parameters — only write this function's own params
        if (!bindings.multiplicityBindings().isEmpty())
        {
            MutableList<ResolvedMultiplicityParameter> resolvedMuls = Lists.mutable.empty();
            for (var entry : bindings.multiplicityBindings().entrySet())
            {
                if (ownMulParams != null && !ownMulParams.contains(entry.getKey()))
                {
                    continue;
                }
                MutableSet<Multiplicity> boundMuls = entry.getValue();
                if (boundMuls != null && boundMuls.notEmpty() && boundMuls.size() == 1)
                {
                    resolvedMuls.add(new ResolvedMultiplicityParameterImpl(model)
                            ._name(entry.getKey())
                            ._value(boundMuls.getFirst()));
                }
            }
            if (resolvedMuls.notEmpty())
            {
                expr._resolvedMultiplicityParameters(resolvedMuls);
            }
        }
        return expr;
    }
}
