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

package org.finos.legend.pure.next.parser.codegen;

import org.finos.legend.pure.next.parser.codegen.mapping.VisitorMappingParser;

import java.util.List;

/**
 * Per-name primitive emitter — translates one DSL function call (e.g.
 * {@code primitiveType("Integer")}) into Java text.
 *
 * <p>Used by {@link VisitorMappingGenerator#registerPrimitive(String, PrimitiveEmitter)}
 * to extend the generator with domain-specific primitives that aren't part of the
 * generic DSL surface. The implementing lambda has access (via the generator's
 * public {@code target()} / {@code emitExpr(...)} accessors) to the active
 * {@link EmitterTarget} and the shared expression-emit helper.</p>
 */
@FunctionalInterface
public interface PrimitiveEmitter
{
    void emit(StringBuilder out,
              List<VisitorMappingParser.ArgContext> args,
              String cachedTextToken,
              boolean inFoldBody);
}
