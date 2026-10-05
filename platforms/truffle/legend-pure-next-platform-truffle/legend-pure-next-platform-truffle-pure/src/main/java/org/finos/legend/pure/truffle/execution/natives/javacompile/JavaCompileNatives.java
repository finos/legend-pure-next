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

package org.finos.legend.pure.truffle.execution.natives.javacompile;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a Java source string in-memory via {@code javax.tools.JavaCompiler},
 * loads the resulting class via a throw-away class loader, and invokes a named
 * static method on it. Used by {@link CompileAndExecuteNode} to actually run
 * the Java emitted by the Pure→Java translator's PCT / round-trip tests.
 *
 * <p>The source may use the JDK only: javac sees an empty class path and the
 * loaded classes see only the platform class loader. The translator's output
 * must stand alone, and inheriting this JVM's class path would let a reference
 * to a Truffle or Pure class compile and run here unnoticed.</p>
 *
 * <p>A source may hold several compilation units, each introduced by a
 * {@code //// FILE <path>} line (generated platform types are public classes in
 * their own packages).</p>
 */
public class JavaCompileNatives
{
    public static Object compileAndInvoke(String source, String className, String methodName, List<Object> args)
    {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null)
        {
            throw new RuntimeException("No system Java compiler available — the runtime needs to be a JDK, not a JRE");
        }

        List<InMemoryJavaSource> sources = compilationUnits(className, source);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, null);
        try
        {
            standard.setLocation(StandardLocation.CLASS_PATH, List.of());
        }
        catch (IOException e)
        {
            throw new RuntimeException("Cannot clear the class path for in-process javac", e);
        }
        InMemoryFileManager fm = new InMemoryFileManager(standard);

        boolean ok = compiler.getTask(null, fm, diagnostics, null, null, sources).call();
        if (!ok)
        {
            StringBuilder sb = new StringBuilder("Java compilation failed:\n");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics())
            {
                sb.append("  ").append(d).append("\n");
            }
            sb.append("Source:\n").append(source);
            throw new RuntimeException(sb.toString());
        }

        Class<?> cls;
        try
        {
            cls = new InMemoryClassLoader(fm.bytecode, ClassLoader.getPlatformClassLoader()).loadClass(className);
        }
        catch (ClassNotFoundException e)
        {
            throw new RuntimeException("Compiled class '" + className + "' not found after javac succeeded", e);
        }

        Method method = findMethod(cls, methodName, args.size());
        try
        {
            return method.invoke(null, args.toArray());
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Error invoking " + className + "." + methodName + ": " + cause.getMessage(), cause);
        }
        catch (IllegalAccessException e)
        {
            throw new RuntimeException("Cannot access " + className + "." + methodName + " (must be public static)", e);
        }
    }

    /** The compilation units of `source`: one per `//// FILE <path>` block, or the whole source as `className`. */
    private static List<InMemoryJavaSource> compilationUnits(String className, String source)
    {
        if (!source.startsWith("//// FILE ") && !source.contains("\n//// FILE "))
        {
            return List.of(new InMemoryJavaSource(className, source));
        }
        List<InMemoryJavaSource> units = new java.util.ArrayList<>();
        for (String block : source.split("(?m)^//// FILE "))
        {
            if (block.isBlank())
            {
                continue;
            }
            int newline = block.indexOf('\n');
            String path = block.substring(0, newline).trim();
            units.add(new InMemoryJavaSource(path.replaceAll("\\.java$", "").replace('/', '.'), block.substring(newline + 1)));
        }
        return units;
    }

    private static Method findMethod(Class<?> cls, String name, int arity)
    {
        for (Method m : cls.getDeclaredMethods())
        {
            if (m.getName().equals(name) && m.getParameterCount() == arity)
            {
                return m;
            }
        }
        throw new RuntimeException("No method '" + name + "' with arity " + arity + " on " + cls.getName());
    }

    // -----------------------------------------------------------------------
    // In-memory plumbing for javac.
    // -----------------------------------------------------------------------

    private static final class InMemoryJavaSource extends SimpleJavaFileObject
    {
        private final String code;

        InMemoryJavaSource(String className, String code)
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

    private static final class InMemoryClassFile extends SimpleJavaFileObject
    {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        InMemoryClassFile(String className)
        {
            super(URI.create("mem:///" + className.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
        }

        @Override
        public OutputStream openOutputStream()
        {
            return bytes;
        }

        byte[] getBytes()
        {
            return bytes.toByteArray();
        }
    }

    private static final class InMemoryFileManager extends ForwardingJavaFileManager<JavaFileManager>
    {
        final Map<String, InMemoryClassFile> bytecode = new HashMap<>();

        InMemoryFileManager(JavaFileManager delegate)
        {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location,
                                                   String className,
                                                   JavaFileObject.Kind kind,
                                                   FileObject sibling)
        {
            InMemoryClassFile file = new InMemoryClassFile(className);
            bytecode.put(className, file);
            return file;
        }
    }

    private static final class InMemoryClassLoader extends ClassLoader
    {
        private final Map<String, InMemoryClassFile> bytecode;

        InMemoryClassLoader(Map<String, InMemoryClassFile> bytecode, ClassLoader parent)
        {
            super(parent);
            this.bytecode = bytecode;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException
        {
            InMemoryClassFile file = bytecode.get(name);
            if (file == null)
            {
                throw new ClassNotFoundException(name);
            }
            byte[] data = file.getBytes();
            return defineClass(name, data, 0, data.length);
        }
    }
}
