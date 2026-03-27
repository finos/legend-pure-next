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

import meta.pure.metamodel.type.Any;
import meta.pure.metamodel.type.Type;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Helper to ensure every metamodel element gets its {@code classifierGenericType}
 * set, satisfying the {@code [1]} multiplicity on {@code Any.classifierGenericType}.
 */
public final class _Any
{
    private _Any()
    {
    }

    /**
     * Set the {@code classifierGenericType} on a metamodel element.
     *
     * @param obj       the element to configure
     * @param classPath the fully qualified path of the element's classifier class (e.g. "meta::pure::metamodel::type::FunctionType")
     * @param model     the metadata access for looking up class types
     * @param <T>       the element type
     * @return the same element, for fluent chaining
     */
    @SuppressWarnings("unchecked")
    public static <T extends Any> T withClassifierGenericType(T obj, String classPath, MetadataAccess model)
    {
        obj._classifierGenericType(
                _GenericType.buildUserDefinedGenericType((Type) model.getElement(classPath), model));
        return obj;
    }
}
