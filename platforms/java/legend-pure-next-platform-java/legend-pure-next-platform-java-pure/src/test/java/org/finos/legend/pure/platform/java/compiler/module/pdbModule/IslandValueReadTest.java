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

package org.finos.legend.pure.platform.java.compiler.module.pdbModule;

import org.finos.legend.pure.m3.PdbTypes;
import org.finos.legend.pure.platform.java.grammar.Language;
import org.finos.legend.pure.platform.java.grammar.PureLanguage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * An island value — a language extension's value specification, held by m3's unions as an ExtensionValueDef
 * — reads back with this platform's decoder as an object of its own class, its pointers resolved like any
 * other. The archive is the specification's island golden for {@code #REF{m::Person}#}; the schema
 * fragment comes with the test language (language_test.json).
 */
public class IslandValueReadTest
{
    private static final String REF = "pure/specification/compiler/tests-pdb-serialization/valueSpecification/island/ref.pdb";

    @Test
    public void anIslandValueReadsBackAsAnObjectOfItsClass() throws Exception
    {
        // m3's table map, then the test language's (IslandTestPdbTypes), as PureRuntime chains them.
        PdbRuntime runtime = PdbRuntime.open(Language.schemaText(PureLanguage.all(), null), PureLanguage.all()
                .select(l -> l.types() != null)
                .collect(Language::types)
                .injectInto(PdbTypes::create, (java.util.function.Function<String, LazyObject> chain, java.util.function.Function<String, LazyObject> next) -> t -> chain.apply(t) != null ? chain.apply(t) : next.apply(t)),
                repoRoot().resolve(REF));
        LazyObject function = (LazyObject) runtime.element("test::island::ref__Any_MANY_");
        LazyObject value = (LazyObject) function.__values("expressionSequence").get(0);
        Assertions.assertEquals("platform::pure::compiler::test::islands::metamodel::RefLiteral", value._purePath());
        LazyObject target = (LazyObject) value.__values("target").get(0);
        Assertions.assertEquals(java.util.List.of("Person"), target.__values("name"), "the RefLiteral points at the class m::Person");
        // Decoded again (runtime.read bypasses the element's property cache): the same object, as for every
        // other node — a blob is re-sliced on each read, so only the decoder can keep it one object.
        Assertions.assertSame(runtime.read(function, "expressionSequence").get(0), runtime.read(function, "expressionSequence").get(0));
    }

    @Test
    public void anIslandValueWithoutItsLanguageFailsNamingTheTable() throws Exception
    {
        PdbRuntime runtime = PdbRuntime.open(Language.schemaText(PureLanguage.all().select(l -> "Pure".equals(l.name())), null),
                PdbTypes::create, repoRoot().resolve(REF));
        LazyObject function = (LazyObject) runtime.element("test::island::ref__Any_MANY_");
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> ((LazyObject) function.__values("expressionSequence").get(0)).__values("target"));
        Assertions.assertTrue(e.getMessage().contains("no schema for RefLiteralDef"), e.getMessage());
    }

    private static Path repoRoot()
    {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(REF)))
        {
            p = p.getParent();
        }
        Assertions.assertNotNull(p, "repo root not found above " + Paths.get("").toAbsolutePath());
        return p;
    }
}
