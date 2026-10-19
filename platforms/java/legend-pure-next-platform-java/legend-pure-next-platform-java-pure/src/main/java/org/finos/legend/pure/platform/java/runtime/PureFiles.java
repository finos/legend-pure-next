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

package org.finos.legend.pure.platform.java.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure's file functions. An I/O failure is an UncheckedIOException. */
public final class PureFiles
{
    private PureFiles()
    {
    }

    /** {@code writeFile(path, content)}: writes the file, parent directories included, and returns the path. */
    public static String writeFile(String path, String content)
    {
        try
        {
            Path file = Path.of(path);
            if (file.getParent() != null)
            {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content);
            return path;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code readFileBytes(path)}: the file as a Binary. */
    public static byte[] readFileBytes(String path)
    {
        try
        {
            return Files.readAllBytes(Path.of(path));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code directoryTree(path)}: every regular file under {@code path}, recursively, sorted. */
    public static List<String> directoryTree(String path)
    {
        List<String> files = new ArrayList<>();
        try
        {
            Files.walkFileTree(Path.of(path), new SimpleFileVisitor<>()
            {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                {
                    if (Files.isRegularFile(file))
                    {
                        files.add(file.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        Collections.sort(files);
        return files;
    }
}
