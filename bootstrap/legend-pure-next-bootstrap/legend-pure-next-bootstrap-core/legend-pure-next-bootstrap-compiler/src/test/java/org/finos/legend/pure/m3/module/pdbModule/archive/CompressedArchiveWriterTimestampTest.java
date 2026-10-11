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

package org.finos.legend.pure.m3.module.pdbModule.archive;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every entry of an archive the reference writer produces carries the same fixed timestamp
 * (1980-01-01 00:00:02) and no extended timestamp field — whose Unix time would depend on the
 * machine's time zone — so writing the same content twice gives the same bytes. With the
 * {@code ZipOutputStream} default (the current time) every regeneration of the golden PDBs
 * rewrote all of them although no entry's content had changed.
 *
 * <p>core.pdb is on this module's class path and was written by {@link CompressedArchiveWriter}.</p>
 */
public class CompressedArchiveWriterTimestampTest
{
    private static final LocalDateTime FIXED = LocalDateTime.of(1980, 1, 1, 0, 0, 2);

    @Test
    public void entriesCarryTheFixedTimestamp() throws IOException
    {
        MutableList<String> offenders;
        try (ZipFile zip = new ZipFile(BootstrapModule.locateCorePdb().toFile()))
        {
            offenders = Lists.mutable.withAll(Collections.list(zip.entries()))
                    .reject(entry -> FIXED.equals(entry.getTimeLocal()) && entry.getExtra() == null)
                    .collect(entry -> entry.getName() + " @ " + entry.getTimeLocal());
        }
        assertEquals(Lists.mutable.empty(), offenders.take(5));
    }
}
