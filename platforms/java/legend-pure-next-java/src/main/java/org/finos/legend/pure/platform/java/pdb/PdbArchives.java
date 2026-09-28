// Copyright (c) 2020-present, Goldman Sachs
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

package org.finos.legend.pure.platform.java.pdb;

import java.util.ArrayList;
import java.util.List;
import org.finos.legend.pure.platform.java.pdb.zip.Zip;

/**
 * The `meta::pure::compiler::pdb::archive` natives on this platform: reading the
 * zip container a .pdb is, with {@link Zip}. See archiveRead.pure.
 */
public final class PdbArchives
{
    private PdbArchives()
    {
    }

    public static List<Object> entryNames(byte[] archive)
    {
        return new ArrayList<>(open(archive).entries().keySet());
    }

    /**
     * The entry's bytes as a Binary — the raw byte[]. Previously this returned
     * a boxing List view because Pure modelled bytes as Integer[*].
     */
    public static byte[] entryBytes(byte[] archive, String name)
    {
        Zip zip = open(archive);
        Zip.Entry entry = zip.entries().get(name);
        if (entry == null)
        {
            throw new IllegalArgumentException("No entry '" + name + "' in the archive");
        }
        return zip.read(entry);
    }

    /** The archive is a Binary, so this is a handle, not a conversion. */
    private static Zip open(byte[] archive)
    {
        return new Zip(archive);
    }
}
