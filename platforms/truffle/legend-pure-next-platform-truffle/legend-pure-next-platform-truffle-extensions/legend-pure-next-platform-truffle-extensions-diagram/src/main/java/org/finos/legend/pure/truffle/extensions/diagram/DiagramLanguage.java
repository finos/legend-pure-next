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

package org.finos.legend.pure.truffle.extensions.diagram;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.finos.legend.pure.truffle.grammar.Language;

/**
 * The {@code ###Diagram} language, for a composition root to install.
 *
 * <p>Two facts, and both are this module's rather than the language's: WHERE its manifest was staged, and
 * WHERE this module's pom generates its parser. Everything else — the grammar's name and entry rule, the
 * module carrying its Pure code, the elements that parse a diagram section and supply its compiler passes
 * — is read from {@code language_diagram.json}, which belongs to the extension and is shared with every
 * other platform.</p>
 */
public final class DiagramLanguage
{
    private static final String MANIFEST = "languages/language_diagram.json";

    /** The package this module's pom generates {@code DiagramLexer} and {@code DiagramParser} into. */
    private static final String GRAMMAR_PACKAGE = "org.finos.legend.pure.truffle.extensions.diagram";

    private DiagramLanguage()
    {
    }

    /** The language, for {@code PureRuntime.Builder.withLanguages}. */
    public static ImmutableList<Language> all()
    {
        return Language.fromResources(Lists.immutable.of(MANIFEST), GRAMMAR_PACKAGE);
    }
}
