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

package org.finos.legend.pure.platform.java.execution;

import org.finos.legend.pure.platform.java.compiler.module.pdbModule.Metadata;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * Calls a translated Pure function: the function's own class when the platform has one, else
 * translating and compiling it first.
 *
 * <p>Everything that runs Pure here goes through {@link #call} — parsing, compiling, and an
 * extension's compiled passes alike — which is why it belongs to the engine.</p>
 *
 * <p>The COMMAND LINE is not here: {@code main} lives in the CLI module
 * ({@code org.finos.legend.pure.platform.java.cli.PureJava}), because it installs the languages and
 * the engine must not name one. Dependencies run from an extension to the engine, never back.</p>
 */
public final class Execute
{
    /** The translator's Java reserved words (safeJavaName): package segments among them end with `_`. */
    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while");

    private Execute()
    {
    }


    /**
     * Calls the function at `path`: its class when the platform has one, else
     * the platform translates and compiles it first (a test, say).
     */
    public static Object call(String path, Object... args) throws Exception
    {
        try
        {
            return invoke(path, args);
        }
        catch (ClassNotFoundException e)
        {
            return Metadata.translateAndInvoke(path, List.of(args));
        }
    }

    /** Calls the translated function at `path` (element path with signature) with `args`. */
    public static Object invoke(String path, Object... args) throws Exception
    {
        Method execute = executeMethod(Class.forName(className(path)), args.length);
        try
        {
            return execute.invoke(null, execute.isVarArgs() ? new Object[]{args} : args);
        }
        catch (IllegalArgumentException e)
        {
            throw new RuntimeException("Cannot call " + JavaSource.describe(execute, List.of(args)), e);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof Exception)
            {
                throw (Exception) cause;
            }
            // An Error travels too. Every assert coder throws AssertionError, and
            // flattening one into the InvocationTargetException — whose own message
            // is null — made Metadata.invoke report `Cannot call <path>: null` in
            // place of the assertion's diff, hiding the real failure of every
            // assertError-based test on this platform.
            if (cause instanceof Error)
            {
                throw (Error) cause;
            }
            throw e;
        }
    }

    /** The Java class of a function: its signature name in the platform package mirroring its Pure package. */
    public static String className(String path)
    {
        String[] segments = path.split("::");
        StringBuilder name = new StringBuilder("org.finos.legend.pure.m3");
        for (int i = 0; i < segments.length - 1; i++)
        {
            name.append('.').append(RESERVED.contains(segments[i]) ? segments[i] + "_" : segments[i]);
        }
        return name.append('.').append(segments[segments.length - 1].replace('~', '_')).toString();
    }

    private static Method executeMethod(Class<?> cls, int arity)
    {
        for (Method m : cls.getMethods())
        {
            if (m.getName().equals("execute") && (m.getParameterCount() == arity || m.isVarArgs()))
            {
                return m;
            }
        }
        throw new IllegalArgumentException("No execute/" + arity + " on " + cls.getName());
    }
}
