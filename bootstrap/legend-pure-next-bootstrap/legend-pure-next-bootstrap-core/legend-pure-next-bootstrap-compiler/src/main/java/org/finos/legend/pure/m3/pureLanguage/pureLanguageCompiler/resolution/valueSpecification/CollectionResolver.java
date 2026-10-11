// Copyright 2026 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification;

import platform.pure.metamodel.valuespecification.Collection;
import platform.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;

public class CollectionResolver
{
    public static Collection resolveCollection(Collection col, MetadataAccess model, CompilationContext context)
    {
        MutableList<ValueSpecification> vs = col._values().collect(x -> ValueSpecificationResolver.resolve(x, model, context));
        return (Collection) ((Collection) col._copy())
                ._values(vs)
                ._genericType(_GenericType.asInferred(_GenericType.findCommonGenericType(vs.collect(ValueSpecification::_genericType), model), model));
    }
}
