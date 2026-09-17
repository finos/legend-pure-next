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

package org.finos.legend.pure.runtime;

import meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.ModuleRegistry;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.pdbModule.PdbModule;
import org.finos.legend.pure.m3.module.sourceModule.PureContent;
import org.finos.legend.pure.m3.module.inMemoryModule.InMemoryModule;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import org.finos.legend.pure.execution.InterpretedExecution;
import org.finos.legend.pure.runtime.compilation.JavaCompilation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host API end to end: register core.pdb and an in-memory module, build a {@link PureRuntime},
 * put Pure source held in a string into the module, compile, execute a function it defines — then
 * modify the code, recompile and execute again.
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
        ModuleRegistry registry = new ModuleRegistry();
        PdbModule core = PdbModule.open(BootstrapModule.locateCorePdb());
        registry.register(core);
        registry.register(new InMemoryModule("sample", List.of(core.name())));
        registry.validate();

        PureRuntime runtime = PureRuntime.builder()
                .withRegistry(registry)
                .withCompilation(JavaCompilation.builder())
                .withExecution(InterpretedExecution.builder())
                .build();

        runtime.setSource("sample", "sample.pure", SOURCE);
        CompilationResult result = runtime.compile();
        if (result.errors().isEmpty())
        {
            System.out.println(
                    runtime.execute(
                            (FunctionDefinition) runtime.registry().getElement("sample::greet_String_1__String_1__String_1_"),
                            "Ada",
                            "Lovelace"
                    )
            );
        }
    }
}
