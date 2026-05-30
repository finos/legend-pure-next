// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.extension.typescriptcompile;

import org.graalvm.polyglot.Value;

/**
 * Opaque handle returned by {@code compile(source)} and consumed by
 * {@code execute(ctx, fnName, args)}. Wraps the evaluated JS module's
 * {@code exports} object.
 *
 * <p>From Pure's perspective this is {@code Any[1]} — a value to pass
 * through, not inspect. The native dispatch path knows the runtime class.</p>
 *
 * <p>The handle carries no metadata-strategy state. Metadata access (lambda
 * PDOs, type lookups, return-class lifting) is the translator's
 * responsibility: emitted source calls a globally-scoped
 * {@code __pureResolve(path)} helper the native wires onto the GraalJS
 * context at init.</p>
 */
final class TypeScriptCompilationContext
{
    /** The evaluated module's {@code exports} object. Looking up a method
     *  on it and calling {@code Value.execute(args)} runs that export. */
    final Value moduleExports;

    TypeScriptCompilationContext(Value moduleExports)
    {
        this.moduleExports = moduleExports;
    }
}
