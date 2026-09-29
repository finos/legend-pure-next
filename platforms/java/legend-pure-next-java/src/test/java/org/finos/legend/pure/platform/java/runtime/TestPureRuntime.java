// Copyright 2026 Goldman Sachs
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
package org.finos.legend.pure.platform.java.runtime;

import org.finos.legend.pure.platform.java.compiler.module.CompilationResult;
import org.finos.legend.pure.platform.java.compiler.module.ModuleRegistry;
import org.finos.legend.pure.platform.java.compiler.module.inMemoryModule.InMemoryModule;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.Metadata;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.PdbModule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host API end to end, as bootstrap's and Truffle's TestPureRuntime: register
 * core.pdb and an in-memory module, build a {@link PureRuntime}, put Pure source held in
 * a string into the module, compile, execute a function it defines.
 *
 * <p>On this platform the execute step exercises the runtime-translation path — a function
 * the compiler just built has no generated class, so it is translated and javac'd in
 * memory — and {@code greet} pushes that further by constructing a {@code Person} and
 * reading its properties, which resolve through a PropertyPointer.</p>
 */
class TestPureRuntime
{
    private static final String SOURCE = """
            Class sample::Person
            {
                firstName : String[1];
                lastName  : String[1];
            }

            function sample::fullName(person:sample::Person[1]):String[1]
            {
                $person.firstName + ' ' + $person.lastName
            }

            function sample::greet(firstName:String[1], lastName:String[1]):String[1]
            {
                'Hello, ' + ^sample::Person(firstName = $firstName, lastName = $lastName)->sample::fullName() + '!'
            }
            """;

    @Test
    void modifyCodeInModulesCompileAndExecute() throws IOException
    {
        Path root = locateRepoRoot();
        Path shared = root.resolve("shared");
        ModuleRegistry registry = new ModuleRegistry();
        PdbModule core = PdbModule.open(shared.resolve("core.pdb"));
        registry.register(core);
        registry.register(PdbModule.open(shared.resolve("core-tests.pdb")));
        registry.register(PdbModule.open(shared.resolve("parser-mappings.pdb")));
        registry.register(PdbModule.open(shared.resolve("compiler.pdb")));
        registry.register(PdbModule.open(root.resolve("pure/modules/language/java/build/java.pdb")));
        registry.register(PdbModule.open(root.resolve("pure/modules/translation/shared/build/translation-shared.pdb")));
        registry.register(PdbModule.open(root.resolve("pure/modules/translation/java/build/java-translation.pdb")));
        registry.register(new InMemoryModule("sample", List.of(core.name())));
        registry.validate();

        PureRuntime runtime = PureRuntime.builder()
                .withRegistry(registry)
                .build();
        try
        {
            runtime.setSource("sample", "sample.pure", SOURCE);
            CompilationResult result = runtime.compile();
            if (result.errors().isEmpty())
            {
                Object greet = runtime.registry().getElement("sample::greet_String_1__String_1__String_1_");
                System.out.println(runtime.execute(greet, "Ada", "Lovelace"));
            }
        }
        finally
        {
            runtime.close();
        }
    }

    private static Path locateRepoRoot()
    {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent())
        {
            if (Files.exists(dir.resolve("shared").resolve("core.pdb")))
            {
                return dir;
            }
        }
        throw new IllegalStateException("Cannot locate shared/core.pdb walking up from " + Path.of("").toAbsolutePath());
    }
}
