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

package org.finos.legend.pure.platform.java;

import org.finos.legend.pure.m3.PdbTypes;
import org.finos.legend.pure.platform.java.pdb.Metadata;
import org.finos.legend.pure.platform.java.pdb.PdbRuntime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Runs a translated Pure function on the Java platform, the counterpart of
 * {@code pure-truffle execute}: opens the PDBs lazily, installs them as the
 * metadata translated code reads, and calls the function's class.
 *
 * <p>Usage: {@code Execute --schema m3.fbs --pdb a.pdb [--pdb b.pdb ...] --function <path> [--args v ...]}.
 * The function path is the element path with its signature
 * ({@code meta::pure::functions::string::joinStrings_String_MANY__String_1__String_1_});
 * arguments are passed as Strings. The result is printed.</p>
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

    public static void main(String[] args) throws Exception
    {
        Path schema = null;
        List<Path> pdbs = new ArrayList<>();
        String function = null;
        List<String> functionArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--schema":
                    schema = Path.of(args[++i]);
                    break;
                case "--pdb":
                    pdbs.add(Path.of(args[++i]));
                    break;
                case "--function":
                    function = args[++i];
                    break;
                case "--args":
                    while (i + 1 < args.length && !args[i + 1].startsWith("--"))
                    {
                        functionArgs.add(args[++i]);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        if (schema == null || pdbs.isEmpty() || function == null)
        {
            System.err.println("usage: Execute --schema m3.fbs --pdb a.pdb [--pdb b.pdb ...] --function <path> [--args v ...]");
            System.exit(2);
        }

        Metadata.install(PdbRuntime.open(schema, PdbTypes::create, pdbs.toArray(new Path[0])));
        System.out.println(call(function, functionArgs.toArray()));
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
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
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
