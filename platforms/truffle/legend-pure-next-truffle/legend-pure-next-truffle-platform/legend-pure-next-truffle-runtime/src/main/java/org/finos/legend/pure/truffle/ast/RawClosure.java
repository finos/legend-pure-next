// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.RootCallTarget;

/**
 * A Pure closure carrying raw (unboxed) captured values.
 *
 * <p>Replaces the Java-evaluator's {@code Closure} (which extends
 * {@code LambdaFunctionImpl} and stores captured scope as
 * {@code Map<String, ValueSpecification>}). {@code RawClosure} is a plain
 * POJO — no metamodel inheritance, no {@code ValueSpecification} types.</p>
 *
 * <p>At capture time, {@code RawLambdaCaptureNode} snapshots open-variable
 * values from the enclosing frame's slots into {@link #capturedValues}.
 * At invocation time, {@code RawLambdaRootNode} writes them back into the
 * callee's frame at the corresponding slot indices before executing the
 * body.</p>
 *
 * @param lambda         the IR reference from the PDB (carries parameter
 *                       declarations, expression sequence, open-variable
 *                       list)
 * @param capturedValues raw Java values (Long, String, DynamicInstance,
 *                       etc.) parallel to {@link #capturedNames}
 * @param capturedNames  variable names parallel to {@link #capturedValues},
 *                       used by the callee's {@code FrameLayout} to find
 *                       the right slot
 * @param callTarget     compiled Truffle {@link RootCallTarget} for this
 *                       lambda, or {@code null} if compilation failed and
 *                       the fallback path should be used
 */
public record RawClosure(
        Object lambda,
        Object[] capturedValues,
        String[] capturedNames,
        RootCallTarget callTarget)
{
}
