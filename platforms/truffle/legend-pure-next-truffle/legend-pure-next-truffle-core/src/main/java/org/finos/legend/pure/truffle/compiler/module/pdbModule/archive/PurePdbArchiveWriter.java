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

package org.finos.legend.pure.truffle.compiler.module.pdbModule.archive;

import org.finos.legend.pure.truffle.compiler.module.MetadataAccess;
import org.finos.legend.pure.m3.module.ModuleManifest;
import org.finos.legend.pure.truffle.runtime.PureRuntime;
import org.finos.legend.pure.truffle.execution.types.ObjectSequence;
import org.finos.legend.pure.truffle.execution.types.PureSequence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a {@code .pdb} archive by invoking the SELF-HOSTED PURE WRITER
 * ({@code meta::pure::compiler::pdb::archive::writeArchive}, compiled into
 * compiler.pdb) through the Truffle runtime — the same Pure code validated
 * byte-for-byte against the flatc-anchored Java reference goldens.
 *
 * <p>This is the ONLY archive writer on the Truffle platform; the
 * Java-codegen writer ({@code GeneratedFlatBufferWriter} via the retired
 * {@code TrufflePdbWriter}) is gone. Consequently compiler.pdb must be among
 * the loaded modules — callers that compile without it cannot write
 * archives.</p>
 */
public final class PurePdbArchiveWriter
{
    private static final String WRITE_ARCHIVE_FN_PATH =
            "meta::pure::compiler::pdb::archive::writeArchive_Any_MANY__String_1__String_1__String_MANY__Map_1__Integer_MANY_";
    private static final String REFBY_FROM_LISTS_FN_PATH =
            "meta::pure::compiler::pdb::archive::referencedByFromLists_String_MANY__String_MANY__Map_1_";

    private PurePdbArchiveWriter()
    {
    }

    /**
     * @param referencedBy the (already partition-filtered) reverse reference
     *                     index, or null/empty for none
     */
    public static void write(PureRuntime runtime,
                             MetadataAccess resolver,
                             List<Object> elements,
                             ModuleManifest manifest,
                             java.util.Map<String, java.util.Set<String>> referencedBy,
                             Path target) throws IOException
    {
        Object writeFn = resolver.getElement(WRITE_ARCHIVE_FN_PATH);
        Object refFn = resolver.getElement(REFBY_FROM_LISTS_FN_PATH);
        if (writeFn == null || refFn == null)
        {
            throw new IllegalStateException(
                    "Self-hosted PDB writer not loaded — compiler.pdb must be among the loaded modules to write archives"
                            + " (missing " + (writeFn == null ? WRITE_ARCHIVE_FN_PATH : REFBY_FROM_LISTS_FN_PATH) + ")");
        }
        // Marshal the reverse index as parallel primitive lists; the Pure
        // adapter rebuilds the Map ('\n' never occurs in element paths).
        List<String> targets = new ArrayList<>();
        List<String> joinedCallers = new ArrayList<>();
        if (referencedBy != null)
        {
            referencedBy.forEach((t, callers) ->
            {
                targets.add(t);
                joinedCallers.add(String.join("\n", callers));
            });
        }
        Object refMap = runtime.execute(refFn,
                new ObjectSequence(targets.toArray()),
                new ObjectSequence(joinedCallers.toArray()));
        Object bytesObj = runtime.execute(writeFn,
                new ObjectSequence(elements.toArray()),
                manifest.name(),
                manifest.packagePattern(),
                new ObjectSequence(manifest.dependencies().toArray()),
                refMap);
        if (target.getParent() != null)
        {
            Files.createDirectories(target.getParent());
        }
        Files.write(target, pureBytes(bytesObj));
    }

    /** Pure {@code Integer[*]} (0..255 values) → {@code byte[]}. */
    private static byte[] pureBytes(Object o)
    {
        if (o instanceof org.finos.legend.pure.truffle.execution.types.LongSequence ls)
        {
            byte[] out = new byte[ls.size()];
            for (int i = 0; i < out.length; i++)
            {
                out[i] = (byte) ls.getLong(i);
            }
            return out;
        }
        if (o instanceof org.finos.legend.pure.truffle.execution.types.PrependLongSequence pls)
        {
            byte[] out = new byte[pls.size()];
            for (int i = 0; i < out.length; i++)
            {
                out[i] = (byte) pls.getLong(i);
            }
            return out;
        }
        if (o instanceof PureSequence ps)
        {
            byte[] out = new byte[ps.size()];
            for (int i = 0; i < out.length; i++)
            {
                out[i] = ((Number) ps.getBoxed(i)).byteValue();
            }
            return out;
        }
        throw new IllegalStateException("Pure writeArchive returned unexpected value: "
                + (o == null ? "null" : o.getClass().getName()));
    }
}
