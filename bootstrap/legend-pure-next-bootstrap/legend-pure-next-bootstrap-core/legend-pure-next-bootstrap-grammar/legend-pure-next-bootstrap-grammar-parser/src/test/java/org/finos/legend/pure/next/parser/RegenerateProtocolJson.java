// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.next.parser;

import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.next.parser.pureLanguage.PureLanguageParser;
import org.finos.legend.pure.next.parser.topLevel.TopLevelParser;
import org.finos.legend.pure.next.parser.topLevel.TopLevelProtocolJsonSerializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Re-mints every {@code protocol.json} in the grammar corpus from its {@code grammar.pure}.
 *
 * <p>The goldens are DERIVED, not authored: this parser plus {@link TopLevelProtocolJsonSerializer} is
 * their only source, and {@link PureToJsonRoundtripTest} then pins the parser against them. So a
 * deliberate protocol change is made here and re-minted — never hand-edited, because the same literal
 * text means different things in different slots (a bare {@code "Integer"} can be an author-written
 * annotation that must stay bare, or a literal's type that must not).</p>
 *
 * <p>Run it from anywhere in the checkout:
 * {@code java -cp <parser classes>:<test classes>:<deps> org.finos.legend.pure.next.parser.RegenerateProtocolJson},
 * then review the diff and run {@code just bootstrap::test}.</p>
 *
 * <p>Locates the corpus by WALKING UP from the working directory, exactly as
 * {@link PureToJsonRoundtripTest} does. It previously resolved {@code pure/specification/grammar/tests/}
 * as a CLASSPATH RESOURCE, which no module stages any more — so it had been failing with "Cannot find
 * tests root" and quietly regenerating nothing.</p>
 */
public class RegenerateProtocolJson
{
    private static final String GRAMMAR_FILE = "grammar.pure";
    private static final String PROTOCOL_FILE = "protocol.json";

    public static void main(String[] args) throws IOException
    {
        Path root = locateTestsRoot();
        TopLevelProtocolJsonSerializer serializer = new TopLevelProtocolJsonSerializer();

        List<Path> fixtures;
        try (Stream<Path> walk = Files.walk(root))
        {
            // Only directories that ALREADY hold both files: a grammar.pure with no golden beside it is
            // an error-path fixture (grammar_error) or a comparison target, not a corpus entry.
            fixtures = walk.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve(GRAMMAR_FILE)) && Files.exists(d.resolve(PROTOCOL_FILE)))
                    .sorted()
                    .toList();
        }

        int written = 0;
        for (Path dir : fixtures)
        {
            String relative = root.relativize(dir).toString().replace('\\', '/');
            try
            {
                meta.pure.protocol.PureFile parsed = TopLevelParser.parse(
                        Files.readString(dir.resolve(GRAMMAR_FILE)), "testFile", Lists.mutable.with(new PureLanguageParser()));
                // No trailing newline: that is how the corpus is committed, and adding one would put all 59
                // files in the diff of any change that touches one of them.
                Files.writeString(dir.resolve(PROTOCOL_FILE), serializer.serialize(parsed));
                written++;
            }
            catch (Exception e)
            {
                // Keep going: one unparseable fixture must not leave the rest of the corpus half-minted.
                System.err.println("FAILED " + relative + ": " + e);
            }
        }
        System.out.println("Regenerated " + written + " / " + fixtures.size() + " protocol.json files under " + root);
    }

    /** Walk up from the working directory until {@code pure/specification/grammar/tests} is found. */
    private static Path locateTestsRoot()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("pure").resolve("specification").resolve("grammar").resolve("tests");
            if (Files.isDirectory(candidate))
            {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate pure/specification/grammar/tests by walking up from "
                + Path.of("").toAbsolutePath());
    }
}
