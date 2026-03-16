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
import meta.pure.metamodel.multiplicity.MultiplicityParameter;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.TypeParameter;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.metadata.FunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;

import java.util.Map;

/**
 * Per-function-call binding store. Extends {@link ParametersBinding} so this
 * node IS the binding container.
 *
 * <p>Overrides {@link #addTypeBinding} to perform owner-aware routing during
 * cross-match unification: if the type parameter is owned by the enclosing
 * function/class, the binding is stored in {@link #enclosingOwner} instead of
 * locally.</p>
 *
 * <p>Holds {@link #unify(MetadataAccess)}, since it has direct access to
 * both its own bindings and the {@link EnclosingOwnerParametersBinding}.</p>
 */
public class FunctionCallParametersBinding extends ParametersBinding
{
    private final FunctionExpression expr;
    private final FunctionIndexEntry entry;
    private final MutableMap<String, TypeParameter> ownTypeParams;
    private final MutableMap<String, MultiplicityParameter> ownMulParams;
    private final EnclosingOwnerParametersBinding enclosingOwner;


    FunctionCallParametersBinding(FunctionExpression expr, FunctionIndexEntry entry,
                                  ParametersBinding seed,
                                  EnclosingOwnerParametersBinding enclosingOwner)
    {
        this.expr = expr;
        this.entry = entry;
        this.ownTypeParams = entry._typeParameters() != null
                ? entry._typeParameters().toMap(TypeParameter::_name, tp -> tp)
                : org.eclipse.collections.impl.factory.Maps.mutable.empty();
        this.ownMulParams = entry._multiplicityParameters() != null
                ? entry._multiplicityParameters().toMap(MultiplicityParameter::_name, mp -> mp)
                : org.eclipse.collections.impl.factory.Maps.mutable.empty();
        this.enclosingOwner = enclosingOwner;

        // Copy seed bindings (pre-resolved type/mul params) into this.
        if (seed != null)
        {
            for (Map.Entry<String, MutableSet<GenericType>> e : seed.typeBindings().entrySet())
            {
                this.typeBindings.put(e.getKey(), e.getValue().clone());
            }
            for (Map.Entry<String, MutableSet<Multiplicity>> e : seed.multiplicityBindings().entrySet())
            {
                this.multiplicityBindings.put(e.getKey(), e.getValue().clone());
            }
        }
    }

    public FunctionExpression expr() { return expr; }
    public FunctionIndexEntry entry() { return entry; }
    public String functionPath() { return entry.fullPath(); }
    public MutableMap<String, TypeParameter> ownTypeParams() { return ownTypeParams; }
    public MutableMap<String, MultiplicityParameter> ownMulParams() { return ownMulParams; }
    public MutableSet<String> ownTypeParamNames() { return ownTypeParams.keysView().toSet(); }
    public MutableSet<String> ownMulParamNames() { return ownMulParams.keysView().toSet(); }
    public EnclosingOwnerParametersBinding enclosingOwner() { return enclosingOwner; }

    public String printParentBindings()
    {
        return enclosingOwner != null ? "[enclosing=" + enclosingOwner + "]" : "[]";
    }

    /**
     * Route type parameter bindings by owner.
     *
     * <p>If the type parameter is owned by the enclosing function/class,
     * the binding is stored in {@link #enclosingOwner}. Otherwise stored locally.
     * This is always correct: callee type params are never owned by the enclosing
     * compilation scope.</p>
     */
    @Override
    public void addTypeBinding(TypeParameter typeParam, GenericType argGT)
    {
        if (enclosingOwner != null)
        {
            Object owner = typeParam._owner();
            if (owner != null && owner == enclosingOwner.owner())
            {
                enclosingOwner.addTypeBinding(typeParam, argGT);
                return;
            }
        }
        typeBindings.computeIfAbsent(typeParam._name(),
                k -> org.eclipse.collections.impl.factory.Sets.mutable.empty()).add(argGT);
    }

