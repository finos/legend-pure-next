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

package org.finos.legend.pure.platform.java.pdb;

import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.FbsNode;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.ZipEntryInfo;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.elementNode_Integer_MANY__String_1__FbsNode_1_;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.readField_FbsSchema_1__FbsNode_1__String_1__Any_MANY_;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.readZipEntries_Integer_MANY__ZipEntryInfo_MANY_;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.zipEntryData_Integer_MANY__ZipEntryInfo_1__Integer_MANY_;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.schema.FbsSchema;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.schema.parseFbs_String_1__FbsSchema_1_;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Spike host for the Java platform: reads one Class element out of a PDB with
 * the self-hosted Pure PDB reader translated to Java (generated/PdbReader.java,
 * package {@code meta::pure::compiler::pdb}). JDK only.
 *
 * <p>{@code java PdbSpike <pdb> <m3.fbs> <class path>} prints the class name
 * and its declared property names.</p>
 */
public final class PdbSpike
{
    private PdbSpike()
    {
    }

    public static void main(String[] args) throws Exception
    {
        long start = System.nanoTime();
        List<Long> bytes = new ByteList(Files.readAllBytes(Path.of(args[0])));
        String entryName = "elements/" + String.join("/", args[2].split("::")) + ".Class";

        ZipEntryInfo entry = null;
        for (Object candidate : readZipEntries_Integer_MANY__ZipEntryInfo_MANY_.execute(bytes))
        {
            if (entryName.equals(((ZipEntryInfo) candidate).name()))
            {
                entry = (ZipEntryInfo) candidate;
            }
        }
        if (entry == null)
        {
            throw new IllegalArgumentException("No element " + args[2] + " in " + args[0]);
        }
        List<Long> data = zipEntryData_Integer_MANY__ZipEntryInfo_1__Integer_MANY_.execute(bytes, entry);
        FbsSchema schema = parseFbs_String_1__FbsSchema_1_.execute(Files.readString(Path.of(args[1])));
        FbsNode node = elementNode_Integer_MANY__String_1__FbsNode_1_.execute(data, "Class");

        List<Object> names = new ArrayList<>(readField_FbsSchema_1__FbsNode_1__String_1__Any_MANY_.execute(schema, node, "name"));
        for (Object property : readField_FbsSchema_1__FbsNode_1__String_1__Any_MANY_.execute(schema, node, "properties"))
        {
            names.addAll(readField_FbsSchema_1__FbsNode_1__String_1__Any_MANY_.execute(schema, (FbsNode) property, "name"));
        }
        System.out.println(names);
        System.out.printf("read in %d ms%n", (System.nanoTime() - start) / 1_000_000);
    }
}
