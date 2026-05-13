// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.ide.backend;

import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.cli.CompilerNatives;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.CompilationStatistics;
import org.finos.legend.pure.m3.module.ElementStatistics;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.localModule.LocalModule;

import java.util.ArrayList;
import java.util.List;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * The original Pure-on-Java backend. Wraps {@link PureExecution}. Same code
 * path the IDE has used since it shipped.
 */
public final class JavaDirectBackend implements PureBackend
{
    @Override
    public String name()
    {
        return "java-direct";
    }

    @Override
    public ExecutionResult execute(MutableList<LocalModule> editableModules,
                                   PureModel model,
                                   CompilationResult compileResult,
                                   FunctionDefinition function,
                                   ValueSpecification... args)
    {
        // Prefer the welcome module as the resolver scope so user-defined
        // elements shadow same-named library elements; fall back to the
        // first module if welcome isn't loaded.
        Module scope = model.getModule("welcome");
        if (scope == null && !model.modules().isEmpty())
        {
            scope = model.modules().get(0);
        }
        PureExecution execution = PureExecution.builder()
                .withResolver(scope != null
                        ? new ScopedMetadataAccess(scope, model)
                        : model.modules().get(0))
                .withNativeExtensions(Lists.mutable.with(new CompilerNatives()))
                .build();

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(captured));
        Object result = null;
        Throwable err = null;
        try
        {
            result = args.length == 0
                    ? execution.execute(function)
                    : execution.execute(function, (Object[]) args);
        }
        // PureAssertionError extends RuntimeException — single catch covers
        // assertion failures and other execution errors.
        catch (RuntimeException e)
        {
            err = e;
        }
        finally
        {
            System.setOut(original);
        }
        return new ExecutionResult(
                captured.toString(StandardCharsets.UTF_8),
                toCompileStats(compileResult),
                result,
                err);
    }

    /**
     * Translate the Java-direct {@link CompilationStatistics} into the
     * backend-agnostic {@link CompileStats}. Returns {@code null} if no
     * compile happened in this request (e.g. caller reused a cached model).
     *
     * <p>Java-direct measures durations in nanoseconds; we round to ms to
     * match the Pure-side schema and the IDE's display units.</p>
     */
    private static CompileStats toCompileStats(CompilationResult compileResult)
    {
        if (compileResult == null || compileResult.statistics() == null)
        {
            return null;
        }
        CompilationStatistics s = compileResult.statistics();
        List<CompileStats.ElementStats> elements = new ArrayList<>();
        s.elementStatistics().forEach((path, es) ->
                elements.add(new CompileStats.ElementStats(
                        es.elementPath(),
                        es.elementType(),
                        es.totalNanos() / 1_000_000L,
                        es.inferenceRollbacks(),
                        es.candidateEvaluations())));
        return new CompileStats(
                s.totalDurationNanos() / 1_000_000L,
                s.parsingDurationNanos() / 1_000_000L,
                s.firstPassDurationNanos() / 1_000_000L,
                s.secondPassDurationNanos() / 1_000_000L,
                s.thirdPassDurationNanos() / 1_000_000L,
                s.elementCount(),
                s.sourceFileCount(),
                s.inferenceRollbackCount(),
                s.candidateEvaluationCount(),
                elements);
    }
}
