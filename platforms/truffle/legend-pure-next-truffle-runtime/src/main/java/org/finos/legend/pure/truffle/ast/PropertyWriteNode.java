package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.Node;

/**
 * Cached property setter node. Caches the setter MethodHandle by
 * (targetClass, propertyName) for JIT-friendly property writes.
 *
 * <p>Used as a {@code @Child} in PropertyAssignNode, NewWithKeysNode,
 * and CopyWithKeysNode.</p>
 */
public final class PropertyWriteNode extends Node
{
    @CompilationFinal
    private Class<?> cachedClass;

    @CompilationFinal
    private String cachedPropName;

    @CompilationFinal
    private java.lang.invoke.MethodHandle cachedSetter;

    @CompilationFinal
    private Class<?> cachedParamType;

    public void execute(Object target, String propName, Object value)
    {
        Class<?> targetClass = target.getClass();
        if (targetClass == cachedClass && propName.equals(cachedPropName) && cachedSetter != null)
        {
            invokeWithCoercion(target, value);
            return;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        lookupAndInvoke(target, propName, value, targetClass);
    }

    private void invokeWithCoercion(Object target, Object value)
    {
        try
        {
            Object coerced = coerceValue(value, cachedParamType);
            ensureEnumCGT(coerced);
            cachedSetter.invoke(target, coerced);
        }
        catch (Throwable t)
        {
            throw new RuntimeException("Error setting property", t);
        }
    }

    private void ensureEnumCGT(Object value)
    {
        if (value instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any any
                && any._classifierGenericType() == null
                && value.getClass().isEnum())
        {
            Class<?>[] ifaces = value.getClass().getInterfaces();
            if (ifaces.length > 0)
            {
                String purePath = ifaces[0].getName()
                        .replace("org.finos.legend.pure.truffle.pdb.", "")
                        .replace(".", "::");
                var resolver = org.finos.legend.pure.truffle.PureLanguage.get(this).resolver();
                if (resolver != null)
                {
                    Object enumType = resolver.getElement(purePath);
                    if (enumType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t)
                    {
                        any._classifierGenericType(
                                org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(t, resolver));
                    }
                }
            }
        }
    }

    private void lookupAndInvoke(Object target, String propName, Object value, Class<?> targetClass)
    {
        String setterName = "_" + propName;
        try
        {
            for (java.lang.reflect.Method m : targetClass.getMethods())
            {
                if (m.getName().equals(setterName) && m.getParameterCount() == 1)
                {
                    cachedClass = targetClass;
                    cachedPropName = propName;
                    cachedSetter = java.lang.invoke.MethodHandles.lookup().unreflect(m);
                    cachedParamType = m.getParameterTypes()[0];
                    invokeWithCoercion(target, value);
                    return;
                }
            }
        }
        catch (IllegalAccessException e)
        {
            throw new RuntimeException("Cannot access setter '_" + propName + "' on " + targetClass.getName(), e);
        }
        throw new RuntimeException("Setter '_" + propName + "' not found on " + targetClass.getName());
    }

    private Object coerceValue(Object value, Class<?> paramType)
    {
        if (value == null) return null;
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty()) return null;
        if (paramType.isInstance(value)) return value;

        // Unwrap single-element sequence
        Object unwrapped = unwrapForSetter(value);
        if (paramType.isInstance(unwrapped)) return unwrapped;

        // PureSequence coercion
        if (org.finos.legend.pure.truffle.types.PureSequence.class.isAssignableFrom(paramType))
        {
            return toPureSequence(unwrapped);
        }
        if (org.eclipse.collections.api.RichIterable.class.isAssignableFrom(paramType)
                || java.util.Collection.class.isAssignableFrom(paramType))
        {
            return toMutableList(unwrapped);
        }

        // Enum coercion — String or Enum value to Java enum constant
        if (value instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enum enumVal)
        {
            if (paramType.isEnum())
            {
                Object coerced = coerceToJavaEnumConstant(paramType, enumVal._name());
                if (coerced != null) return coerced;
            }
            Object coerced = coerceStringToEnumInterface(paramType, enumVal._name());
            if (coerced != null) return coerced;
        }
        if (value instanceof String s)
        {
            if (paramType.isEnum())
            {
                Object coerced = coerceToJavaEnumConstant(paramType, s);
                if (coerced != null) return coerced;
            }
            Object coerced = coerceStringToEnumInterface(paramType, s);
            if (coerced != null) return coerced;
        }

        return value;
    }

    // =========================================================================
    // Value coercion helpers
    // =========================================================================

    private static Object unwrapForSetter(Object value)
    {
        if (value == null || (value instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty()))
        {
            return null;
        }
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence seq && seq.size() == 1)
        {
            return seq.getBoxed(0);
        }
        return value;
    }

    private static org.finos.legend.pure.truffle.types.PureSequence toPureSequence(Object value)
    {
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence seq) return seq;
        if (value instanceof org.eclipse.collections.api.list.MutableList<?> ml)
        {
            return new org.finos.legend.pure.truffle.types.ObjectSequence(ml.toArray());
        }
        if (value instanceof java.util.List<?> list)
        {
            return new org.finos.legend.pure.truffle.types.ObjectSequence(list.toArray());
        }
        if (value == null)
        {
            return new org.finos.legend.pure.truffle.types.ObjectSequence(new Object[0]);
        }
        return new org.finos.legend.pure.truffle.types.ObjectSequence(new Object[]{value});
    }

    private static org.eclipse.collections.api.list.MutableList<Object> toMutableList(Object value)
    {
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence seq)
        {
            return org.eclipse.collections.api.factory.Lists.mutable.with(seq.toBoxedArray());
        }
        if (value instanceof java.util.List<?> list)
        {
            return org.eclipse.collections.api.factory.Lists.mutable.withAll((java.util.Collection<?>) list);
        }
        if (value == null)
        {
            return org.eclipse.collections.api.factory.Lists.mutable.empty();
        }
        return org.eclipse.collections.api.factory.Lists.mutable.with(value);
    }

    private static Object coerceToJavaEnumConstant(Class<?> enumClass, String name)
    {
        if (name == null) return null;
        for (Object constant : enumClass.getEnumConstants())
        {
            if (constant instanceof java.lang.Enum<?> e && e.name().equals(name))
            {
                return constant;
            }
        }
        return null;
    }

    private static Object coerceStringToEnumInterface(Class<?> targetInterface, String name)
    {
        String valueName = name;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx >= 0) valueName = name.substring(dotIdx + 1);
        int colonIdx = valueName.lastIndexOf(':');
        if (colonIdx >= 0) valueName = valueName.substring(colonIdx + 1);
        try
        {
            Class<?> enumClass = Class.forName(targetInterface.getName() + "Enum");
            if (enumClass.isEnum())
            {
                return coerceToJavaEnumConstant(enumClass, valueName);
            }
        }
        catch (ClassNotFoundException ignored) {}
        return null;
    }
}
