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

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * `compileAndExecute(source, className, methodName, args)` on the Java platform:
 * compiles Java source in memory and calls a static method on it, so translated
 * code can translate, compile and run further Pure — what the PCT adapter does.
 *
 * <p>The source sees the JDK and this platform's classes (its runtime and
 * generated types), and nothing else. A source may hold several compilation
 * units, each introduced by a {@code //// FILE <path>} line.</p>
 */
public final class JavaSource
{
    private JavaSource()
    {
    }

    public static Object compileAndExecute(String source, String className, String methodName, List<Object> args)
    {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null)
        {
            throw new RuntimeException("No system Java compiler available — the runtime needs to be a JDK, not a JRE");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, null);
        InMemoryFileManager files = new InMemoryFileManager(standard);
        if (!compiler.getTask(null, files, diagnostics, List.of("-nowarn", "-proc:none"), null, units(className, source)).call())
        {
            StringBuilder message = new StringBuilder("Java compilation failed:\n");
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics())
            {
                message.append("  ").append(diagnostic).append("\n");
            }
            throw new RuntimeException(message.append("Source:\n").append(source).toString());
        }

        try
        {
            Class<?> compiled = new InMemoryClassLoader(files.bytecode, JavaSource.class.getClassLoader()).loadClass(className);
            Method method = method(compiled, methodName, args.size());
            try
            {
                // A stub takes (Object...): the arguments go in as one array.
                return method.invoke(null, method.isVarArgs() ? new Object[]{args.toArray()} : args.toArray());
            }
            catch (IllegalArgumentException e)
            {
                throw new RuntimeException("Cannot call " + describe(method, args), e);
            }
        }
        catch (ClassNotFoundException e)
        {
            throw new RuntimeException("Compiled class '" + className + "' not found after javac succeeded", e);
        }
        catch (IllegalAccessException e)
        {
            throw new RuntimeException("Cannot access " + className + "." + methodName + " (must be public static)", e);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException)
            {
                // A Pure error travels unchanged: `assertError` matches the
                // message, and this boundary is invisible to the Pure program.
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("Error invoking " + className + "." + methodName + ": " + cause.getMessage(), cause);
        }
    }

    /** What a method expects against what it was given, for a reflective call that didn't fit. */
    static String describe(Method method, List<Object> args)
    {
        StringBuilder text = new StringBuilder(method.getDeclaringClass().getSimpleName()).append('.').append(method.getName()).append('(');
        for (int i = 0; i < method.getParameterCount(); i++)
        {
            text.append(i == 0 ? "" : ", ").append(method.getParameterTypes()[i].getSimpleName());
        }
        text.append(") given (");
        for (int i = 0; i < args.size(); i++)
        {
            text.append(i == 0 ? "" : ", ").append(args.get(i) == null ? "null" : args.get(i).getClass().getName());
        }
        return text.append(")").toString();
    }

    /** One unit per `//// FILE <path>` block, or the whole source as `className`. */
    private static List<InMemorySource> units(String className, String source)
    {
        if (!source.startsWith("//// FILE ") && !source.contains("\n//// FILE "))
        {
            return List.of(new InMemorySource(className, source));
        }
        List<InMemorySource> units = new ArrayList<>();
        for (String block : source.split("(?m)^//// FILE "))
        {
            if (!block.isBlank())
            {
                int newline = block.indexOf('\n');
                String path = block.substring(0, newline).trim();
                units.add(new InMemorySource(path.replaceAll("\\.java$", "").replace('/', '.'), block.substring(newline + 1)));
            }
        }
        return units;
    }

    private static Method method(Class<?> compiled, String name, int arity)
    {
        for (Method method : compiled.getDeclaredMethods())
        {
            if (method.getName().equals(name) && (method.getParameterCount() == arity || method.isVarArgs()))
            {
                return method;
            }
        }
        throw new RuntimeException("No method '" + name + "' with arity " + arity + " on " + compiled.getName());
    }

    private static final class InMemorySource extends SimpleJavaFileObject
    {
        private final String code;

        InMemorySource(String className, String code)
        {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors)
        {
            return code;
        }
    }

    private static final class InMemoryClass extends SimpleJavaFileObject
    {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        InMemoryClass(String className)
        {
            super(URI.create("mem:///" + className.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
        }

        @Override
        public OutputStream openOutputStream()
        {
            return bytes;
        }

        byte[] bytes()
        {
            return bytes.toByteArray();
        }
    }

    private static final class InMemoryFileManager extends ForwardingJavaFileManager<JavaFileManager>
    {
        final Map<String, InMemoryClass> bytecode = new HashMap<>();

        InMemoryFileManager(JavaFileManager delegate)
        {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling)
        {
            InMemoryClass file = new InMemoryClass(className);
            bytecode.put(className, file);
            return file;
        }
    }

    private static final class InMemoryClassLoader extends ClassLoader
    {
        private final Map<String, InMemoryClass> bytecode;

        InMemoryClassLoader(Map<String, InMemoryClass> bytecode, ClassLoader parent)
        {
            super(parent);
            this.bytecode = bytecode;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException
        {
            InMemoryClass file = bytecode.get(name);
            if (file == null)
            {
                throw new ClassNotFoundException(name);
            }
            byte[] data = file.bytes();
            return defineClass(name, data, 0, data.length);
        }
    }
}
