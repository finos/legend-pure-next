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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.elements;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.extension.Profile;
import meta.pure.metamodel.extension.ProfileImpl;
import meta.pure.metamodel.extension.StereotypeImpl;
import meta.pure.metamodel.extension.TagImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Handler for Profile.
 */
public final class ProfileHandler
{
    private ProfileHandler()
    {
    }

    public static Profile firstPass(meta.pure.protocol.grammar.extension.Profile grammar)
    {
        ProfileImpl result = new ProfileImpl()
                ._name(grammar._name());

        return result
                ._p_stereotypes(grammar._p_stereotypes().collect(s ->
                        new StereotypeImpl()
                                ._value(s._value())
                                ._profile(result)))
                ._p_tags(grammar._p_tags().collect(t ->
                        new TagImpl()
                                ._value(t._value())
                                ._profile(result)));
    }

    public static Profile secondPass(ProfileImpl result, meta.pure.protocol.grammar.extension.Profile grammar, MetadataAccess model)
    {
        return result._classifierGenericType(
                new ConcreteGenericTypeImpl()
                        ._rawType((Type) model.getElement("meta::pure::metamodel::extension::Profile")));
    }

    public static PackageableElement thirdPass(Profile pr, MetadataAccess pureModel, CompilationContext context)
    {
        return pr;
    }
}
