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

package org.finos.legend.pure.truffle.runtime.compilation;

import org.finos.legend.pure.truffle.compiler.module.TestElementFilter;
import org.finos.legend.pure.truffle.grammar.PureLanguage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The languages a compile runs with are an argument, not something a caller's customizer may forget:
 * the native library's pure_compile passed a no-op customizer and so built a runtime with no languages.
 */
public class TruffleCompilerBinaryBuilderLanguagesTest
{
    @Test
    public void aModuleCompilesWithTheLanguagesItIsGiven(@TempDir Path dir) throws IOException
    {
        Path source = Files.createDirectories(dir.resolve("src"));
        Files.writeString(source.resolve("module.json"),
                "{ \"name\": \"tiny\", \"packagePattern\": \"(t)(::.*)?\", \"dependencies\": [\"core\"] }");
        Files.writeString(source.resolve("tiny.pure"), "Class t::A { name: String[1]; }\n");
        Path out = Files.createDirectories(dir.resolve("out"));

        Path shared = sharedDir();
        TruffleCompilerBinaryBuilder.compile(
                List.of(shared.resolve("core.pdb"), shared.resolve("parser-mappings.pdb"), shared.resolve("compiler.pdb")),
                List.of(source), out, TestElementFilter.Mode.NONE, PureLanguage.all(), b -> { });

        Assertions.assertTrue(Files.exists(out.resolve("tiny.pdb")), "tiny.pdb written");
    }

    @Test
    public void aModuleCompilesFromSeveralSourceDirectories(@TempDir Path dir) throws IOException
    {
        // code/ and generated/ are the module; tests/ beside them holds fixtures that are not Pure to load.
        Files.writeString(dir.resolve("module.json"),
                "{ \"name\": \"two\", \"packagePattern\": \"(t)(::.*)?\", \"dependencies\": [\"core\"] }");
        Path code = Files.createDirectories(dir.resolve("code"));
        Path generated = Files.createDirectories(dir.resolve("generated"));
        Files.writeString(code.resolve("a.pure"), "Class t::A { b: t::B[1]; }\n");
        Files.writeString(generated.resolve("b.pure"), "Class t::B { }\n");
        Files.writeString(Files.createDirectories(dir.resolve("tests")).resolve("fixture.pure"), "not Pure at all\n");
        Path out = Files.createDirectories(dir.resolve("out"));

        Path shared = sharedDir();
        TruffleCompilerBinaryBuilder.compile(
                List.of(shared.resolve("core.pdb"), shared.resolve("parser-mappings.pdb"), shared.resolve("compiler.pdb")),
                List.of(code, generated), out, TestElementFilter.Mode.NONE, PureLanguage.all(), b -> { });

        Assertions.assertTrue(Files.exists(out.resolve("two.pdb")), "two.pdb written from code/ + generated/, tests/ ignored");
    }

    private static Path sharedDir()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("shared").resolve("core.pdb")))
        {
            current = current.getParent();
        }
        if (current == null)
        {
            throw new IllegalStateException("Cannot locate the repository (shared/core.pdb)");
        }
        return current.resolve("shared");
    }
}
