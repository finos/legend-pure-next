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

package org.finos.legend.pure.m3.module.bootstrapModule;

import meta.pure.metamodel.Package;
import meta.pure.metamodel.PackageImpl;
import meta.pure.metamodel.PackageableElement;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.MetadataAccessExtension;
import org.finos.legend.pure.m3.module.Module;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * A module that provides the M3 bootstrap types loaded from {@code m3.ttl}.
 *
 * <p>This module wraps the {@link M3BootstrapReader} and exposes the
 * bootstrapped elements (classes, primitive types, enumerations, profiles,
 * multiplicities) through the {@link Module} interface.</p>
 *
 * <p>The function index is empty since the bootstrap graph contains
 * no user-defined or native functions.</p>
 */
public class BootstrapModule implements Module
{
    private final MutableMap<String, PackageableElement> index;
    private final Package root;

    /**
     * Create a bootstrap module, loading all M3 types from the given {@code m3.ttl} file.
     */
    public BootstrapModule(Path m3TtlPath)
    {
        this.root = new PackageImpl()._name("::");
        this.index = Maps.mutable.empty();
        M3BootstrapReader.bootstrap(m3TtlPath, this.root, this.index);
        // Freeze all bootstrap elements to prevent mutation via shared references
        freezeAll();
    }

    /**
     * Walk upward from the current working directory looking for a
     * {@code specification/m3.ttl} file. Useful for tests and ad-hoc
     * invocations where the project root is reachable from the cwd.
     *
     * @throws RuntimeException if m3.ttl cannot be located
     */
    /**
     * Load m3.ttl from the classpath (packaged in the generators JAR).
     */
    public static Path locateM3Ttl()
    {
        return extractFromClasspath("generated-specification/m3.ttl");
    }

    /**
     * Load core.pdb (lean — no <<test.*>>-annotated elements) from the
     * classpath (packaged in the compiler JAR).
     */
    public static Path locateCorePdb()
    {
        return extractFromClasspath("core.pdb");
    }

    /**
     * Load core-tests.pdb (companion to core.pdb, holds only
     * <<test.*>>-annotated elements) from the classpath. Test runners that
     * need to discover <<test.Test>>/<<test.TestDependency>> elements load
     * this alongside core.pdb.
     */
    public static Path locateCoreTestsPdb()
    {
        return extractFromClasspath("core-tests.pdb");
    }

    /**
     * Load compiler.pdb from the classpath.
     */
    public static Path locateCompilerPdb()
    {
        return extractFromClasspath("compiler.pdb");
    }

    /**
     * Load parser-mappings.pdb from the classpath. Produced by the
     * bootstrap-parser-validation module's exec-maven-plugin execution into
     * its own target/classes — any test module that depends on
     * bootstrap-parser-validation gets it on the classpath.
     */
    public static Path locateParserMappingsPdb()
    {
        return extractFromClasspath("parser-mappings.pdb");
    }

    private static Path extractFromClasspath(String resourceName)
    {
        try (java.io.InputStream in = BootstrapModule.class.getClassLoader().getResourceAsStream(resourceName))
        {
            if (in == null)
            {
                throw new RuntimeException("Resource '" + resourceName + "' not found on classpath");
            }
            String safeName = resourceName.replace('/', '-');
            Path tempFile = Files.createTempFile("pure-", "-" + safeName);
            tempFile.toFile().deleteOnExit();
            Files.copy(in, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        }
        catch (java.io.IOException e)
        {
            throw new RuntimeException("Failed to extract '" + resourceName + "' from classpath", e);
        }
    }


    /**
     * Recursively freeze all elements in the index to make them immutable.
     * This prevents cross-compilation leaks where shared type hierarchy objects
     * (e.g., GenericType objects in generalizations) get mutated.
     */
    private void freezeAll()
    {
        java.util.Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        this.index.forEachValue(element -> deepFreeze(element, visited));
    }

    private static void deepFreeze(Object obj, java.util.Set<Object> visited)
    {
        if (obj == null || !visited.add(obj))
        {
            return;
        }
        // Freeze the object if it has a freeze() method (generated Impl classes)
        try
        {
            java.lang.reflect.Method freezeMethod = obj.getClass().getMethod("freeze");
            freezeMethod.invoke(obj);
        }
        catch (NoSuchMethodException ignored)
        {
            // Not a freezable Impl class
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to freeze " + obj.getClass().getSimpleName(), e);
        }
        // Recurse into all getter results that are Impl instances or lists of them
        for (java.lang.reflect.Method method : obj.getClass().getMethods())
        {
            if (method.getName().startsWith("_") && method.getParameterCount() == 0
                    && !method.getName().equals("_copy")
                    && method.getReturnType() != void.class
                    && method.getReturnType() != boolean.class
                    && method.getReturnType() != int.class
                    && method.getReturnType() != long.class
                    && method.getReturnType() != double.class
                    && method.getReturnType() != float.class
                    && method.getReturnType() != String.class)
            {
                try
                {
                    Object value = method.invoke(obj);
                    if (value instanceof Iterable<?> iterable)
                    {
                        for (Object item : iterable)
                        {
                            deepFreeze(item, visited);
                        }
                    }
                    else if (value != null && value.getClass().getName().endsWith("Impl"))
                    {
                        deepFreeze(value, visited);
                    }
                }
                catch (Exception ignored)
                {
                    // Skip inaccessible properties
                }
            }
        }
    }

    @Override
    public void setPureModel(PureModel model)
    {
    }

    @Override
    public <T extends MetadataAccessExtension> MutableList<T> getMetadataAccessExtension(Class<T> clz)
    {
        return Lists.mutable.empty();
    }

    @Override
    public String getName()
    {
        return "m3";
    }

    @Override
    public List<String> getDependencies()
    {
        return List.of();
    }

    @Override
    public String getPackagePattern()
    {
        return "meta::*";
    }

    @Override
    public PackageableElement getElement(String path)
    {
        return index.get(path);
    }

    @Override
    public boolean hasElement(String path)
    {
        return index.containsKey(path);
    }

    @Override
    public Set<String> elementPaths()
    {
        return index.keySet();
    }

    /**
     * @return the root package of the bootstrap graph
     */
    public Package root()
    {
        return root;
    }
}
