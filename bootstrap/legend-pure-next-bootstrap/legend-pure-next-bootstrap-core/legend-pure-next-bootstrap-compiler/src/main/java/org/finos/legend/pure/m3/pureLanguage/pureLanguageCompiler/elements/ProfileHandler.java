// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.elements;

import platform.pure.metamodel.PackageableElement;
import platform.pure.metamodel.extension.Profile;
import platform.pure.metamodel.extension.ProfileImpl;
import platform.pure.metamodel.extension.StereotypeImpl;
import platform.pure.metamodel.extension.TagImpl;
import platform.pure.metamodel.type.Type;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.SourceInformationCompiler;

/**
 * Handler for Profile.
 */
public final class ProfileHandler
{
    private ProfileHandler()
    {
    }

    public static Profile firstPass(platform.pure.protocol.grammar.extension.Profile grammar, MetadataAccess model, org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationContext context)
    {
        ProfileImpl result = new ProfileImpl(model)
                                ._name(grammar._name());

        return result
                ._p_stereotypes(grammar._p_stereotypes().collect(s ->
                        new StereotypeImpl(model)
                                ._value(s._value())
                                ._profile(result)))
                ._p_tags(grammar._p_tags().collect(t ->
                        new TagImpl(model)
                                ._value(t._value())
                                ._profile(result)))
                ._sourceInformation(SourceInformationCompiler.compile(grammar._p_sourceInformation(), context.getSourceId(), model));
    }

    public static Profile secondPass(ProfileImpl result, platform.pure.protocol.grammar.extension.Profile grammar, MetadataAccess model)
    {
        // Ctor (firstPass) already set classifier to canonical GenericType_Profile (UDPGT).
        return result;
    }

    public static PackageableElement thirdPass(Profile pr, MetadataAccess pureModel, CompilationContext context)
    {
        return pr;
    }
}
