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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler;

import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.TypeParameter;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;

import java.util.Map;

/**
 * Groups type parameter bindings and multiplicity parameter bindings
 * collected during function signature matching.
 *
 * <p>This is an abstract base class. Concrete subtypes:
 * <ul>
 *   <li>{@link EnclosingOwnerParametersBinding} — the root scope, adds bindings locally.</li>
 *   <li>{@link FunctionCallParametersBinding} — per-call scope, routes bindings by owner
 *       to the enclosing owner during cross-match unification.</li>
 *   <li>{@link PlainParametersBinding} — no owner, no routing; for temporary
 *       accumulation and snapshots ({@link #copy()}).</li>
 * </ul>
 * </p>
 */
public abstract class ParametersBinding
{
    protected final MutableMap<String, MutableSet<GenericType>> typeBindings = Maps.mutable.empty();
    protected final MutableMap<String, MutableSet<Multiplicity>> multiplicityBindings = Maps.mutable.empty();

    public MutableMap<String, MutableSet<GenericType>> typeBindings()
    {
        return this.typeBindings;
    }

    public MutableMap<String, MutableSet<Multiplicity>> multiplicityBindings()
    {
        return this.multiplicityBindings;
    }

    /**
     * Add a type parameter binding. {@link EnclosingOwnerParametersBinding} adds locally;
     * {@link FunctionCallParametersBinding} may route to the enclosing owner
     * during cross-match unification.
     */
    public abstract void addTypeBinding(TypeParameter typeParam, GenericType argGT);

    /**
     * Create a plain data-only copy of this binding set (no routing).
     * Used to snapshot bindings for validation or rollback.
     */
    public ParametersBinding copy()
    {
        PlainParametersBinding result = PlainParametersBinding.empty();
        for (Map.Entry<String, MutableSet<GenericType>> e : typeBindings.entrySet())
        {
            result.typeBindings.put(e.getKey(), e.getValue().clone());
        }
        for (Map.Entry<String, MutableSet<Multiplicity>> e : multiplicityBindings.entrySet())
        {
            result.multiplicityBindings.put(e.getKey(), e.getValue().clone());
        }
        return result;
    }


    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, MutableSet<GenericType>> e : typeBindings.entrySet())
        {
            if (!first)
            {
                sb.append(", ");
            }
            first = false;
            sb.append(e.getKey()).append("=");
            sb.append(e.getValue().collect(_GenericType::print).makeString("/"));
        }
        if (!multiplicityBindings.isEmpty())
        {
            if (!typeBindings.isEmpty())
            {
                sb.append(" | ");
            }
            boolean firstMul = true;
            for (Map.Entry<String, MutableSet<Multiplicity>> e : multiplicityBindings.entrySet())
            {
                if (!firstMul)
                {
                    sb.append(", ");
                }
                firstMul = false;
                sb.append(e.getKey()).append("=");
                sb.append(e.getValue().collect(_Multiplicity::print).makeString("/"));
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
