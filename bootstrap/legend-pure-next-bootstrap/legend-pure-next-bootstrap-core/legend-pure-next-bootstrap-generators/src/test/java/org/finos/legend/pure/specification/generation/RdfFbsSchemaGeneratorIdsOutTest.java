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

package org.finos.legend.pure.specification.generation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code --ids-out}: the registry a generation updates is written to an output, and the tracked registry is left as it
 * was — what a build tool that never writes its sources needs. Without it the tracked registry is updated in place, as
 * the Maven build expects.
 */
public class RdfFbsSchemaGeneratorIdsOutTest
{
    private static final String DROPPED = "ArrowInvocationDef.func=";

    /** The tracked registry, without the `func` entry of ArrowInvocationDef, so a generation has an id to assign. */
    private static Path registryMissingAnEntry(Path dir) throws IOException
    {
        List<String> lines = Files.readAllLines(specification().resolve("m3-fbs-field-ids.txt"), StandardCharsets.UTF_8);
        Path registry = dir.resolve("ids.txt");
        Files.write(registry, org.eclipse.collections.impl.factory.Lists.adapt(lines).reject(l -> l.startsWith(DROPPED)), StandardCharsets.UTF_8);
        return registry;
    }

    @Test
    public void theUpdatedRegistryGoesToTheOutputAndTheTrackedOneIsUntouched(@TempDir Path dir) throws IOException
    {
        Path registry = registryMissingAnEntry(dir);
        byte[] before = Files.readAllBytes(registry);
        Path out = dir.resolve("ids-out.txt");
        RdfFbsSchemaGenerator.main(new String[]{specification().resolve("m3.ttl").toString(), dir.resolve("fbs").toString(),
                "--ids", registry.toString(), "--ids-out", out.toString()});
        Assertions.assertArrayEquals(before, Files.readAllBytes(registry));
        Assertions.assertTrue(Files.readString(out, StandardCharsets.UTF_8).contains(DROPPED), "the output holds the id assigned");
    }

    @Test
    public void withoutAnOutputTheRegistryIsUpdatedInPlace(@TempDir Path dir) throws IOException
    {
        Path registry = registryMissingAnEntry(dir);
        RdfFbsSchemaGenerator.main(new String[]{specification().resolve("m3.ttl").toString(), dir.resolve("fbs").toString(),
                "--ids", registry.toString()});
        Assertions.assertTrue(Files.readString(registry, StandardCharsets.UTF_8).contains(DROPPED), "the registry holds the id assigned");
    }

    /** pure/specification, above the working directory. */
    private static Path specification()
    {
        return specificationAbove(Path.of("").toAbsolutePath());
    }

    private static Path specificationAbove(Path dir)
    {
        if (dir == null)
        {
            throw new IllegalStateException("no 'pure/specification/m3.ttl' above the working directory");
        }
        Path spec = dir.resolve("pure").resolve("specification");
        return Files.isRegularFile(spec.resolve("m3.ttl")) ? spec : specificationAbove(dir.getParent());
    }
}
