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

package org.finos.legend.pure.platform.java.example;

import org.finos.legend.pure.m3.meta.pure.diagram.metamodel.Diagram;
import org.finos.legend.pure.platform.java.compiler.module.CompilationResult;
import org.finos.legend.pure.platform.java.compiler.module.ModuleRegistry;
import org.finos.legend.pure.platform.java.compiler.module.inMemoryModule.InMemoryModule;
import org.finos.legend.pure.platform.java.extensions.diagram.DiagramLanguage;
import org.finos.legend.pure.platform.java.grammar.PureLanguage;
import org.finos.legend.pure.platform.java.runtime.PureRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * EMBEDDING THE JAVA PLATFORM, end to end: name the languages, put Pure source held in a string into a
 * module of your own, compile it, and call what it defines.
 *
 * <p>Run it: {@code just java::usage-example}.
 *
 * <p>ONE FILE, TWO LANGUAGES. {@code ###Pure} is the language itself; {@code ###Diagram} comes from a
 * language extension (pure/extensions/diagram) and compiles to an element in the graph beside the
 * classes, its view pointing at the class the same compile produced.
 *
 * <p>THE LANGUAGES ARE NAMED HERE because an embedder is the only thing that knows which it wants, and
 * the order matters — {@code ###Pure} before anything is parsed. WHAT THEY NEED IS NOT: each language
 * declares its own modules in its manifest, and the runtime registers those plus this platform's
 * metamodel and translator. The only module named below is the one holding the source.
 *
 * <p>The same shape as the JavaScript platform's usage example, deliberately.
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

            function sample::greet(firstName:String[1], lastName:String[1]):String[1]
            {
                'Hello, ' + $firstName + ' ' + $lastName + '!'
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

    public static void main(String[] args) throws Exception
    {
        // 1. The only module this host owns: "sample" holds source rather than a prebuilt archive, and so
        //    is the only one `compile()` has anything to do.
        InMemoryModule sample = new InMemoryModule("sample", List.of("core"));
        sample.setSource("sample.pure", SOURCE);

        ModuleRegistry registry = new ModuleRegistry();
        registry.register(sample);

        // 2. The runtime, which brings the rest.
        PureRuntime runtime = PureRuntime.builder()
                .withRegistry(registry)
                .withLanguages(PureLanguage.all().newWithAll(DiagramLanguage.all()))
                .withModuleRoot(repoRoot())
                .build();
        try
        {
            // 3. Compile. Both sections: the classes and the function, and the diagram through the
            //    extension's own compiler passes.
            CompilationResult result = runtime.compile();
            if (!result.errors().isEmpty())
            {
                throw new IllegalStateException("compile failed: " + String.join("; ", result.errors()));
            }
            System.out.println("compiled " + result.elements().size() + " elements");

            // 4. Execute something it defines.
            Object greet = registry.getElement("sample::greet_String_1__String_1__String_1_");
            System.out.println(runtime.execute(greet, "Ada", "Lovelace"));

            // 5. And the diagram is in the graph beside them, a compiled element with its view resolved —
            //    not carried text.
            Diagram diagram = (Diagram) registry.getElement("sample::PersonDiagram");
            System.out.println("sample::PersonDiagram: " + diagram.typeViews().size() + " type view(s)");
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
