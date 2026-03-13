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
import meta.pure.metamodel.function.NativeFunction;
import meta.pure.metamodel.function.NativeFunctionImpl;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.AnnotationCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.PackageableFunctionCompiler;

import java.util.Objects;

/**
 * Handler for NativeFunction.
 */
public final class NativeFunctionHandler
{
    private NativeFunctionHandler()
    {
    }

    public static NativeFunction firstPass(meta.pure.protocol.grammar.function.NativeFunction grammar)
    {
        return new NativeFunctionImpl()
                ._name(grammar._name())
                ._functionName(grammar._functionName());
    }

    public static NativeFunction secondPass(NativeFunctionImpl result, meta.pure.protocol.grammar.function.NativeFunction grammar, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        PackageableFunctionCompiler.compileShared(result, grammar, imports, model, context);

        // Compile stereotypes and tagged values
        if (grammar._stereotypes() != null && grammar._stereotypes().notEmpty())
        {
            result._stereotypes(grammar._stereotypes()
                    .collect(s -> AnnotationCompiler.resolveStereotype(s, imports, model, context))
                    .select(Objects::nonNull));
        }
        if (grammar._taggedValues() != null && grammar._taggedValues().notEmpty())
        {
            result._taggedValues(grammar._taggedValues()
                    .collect(tv -> AnnotationCompiler.resolveTaggedValue(tv, imports, model, context))
                    .select(Objects::nonNull));
        }

        context.enrichCurrentErrors("native function '" + PackageableFunctionCompiler.fullPath(grammar) + "'");
        return result;
    }

    public static PackageableElement thirdPass(NativeFunction nf, MetadataAccess pureModel, CompilationContext context)
    {
        return nf;
    }
}

