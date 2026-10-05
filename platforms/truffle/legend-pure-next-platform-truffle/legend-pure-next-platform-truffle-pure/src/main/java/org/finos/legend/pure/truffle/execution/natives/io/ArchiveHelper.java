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

package org.finos.legend.pure.truffle.execution.natives.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;

/**
 * Reading the ZIP container a .pdb is — see archiveRead.pure. The pdb FORMAT
 * stays in Pure; the container is a host service every platform already has.
 */
final class ArchiveHelper
{
    private ArchiveHelper()
    {
    }

    private static java.nio.file.Path spill(byte[] archive)
    {
        try
        {
            java.nio.file.Path file = java.nio.file.Files.createTempFile("pure-archive", ".zip");
            file.toFile().deleteOnExit();
            java.nio.file.Files.write(file, archive);
            return file;
        }
        catch (IOException e)
        {
            throw new RuntimeException("Cannot stage the archive", e);
        }
    }

    static List<String> names(byte[] archive)
    {
        java.nio.file.Path file = spill(archive);
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile()))
        {
            List<String> names = new ArrayList<>();
            for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); )
            {
                names.add(e.nextElement().getName());
            }
            return names;
        }
        catch (IOException e)
        {
            throw new RuntimeException("Cannot read the archive: " + e, e);
        }
        finally
        {
            file.toFile().delete();
        }
    }

    static byte[] entry(byte[] archive, String wanted)
    {
        java.nio.file.Path file = spill(archive);
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile()))
        {
            ZipEntry found = zip.getEntry(wanted);
            if (found == null)
            {
                throw new RuntimeException("No entry '" + wanted + "' in the archive");
            }
            try (java.io.InputStream in = zip.getInputStream(found))
            {
                return in.readAllBytes();
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException("Cannot read '" + wanted + "' from the archive: " + e, e);
        }
        finally
        {
            file.toFile().delete();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value)
    {
        return (List<Object>) value;
    }

    /** A Pure `Integer[*]` of bytes as a byte[]. */
    /**
     * The archive is a Binary — a host byte array — so this is a handle, not a
     * conversion. It used to unbox a multi-megabyte list per call.
     */
    static byte[] bytes(Object value)
    {
        return org.finos.legend.pure.truffle.execution.types.PureBinary.asBytes(value);
    }
}
