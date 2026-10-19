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

import org.eclipse.collections.api.list.ImmutableList;
import org.finos.legend.pure.m3.PdbTypes;
import org.finos.legend.pure.platform.java.extensions.tds.TdsLanguage;
import org.finos.legend.pure.platform.java.grammar.Language;
import org.finos.legend.pure.platform.java.grammar.PureLanguage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Function;

/**
 * A {@code #TDS{…}#} value — the TDS extension's value specification, held by m3's unions as an
 * ExtensionValueDef — reads back with this platform's decoder as a TDS: its csv as written, its relation
 * type's columns with their types. The archive is the TDS golden of the typedHeader fixture; the schema
 * fragment and the table map come with the tds language (language_tds.json, TdsPdbTypes).
 */
public class TdsReadTest
{
    private static final String GOLDEN = "pure/extensions/pure/tds/tests/compiler-pdb-serialization/tds/typedHeader.pdb";
    private static final String FUNCTION = "test::tds::typedHeader__Any_MANY_";

    @Test
    public void aTdsReadsBackAsTheRelationItCompiledTo() throws Exception
    {
        ImmutableList<Language> languages = PureLanguage.all().newWithAll(TdsLanguage.all());
        PdbRuntime runtime = PdbRuntime.open(Language.schemaText(languages, null), languages
                .select(l -> l.types() != null)
                .collect(Language::types)
                .injectInto(PdbTypes::create, (Function<String, LazyObject> chain, Function<String, LazyObject> next) -> t -> chain.apply(t) != null ? chain.apply(t) : next.apply(t)),
                repoRoot().resolve(GOLDEN));
        LazyObject tds = (LazyObject) ((LazyObject) runtime.element(FUNCTION)).__values("expressionSequence").get(0);
        Assertions.assertEquals("platform::extensions::pure::tds::metamodel::TDS", tds._purePath());
        Assertions.assertTrue(((String) tds.__values("csv").get(0)).startsWith("id, name, amount\n"), "csv starts with the column names");
        LazyObject relation = (LazyObject) ((LazyObject) ((LazyObject) tds.__values("genericType").get(0)).__values("typeArguments").get(0)).__values("type").get(0);
        List<Object> names = new java.util.ArrayList<>();
        for (Object column : relation.__values("columns"))
        {
            names.add(((LazyObject) column).__values("name").get(0));
        }
        Assertions.assertEquals(List.of("id", "name", "amount"), names);
    }

    @Test
    public void aTdsWithoutItsLanguageFailsNamingTheTable() throws Exception
    {
        PdbRuntime runtime = PdbRuntime.open(Language.schemaText(PureLanguage.all().select(l -> "Pure".equals(l.name())), null),
                PdbTypes::create, repoRoot().resolve(GOLDEN));
        LazyObject function = (LazyObject) runtime.element(FUNCTION);
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> ((LazyObject) function.__values("expressionSequence").get(0)).__values("csv"));
        Assertions.assertTrue(e.getMessage().contains("no schema for TDSDef"), e.getMessage());
    }

    private static Path repoRoot()
    {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(GOLDEN)))
        {
            p = p.getParent();
        }
        Assertions.assertNotNull(p, "repo root not found above " + Paths.get("").toAbsolutePath());
        return p;
    }
}
