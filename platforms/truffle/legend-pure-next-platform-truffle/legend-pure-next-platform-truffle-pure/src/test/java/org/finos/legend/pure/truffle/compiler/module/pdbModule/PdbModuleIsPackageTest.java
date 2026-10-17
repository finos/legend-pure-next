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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether an element is a Package is in the archive's entry name, so registering modules — whose
 * package paths legitimately overlap — needs no decoding, and so no schema. A module with no resolver
 * cannot decode at all: these answers prove nothing was decoded.
 */
public class PdbModuleIsPackageTest
{
    @Test
    public void aPackageIsKnownWithoutDecoding() throws IOException
    {
        PdbModule core = PdbModule.open(sharedDir().resolve("core.pdb"));
        Assertions.assertTrue(core.isPackage("platform::pure"));
        Assertions.assertFalse(core.isPackage("platform::pure::functions::collection::List"));
        Assertions.assertFalse(core.isPackage("no::such::element"));
    }

    private static Path sharedDir()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("shared").resolve("core.pdb")))
        {
            current = current.getParent();
        }
        if (current == null)
        {
            throw new IllegalStateException("Cannot locate the repository (shared/core.pdb)");
        }
        return current.resolve("shared");
    }
}
