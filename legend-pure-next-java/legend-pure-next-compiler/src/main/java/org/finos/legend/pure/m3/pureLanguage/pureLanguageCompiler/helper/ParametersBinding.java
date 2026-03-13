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
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.util.Map;

/**
 * Groups type parameter bindings and multiplicity parameter bindings
 * collected during function signature matching.
 *
 * <p>Type bindings map each type parameter name (e.g. {@code "T"}) to the
 * set of concrete {@link GenericType}s bound to it. Multiplicity bindings
 * map each multiplicity parameter name (e.g. {@code "m"}) to the set of
 * concrete {@link Multiplicity} values bound to it.</p>
 */
public class ParametersBinding
{
    private final MutableMap<String, MutableSet<GenericType>> typeBindings = Maps.mutable.empty();
    private final MutableMap<String, MutableSet<Multiplicity>> multiplicityBindings = Maps.mutable.empty();

    public MutableMap<String, MutableSet<GenericType>> typeBindings()
    {
        return this.typeBindings;
    }

    public MutableMap<String, MutableSet<Multiplicity>> multiplicityBindings()
    {
        return this.multiplicityBindings;
    }

    /**
     * Create a shallow copy of this binding set.
     * Each type/multiplicity parameter gets its own mutable set copy.
     */
    public ParametersBinding copy()
    {
        ParametersBinding result = new ParametersBinding();
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

    /**
     * Unify parameters that are bound to multiple values by finding their
     * common type/multiplicity. Returns {@code true} if all bindings were
     * successfully reduced to a single value, {@code false} if any conflicts
     * remain (e.g. common type is {@code Any}, or no common multiplicity).
     */
    public void unify(MetadataAccess model)
    {
        for (var entry : typeBindings.entrySet())
        {
            if (entry.getValue().size() > 1)
            {
                GenericType common = _GenericType.findCommonGenericType(Lists.mutable.withAll(entry.getValue()), model);
                entry.getValue().clear();
                entry.getValue().add(common);
            }
        }
        for (var entry : multiplicityBindings.entrySet())
        {
            if (entry.getValue().size() > 1)
            {
                Multiplicity common = _Multiplicity.findCommonMultiplicity(Lists.mutable.withAll(entry.getValue()));
                entry.getValue().clear();
                entry.getValue().add(common);
            }
        }
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
