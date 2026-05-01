// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.test;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.function.FunctionDefinition;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.extensions.compiledgraph.CompiledGraphLanguageExtension;
import org.finos.legend.pure.m3.extensions.error.ErrorLanguageExtension;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.PureContent;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Side-by-side trace comparison for the Java compiler vs the Pure-on-Java
 * compiler on a single test fixture. Both produce the same `validateParam`,
 * `trying candidate`, `=> RESOLVED` debug lines (the Pure compiler's debug
 * helper mirrors the Java compiler's format strings), so a diff of the two
 * traces points directly at the algorithmic divergence.
 */
public final class TraceCompare
{
    public static void main(String[] args) throws Exception
    {
        if (args.length < 1)
        {
            System.err.println("Usage: TraceCompare <fixture.pure>");
            System.exit(1);
        }
        Path fixture = Paths.get(args[0]);
        String content = Files.readString(fixture);
        String testName = fixture.getFileName().toString().replaceFirst("\\.pure$", "");

        // Strip the ###CompiledGraph section so the compiler doesn't trip over the
        // expected-output fixture text. We only want the ###Pure source.
        int graphIdx = content.indexOf("###CompiledGraph");
        String pureContent = graphIdx >= 0 ? content.substring(0, graphIdx) : content;

        // Trace 1: Java bootstrap compiler (DEBUG enabled via system property)
        Path javaTracePath = Paths.get("/tmp/java-trace.txt");
        runJavaCompile(testName, pureContent, javaTracePath);

        // Trace 2: Pure-on-Java compiler (debug=true via 2-arg compile)
        Path pureTracePath = Paths.get("/tmp/pure-trace.txt");
        runPureCompile(testName, pureContent, pureTracePath);

        System.out.println();
        System.out.println("Java trace:  " + javaTracePath + " (" + Files.size(javaTracePath) + " bytes)");
        System.out.println("Pure trace:  " + pureTracePath + " (" + Files.size(pureTracePath) + " bytes)");
        System.out.println();
        System.out.println("Diff with: diff " + javaTracePath + " " + pureTracePath);
    }

    private static void runJavaCompile(String testName, String content, Path traceOut) throws Exception
    {
        // The DEBUG flag is read at class-load time, so it must already be set
        // via -Dlegend.pure.compileDebug=true on the JVM before this main runs.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        System.setOut(new PrintStream(buf, true));
        try
        {
            CompiledGraphLanguageExtension cgExt = new CompiledGraphLanguageExtension();
            PureLanguageExtension pureExt = new PureLanguageExtension();
            PDBModule corePdb = new PDBModule(BootstrapModule.locateCorePdb(), PDBModule.Mode.COMPILATION);
            PureModel model = PureModel.withModules(Lists.mutable.with(
                    new LocalModule("test", "*", Lists.mutable.with(corePdb.getName()),
                            Lists.mutable.with(new PureContent(content, testName))),
                    corePdb))
                    .withExtensions(Lists.mutable.with(cgExt, pureExt))
                    .build();
            CompilationResult r = model.compile();
            origOut.println("[Java] errors=" + r.errors().size());
        }
        finally
        {
            System.setOut(origOut);
        }
        Files.writeString(traceOut, buf.toString());
    }

    private static void runPureCompile(String testName, String content, Path traceOut) throws Exception
    {
        Path corePdb = BootstrapModule.locateCorePdb();
        Path compilerPdb = BootstrapModule.locateCompilerPdb();
        PDBModule coreModule = new PDBModule(corePdb, PDBModule.Mode.COMPILATION,
                "core", "*", List.of());
        PDBModule compilerModule = new PDBModule(compilerPdb, PDBModule.Mode.COMPILATION,
                "compiler", "*", List.of("core"));
        PureModel model = PureModel.withModules(Lists.mutable.with(coreModule, compilerModule))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();
        ScopedMetadataAccess resolver = new ScopedMetadataAccess(compilerModule, model);
        PureExecution execution = PureExecution.builder()
                .withResolver(resolver)
                .withParserExtensions(List.of(new CompiledGraphLanguageExtension(), new ErrorLanguageExtension()))
                .build();
        // compileSource($sourceId, $content, $debug) — wraps parse + compile so
        // we don't need to construct a PureFile manually; debug=true threads
        // through to the Pure runtime's debug helper.
        String fnPath = "meta::pure::compiler::compileSource_String_1__String_1__Boolean_1__CompilationResult_1_";
        PackageableElement fn = compilerModule.getElement(fnPath);
        if (fn == null)
        {
            throw new RuntimeException("compileSource not found: " + fnPath);
        }

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        System.setOut(new PrintStream(buf, true));
        try
        {
            execution.execute((FunctionDefinition) fn, testName, content, true);
        }
        finally
        {
            System.setOut(origOut);
        }
        Files.writeString(traceOut, buf.toString());
    }

    private TraceCompare() {}
}
