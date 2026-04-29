package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.Node;

/**
 * Cached property getter node. Caches the getter MethodHandle by
 * (targetClass, propertyName) for JIT-friendly property reads.
 */
public final class PropertyReadNode extends Node
{
    @CompilationFinal
    private Class<?> cachedClass;

    @CompilationFinal
    private String cachedPropName;

    @CompilationFinal
    private java.lang.invoke.MethodHandle cachedGetter;

    public Object execute(Object target, String propName)
    {
        if (target == null)
        {
            return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        }
        Class<?> targetClass = target.getClass();
        if (targetClass == cachedClass && propName.equals(cachedPropName) && cachedGetter != null)
        {
            try
            {
                return cachedGetter.invoke(target);
            }
            catch (Throwable t)
            {
                throw new RuntimeException("Error reading '" + propName + "'", t);
            }
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        return lookupAndInvoke(target, propName, targetClass);
    }

    private Object lookupAndInvoke(Object target, String propName, Class<?> targetClass)
    {
        String methodName = "_" + propName;
        try
        {
            java.lang.reflect.Method method = targetClass.getMethod(methodName);
            if (method.getDeclaringClass() != Object.class)
            {
                cachedClass = targetClass;
                cachedPropName = propName;
                cachedGetter = java.lang.invoke.MethodHandles.lookup().unreflect(method);
                return cachedGetter.invoke(target);
            }
        }
        catch (NoSuchMethodException ignored) {}
        catch (Throwable t)
        {
            throw new RuntimeException("Error reading '" + propName + "' on " + targetClass.getName(), t);
        }
        return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
    }
}
