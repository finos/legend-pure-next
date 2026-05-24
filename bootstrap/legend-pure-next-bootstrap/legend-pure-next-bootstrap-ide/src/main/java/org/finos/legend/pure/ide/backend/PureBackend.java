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
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.localModule.LocalModule;

/**
 * Backend abstraction for the Pure IDE — selected once at startup via the
 * {@code --backend java|truffle} CLI flag and used by the LSP server for the
 * execute leg of every command.
 *
 * <p>Diagnostics + navigation stay on Java-direct's {@link PureModel} in both
 * backends (the model is portable and Java-direct's compile is the fastest
 * path for compile-on-save). Backends diverge at execute time:</p>
 * <ul>
 *   <li>Java-direct uses the supplied {@link PureModel} and
 *       {@link FunctionDefinition} directly via {@code PureExecution}.</li>
 *   <li>Truffle ignores the Java-direct model + function (function
 *       representations are incompatible — Truffle's resolver only knows
 *       {@code PureDynamicObject}s). It re-compiles the editable modules via
 *       {@code meta::pure::compiler::parseDir} + {@code meta::pure::compiler::compile}
 *       invoked through {@code PureTruffleRuntime}, then finds the target
 *       function in the resulting {@code CompilationResult.elements} by matching
 *       on {@code function._name()}.</li>
 * </ul>
 */
public interface PureBackend
{
    /** Identifier for logs / UI ({@code "java-direct"} or {@code "truffle"}). */
    String name();

    /**
     * Execute {@code function} from one of the {@code editableModules}.
     *
     * <p>Java-direct uses {@code model} + {@code function} directly and
     * translates the already-computed {@code compileResult} into
     * {@link CompileStats}. Truffle uses {@code editableModules} to locate
     * the source it must re-compile via the Truffle-hosted compiler, then
     * looks up the function by name, and pulls stats from the Truffle-side
     * {@code CompilationResult.statistics} (ignores the Java-direct
     * {@code compileResult}).</p>
     *
     * <p>Stdout is captured during the call and returned in the result.
     * RuntimeExceptions (including {@code PureAssertionError}) are caught
     * and returned in {@link ExecutionResult#error}; callers should inspect
     * {@link ExecutionResult#ok} rather than try/catch.</p>
     */
    ExecutionResult execute(MutableList<LocalModule> editableModules,
                            PureModel model,
                            CompilationResult compileResult,
                            FunctionDefinition function,
                            ProgressSink progress,
                            ValueSpecification... args);

    /** Release resources (Truffle polyglot context, etc.). Idempotent. */
    default void close() {}

    /**
     * Receiver for fine-grained progress events emitted by the backend during
     * {@link #execute}. The IDE plugs in a sink that forwards each event to
     * the browser as a {@code pure/executeProgress} LSP notification so the
     * user sees stage + bar updates live instead of staring at a spinner.
     *
     * <p>Implementations must be thread-safe; events fire from whichever
     * thread the backend uses for compile/execute (Truffle uses its own
     * single-threaded executor, Java-direct uses the caller's thread).</p>
     */
    @FunctionalInterface
    interface ProgressSink
    {
        ProgressSink NOOP = e -> {};

        void post(ProgressEvent event);
    }

    /**
     * Single progress tick.
     * <ul>
     *   <li>{@code stage} — coarse phase name shown as the caption
     *       (e.g. {@code "Parsing"}, {@code "Compile pass 1/3 — Skeletons"},
     *       {@code "Executing"}).</li>
     *   <li>{@code done}/{@code total} — granular counter for a progress bar;
     *       {@code total == 0} means indeterminate (just show the stage).</li>
     *   <li>{@code detail} — what's currently being processed
     *       (e.g. the element being compiled). May be {@code null}.</li>
     * </ul>
     */
    record ProgressEvent(String stage, int done, int total, String detail) {}

    /**
     * Result of an execute call.
     * <ul>
     *   <li>{@code capturedStdout} — Pure {@code println} output from the
     *       executing function. Goes to the IDE Output tab.</li>
     *   <li>{@code compileStats} — structured compile metrics (Truffle only)
     *       pulled directly from {@code CompilationResult.statistics},
     *       sidestepping {@code compile}'s progress-bar stdout. Goes to
     *       the IDE Compile tab. {@code null} for backends that don't run a
     *       compile phase (Java-direct re-uses {@code lastModel}).</li>
     *   <li>{@code error} — thrown exception, or {@code null} on success.</li>
     * </ul>
     */
    record ExecutionResult(String capturedStdout, CompileStats compileStats, Object returnValue, Throwable error)
    {
        public boolean ok()
        {
            return error == null;
        }
    }

    /**
     * Typed mirror of Pure's {@code meta::pure::compiler::CompilationStatistics}.
     * Built by the backend by reading the structured fields off the returned
     * {@code CompilationResult} — never parsed from stdout.
     */
    record CompileStats(
            long totalMillis,
            long parsingMillis,
            long firstPassMillis,
            long secondPassMillis,
            long thirdPassMillis,
            long elementCount,
            long sourceFileCount,
            long inferenceRollbackCount,
            long candidateEvaluationCount,
            java.util.List<ElementStats> elementStatistics)
    {
        public record ElementStats(
                String elementPath,
                String elementType,
                long totalMillis,
                long inferenceRollbacks,
                long candidateEvaluations) {}
    }
}
