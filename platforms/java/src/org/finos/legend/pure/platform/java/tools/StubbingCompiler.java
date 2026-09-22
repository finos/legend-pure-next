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

package org.finos.legend.pure.platform.java.tools;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compiles translated Pure with javac, replacing every function class javac
 * rejects by a stub whose {@code execute} throws "not compiled: <first error>",
 * until the rest compiles. Callers of a function always cast its result, so they
 * compile against a stub as against the real class; the gap then shows where it
 * is reached at run time, like a function the translator couldn't translate.
 *
 * <p>Only function classes (a {@code public static ... execute(}) are stubbed:
 * an error in a type or in hand-written code fails the build.</p>
 *
 * <p>Usage: {@code StubbingCompiler <outputDir> <classPath> <sourceDir>...}</p>
 */
public final class StubbingCompiler
{
    private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.$]+);", Pattern.MULTILINE);
    private static final Pattern EXECUTE = Pattern.compile("public static [^\\n]*\\bexecute\\(");
    private static final int MAX_ROUNDS = 20;

    private StubbingCompiler()
    {
    }

    public static void main(String[] args) throws IOException
    {
        if (args.length < 3)
        {
            System.err.println("usage: StubbingCompiler <outputDir> <classPath> <sourceDir>...");
            System.exit(2);
        }
        Path output = Path.of(args[0]);
        String classPath = args[1];
        List<Path> sources = new ArrayList<>();
        for (int i = 2; i < args.length; i++)
        {
            try (Stream<Path> walk = Files.walk(Path.of(args[i])))
            {
                walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(sources::add);
            }
        }

        Map<Path, String> stubbed = new LinkedHashMap<>();
        for (int round = 1; round <= MAX_ROUNDS; round++)
        {
            Map<Path, String> errors = compile(output, classPath, sources);
            if (errors.isEmpty())
            {
                report(sources.size(), stubbed);
                return;
            }
            List<Path> unstubbable = new ArrayList<>();
            for (Map.Entry<Path, String> error : errors.entrySet())
            {
                String source = Files.readString(error.getKey(), StandardCharsets.UTF_8);
                if (!EXECUTE.matcher(source).find() || stubbed.containsKey(error.getKey()))
                {
                    unstubbable.add(error.getKey());
                    continue;
                }
                // The rejected source stays next to its stub (javac ignores it) for debugging.
                Files.writeString(Path.of(error.getKey() + ".rejected"), source, StandardCharsets.UTF_8);
                Files.writeString(error.getKey(), stub(error.getKey(), source, error.getValue()), StandardCharsets.UTF_8);
                stubbed.put(error.getKey(), error.getValue());
            }
            if (!unstubbable.isEmpty())
            {
                System.err.println("javac errors outside function classes:");
                unstubbable.forEach(p -> System.err.println("  " + p + ": " + errors.get(p)));
                System.exit(1);
            }
        }
        System.err.println("still failing after " + MAX_ROUNDS + " rounds");
        System.exit(1);
    }

    /** The first error of each file javac rejects (empty when everything compiled). */
    private static Map<Path, String> compile(Path output, String classPath, List<Path> sources) throws IOException
    {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Files.createDirectories(output);
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8))
        {
            List<String> options = List.of("-nowarn", "-proc:none", "-Xmaxerrs", "1000000", "-cp", classPath, "-d", output.toString());
            javac.getTask(null, files, diagnostics, options, null, files.getJavaFileObjectsFromPaths(sources)).call();
        }
        Map<Path, String> errors = new LinkedHashMap<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics())
        {
            if (d.getKind() == Diagnostic.Kind.ERROR && d.getSource() != null)
            {
                errors.putIfAbsent(Path.of(d.getSource().toUri()), "line " + d.getLineNumber() + ": " + d.getMessage(null).split("\n")[0]);
            }
        }
        return errors;
    }

    private static String stub(Path file, String source, String error)
    {
        Matcher pkg = PACKAGE.matcher(source);
        String packageName = pkg.find() ? pkg.group(1) : "";
        String simpleName = file.getFileName().toString().replaceAll("\\.java$", "");
        String message = ("not compiled: " + packageName + "." + simpleName + ": " + error)
                .replace("\\", "\\\\").replace("\"", "\\\"");
        return (packageName.isEmpty() ? "" : "package " + packageName + ";\n\n")
                + "public class " + simpleName + "\n{\n"
                + "    public static Object execute(Object... args)\n    {\n"
                + "        throw new UnsupportedOperationException(\"" + message + "\");\n"
                + "    }\n}\n";
    }

    private static void report(int total, Map<Path, String> stubbed)
    {
        System.out.println("  compiled " + total + " files, " + stubbed.size() + " function classes stubbed");
        stubbed.forEach((file, error) -> System.out.println("    stub " + file.getFileName() + " (" + error + ")"));
    }
}
