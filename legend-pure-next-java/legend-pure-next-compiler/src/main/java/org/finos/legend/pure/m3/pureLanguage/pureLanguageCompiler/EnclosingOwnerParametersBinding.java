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

import meta.pure.metamodel.type.generics.TypeAndMultiplicityParametersOwner;

/**
 * Root binding store for the enclosing function or class currently being compiled.
 * Extends {@link ParametersBinding} so it directly IS the binding container —
 * no delegation needed.
 *
 * <p>Created by {@link PureLanguageCompilerContext#setEnclosingOwner} and
 * referenced by {@link FunctionCallParametersBinding}s during cross-match unification to
 * receive type parameter bindings owned by the enclosing element.</p>
 */
public class EnclosingOwnerParametersBinding extends ParametersBinding
{
    private final TypeAndMultiplicityParametersOwner owner;

    public EnclosingOwnerParametersBinding(TypeAndMultiplicityParametersOwner owner)
    {
        this.owner = owner;
    }
    /**
     * The enclosing function or class whose type/multiplicity parameters are in scope.
     */
    public TypeAndMultiplicityParametersOwner owner()
    {
        return owner;
    }

    /**
     * Adds the binding locally. The enclosing owner never needs to route — it IS the
     * top-level scope that FunctionCallParametersBinding routes to.
     */
    @Override
    public void addTypeBinding(meta.pure.metamodel.type.generics.TypeParameter typeParam,
                               meta.pure.metamodel.type.generics.GenericType argGT)
    {
        typeBindings.computeIfAbsent(typeParam._name(),
                k -> org.eclipse.collections.impl.factory.Sets.mutable.empty()).add(argGT);
    }
}
