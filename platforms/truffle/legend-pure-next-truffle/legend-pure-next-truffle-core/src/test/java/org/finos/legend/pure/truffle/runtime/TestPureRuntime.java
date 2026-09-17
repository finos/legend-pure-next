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
package org.finos.legend.pure.truffle.runtime;

import org.finos.legend.pure.truffle.compiler.module.ModuleRegistry;
import org.finos.legend.pure.truffle.compiler.module.inMemoryModule.InMemoryModule;
import org.finos.legend.pure.truffle.compiler.module.pdbModule.PdbModule;
import org.finos.legend.pure.truffle.compiler.module.CompilationResult;
import org.finos.legend.pure.truffle.runtime.PureRuntime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The host API end to end, as bootstrap's TestPureRuntime: register core.pdb and an in-memory module, build a
 * {@link PureRuntime}, put Pure source held in a string into the module, compile, execute a function it defines.
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
        Path shared = locateSharedDir();
        ModuleRegistry registry = new ModuleRegistry();
        PdbModule core = PdbModule.open(shared.resolve("core.pdb"));
        registry.register(core);
        // Parsing runs the Pure parser mappings and compiling runs compiler-pure: register them too.
        registry.register(PdbModule.open(shared.resolve("parser-mappings.pdb")));
        registry.register(PdbModule.open(shared.resolve("compiler.pdb")));
        registry.register(new InMemoryModule("sample", List.of(core.name())));
        registry.validate();

        PureRuntime runtime = PureRuntime.builder()
                .withRegistry(registry)
                .build();

        runtime.setSource("sample", "sample.pure", SOURCE);
        CompilationResult result = runtime.compile();
        if (result.errors().isEmpty())
        {
            System.out.println(
                    runtime.execute(
                            runtime.registry().getElement("sample::greet_String_1__String_1__String_1_"),
                            "Ada",
                            "Lovelace"
                    )
            );
        }
        runtime.close();
    }

    private static Path locateSharedDir()
    {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent())
        {
            if (Files.exists(dir.resolve("shared").resolve("core.pdb")))
            {
                return dir.resolve("shared");
            }
        }
        throw new IllegalStateException("Cannot locate shared/core.pdb walking up from " + Path.of("").toAbsolutePath());
    }
}
