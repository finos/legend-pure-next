// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.next.parser.pureLanguage.codegen;

import org.finos.legend.pure.next.parser.codegen.VisitorMappingGenerator;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Build-time entry point for the Pure-language visitor-mapping DSL — wires the
 * Pure-specific primitives ({@code primitiveType}, {@code multBounds},
 * {@code enumPointer}, {@code dateLiteralType}) into the otherwise-generic
 * {@link VisitorMappingGenerator}, then delegates to it.
 *
 * <p>Other DSLs (top-mappings, future grammars) can reuse {@code VisitorMappingGenerator}
 * directly — only consumers that build the Pure metamodel structures route through here.</p>
 */
public final class ProtocolPureLanguageVisitorMappingGenerator
{
    public static void main(String[] args) throws IOException
    {
        if (args.length < 2)
        {
            System.err.println("Usage: PureLanguageVisitorMappingGenerator <dsl-file> <output-java-file>");
            System.exit(1);
        }
        ProtocolPureLanguagePrimitives.register();
        VisitorMappingGenerator.compile(Path.of(args[0]), Path.of(args[1]), new ProtocolPureLanguageEmitterTarget());
    }
}
