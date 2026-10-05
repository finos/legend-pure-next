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
import org.eclipse.collections.api.list.ImmutableList;

/**
 * The Pure language and the compiler's test sections, for a composition root to install.
 *
 * <p>The exact counterpart of an extension's own accessor ({@code DiagramLanguage.all()}), and it
 * carries the same two host facts: where this module staged the manifests, and where ANTLR put the
 * parser they name. Everything else — the grammar's entry rule, the Pure element that parses a section —
 * is read from {@code language_pure.json} and {@code language_test.json}.</p>
 *
 * <p>ORDER IS REGISTRATION ORDER, and {@code ###Pure} is first because nothing can be parsed before it.
 * That is why this returns a list rather than a set.</p>
 */
public final class PureLanguage
{
    private static final ImmutableList<String> MANIFESTS =
            Lists.immutable.of("languages/language_pure.json", "languages/language_test.json");

    /**
     * Where ANTLR puts the parser the manifest names. The pom stages the shared grammars keeping their
     * {@code m3/} sub-directory, so {@code M3Lexer} and {@code M3Parser} land one package below this one.
     */
    private static final String GRAMMAR_PACKAGE = "org.finos.legend.pure.platform.java.grammar.m3";

    private PureLanguage()
    {
    }

    /** The languages this module owns, for {@code PureRuntime.installLanguages}. */
    public static ImmutableList<Language> all()
    {
        return Language.fromResources(MANIFESTS, GRAMMAR_PACKAGE);
    }
}
