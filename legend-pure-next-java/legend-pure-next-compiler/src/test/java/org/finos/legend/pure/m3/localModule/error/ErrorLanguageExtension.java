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

package org.finos.legend.pure.m3.localModule.error;

import meta.pure.metamodel.PackageableElement;
import meta.pure.protocol.grammar.Package_PointerImpl;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilerContextExtension;
import org.finos.legend.pure.m3.module.localModule.topLevel.IndexEntry;
import org.finos.legend.pure.m3.LanguageExtension;

import java.util.List;

/**
 * Language extension for the {@code ###Error} test section.
 *
 * <p>Bundles grammar parsing (captures raw content as an {@link Error})
 * and compilation (creates an {@link ErrorImpl} from it).</p>
 */
public final class ErrorLanguageExtension implements LanguageExtension
{
    @Override
    public String sectionName()
    {
        return "Error";
    }


    @Override
    public CompilerContextExtension buildCompilerContextExtension()
    {
        return null;
    }

    @Override
    public List<meta.pure.protocol.grammar.PackageableElement> parseSection(String content, String sourceId, int lineOffset)
    {
        return List.of(
                new Error()
                        ._value(content)
                        ._package(new Package_PointerImpl()._pointerValue(sourceId)));
    }

    @Override
    public PackageableElement firstPass(meta.pure.protocol.grammar.PackageableElement grammar)
    {
        if (grammar instanceof Error e)
        {
            return new ErrorImpl()
                    ._name(e._name())
                    ._value(e._value());
        }
        return null;
    }

    @Override
    public PackageableElement secondPass(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        if (entry.grammarElement() instanceof Error)
        {
            return entry.element();
        }
        return null;
    }

    @Override
    public PackageableElement thirdPass(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        if (entry.grammarElement() instanceof Error)
        {
            return entry.element();
        }
        return null;
    }
}
