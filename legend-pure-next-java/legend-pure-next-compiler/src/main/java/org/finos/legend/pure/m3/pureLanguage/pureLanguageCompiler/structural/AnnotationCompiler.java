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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.extension.Profile;
import meta.pure.metamodel.extension.Stereotype;
import meta.pure.metamodel.extension.Tag;
import meta.pure.metamodel.extension.TaggedValue;
import meta.pure.metamodel.extension.TaggedValueImpl;
import meta.pure.protocol.grammar.extension.Stereotype_Pointer;
import meta.pure.protocol.grammar.extension.Tag_Pointer;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;

/**
 * Compiles stereotype references and tagged values from grammar pointers
 * to metamodel instances by resolving them against compiled Profiles.
 */
public final class AnnotationCompiler
{
    private AnnotationCompiler()
    {
    }

    /**
     * Resolve a grammar Stereotype_Pointer to a metamodel Stereotype.
     *
     * <p>The pointer's {@code pointerValue} holds the profile path, and
     * {@code extraPointerValues[0].value} holds the stereotype name.</p>
     *
     * @return the resolved Stereotype, or null if resolution fails
     */
    public static Stereotype resolveStereotype(Stereotype_Pointer pointer, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        String profilePath = pointer._pointerValue();
        if (pointer._extraPointerValues() == null || pointer._extraPointerValues().isEmpty())
        {
            context.addError(new CompilationError("Invalid stereotype reference '" + profilePath + "'", SourceInformationCompiler.compile(pointer._sourceInformation())));
            return null;
        }
        String stereotypeName = pointer._extraPointerValues().getFirst()._value();

        Profile profile = resolveProfile(profilePath, imports, model, context, SourceInformationCompiler.compile(pointer._sourceInformation()));
        if (profile == null)
        {
            return null;
        }

        Stereotype found = profile._p_stereotypes().detect(s -> stereotypeName.equals(s._value()));
        if (found == null)
        {
            context.addError(new CompilationError(
                    "The stereotype '" + stereotypeName + "' can't be found in profile '" + profilePath + "'", SourceInformationCompiler.compile(pointer._extraPointerValues().getFirst()._sourceInformation())));
            return null;
        }
        return found;
    }

    /**
     * Resolve a grammar TaggedValue to a metamodel TaggedValue.
     *
     * <p>The tag pointer's {@code pointerValue} holds the profile path, and
     * {@code extraPointerValues[0].value} holds the tag name.</p>
     *
     * @return the resolved TaggedValue, or null if resolution fails
     */
    public static TaggedValue resolveTaggedValue(meta.pure.protocol.grammar.extension.TaggedValue grammarTV, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        Tag_Pointer tagPointer = grammarTV._tag();
        String profilePath = tagPointer._pointerValue();
        if (tagPointer._extraPointerValues() == null || tagPointer._extraPointerValues().isEmpty())
        {
            context.addError(new CompilationError("Invalid tag reference '" + profilePath + "'", SourceInformationCompiler.compile(tagPointer._sourceInformation())));
            return null;
        }
        String tagName = tagPointer._extraPointerValues().getFirst()._value();

        Profile profile = resolveProfile(profilePath, imports, model, context, SourceInformationCompiler.compile(tagPointer._sourceInformation()));
        if (profile == null)
        {
            return null;
        }

        Tag found = profile._p_tags().detect(t -> tagName.equals(t._value()));
        if (found == null)
        {
            context.addError(new CompilationError(
                    "The tag '" + tagName + "' can't be found in profile '" + profilePath + "'", SourceInformationCompiler.compile(tagPointer._extraPointerValues().getFirst()._sourceInformation())));
            return null;
        }
        return new TaggedValueImpl()
                ._tag(found)
                ._value(grammarTV._value());
    }

    private static Profile resolveProfile(String profilePath, MutableList<String> imports, MetadataAccess model, CompilationContext context, SourceInformation sourceInformation)
    {
        PackageableElement element = _PackageableElement.findElementOrReportError(profilePath, imports, model, context, sourceInformation);
        if (element instanceof Profile p)
        {
            return p;
        }
        if (element != null)
        {
            context.addError(new CompilationError("The element '" + profilePath + "' is not a Profile", sourceInformation));
        }
        else if (context.currentErrorCount() == 0)
        {
            context.addError(new CompilationError("The profile '" + profilePath + "' can't be found", sourceInformation));
        }
        return null;
    }
}
