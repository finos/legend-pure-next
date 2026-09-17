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

package org.finos.legend.pure.execution;

import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.compilation.Compilation;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.next.parser.GrammarExtension;

import java.util.List;
import java.util.function.Supplier;

/**
 * What {@code PureRuntime} hands an {@link Execution.Builder}: the registry and the
 * runtime's language extensions (each a parser and a compiler for a section) plus the
 * ANTLR grammars they parse with, and the runtime's compilation (natives such as
 * {@code compileSource} compile through it). Execution strategies consume these; they do not own them.
 */
public record ExecutionContext(MetadataAccess registry,
                               List<LanguageExtension> languageExtensions,
                               List<GrammarExtension> grammarExtensions,
                               Supplier<Compilation> compilation)
{
    public ExecutionContext
    {
        languageExtensions = List.copyOf(languageExtensions);
        grammarExtensions = List.copyOf(grammarExtensions);
    }
}
