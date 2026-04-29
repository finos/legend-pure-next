// Copyright 2024 Goldman Sachs
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

import meta.pure.metamodel.valuespecification.CollectionImpl;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Filesystem natives for Pure — directoryTree and readFile.
 *
 * <p>These enable Pure-native workflows that need to walk the filesystem and
 * read files, such as the in-Pure test runner. In the browser, equivalent
 * implementations must be provided by the TypeScript interpreter (typically
 * backed by a virtual FS).</p>
 */
public class FileSystemNatives
{
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        // directoryTree(String[1]) : String[*]
        // Returns all regular file paths (recursively) under the given directory, sorted.
        natives.put("directoryTree_String_1__String_MANY_", (args, eval, genericType, multiplicity) ->
        {
            String root = (String) _E_ValueSpecification.unwrap(args.get(0));
            List<String> paths;
            try (Stream<Path> stream = Files.walk(Path.of(root)))
            {
                paths = stream
                        .filter(Files::isRegularFile)
                        .map(Path::toString)
                        .sorted(Comparator.naturalOrder())
                        .toList();
            }
            catch (IOException e)
            {
                throw new RuntimeException("Failed to walk directory: " + root, e);
            }

            List<ValueSpecification> wrapped = new ArrayList<>();
            for (String p : paths)
            {
                wrapped.add(_E_ValueSpecification.wrap(p, genericType, null, resolver));
            }
            meta.pure.metamodel.type.generics.GenericType gt = wrapped.isEmpty() ? null : wrapped.get(0)._genericType();
            return new CollectionImpl(resolver)
                    ._values(org.eclipse.collections.api.factory.Lists.mutable.withAll(wrapped))
                    ._genericType(gt)
                    ._multiplicity(_Multiplicity.concreteMultiplicity(paths.size(), paths.size(), resolver));
        });

        // readFile(String[1]) : String[1]
        natives.put("readFile_String_1__String_1_", (args, eval, genericType, multiplicity) ->
        {
            String path = (String) _E_ValueSpecification.unwrap(args.get(0));
            try
            {
                String content = Files.readString(Path.of(path));
                return _E_ValueSpecification.wrap(content, genericType, multiplicity, resolver);
            }
            catch (IOException e)
            {
                throw new RuntimeException("Failed to read file: " + path, e);
            }
        });
    }
}
