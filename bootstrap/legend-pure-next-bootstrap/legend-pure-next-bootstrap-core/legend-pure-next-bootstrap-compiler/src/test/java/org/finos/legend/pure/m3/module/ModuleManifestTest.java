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

package org.finos.legend.pure.m3.module;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

/**
 * The manifest's two shapes: {@code module.json} on disk, which may carry
 * {@code testDependencies}, and the {@code manifest} section inside a {@code .pdb}, which
 * must not.
 *
 * <p>That asymmetry is load-bearing. The archive section is read by four independent
 * implementations — this one, Truffle's duplicate, the JavaScript host, and the self-hosted
 * Pure writer's {@code manifestJson} — and is byte-compared by 149 PDB goldens that may only
 * be re-minted from the flatc-anchored reference. Emitting a fourth key would break all of
 * them at once, so {@link ModuleManifest#toJson} excluding it is pinned here rather than
 * left to a comment.</p>
 */
public class ModuleManifestTest
{
    private static final String JSON_WITH_TEST_DEPS = """
            {
              "name": "javascript-translation",
              "packagePattern": "(meta::external)(::.*)?",
              "dependencies": ["core", "javascript"],
              "testDependencies": ["core-tests"]
            }
            """;

    @Test
    public void parsesTestDependencies()
    {
        ModuleManifest m = ModuleManifest.parse(JSON_WITH_TEST_DEPS);
        assertEquals("javascript-translation", m.name());
        assertEquals(List.of("core", "javascript"), m.dependencies());
        assertEquals(List.of("core-tests"), m.testDependencies());
    }

    @Test
    public void testDependenciesDefaultToEmptyWhenAbsent()
    {
        ModuleManifest m = ModuleManifest.parse(
                "{\"name\": \"core\", \"packagePattern\": \"*\", \"dependencies\": []}");
        assertEquals(List.of(), m.testDependencies());
        assertEquals(List.of(), m.allDependencies());
    }

    @Test
    public void allDependenciesIsTheUnionWithoutDuplicates()
    {
        ModuleManifest m = new ModuleManifest("m", "*", List.of("core", "x"), List.of("core-tests", "core"));
        assertEquals(List.of("core", "x", "core-tests"), m.allDependencies());
    }

    /** The archive section must stay three keys, whatever the source manifest carried. */
    @Test
    public void toJsonNeverEmitsTestDependencies()
    {
        String json = ModuleManifest.parse(JSON_WITH_TEST_DEPS).toJson();
        assertFalse(json.contains("testDependencies"), json);
        assertTrue(json.contains("\"dependencies\": [\"core\", \"javascript\"]"), json);
    }

    /** So a manifest round-tripped through the archive form loses it — by design. */
    @Test
    public void archiveRoundTripDropsTestDependencies()
    {
        ModuleManifest source = ModuleManifest.parse(JSON_WITH_TEST_DEPS);
        ModuleManifest fromArchive = ModuleManifest.parse(source.toJson());
        assertEquals(List.of(), fromArchive.testDependencies());
        assertEquals(source.dependencies(), fromArchive.dependencies());
    }

    /** An archive holding both halves declares the union as ordinary dependencies. */
    @Test
    public void withAllDependenciesFlattensForANonSplitArchive()
    {
        ModuleManifest full = ModuleManifest.parse(JSON_WITH_TEST_DEPS).withAllDependencies();
        assertEquals(List.of("core", "javascript", "core-tests"), full.dependencies());
        assertEquals(List.of(), full.testDependencies());
        assertFalse(full.toJson().contains("testDependencies"));
    }

    /** The tests half is where they are used, so there they are ordinary dependencies. */
    @Test
    public void testsManifestFoldsTestDependenciesIn()
    {
        ModuleManifest tests = TestElementFilter.testsManifest(ModuleManifest.parse(JSON_WITH_TEST_DEPS));
        assertEquals("javascript-translation-tests", tests.name());
        assertTrue(tests.dependencies().contains("core-tests"));
        // And it still depends on the lean half it shadows.
        assertTrue(tests.dependencies().contains("javascript-translation"));
    }

    /** An unknown key is still rejected — the field list is closed on purpose. */
    @Test
    public void unknownKeysAreStillRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> ModuleManifest.parse(
                "{\"name\": \"m\", \"packagePattern\": \"*\", \"dependencies\": [], \"nope\": []}"));
    }
}
