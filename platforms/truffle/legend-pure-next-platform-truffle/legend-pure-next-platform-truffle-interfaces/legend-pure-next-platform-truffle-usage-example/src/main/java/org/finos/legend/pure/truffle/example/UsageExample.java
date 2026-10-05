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

package org.finos.legend.pure.truffle.example;

import org.finos.legend.pure.truffle.compiler.module.CompilationResult;
import org.finos.legend.pure.truffle.compiler.module.ModuleRegistry;
import org.finos.legend.pure.truffle.compiler.module.inMemoryModule.InMemoryModule;
import org.finos.legend.pure.truffle.extensions.diagram.DiagramLanguage;
import org.finos.legend.pure.truffle.grammar.PureLanguage;
import org.finos.legend.pure.truffle.runtime.PureRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * EMBEDDING THE TRUFFLE PLATFORM, end to end: name the language, put Pure source held in a string into a
 * module of your own, compile it, and call what it defines.
 *
 * <p>Run it: {@code just truffle::usage-example}.
 *
 * <p>THE LANGUAGE IS NAMED HERE because an embedder is the only thing that knows which it wants. WHAT IT
 * NEEDS IS NOT: {@code language_pure.json} declares core.pdb, parser-mappings.pdb and compiler.pdb — the
 * modules {@code ###Pure} means on any host — and the runtime registers them. The only module named below
 * is the one holding the source. This example used to list those three itself.
 *
 * <p>ONE FILE, TWO LANGUAGES. {@code ###Pure} is the language itself; {@code ###Diagram} comes from a
 * language extension (pure/extensions/diagram) and compiles to an element in the graph beside the
 * classes. Until this platform had a generated Diagram parser that section failed with "Unknown grammar:
 * DiagramParser" — the reason the extension's own tests passed on JavaScript and Java but not here.
 *
 * <p>This platform adds no modules of its own: it runs Pure directly rather than translating it, so there
 * is no counterpart to the Java and JavaScript platforms' PlatformModules.
 */
public final class UsageExample
{
    private static final String SOURCE = """
            ###Pure
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

            ###Diagram
            Diagram sample::PersonDiagram
            {
               TypeView tv_person(type=sample::Person, position=(100.0, 20.0), width=200.0, height=80.0)
            }
            """;

    private UsageExample()
    {
    }

    public static void main(String[] args)
    {
        // 1. The runtime, which registers what the language declares.
        ModuleRegistry registry = new ModuleRegistry();
        PureRuntime runtime = PureRuntime.builder()
                .withRegistry(registry)
                .withLanguages(PureLanguage.all().newWithAll(DiagramLanguage.all()))
                .withModuleRoot(repoRoot())
                .build();
        try
        {
            // 2. Then the only module this host owns: "sample" holds source rather than a prebuilt
            //    archive, and so is the only one `compile()` has anything to do. AFTER the runtime,
            //    because this platform's registry checks a module's dependencies as it is registered —
            //    "core" has to be there already. (The Java platform defers that to validate(), so its
            //    example registers its module first; the order here is this registry's, not the shape's.)
            InMemoryModule sample = new InMemoryModule("sample", List.of("core"));
            sample.setSource("sample.pure", SOURCE);
            registry.register(sample);

            // 3. Compile, then 4. call what it defines.
            CompilationResult result = runtime.compile();
            if (!result.errors().isEmpty())
            {
                throw new IllegalStateException("compile failed: " + String.join("; ", result.errors()));
            }
            System.out.println("compiled " + result.elements().size() + " elements");

            Object greet = registry.getElement("sample::greet_String_1__String_1__String_1_");
            System.out.println(runtime.execute(greet, "Ada", "Lovelace"));

            // 5. And the diagram is in the graph beside them, a compiled element rather than carried text.
            Object diagram = registry.getElement("sample::PersonDiagram");
            System.out.println("sample::PersonDiagram compiled: " + (diagram != null));
        }
        finally
        {
            runtime.close();
        }
    }

    /**
     * The repository root, found by walking up for shared/core.pdb. The paths a manifest carries are
     * repo-relative, because they name modules each subproject builds into its own build/ — so something
     * has to anchor them, and an embedder would anchor them at its own installation.
     */
    private static Path repoRoot()
    {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent())
        {
            if (Files.exists(dir.resolve("shared").resolve("core.pdb")))
            {
                return dir;
            }
        }
        throw new IllegalStateException("Cannot locate shared/core.pdb from " + Path.of("").toAbsolutePath());
    }
}
