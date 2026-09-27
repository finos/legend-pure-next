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

package org.finos.legend.pure.execution.natives.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import meta.pure.metamodel.valuespecification.CollectionImpl;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.natives.NativeRegistry.LazyNativeImpl;
import org.finos.legend.pure.execution.natives.NativeRegistry.NativeImpl;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;

/**
 * Reading the ZIP container a .pdb is — see archiveRead.pure.
 *
 * <p>The pdb FORMAT stays in Pure; the container does not. Every host already
 * ships a zip reader, and doing it in Pure meant each platform ran a translated
 * bit-at-a-time DEFLATE.</p>
 */
public class ArchiveNatives
{
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        // entryNames(Binary[1]) : String[*] — every entry, in archive order.
        natives.put("entryNames_Binary_1__String_MANY_", (args, eval, genericType, multiplicity) ->
        {
            List<ValueSpecification> names = new ArrayList<>();
            for (String name : names(bytes(args.get(0))))
            {
                names.add(_E_ValueSpecification.wrap(name, null, null, resolver));
            }
            return collection(names, resolver);
        });

        // entryBytes(Binary[1], String[1]) : Binary[1] — one entry, inflated.
        natives.put("entryBytes_Binary_1__String_1__Binary_1_", (args, eval, genericType, multiplicity) ->
        {
            String wanted = (String) _E_ValueSpecification.unwrap(args.get(1));
            byte[] found = entry(bytes(args.get(0)), wanted);
            if (found == null)
            {
                throw new RuntimeException("No entry '" + wanted + "' in the archive");
            }
            return _E_ValueSpecification.wrap(found, genericType, multiplicity, resolver);
        });
    }

    private static CollectionImpl collection(List<ValueSpecification> values, MetadataAccess resolver)
    {
        meta.pure.metamodel.type.generics.GenericType gt = values.isEmpty() ? null : values.get(0)._genericType();
        return new CollectionImpl(resolver)
                ._values(org.eclipse.collections.api.factory.Lists.mutable.withAll(values))
                ._genericType(gt)
                ._multiplicity(_Multiplicity.concreteMultiplicity(values.size(), values.size(), resolver));
    }

    private static byte[] bytes(Object argument)
    {
        Object raw = _E_ValueSpecification.unwrap(argument);
        if (raw instanceof byte[] b)
        {
            return b;
        }
        throw new IllegalArgumentException(
                "Expected a Binary (byte[]) archive, got " + (raw == null ? "null" : raw.getClass().getName()));
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

    private static List<String> names(byte[] archive)
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

    private static byte[] entry(byte[] archive, String wanted)
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
}
