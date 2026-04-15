// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.execution;

import meta.pure.metamodel.type.generics.UserDefinedGenericType;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class ProtocolToDynamicInstance
{
    private static final Map<Class<?>, List<Method>> PROPERTY_METHODS_CACHE = new ConcurrentHashMap<>();

    private final MetadataAccess resolver;
    private final Function<Object, String> typeResolutionStrategy;

    public ProtocolToDynamicInstance(MetadataAccess resolver)
    {
        this(resolver, ProtocolToDynamicInstance::defaultTypeResolutionStrategy);
    }

    public ProtocolToDynamicInstance(MetadataAccess resolver, Function<Object, String> typeResolutionStrategy)
    {
        this.resolver = resolver;
        this.typeResolutionStrategy = typeResolutionStrategy;
    }

    public static String defaultTypeResolutionStrategy(Object javaPOJO)
    {
        if (javaPOJO.getClass().getName().startsWith("org.finos.legend.pure.m3.generated.protocol.")
            || javaPOJO.getClass().getName().startsWith("meta.pure.protocol.")
            || javaPOJO.getClass().getName().startsWith("meta.pure.compiler."))
        {
            String fullName = javaPOJO.getClass().getName();
            if (fullName.startsWith("org.finos.legend.pure.m3.generated."))
            {
                fullName = "meta.pure." + fullName.substring("org.finos.legend.pure.m3.generated.".length());
            }
            if (fullName.endsWith("Impl"))
            {
                fullName = fullName.substring(0, fullName.length() - 4);
            }
            return fullName.replace(".", "::");
        }
        return null;
    }

    public Object convert(Object javaPOJO)
    {
        return convert(javaPOJO, 0);
    }

    public Object convert(Object javaPOJO, int depth)
    {
        if (depth > 100)
        {
            throw new RuntimeException("StackOverflow safe-guard tripped! Deeply nested or cyclic POJO: " + (javaPOJO != null ? javaPOJO.getClass().getName() : "null"));
        }

        if (javaPOJO == null)
        {
            return null;
        }

        // Java enums (from protocol POJOs) → resolve to metamodel Enum instances
        if (javaPOJO instanceof java.lang.Enum<?> javaEnum)
        {
            return resolveJavaEnumToMetamodelEnum(javaEnum);
        }

        if (javaPOJO instanceof Collection)
        {
            List<Object> results = Lists.mutable.empty();
            for (Object item : (Collection<?>) javaPOJO)
            {
                results.add(convert(item, depth + 1));
            }
            return results;
        }

        String pureClassPath = this.typeResolutionStrategy.apply(javaPOJO);
        if (pureClassPath != null)
        {
            meta.pure.metamodel.PackageableElement pureClass = resolver.getElement(pureClassPath);
            if (pureClass == null)
            {
                throw new RuntimeException("Could not find Pure class for protocol object: " + pureClassPath);
            }

            DynamicInstance instance = new DynamicInstance(pureClassPath);
            UserDefinedGenericType cgt = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) pureClass, resolver);
            instance.setClassifierGenericType(cgt);

            for (Method method : getPropertyMethods(javaPOJO.getClass()))
            {
                String propName = method.getName().substring(1);
                try
                {
                    Object val = method.invoke(javaPOJO);
                    if (val != null && val != javaPOJO)
                    {
                        Object convertedVal = convert(val, depth + 1);
                        if (convertedVal instanceof List<?> listVal)
                        {
                            instance.put(propName, org.eclipse.collections.api.factory.Lists.mutable.withAll(listVal));
                        }
                        else
                        {
                            instance.put(propName, convertedVal);
                        }
                    }
                }
                catch (Exception e)
                {
                    throw new RuntimeException("Error converting property " + propName, e);
                }
            }
            return instance;
        }

        return javaPOJO;
    }

    /**
     * Resolve a Java enum value (from a protocol POJO) to the corresponding
     * metamodel Enum instance so that Pure == comparison works.
     * <p>
     * Protocol Java enums (e.g., {@code AggregationKind.Composite}) are matched
     * by name to metamodel Enum instances (e.g., {@code AggregationKind_Composite}).
     */
    private Object resolveJavaEnumToMetamodelEnum(java.lang.Enum<?> javaEnum)
    {
        // Map protocol class name to metamodel path
        // e.g., meta.pure.protocol.grammar.function.property.AggregationKind → meta::pure::metamodel::function::property::AggregationKind
        String protoClassName = javaEnum.getDeclaringClass().getName();

        // Try protocol → metamodel mapping
        String metamodelPath;
        if (protoClassName.startsWith("meta.pure.protocol.grammar."))
        {
            metamodelPath = "meta::pure::metamodel::" + protoClassName.substring("meta.pure.protocol.grammar.".length()).replace(".", "::");
        }
        else if (protoClassName.startsWith("meta.pure.metamodel."))
        {
            metamodelPath = protoClassName.replace(".", "::");
        }
        else
        {
            // Unknown enum type — return as-is (will become a DynamicInstance)
            return javaEnum;
        }

        // Java enum constant name matches the metamodel element name (PascalCase)
        String elementPath = metamodelPath + "_" + javaEnum.name();
        meta.pure.metamodel.PackageableElement element = resolver.getElement(elementPath);
        if (element instanceof meta.pure.metamodel.type.Enum enumInstance)
        {
            return enumInstance;
        }

        // Fallback: return the Java enum as-is
        return javaEnum;
    }

    private static List<Method> getPropertyMethods(Class<?> cls)
    {
        return PROPERTY_METHODS_CACHE.computeIfAbsent(cls, c ->
        {
            List<Method> result = new java.util.ArrayList<>();
            for (Method m : c.getMethods())
            {
                String name = m.getName();
                if (name.startsWith("_") && m.getParameterCount() == 0
                    && !name.equals("_classifierGenericType")
                    && !name.equals("_copy")
                    && !name.equals("_elementOverride"))
                {
                    result.add(m);
                }
            }
            return List.copyOf(result);
        });
    }
}
