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

package org.finos.legend.pure.truffle.runtime;

import meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.truffle.types.ObjectSequence;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Materializes FlatBuffer wrapper objects into PDB-generated Impl instances.
 *
 * <p>FlatBuffer wrappers (from the bootstrap PDB loader) use {@code MutableList}
 * for {@code [*]} properties. PDB-generated Impl classes (from
 * {@link org.finos.legend.pure.truffle.codegen.PdbJavaGenerator}) use
 * {@code PureSequence}. This materializer bridges the gap by reading property
 * values from FlatBuffer wrappers and writing them into fresh Impl instances,
 * converting {@code MutableList} to {@code ObjectSequence} along the way.</p>
 *
 * <p>Wraps a {@link MetadataAccess} delegate and intercepts
 * {@link #getElement(String)} to materialize on demand.</p>
 */
public final class PdbMaterializer implements MetadataAccess
{
    private final MetadataAccess delegate;
    private final Map<String, PackageableElement> cache = new HashMap<>();

    public PdbMaterializer(MetadataAccess delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public PackageableElement getElement(String path)
    {
        PackageableElement cached = cache.get(path);
        if (cached != null)
        {
            return cached;
        }
        PackageableElement original = delegate.getElement(path);
        if (original == null)
        {
            return null;
        }
        // Materialize FlatBuffer wrappers into Impl instances
        PackageableElement materialized = materialize(original);
        cache.put(path, materialized);
        return materialized;
    }

    @Override
    public boolean hasElement(String path)
    {
        return delegate.hasElement(path);
    }

    @Override
    public Set<String> elementPaths()
    {
        return delegate.elementPaths();
    }

    @Override
    public <T extends org.finos.legend.pure.m3.module.MetadataAccessExtension>
    org.eclipse.collections.api.list.MutableList<T> getMetadataAccessExtension(Class<T> clz)
    {
        return delegate.getMetadataAccessExtension(clz);
    }

    /**
     * Materialize a FlatBuffer wrapper into a PDB-generated Impl.
     * If no Impl class exists (e.g. metamodel classes), returns the original.
     */
    private PackageableElement materialize(PackageableElement original)
    {
        // Only materialize FlatBuffer wrappers — skip objects that are already Impls
        String className = original.getClass().getSimpleName();
        if (!className.contains("FlatBuffer") && !className.contains("Wrapper"))
        {
            return original;
        }

        // Find the target Impl class from the interface name
        Class<?>[] interfaces = original.getClass().getInterfaces();
        if (interfaces.length == 0)
        {
            return original;
        }

        String implClassName = interfaces[0].getName() + "Impl";
        try
        {
            Class<?> implClass = Class.forName(implClassName);
            Object impl = implClass.getDeclaredConstructor().newInstance();

            // Copy all _xxx() property values from the wrapper to the Impl
            for (Method getter : interfaces[0].getMethods())
            {
                String name = getter.getName();
                if (!name.startsWith("_") || getter.getParameterCount() != 0)
                {
                    continue;
                }
                // Skip internal methods
                if ("_copy".equals(name) || "_class".equals(name) || "_hashCode".equals(name))
                {
                    continue;
                }

                try
                {
                    Object value = getter.invoke(original);
                    if (value == null)
                    {
                        continue;
                    }

                    // Convert MutableList to ObjectSequence for [*] properties
                    Object converted = convertValue(value);

                    // Find setter
                    String setterName = name;
                    for (Method setter : implClass.getMethods())
                    {
                        if (setter.getName().equals(setterName) && setter.getParameterCount() == 1)
                        {
                            Class<?> paramType = setter.getParameterTypes()[0];
                            if (paramType.isInstance(converted))
                            {
                                setter.invoke(impl, converted);
                                break;
                            }
                        }
                    }
                }
                catch (Exception ignored)
                {
                    // Skip properties that can't be copied (lazy resolution failures, etc.)
                }
            }

            return (PackageableElement) impl;
        }
        catch (Exception e)
        {
            // No Impl class available — return the original wrapper
            return original;
        }
    }

    /**
     * Convert a property value from FlatBuffer representation to Truffle representation.
     * {@code MutableList} → {@code ObjectSequence}.
     */
    private static Object convertValue(Object value)
    {
        if (value instanceof org.eclipse.collections.api.list.MutableList<?> ml)
        {
            if (ml.isEmpty())
            {
                return null;
            }
            return new ObjectSequence(ml.toArray());
        }
        return value;
    }
}
