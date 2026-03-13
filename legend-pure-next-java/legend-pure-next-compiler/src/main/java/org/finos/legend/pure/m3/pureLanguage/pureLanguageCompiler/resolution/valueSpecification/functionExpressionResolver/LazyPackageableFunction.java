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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver;

import meta.pure.metamodel.function.PackageableFunction;
import meta.pure.metamodel.type.FunctionType;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.pureLanguage.metadata.FunctionIndexEntry;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A lazy proxy for {@link PackageableFunction} that defers loading the full
 * element from the model until a method is actually invoked on it.
 * <p>
 * During compilation, the {@link FunctionIndexEntry#functionType()} provides all
 * the information needed for parameter matching and type inference. The proxy is
 * set on {@code expr._func()} without triggering a {@code model.getElement()} call.
 * The actual element is only loaded when the function is accessed at execution time.
 * </p>
 */
public class LazyPackageableFunction implements InvocationHandler
{
    /**
     * When true, any call to {@link #resolve()} will throw an {@link AssertionError}.
     * Enable this in tests to verify that compilation never triggers function element loading.
     */
    private static volatile boolean failOnResolve = false;

    private final FunctionIndexEntry entry;
    private final MetadataAccess model;
    private volatile PackageableFunction resolved;

    /**
     * Enable or disable the resolve guard.
     * When enabled, any proxy method invocation that triggers element loading
     * will throw {@link AssertionError}.
     */
    public static void setFailOnResolve(boolean fail)
    {
        failOnResolve = fail;
    }

    public static boolean isFailOnResolve()
    {
        return failOnResolve;
    }

    private LazyPackageableFunction(FunctionIndexEntry entry, MetadataAccess model)
    {
        this.entry = entry;
        this.model = model;
    }

    /**
     * Create a lazy proxy that implements {@link PackageableFunction}.
     * No element loading occurs until a method is invoked on the returned proxy.
     */
    public static PackageableFunction create(FunctionIndexEntry entry, MetadataAccess model)
    {
        return (PackageableFunction) Proxy.newProxyInstance(
                PackageableFunction.class.getClassLoader(),
                collectInterfaces(PackageableFunction.class),
                new LazyPackageableFunction(entry, model));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
    {
        // Intercept methods that can be answered from the index entry
        // without loading the full element from the model.
        String methodName = method.getName();
        if ("_functionName".equals(methodName) && (args == null || args.length == 0))
        {
            return entry.functionName();
        }
        return method.invoke(resolve(), args);
    }

    private PackageableFunction resolve()
    {
        if (resolved == null)
        {
            if (failOnResolve && !CompilationContext.isDebug())
            {
                throw new AssertionError("LazyFunction.resolve() was called during compilation for: " + entry.fullPath());
            }
            resolved = (PackageableFunction) model.getElement(entry.fullPath());
        }
        return resolved;
    }

    /**
     * @return true if the underlying element has been loaded.
     */
    public boolean isResolved()
    {
        return resolved != null;
    }

    /**
     * @return the {@link FunctionType} from the underlying {@link FunctionIndexEntry},
     *         without triggering element resolution.
     */
    public FunctionType functionType()
    {
        return entry.functionType();
    }

    /**
     * Extract the {@link LazyPackageableFunction} handler from a proxy, or null if not a lazy proxy.
     */
    public static LazyPackageableFunction unwrap(Object proxy)
    {
        if (Proxy.isProxyClass(proxy.getClass()))
        {
            InvocationHandler handler = Proxy.getInvocationHandler(proxy);
            if (handler instanceof LazyPackageableFunction lf)
            {
                return lf;
            }
        }
        return null;
    }

    /**
     * Collect all interfaces implemented by the given class and its supertypes,
     * so the proxy implements the full type hierarchy.
     */
    private static Class<?>[] collectInterfaces(Class<?> iface)
    {
        Set<Class<?>> all = new LinkedHashSet<>();
        collectInterfacesRecursive(iface, all);
        return all.toArray(new Class<?>[0]);
    }

    private static void collectInterfacesRecursive(Class<?> iface, Set<Class<?>> result)
    {
        if (iface != null && iface.isInterface() && result.add(iface))
        {
            for (Class<?> parent : iface.getInterfaces())
            {
                collectInterfacesRecursive(parent, result);
            }
        }
    }
}
