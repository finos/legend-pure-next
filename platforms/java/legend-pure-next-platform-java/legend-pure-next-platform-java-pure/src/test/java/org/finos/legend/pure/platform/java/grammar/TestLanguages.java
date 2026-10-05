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

package org.finos.legend.pure.platform.java.grammar;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The languages are READ from their manifests and their grammars resolved by reflection, so what javac
 * used to catch now has to be caught here: these pin both what a good manifest yields and what a bad
 * one says. Without them, a manifest naming a class or rule that does not exist would surface as a
 * section that mysteriously stops parsing.
 *
 * <p>The engine's own languages are installed in a static block, because the natives read the installed
 * set and nothing else in this module installs it. No extension is installed, which is what lets the
 * Top-belongs-to-no-language check mean something.</p>
 */
class TestLanguages
{
    /** Installed once so the natives can reach them; the engine's own languages, no extension. */
    static
    {
        org.finos.legend.pure.platform.java.runtime.PureRuntime.installLanguages(PureLanguage.all());
    }

    private static final String GRAMMAR_PACKAGE = "org.finos.legend.pure.platform.java.grammar.m3";

    @Test
    void theEnginesOwnManifestsAreReadInRegistrationOrder()
    {
        assertEquals(Lists.immutable.of("Pure", "compiler test sections"),
                PureLanguage.all().collect(Language::name),
                "###Pure must come first: nothing can be parsed before it is registered");
    }

    @Test
    void thePureLanguageDeclaresM3AndIsBundled()
    {
        Language pure = PureLanguage.all().get(0);
        // `bundled` is why its section parser is never guarded into silence: ###Pure is not optional.
        assertTrue(pure.bundled(), "the Pure language is bundled");
        assertNull(pure.module(), "it has no module to be guarded on");
        assertEquals("meta::pure::parser::mappings::interpreter::pureSectionParser__Pair_1_", pure.sectionParsers());
        assertNull(pure.compilerExtension(), "the built-in passes compile ###Pure");
        assertNotNull(pure.grammar(), "M3 comes from the manifest, not from a literal in the host");
        assertEquals("M3Parser", pure.grammar().grammarName());
    }

    @Test
    void theTestSectionsAddNoSyntaxAndAreGuardedOnTheirModule()
    {
        Language tests = PureLanguage.all().get(1);
        assertEquals("compiler-tests", tests.module(), "guarded on this module being registered");
        assertTrue(!tests.bundled(), "an unbundled language stays inert until its module arrives");
        assertNull(tests.grammar(), "its sections are parsed by Pure functions, not by ANTLR");
    }

    @Test
    void topIsRegisteredButBelongsToNoLanguage()
    {
        // Top frames a document into ###Section blocks before any language is consulted — ###Diagram
        // goes through it too — so it is the engine's, and no manifest may claim it.
        assertTrue(PureLanguage.all().noneSatisfy(
                        (l) -> l.grammar() != null && "TopParser".equals(l.grammar().grammarName())),
                "no language may declare the Top grammar");
        assertNotNull(Antlr.parseAntlr("###Pure\nClass a::B{}", "TopParser", "x.pure", 0L));
    }

    @Test
    void aGrammarClassThatIsNotOnTheClassPathNamesItself()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Language.fromResources(Lists.immutable.of("languages/language_pure.json"), "no.such.package"));
        assertTrue(e.getMessage().contains("no.such.package.M3Lexer"), e.getMessage());
        assertTrue(e.getMessage().contains("Pure"), "it says which language: " + e.getMessage());
    }

    @Test
    void aMissingManifestSaysWhichResourceAndWhy()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Language.fromResources(Lists.immutable.of("languages/language_nope.json"), GRAMMAR_PACKAGE));
        assertTrue(e.getMessage().contains("languages/language_nope.json"), e.getMessage());
        assertTrue(e.getMessage().contains("stage"), "it points at the pom: " + e.getMessage());
    }
}
