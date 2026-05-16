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

package org.finos.legend.pure.next.parser.topLevel.codegen;

import org.finos.legend.pure.next.parser.codegen.VisitorMappingGenerator;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Build-time entry point: parses {@code top-mappings.dsl} and emits
 * {@code TopLevelProtocolBuilder.java} for the bootstrap protocol path.
 * Invoked from the bootstrap-parser pom via exec-maven-plugin.
 */
public final class ProtocolTopLevelGenerator
{
    public static void main(String[] args) throws IOException
    {
        if (args.length < 2)
        {
            System.err.println("Usage: TopLevelProtocolGenerator <dsl-file> <output-java-file>");
            System.exit(1);
        }
        // top-mappings.dsl uses only generic primitives — no Pure-language extension needed.
        VisitorMappingGenerator.compile(Path.of(args[0]), Path.of(args[1]), new ProtocolTopLevelEmitterTarget());
    }
}