    /**
     * Unify parameters bound to multiple values by finding their common type/multiplicity.
     *
     * <ul>
     *   <li><b>Cross-match:</b> pairwise decompose multi-valued type param bindings to
     *       extract inner bindings. Bare type-param references are not used as templates.</li>
     *   <li><b>Transitive resolution:</b> resolve chains like V→Z→TPR_Person.</li>
     *   <li><b>Pre-reduction:</b> resolve outer-scope type param refs via
     *       {@link #enclosingOwner} bindings before common-type reduction.</li>
     *   <li><b>Common-type reduction:</b> collapse multi-valued sets to a single value.</li>
     * </ul>
     */
    public void unify(MetadataAccess model)
    {
        // Cross-match: for type params with multiple bindings, compare them pairwise
        // to extract inner bindings (e.g. T→{List<V>, List<String>} → V=String).
        // Only structured types (with rawType) are used as templates.
        for (var entry : typeBindings.entrySet())
        {
            if (entry.getValue().size() > 1)
            {
                MutableList<GenericType> values = Lists.mutable.withAll(entry.getValue());
                for (int i = 0; i < values.size(); i++)
                {
                    for (int j = i + 1; j < values.size(); j++)
                    {
                        // 'addTypeBinding' called within collectTypeParameterBindings is dispatching to the proper context (this or EnclosingOwner).
                        _GenericType.collectTypeParameterBindings(values.get(i), values.get(j), this);
                        _GenericType.collectTypeParameterBindings(values.get(j), values.get(i), this);
                    }
                }
            }
        }

        // Transitive resolution: V→Z and Z→TPR_Person → V→TPR_Person.
        // Checks local bindings first, then enclosing owner's bindings.
        boolean changed = true;
        while (changed)
        {
            changed = false;
            for (var entry : typeBindings.entrySet())
            {
                if (entry.getValue().size() == 1)
                {
                    GenericType gt = entry.getValue().getFirst();
                    if (gt != null && gt._typeParameter() != null && gt._rawType() == null)
                    {
                        String targetName = gt._typeParameter()._name();
                        MutableSet<GenericType> targetBinding = typeBindings.get(targetName);

                        if ((targetBinding == null || targetBinding.isEmpty()) && enclosingOwner != null)
                        {
                            Object targetOwner = gt._typeParameter()._owner();
                            if (targetOwner == enclosingOwner.owner())
                            {
                                targetBinding = enclosingOwner.typeBindings().get(targetName);
                            }
                        }

                        if (targetBinding != null && targetBinding.size() == 1)
                        {
                            GenericType resolved = targetBinding.getFirst();
                            if (resolved._rawType() != null)
                            {
                                entry.getValue().clear();
                                entry.getValue().add(resolved);
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        // Transitive resolution for multiplicity parameters.
        changed = true;
        while (changed)
        {
            changed = false;
            for (var entry : multiplicityBindings.entrySet())
            {
                if (entry.getValue().size() == 1)
                {
                    Multiplicity mul = entry.getValue().getFirst();
                    if (mul instanceof MultiplicityParameter mulParam)
                    {
                        String targetName = mulParam._name();
                        MutableSet<Multiplicity> targetBinding = multiplicityBindings.get(targetName);
                        if (targetBinding != null && targetBinding.size() == 1)
                        {
                            Multiplicity resolved = targetBinding.getFirst();
                            if (!(resolved instanceof MultiplicityParameter))
                            {
                                entry.getValue().clear();
                                entry.getValue().add(resolved);
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        // Pre-reduction: replace bare outer-scope type param references with their
        // resolved value from enclosingOwner. E.g. T→{outerV, String} becomes
        // T→{TPR_Person, String} if enclosingOwner has V→TPR_Person.
        if (enclosingOwner != null)
        {
            for (var entry : typeBindings.entrySet())
            {
                if (entry.getValue().size() > 1)
                {
                    MutableList<GenericType> resolved = Lists.mutable.empty();
                    boolean anyResolved = false;
                    for (GenericType gt : entry.getValue())
                    {
                        if (gt._typeParameter() != null && gt._rawType() == null
                                && gt._typeParameter()._owner() == enclosingOwner.owner())
                        {
                            MutableSet<GenericType> ownerBinding = enclosingOwner.typeBindings()
                                    .get(gt._typeParameter()._name());
                            if (ownerBinding != null && ownerBinding.size() == 1)
                            {
                                GenericType ownerResolved = ownerBinding.getFirst();
                                if (ownerResolved._rawType() != null)
                                {
                                    resolved.add(ownerResolved);
                                    anyResolved = true;
                                    continue;
                                }
                            }
                        }
                        resolved.add(gt);
                    }
                    if (anyResolved)
                    {
                        entry.getValue().clear();
                        entry.getValue().addAll(resolved);
                    }
                }
            }
        }

        // Reduce multi-valued type bindings to their common type.
        for (var entry : typeBindings.entrySet())
        {
            if (entry.getValue().size() > 1)
            {
                GenericType common = _GenericType.findCommonGenericType(
                        Lists.mutable.withAll(entry.getValue()), model);
                entry.getValue().clear();
                entry.getValue().add(common);
            }
        }
        for (var entry : multiplicityBindings.entrySet())
        {
            if (entry.getValue().size() > 1)
            {
                Multiplicity common = _Multiplicity.findCommonMultiplicity(
                        Lists.mutable.withAll(entry.getValue()));
                entry.getValue().clear();
                entry.getValue().add(common);
            }
        }
    }
}
