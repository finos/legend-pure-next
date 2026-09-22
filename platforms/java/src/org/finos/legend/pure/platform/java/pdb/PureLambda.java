// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.platform.java.pdb;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A Pure lambda on the Java platform: callable like a Java lambda, and its own
 * metadata. Code that translates or reflects on a function value (the PCT
 * adapter, `openVariableValues`, `expressionSequence`) needs the AST, which a
 * bare Java lambda has none of.
 *
 * <p>The lambda is addressed by the function it was translated from and its
 * index in that function's lambdas — the translator and this class read that
 * order from the same Pure walk (`lambdasInFunction`), so they agree.</p>
 */
public final class PureLambda
{
    private static final String LAMBDAS_IN_FUNCTION =
            "meta::external::language::java::translation::lambdasInFunction_String_1__LambdaFunction_MANY_";

    private PureLambda()
    {
    }

    /** `callable` as a value that is also the lambda's metadata. */
    public static Object of(Object callable, String owner, long index)
    {
        return of(callable, owner, index, java.util.Collections.emptyMap());
    }

    /** The same, with the values the lambda captured (see `openVariableValues`). */
    public static Object of(Object callable, String owner, long index, java.util.Map<?, ?> captures)
    {
        Class<?> lambdaFunction;
        try
        {
            lambdaFunction = Class.forName("org.finos.legend.pure.m3.meta.pure.metamodel.function.LambdaFunction");
        }
        catch (ClassNotFoundException e)
        {
            // No platform types: the value stays a plain Java lambda.
            return callable;
        }
        // Supplier as well as the arity's interface, so a value can be called
        // however the caller expects. Function and BiFunction cannot both be
        // implemented (their andThen methods clash), so the arity picks one.
        Class<?> byArity = callable instanceof org.finos.legend.pure.platform.java.runtime.PureFunction3
                ? org.finos.legend.pure.platform.java.runtime.PureFunction3.class
                : callable instanceof BiFunction ? BiFunction.class : Function.class;
        Class<?>[] interfaces = new Class<?>[]{lambdaFunction, Supplier.class, byArity};
        return Proxy.newProxyInstance(PureLambda.class.getClassLoader(), interfaces, new Handler(callable, owner, index, captures));
    }

    /**
     * A FUNCTION REFERENCE — `someFunction_Integer_1__String_1_` used as a
     * value. It is callable like any lambda, and it IS the element it names:
     * `assertIs(someFunction, pathToElement('...'))` holds because both sides
     * answer with the same metadata element.
     */
    public static Object ofElement(Object callable, String elementPath)
    {
        Class<?> lambdaFunction;
        try
        {
            lambdaFunction = Class.forName("org.finos.legend.pure.m3.meta.pure.metamodel.function.LambdaFunction");
        }
        catch (ClassNotFoundException e)
        {
            return callable;
        }
        Class<?> byArity = callable instanceof org.finos.legend.pure.platform.java.runtime.PureFunction3
                ? org.finos.legend.pure.platform.java.runtime.PureFunction3.class
                : callable instanceof BiFunction ? BiFunction.class : Function.class;
        Class<?>[] interfaces = new Class<?>[]{lambdaFunction, Supplier.class, byArity};
        return Proxy.newProxyInstance(PureLambda.class.getClassLoader(), interfaces,
                new Handler(callable, elementPath, -1, java.util.Collections.emptyMap()));
    }

    /** The element a function reference stands for, or null for anything else. */
    public static Object elementOf(Object value)
    {
        if (value != null && Proxy.isProxyClass(value.getClass()) && Proxy.getInvocationHandler(value) instanceof Handler)
        {
            Handler handler = (Handler) Proxy.getInvocationHandler(value);
            if (handler.isElement())
            {
                return handler.element();
            }
        }
        return null;
    }

    /** True when `value` is a lambda this class wrapped. */
    static boolean isPureLambda(Object value)
    {
        return value != null && Proxy.isProxyClass(value.getClass()) && Proxy.getInvocationHandler(value) instanceof Handler;
    }

    /** What `lambda` captured, when it is one of ours; nothing otherwise. */
    static java.util.Map<?, ?> capturesOf(Object lambda)
    {
        if (Proxy.isProxyClass(lambda.getClass()) && Proxy.getInvocationHandler(lambda) instanceof Handler)
        {
            return ((Handler) Proxy.getInvocationHandler(lambda)).captures();
        }
        return java.util.Collections.emptyMap();
    }

    private static final class Handler implements InvocationHandler
    {
        private final Object callable;
        private final String owner;
        private final long index;
        private final java.util.Map<?, ?> captures;
        private Object ast;

        Handler(Object callable, String owner, long index, java.util.Map<?, ?> captures)
        {
            this.callable = callable;
            this.owner = owner;
            this.index = index;
            this.captures = captures;
        }

        java.util.Map<?, ?> captures()
        {
            return captures;
        }

        /** A function reference names an element rather than a lambda inside one. */
        boolean isElement()
        {
            return index < 0;
        }

        Object element()
        {
            return Metadata.lenientElement(owner);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
        {
            String name = method.getName();
            Object[] arguments = args == null ? new Object[0] : args;
            if (name.equals("get") || name.equals("apply"))
            {
                return Metadata.invoke(callable, Arrays.asList(arguments));
            }
            if (name.equals("_purePath"))
            {
                return "meta::pure::metamodel::function::LambdaFunction";
            }
            if (name.equals("equals") && arguments.length == 1)
            {
                return proxy == arguments[0];
            }
            if (name.equals("hashCode"))
            {
                return System.identityHashCode(proxy);
            }
            if (name.equals("toString"))
            {
                return isElement() ? owner : owner + "$lambda/" + index;
            }
            return onAst(method, arguments);
        }

        /** Every other call is the lambda's metadata answering for itself. */
        private Object onAst(Method method, Object[] arguments) throws Throwable
        {
            Object lambda = ast();
            try
            {
                return lambda.getClass().getMethod(method.getName(), method.getParameterTypes()).invoke(lambda, arguments);
            }
            catch (NoSuchMethodException e)
            {
                throw new UnsupportedOperationException("A lambda value has no " + method.getName() + "()");
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause() == null ? e : e.getCause();
            }
        }

        private Object ast()
        {
            if (ast == null && isElement())
            {
                ast = Metadata.element(owner);
            }
            if (ast == null)
            {
                try
                {
                    List<?> lambdas = (List<?>) org.finos.legend.pure.platform.java.Execute.invoke(LAMBDAS_IN_FUNCTION, owner);
                    if (index >= lambdas.size())
                    {
                        throw new IllegalStateException("No lambda " + index + " in " + owner + " (it has " + lambdas.size() + ")");
                    }
                    ast = lambdas.get((int) index);
                }
                catch (RuntimeException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    throw new RuntimeException("Cannot read the metadata of " + owner + "$lambda/" + index + ": " + e.getMessage(), e);
                }
            }
            return ast;
        }
    }
}
