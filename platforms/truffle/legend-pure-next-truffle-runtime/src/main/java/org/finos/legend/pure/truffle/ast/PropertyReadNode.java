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

    /**
     * Sentinel returned by {@link #executeOrAbsent} when the target has no
     * such property at all (vs. has it and it's empty). Callers that need
     * to distinguish those two cases — e.g. enumeration property access,
     * which falls back to enum-value lookup only when the property doesn't
     * exist — should use {@code executeOrAbsent} and check for this token.
     */
    public static final Object ABSENT = new Object();

    public Object execute(Object target, String propName)
    {
        Object result = executeOrAbsent(target, propName);
        return result == ABSENT ? org.finos.legend.pure.truffle.types.PureSequence.EMPTY : result;
    }

    /**
     * Like {@link #execute}, but returns {@link #ABSENT} when the target
     * class has no getter for the property (rather than masking that as an
     * empty sequence).
     */
    public Object executeOrAbsent(Object target, String propName)
    {
        if (target == null)
        {
            return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        }
        // RawClosure wraps a LambdaFunction in a plain record (capturedValues,
        // callTarget, ...) and doesn't implement the LambdaFunction interface.
        // Delegate property reads to the underlying lambda — otherwise
        // `$closure.expressionSequence` etc. silently return EMPTY, which made
        // testShortCircuitInDynamicEvaluation observe a lambda with an empty
        // body when it copied `$fn.expressionSequence` to ^LambdaFunction(...).
        if (target instanceof RawClosure rc)
        {
            target = rc.lambda();
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
        return ABSENT;
    }
}
