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

package org.finos.legend.pure.truffle.compiler.module.pdbModule;

import org.finos.legend.pure.truffle.compiler.helper._Any;
import org.finos.legend.pure.truffle.compiler.module.ModuleRegistry;
import org.finos.legend.pure.truffle.execution.natives.NativeRegistry;
import org.finos.legend.pure.truffle.grammar.PureLanguage;
import org.finos.legend.pure.truffle.runtime.PureRuntime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An island value — a language extension's value specification, which m3's unions hold as an
 * ExtensionValueDef — reads back with this host's decoder as an instance of its own class, its pointers
 * resolved like any other. The archive is the specification's island golden for
 * {@code #REF{m::Person}#} (pure/specification/compiler/tests-pdb-serialization/valueSpecification/island);
 * its schema fragment comes with the test language (language_test.json).
 */
public class IslandValueReadTest
{
    private static ModuleRegistry registry;

    @BeforeAll
    static void load() throws IOException
    {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("shared")) || !Files.exists(root.resolve("shared").resolve("compiler-tests.pdb")))
        {
            root = root.getParent();
        }
        Path shared = root.resolve("shared");
        PdbModule core = new PdbModule(shared.resolve("core.pdb"));
        PdbModule compiler = new PdbModule(shared.resolve("compiler.pdb"));
        PdbModule compilerTests = new PdbModule(shared.resolve("compiler-tests.pdb"));
        PdbModule golden = new PdbModule(root.resolve("pure/specification/compiler/tests-pdb-serialization/valueSpecification/island/ref.pdb"));
        registry = new ModuleRegistry();
        registry.register(core);
        registry.register(compiler);
        registry.register(compilerTests);
        registry.register(golden);
        core.setResolver(registry);
        compiler.setResolver(registry);
        compilerTests.setResolver(registry);
        golden.setResolver(registry);
        PureRuntime.builder().withRegistry(registry).withLanguages(PureLanguage.all()).withNatives(NativeRegistry.createDefault()).build();
    }

    @Test
    public void anIslandValueReadsBackAsAnInstanceOfItsClass()
    {
        Object function = registry.getElement("test::island::ref__Any_MANY_");
        Object body = _Any.read(function, "expressionSequence");
        Object first = body instanceof org.finos.legend.pure.truffle.execution.types.PureSequence ? ((org.finos.legend.pure.truffle.execution.types.PureSequence) body).getBoxed(0) : body;
        Assertions.assertTrue(_Any.isType(first, "platform::pure::compiler::test::islands::metamodel::RefLiteral", registry),
                "the first expression is the island value: " + first);
        Object target = _Any.read(first, "target");
        Assertions.assertEquals("Person", _Any.read(target, "name"), "the RefLiteral points at the class m::Person");
    }
}
