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

import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.TypeParameter;

/**
 * A plain, data-only {@link ParametersBinding}: adds bindings locally,
 * no owner, no routing. Used for temporary accumulation in helper
 * computations and for snapshots (via {@link ParametersBinding#copy()}).
 */
public class PlainParametersBinding extends ParametersBinding
{
    private PlainParametersBinding()
    {
    }

    /** Create an empty plain binding store. */
    public static PlainParametersBinding empty()
    {
        return new PlainParametersBinding();
    }

    @Override
    public void addTypeBinding(TypeParameter typeParam, GenericType argGT)
    {
        typeBindings.computeIfAbsent(typeParam._name(),
                k -> org.eclipse.collections.impl.factory.Lists.mutable.empty()).add(argGT);
    }
}
