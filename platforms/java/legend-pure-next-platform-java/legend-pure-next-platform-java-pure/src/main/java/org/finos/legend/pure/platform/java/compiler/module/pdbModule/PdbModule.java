// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.platform.java.compiler.module.pdbModule;

import org.finos.legend.pure.platform.java.compiler.module.Module;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.archive.Zip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A compiled module on disk: a PDB archive. Identity comes from the archive's own
 * manifest, so registering one needs no configuration.
 *
 * <p>This is a handle, not a reader. {@link org.finos.legend.pure.platform.java.runtime.PureRuntime}
 * opens every registered archive as ONE {@link PdbRuntime} — the decoder is shared
 * because an element in one archive refers to elements in another — so the module
 * carries its path and lets the runtime do the reading.</p>
 */
public final class PdbModule implements Module
{
    private final Path path;
    private final String name;
    private final List<String> dependencies;

    private PdbModule(Path path, String name, List<String> dependencies)
    {
        this.path = path;
        this.name = name;
        this.dependencies = List.copyOf(dependencies);
    }

    /** Read {@code path}'s manifest for its name and dependencies. */
    public static PdbModule open(Path path) throws IOException
    {
        Zip zip = new Zip(Files.readAllBytes(path));
        Zip.Entry manifest = zip.entries().get("manifest");
        if (manifest == null)
        {
            throw new IOException("Not a PDB archive (no manifest section): " + path);
        }
        String json = new String(zip.read(manifest), StandardCharsets.UTF_8);
        String name = jsonString(json, "name");
        if (name == null)
        {
            throw new IOException("PDB manifest has no name: " + path);
        }
        return new PdbModule(path, name, jsonStrings(json, "dependencies"));
    }

    public Path path()
    {
        return path;
    }

    @Override
    public String name()
    {
        return name;
    }

    @Override
    public List<String> dependencies()
    {
        return dependencies;
    }

    @Override
    public String toString()
    {
        return "PdbModule(" + name + ")";
    }

    /** The string value of {@code key} in a flat JSON object — the manifest is one. */
    private static String jsonString(String json, String key)
    {
        int at = json.indexOf('"' + key + '"');
        int start = at < 0 ? -1 : json.indexOf('"', json.indexOf(':', at) + 1);
        return start < 0 ? null : json.substring(start + 1, json.indexOf('"', start + 1));
    }

    /** The string members of {@code key}'s JSON array; empty when absent or `[]`. */
    private static List<String> jsonStrings(String json, String key)
    {
        int at = json.indexOf('"' + key + '"');
        int open = at < 0 ? -1 : json.indexOf('[', at);
        int close = open < 0 ? -1 : json.indexOf(']', open);
        List<String> values = new ArrayList<>();
        for (int i = open + 1; open >= 0 && i < close; )
        {
            int start = json.indexOf('"', i);
            if (start < 0 || start > close)
            {
                break;
            }
            int end = json.indexOf('"', start + 1);
            values.add(json.substring(start + 1, end));
            i = end + 1;
        }
        return values;
    }
}
