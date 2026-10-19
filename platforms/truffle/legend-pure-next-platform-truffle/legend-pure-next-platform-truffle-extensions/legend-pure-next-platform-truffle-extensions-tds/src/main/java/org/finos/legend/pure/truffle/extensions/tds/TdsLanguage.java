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

package org.finos.legend.pure.truffle.extensions.tds;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.finos.legend.pure.truffle.grammar.Language;

/**
 * The {@code #TDS{…}#} island of the Pure language, for a composition root to install.
 *
 * <p>One fact is this module's rather than the language's: WHERE its manifest was staged. Everything else —
 * the module carrying its Pure code, the compiler extension whose island handler compiles a TDS, the schema
 * fragment its values are stored with — is read from {@code language_tds.json}, shared with every other
 * platform. It has no grammar: M3 parses a TDS header.</p>
 */
public final class TdsLanguage
{
    private static final String MANIFEST = "languages/language_tds.json";

    private TdsLanguage()
    {
    }

    /** The language, for {@code PureRuntime.Builder.withLanguages}. */
    public static ImmutableList<Language> all()
    {
        return Language.fromResources(Lists.immutable.of(MANIFEST), TdsLanguage.class.getPackageName());
    }
}
