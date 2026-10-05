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

package org.finos.legend.pure.platform.java.runtime;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * The Pure modules THIS PLATFORM brings, as opposed to the ones the language needs.
 *
 * <p>The distinction matters because the two lists have different owners. A language's list comes from
 * its own manifest — {@code Language.pdbs()}, which for {@code ###Pure} is core.pdb and compiler.pdb:
 * what the language MEANS, on any host. The three below are the Java metamodel and the Pure → Java
 * translator, which are what makes Pure EXECUTABLE here rather than merely compilable; a JavaScript or
 * Truffle host brings its own instead. A language specification must not name one platform's
 * translator.</p>
 *
 * <p>The JavaScript platform keeps the same split in its own PlatformModules.js, for the same reason.</p>
 */
public final class PlatformModules
{
    /** Repo-relative, like a language's own list: each module writes into its own build/. */
    public static final ImmutableList<String> PDBS = Lists.immutable.of(
            "pure/modules/language/java/build/java.pdb",                      // the Java metamodel
            "pure/modules/translation/shared/build/translation-shared.pdb",   // shared canonicalisation
            "pure/modules/translation/java/build/java-translation.pdb");      // Pure -> Java

    private PlatformModules()
    {
    }
}
