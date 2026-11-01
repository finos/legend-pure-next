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
package org.finos.legend.pure.truffle.extensions.relation;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.finos.legend.pure.truffle.grammar.Language;

/**
 * The relation functions on this platform, for a composition root to install.
 *
 * <p>What this module adds to the language's manifest ({@code language_relation.json}, shared with every
 * other platform) is this platform's implementation of its natives: {@link RelationNatives}.</p>
 */
public final class RelationLanguage
{
    private static final String MANIFEST = "languages/language_relation.json";

    private RelationLanguage()
    {
    }

    public static ImmutableList<Language> all()
    {
        return Language.fromResources(Lists.immutable.of(MANIFEST), RelationLanguage.class.getPackageName())
                .collect(language -> language.withNatives(RelationNatives::registerAll));
    }
}
