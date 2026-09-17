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

package org.finos.legend.pure.m3.compilation;

import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.util.List;

/**
 * What {@code PureRuntime} hands a {@link Compilation.Builder}: the registry, the language
 * extensions, and a way to execute Pure functions (strategies that run the Pure compiler
 * execute it through the runtime).
 */
public record CompilationContext(MetadataAccess registry,
                                 List<LanguageExtension> languageExtensions,
                                 FunctionExecutor executor)
{
    public CompilationContext
    {
        languageExtensions = List.copyOf(languageExtensions);
    }
}
