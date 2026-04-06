package org.finos.legend.pure.compiler.pure.natives;

import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;


import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

public class ProtocolToDynamicInstance
{
    private final MetadataAccess resolver;

    public ProtocolToDynamicInstance(MetadataAccess resolver)
    {
        this.resolver = resolver;
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

        // If it's a generated protocol class (from meta.pure.protocol packaging)
        if (javaPOJO.getClass().getName().startsWith("org.finos.legend.pure.m3.generated.protocol.") 
            || javaPOJO.getClass().getName().startsWith("meta.pure.protocol."))
        {
            String fullName = javaPOJO.getClass().getName();
            if (fullName.startsWith("org.finos.legend.pure.m3.generated."))
            {
                fullName = "meta.pure." + fullName.substring("org.finos.legend.pure.m3.generated.".length());
            }
            if (fullName.endsWith("Impl")) {
                fullName = fullName.substring(0, fullName.length() - 4);
            }

            String pureClassPath = fullName.replace(".", "::");
            meta.pure.metamodel.PackageableElement pureClass = resolver.getElement(pureClassPath);
            if (pureClass == null) {
                throw new RuntimeException("Could not find Pure class for protocol object: " + pureClassPath);
            }

            DynamicInstance instance = new DynamicInstance(pureClassPath);
            meta.pure.metamodel.type.generics.GenericType cgt = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) pureClass, resolver);
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
                            
                            // To properly put in a DynamicInstance, it must be wrapped in a ValueSpecification
                            meta.pure.metamodel.multiplicity.Multiplicity mulOne = (meta.pure.metamodel.multiplicity.Multiplicity) resolver.getElement("meta::pure::metamodel::multiplicity::PureOne");
                            meta.pure.metamodel.multiplicity.Multiplicity mulMany = (meta.pure.metamodel.multiplicity.Multiplicity) resolver.getElement("meta::pure::metamodel::multiplicity::ZeroMany");
                            
                            ValueSpecification vs;
                            if (convertedVal instanceof List) {
                                org.eclipse.collections.api.list.MutableList<ValueSpecification> wrappedItems = org.eclipse.collections.api.factory.Lists.mutable.empty();
                                for (Object item : (List<?>) convertedVal) {
                                    wrappedItems.add(_E_ValueSpecification.wrap(item, null, mulOne, resolver));
                                }
                                vs = new meta.pure.metamodel.valuespecification.CollectionImpl(resolver)
                                        ._values(wrappedItems)
                                        ._multiplicity(mulMany);
                            } else {
                                vs = _E_ValueSpecification.wrap(convertedVal, null, mulOne, resolver);
                            }

                            instance.put(propName, vs);
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
