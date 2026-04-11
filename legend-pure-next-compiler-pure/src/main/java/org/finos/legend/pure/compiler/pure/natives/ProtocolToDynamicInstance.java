package org.finos.legend.pure.compiler.pure.natives;

import meta.pure.metamodel.type.generics.UserDefinedGenericType;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.m3.module.MetadataAccess;


import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

import java.util.function.Function;

public class ProtocolToDynamicInstance
{
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
            if (fullName.endsWith("Impl")) {
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
            if (pureClass == null) {
                throw new RuntimeException("Could not find Pure class for protocol object: " + pureClassPath);
            }

            DynamicInstance instance = new DynamicInstance(pureClassPath);
            UserDefinedGenericType cgt = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) pureClass, resolver);
            instance.setClassifierGenericType(cgt);
            
            // Loop through all _XXX() methods of the POJO to get properties
            for (Method method : javaPOJO.getClass().getMethods())
            {
                String name = method.getName();
                if (name.startsWith("_") && method.getParameterCount() == 0 
                    && !name.equals("_classifierGenericType")
                    && !name.equals("_copy")
                    && !name.equals("_elementOverride"))
                {
                    String propName = name.substring(1);
                    try
                    {
                        Object val = method.invoke(javaPOJO);
                        // We must convert the value
                        if (val != null)
                        {
                            if (val == javaPOJO)
                            {
                                continue; // Avoid trivial self recursion
                            }
                            Object convertedVal = convert(val, depth + 1);
                            
                            // DynamicInstance now stores raw objects directly
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
            }
            return instance;
        }

        // Return primitives as is (String, Long, Boolean)
        return javaPOJO;
    }
}
