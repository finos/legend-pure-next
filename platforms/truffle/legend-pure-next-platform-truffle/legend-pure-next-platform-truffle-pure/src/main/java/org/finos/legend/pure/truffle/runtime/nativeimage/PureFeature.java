// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.truffle.runtime.nativeimage;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
 * {@code meta.pure.*} protocol/metamodel classes and
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
 * <p>To activate, pass {@code --features=org.finos.legend.pure.truffle.runtime.nativeimage.PureFeature}
 * to {@code native-image}. Configured in the {@code native} Maven profile of
 * {@code platforms/truffle/pom.xml}.</p>
 */
public final class PureFeature implements Feature
{
    // Package roots to register, as they exist on THIS image's class path. Every
    // one is asserted non-empty in beforeAnalysis: when Truffle was decoupled from
    // bootstrap the old roots ("meta/pure", "org/finos/legend/pure/next/parser",
    // the flatbuffers "…/pdbModule/fbs") stopped matching anything, the scan
    // quietly registered 7 classes instead of hundreds, and the image failed every
    // reflective call at run time with "Failed to invoke section on DocumentContext".
    private static final String[] REFLECTIVE_PACKAGE_ROOTS = new String[]{
            // ANTLR-generated parser contexts. The Pure-side mappings
            // (parser-mappings.pdb) drive `parseDocument` reflectively via
            // AntlrNodes#invokeNamed -> ctx.<rule>(), e.g.
            // TopParser$DocumentContext.section(). Truffle's own copy since the
            // decoupling — NOT org.finos.legend.pure.next.parser.
            "org/finos/legend/pure/truffle/grammar",
            // The translated PDB reader and the metamodel it decodes into, emitted
            // under the m3 base package by truffle::generate-pdb-reader.
            "org/finos/legend/pure/m3",
            // The reader's own module code, and with it the four support classes the
            // generated reader calls (hooks/: LazyObject, Metadata, PureLambda,
            // PureValues) — passed to the generator as `hooksPackage`, see
            // truffle::generate-pdb-reader.
            "org/finos/legend/pure/truffle/compiler/module/pdbModule",
            // TruffleInstanceFactory targets.
            "org/finos/legend/pure/truffle/runtime",
            // A LANGUAGE EXTENSION's generated parser — DiagramLexer/DiagramParser and their contexts.
            // Reached reflectively twice over: Grammars resolves the lexer and parser by name from what the
            // extension's manifest declares, and the Pure-side mappings then invoke each context's rule
            // methods. It is NOT under the grammar root above, because an extension's parser belongs to the
            // extension's own module.
            "org/finos/legend/pure/truffle/extensions"
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
            int fromRoot = 0;
            for (Class<?> cls : scanPackage(root, access))
            {
                if (!seen.add(cls))
                {
                    continue;
                }
                register(cls);
                registered++;
                fromRoot++;
            }
            System.out.println("[PureFeature] " + root + " -> " + fromRoot + " classes");
            // A root that finds NOTHING is a misconfigured scan, and the image it
            // produces is broken in a way only a full spec run reveals: every
            // reflective lookup fails at run time with something as remote as
            // "Failed to invoke section on DocumentContext". Fail the build here,
            // where the cause is still legible.
            if (fromRoot == 0)
            {
                throw new IllegalStateException("[PureFeature] package root '" + root
                        + "' matched no classes — reflection metadata would be missing and the"
                        + " native image would fail every reflective call at run time."
                        + " Check the root against the application class path.");
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
            // Register all methods. The interpreter calls a variety of
            // reflectively-looked-up methods that aren't easily expressed as
            // a single name filter:
            //   - flatc field accessors on the m3 Def classes (GenericFbDecoder
            //     resolves them with getMethod and calls them per decode).
            //   - {@code getRootAsX(ByteBuffer)} FB factory methods on m3
            //     protocol Def classes.
            //   - Possibly more across the m3 protocol surface.
            // A previous attempt narrowed this to a name-prefix filter and
            // broke `valueOf` + other reflective lookups in subtle ways.
            // Registering everything is safer; the bytes saved by tightening
            // are small (~2MB) and not worth the brittleness.
            for (Method m : cls.getMethods())
            {
                RuntimeReflection.register(m);
            }
        }
        catch (Throwable ignored)
        {
            // Some interfaces may raise; keep going.
        }
        try
        {
            // Public fields — required for things like ANTLR's `M3Parser.ruleNames`
            // (read by AntlrNodes#grammarRuleName via Class.getField).
            for (Field f : cls.getFields())
            {
                RuntimeReflection.register(f);
            }
        }
        catch (Throwable ignored)
        {
            // Same defensive guard as for methods.
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
            // The APPLICATION class loader, not the thread context one and not
            // `java.class.path`: inside a native-image build the context loader does
            // not carry the image class path, and java.class.path is the BUILDER's
            // (one entry). Only access.getApplicationClassLoader() — an
            // svm NativeImageClassLoader — resolves the image's own jars.
            Enumeration<URL> resources = access.getApplicationClassLoader().getResources(packageRoot);
            while (resources.hasMoreElements())
            {
                scanUrl(resources.nextElement(), packageRoot, classes, access);
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
        if ("file".equals(url.getProtocol()))
        {
            scanDir(Path.of(url.getPath()), packageRoot, classes, access);
            return;
        }
        if (!"jar".equals(url.getProtocol()))
        {
            return;
        }
        // jar:file:/path/to/foo.jar!/org/finos/legend/pure/truffle/grammar
        String spec = url.toString();
        int bang = spec.indexOf('!');
        String innerPath = spec.substring(bang + 1);
        if (innerPath.startsWith("/"))
        {
            innerPath = innerPath.substring(1);
        }
        try (FileSystem fs = FileSystems.newFileSystem(
                java.net.URI.create(spec.substring(0, bang + 2)), Collections.emptyMap()))
        {
            scanDir(fs.getPath(innerPath), packageRoot, classes, access);
        }
        catch (Exception e)
        {
            // A jar that cannot be opened contributes nothing; the empty-root guard
            // in beforeAnalysis catches the case where that leaves a root with none.
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
                                access.getApplicationClassLoader());
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
