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

package org.finos.legend.pure.truffle.nativeimage;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * GraalVM Native Image {@link Feature} that registers reflection metadata
 * for the generated Pure metamodel + protocol classes.
 *
 * <p>Scanning is done at image-build time. We walk every class under
 * {@code meta.pure.metamodel.*} and {@code meta.pure.protocol.*} and
 * register:</p>
 * <ul>
 *   <li>The class itself (for {@code Class.forName}).</li>
 *   <li>All public constructors (used by {@code MetaNatives}' {@code new()}
 *       native via reflection).</li>
 *   <li>All declared and inherited methods (the interpreter looks up
 *       {@code _<property>()} accessors by name via
 *       {@code ValueSpecificationEvaluator.lookupMethod}).</li>
 * </ul>
 *
 * <p>This replaces maintaining a fragile static {@code reflect-config.json}
 * and handles newly-generated metamodel classes without extra configuration.</p>
 *
 * <p>To activate, pass {@code --features=org.finos.legend.pure.truffle.nativeimage.PureFeature}
 * to {@code native-image}. Configured in the {@code native} Maven profile of
 * {@code platforms/truffle/pom.xml}.</p>
 */
public final class PureFeature implements Feature
{
    private static final String[] REFLECTIVE_PACKAGE_ROOTS = new String[]{
            "meta/pure"  // covers metamodel, protocol, compiler, functions, etc.
    };

    @Override
    public String getDescription()
    {
        return "Registers reflection metadata for Pure metamodel + protocol classes.";
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access)
    {
        int registered = 0;
        Set<Class<?>> seen = new HashSet<>();
        for (String root : REFLECTIVE_PACKAGE_ROOTS)
        {
            for (Class<?> cls : scanPackage(root, access))
            {
                if (!seen.add(cls))
                {
                    continue;
                }
                register(cls);
                registered++;
            }
        }
        System.out.println("[PureFeature] Registered " + registered + " reflective classes from "
                + REFLECTIVE_PACKAGE_ROOTS.length + " package roots.");
    }

    private void register(Class<?> cls)
    {
        RuntimeReflection.register(cls);
        try
        {
            for (Constructor<?> ctor : cls.getDeclaredConstructors())
            {
                RuntimeReflection.register(ctor);
            }
        }
        catch (Throwable ignored)
        {
            // Some generated classes may be unloadable or inner types with no ctors.
        }
        try
        {
            // Register all methods; the interpreter only calls `_<property>()` accessors
            // but registering everything is safer and costs only build-time metadata.
            for (Method m : cls.getMethods())
            {
                RuntimeReflection.register(m);
            }
        }
        catch (Throwable ignored)
        {
            // Some interfaces may raise; keep going.
        }
    }

    /**
     * Walk the classpath for every {@code .class} file whose resource path
     * starts with {@code packageRoot} (using '/' separators, no trailing slash).
     */
    private Set<Class<?>> scanPackage(String packageRoot, BeforeAnalysisAccess access)
    {
        Set<Class<?>> classes = new HashSet<>();
        try
        {
            Enumeration<URL> resources = Thread.currentThread().getContextClassLoader()
                    .getResources(packageRoot);
            while (resources.hasMoreElements())
            {
                URL url = resources.nextElement();
                scanUrl(url, packageRoot, classes, access);
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to scan package " + packageRoot, e);
        }
        return classes;
    }

    private void scanUrl(URL url, String packageRoot, Set<Class<?>> classes, BeforeAnalysisAccess access) throws IOException
    {
        String protocol = url.getProtocol();
        if ("file".equals(protocol))
        {
            Path dir = Path.of(url.getPath());
            if (Files.isDirectory(dir))
            {
                scanDir(dir, packageRoot, classes, access);
            }
        }
        else if ("jar".equals(protocol))
        {
            // jar:file:/path/to/foo.jar!/meta/pure/metamodel
            String spec = url.toString();
            String jarPart = spec.substring(spec.indexOf(':') + 1, spec.indexOf("!"));
            String innerPath = spec.substring(spec.indexOf("!") + 1);
            if (innerPath.startsWith("/"))
            {
                innerPath = innerPath.substring(1);
            }
            try (FileSystem fs = FileSystems.newFileSystem(
                    java.net.URI.create(spec.substring(0, spec.indexOf("!") + 2)),
                    Collections.emptyMap()))
            {
                Path root = fs.getPath(innerPath);
                scanDir(root, packageRoot, classes, access);
            }
            catch (Exception e)
            {
                // Some jars may not expose a FileSystem — skip.
            }
        }
    }

    private void scanDir(Path root, String packageRoot, Set<Class<?>> classes, BeforeAnalysisAccess access) throws IOException
    {
        if (!Files.isDirectory(root))
        {
            return;
        }
        try (Stream<Path> stream = Files.walk(root))
        {
            stream.filter(p -> p.toString().endsWith(".class")).forEach(p ->
            {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.endsWith(".class"))
                {
                    String className = (packageRoot + "/" + rel)
                            .replace('/', '.')
                            .replaceAll("\\.class$", "");
                    try
                    {
                        Class<?> cls = Class.forName(className, false,
                                Thread.currentThread().getContextClassLoader());
                        classes.add(cls);
                    }
                    catch (Throwable ignored)
                    {
                        // Ignore classes that can't be loaded (e.g., inner classes with missing deps).
                    }
                }
            });
        }
    }
}
