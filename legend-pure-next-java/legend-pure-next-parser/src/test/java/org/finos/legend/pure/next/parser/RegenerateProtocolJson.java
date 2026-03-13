// Copyright 2024 Goldman Sachs
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
import org.finos.legend.pure.next.parser.m3.PureLanguageParser;
import org.finos.legend.pure.next.parser.topLevel.TopLevelProtocolBuilder;
import org.finos.legend.pure.next.parser.topLevel.TopLevelProtocolJsonSerializer;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.stream.Stream;

/**
 * Utility to regenerate all protocol.json files from grammar.pure files.
 *
 * <p>Usage: Run the main method to re-generate all protocol.json files
 * under src/test/resources/tests/ using the current parser and serializer.
 */
public class RegenerateProtocolJson
{
    private static final String TESTS_ROOT = "specification/grammar/tests/";
    private static final String GRAMMAR_FILE = "grammar.pure";
    private static final String PROTOCOL_FILE = "protocol.json";

    public static void main(final String[] args) throws Exception
    {
        ClassLoader cl = RegenerateProtocolJson.class.getClassLoader();
        java.net.URL baseUrl = cl.getResource(TESTS_ROOT);
        if (baseUrl == null)
        {
            System.err.println("Cannot find tests root: " + TESTS_ROOT);
            return;
        }

        // Find the source resources directory
        Path sourceResourcesDir = findSourceResourcesDir();
        if (sourceResourcesDir == null)
        {
            System.err.println("Cannot find source resources directory");
            return;
        }

        URI baseUri = baseUrl.toURI();
        Path rootDir;
        FileSystem jarFs = null;

        if ("jar".equals(baseUri.getScheme()))
        {
            String[] parts = baseUri.toString().split("!");
            jarFs = FileSystems.newFileSystem(
                    URI.create(parts[0]), Collections.emptyMap());
            rootDir = jarFs.getPath(parts[1]);
        }
        else
        {
            rootDir = Paths.get(baseUri);
        }


        TopLevelProtocolJsonSerializer jsonSerializer =
                new TopLevelProtocolJsonSerializer();

        try (Stream<Path> walk = Files.walk(rootDir))
        {
            walk.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(GRAMMAR_FILE))
                            && Files.exists(dir.resolve(PROTOCOL_FILE)))
                    .sorted()
                    .forEach(dir ->
                    {
                        try
                        {
                            String relative = rootDir.relativize(dir)
                                    .toString().replace('\\', '/');
                            if (relative.isEmpty())
                            {
                                return;
                            }

                            // Read grammar.pure
                            String pureSource = Files.readString(
                                    dir.resolve(GRAMMAR_FILE));

                            // Parse and serialize to JSON
                            meta.pure.protocol.PureFile pureFile =
                                    TopLevelProtocolBuilder.parse(pureSource, "testFile", Lists.mutable.with(new PureLanguageParser()));
                            String json = jsonSerializer.serialize(pureFile);

                            // Write to source directory
                            Path targetFile = sourceResourcesDir
                                    .resolve(TESTS_ROOT)
                                    .resolve(relative)
                                    .resolve(PROTOCOL_FILE);
                            Files.writeString(targetFile, json + "\n");

                            System.out.println("Regenerated: " + relative
                                    + "/" + PROTOCOL_FILE);
                        }
                        catch (Exception e)
                        {
                            System.err.println("Error processing " + dir
                                    + ": " + e.getMessage());
                            e.printStackTrace();
                        }
                    });
        }
        finally
        {
            if (jarFs != null)
            {
                jarFs.close();
            }
        }

        System.out.println("\nDone. Rebuild to pick up changes.");
    }

    private static Path findSourceResourcesDir()
    {
        // Walk up from the specification target to find source dir
        Path specResources = Paths.get(
                "legend-pure-next-specification/src/main/resources");
        if (Files.isDirectory(specResources))
        {
            return specResources;
        }

        // Try from project root
        Path fromRoot = Paths.get(
                "../legend-pure-next-specification/src/main/resources");
        if (Files.isDirectory(fromRoot))
        {
            return fromRoot;
        }

        return null;
    }
}
