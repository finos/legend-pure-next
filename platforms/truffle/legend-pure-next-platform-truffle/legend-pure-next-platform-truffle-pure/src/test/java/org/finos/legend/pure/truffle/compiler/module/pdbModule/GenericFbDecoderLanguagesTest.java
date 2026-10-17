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

import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.truffle.grammar.Language;
import org.finos.legend.pure.truffle.grammar.PureLanguage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The decoder's schema is the registered languages' own, the always-active ones (m3, from
 * {@code ###Pure}) FIRST whatever the registration order, each language once.
 */
public class GenericFbDecoderLanguagesTest
{
    private static Language extension(String name, String fbs)
    {
        return new Language(name, name, Lists.immutable.empty(), null, null, null, fbs);
    }

    @Test
    public void noAlwaysActiveLanguageIsAnErrorNamingWithLanguages()
    {
        // Nothing registers ###Pure on the decoder's behalf any more: decoding before a runtime is built
        // is an error that says what to build, not a default.
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> GenericFbDecoder.schemaText(Lists.immutable.empty()));
        Assertions.assertTrue(e.getMessage().contains("withLanguages(...)"), e.getMessage());
    }

    @Test
    public void theAlwaysActiveSchemaComesFirst()
    {
        Assertions.assertTrue(GenericFbDecoder.schemaText(PureLanguage.all())
                .startsWith(PureLanguage.all().get(0).fbsText()));
    }

    @Test
    public void aLanguageRegisteredOnceStaysRegistered()
    {
        // Two runtimes with different languages in one JVM: the second does not unregister the first's,
        // so PDBs written under the first still decode. The schema only grows.
        GenericFbDecoder.registerLanguage(extension("kept-x", "table KeptXDef {}"));
        PureLanguage.all().forEach(GenericFbDecoder::registerLanguage);
        Assertions.assertTrue(GenericFbDecoder.schemaText().contains("table KeptXDef {}"));
    }

    @Test
    public void anExtensionRegisteredFirstStillFollowsM3()
    {
        GenericFbDecoder.registerLanguage(extension("first-x", "table FirstXDef {}"));
        PureLanguage.all().forEach(GenericFbDecoder::registerLanguage);
        String text = GenericFbDecoder.schemaText();
        Assertions.assertTrue(text.startsWith(PureLanguage.all().get(0).fbsText()), "m3 first");
        Assertions.assertTrue(text.contains("\ntable FirstXDef {}"));
    }

    @Test
    public void registeringALanguageTwiceAddsItOnce()
    {
        PureLanguage.all().forEach(GenericFbDecoder::registerLanguage);
        PureLanguage.all().forEach(GenericFbDecoder::registerLanguage);
        String text = GenericFbDecoder.schemaText();
        String m3 = PureLanguage.all().get(0).fbsText();
        Assertions.assertEquals(text.indexOf(m3), text.lastIndexOf(m3), "m3's tables appear once");
    }
}
