// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.m3.module.localModule.topLevel;

/**
 * Stable semantic identifiers for {@link CompilationContext#rollbackErrorsTo(int, String)}
 * call sites. The Pure compiler mirrors the same names in {@code rollbackErrors}
 * and {@code bumpRollbackSite} so {@code ###CompilerStats} histograms diff
 * one-to-one across the two implementations without depending on source-line
 * numbers.
 *
 * <p>Each constant names <em>what kind of speculative work was discarded</em>,
 * not which line of code did the discarding. Adding/removing comments or
 * splitting a method must not change these values.</p>
 */
public final class RollbackSite
{
    private RollbackSite() {}

    /** Pre-resolve speculative compile of non-lambda params before the multi-candidate fold. Always rolls back. */
    public static final String MULTI_CANDIDATE_PRE_RESOLVE = "MULTI_CANDIDATE_PRE_RESOLVE";

    /** A candidate try inside the multi-candidate fold produced errors and was discarded. */
    public static final String CANDIDATE_FAILED = "CANDIDATE_FAILED";

    /** A param failed to resolve during the fixpoint pass; saved errors stay on the {@code ParameterInfo} for replay. */
    public static final String PARAM_UNRESOLVED = "PARAM_UNRESOLVED";

    /** Lambda dispatch in {@code resolveParameterValue} — strip pre-resolve errors before {@code finishProcessing}. */
    public static final String LAMBDA_DISPATCH = "LAMBDA_DISPATCH";

    /** Reverse-match dispatch in {@code resolveParameterValue} — strip pre-resolve errors before {@code finishProcessing}. */
    public static final String REVERSE_MATCH_DISPATCH = "REVERSE_MATCH_DISPATCH";

    /** Collection dispatch in {@code resolveParameterValue} — strip pre-resolve errors before per-element walk. */
    public static final String COLLECTION_DISPATCH = "COLLECTION_DISPATCH";

    /** Post-finishProcessing rollback in the collection branch — discards errors that {@code VSR.resolve} re-produced for already-processed elements. */
    public static final String POST_FINISH_COLLECTION_ROLLBACK = "POST_FINISH_COLLECTION_ROLLBACK";
}
