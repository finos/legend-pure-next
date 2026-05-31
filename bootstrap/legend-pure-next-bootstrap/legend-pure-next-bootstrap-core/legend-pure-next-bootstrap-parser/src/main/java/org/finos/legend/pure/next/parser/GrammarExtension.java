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

package org.finos.legend.pure.next.parser;

import org.antlr.v4.runtime.ParserRuleContext;

/**
 * Plug-in point for an ANTLR grammar. Each registered extension answers
 * to a single {@code grammarName} (e.g. {@code "M3Parser"},
 * {@code "TopParser"}) and produces a root {@link ParserRuleContext} from
 * a source string.
 *
 * <p>Registered via {@link PureExecution.Builder#withGrammarExtensions} and
 * looked up by the {@code parseAntlr} native: Pure code calls
 * {@code parseAntlr($source, 'M3Parser', $sourceId, $lineOffset)}; the native
 * resolves the matching {@code GrammarExtension} from the per-runtime
 * registry and invokes its {@link #parse}.</p>
 *
 * <p>The execution module stays grammar-agnostic — it only knows about
 * {@code ParserRuleContext} (antlr-runtime). Concrete grammars (M3, top-level,
 * future ones) live in downstream parser modules and contribute
 * implementations of this interface.</p>
 *
 * <p>Sibling to {@link org.finos.legend.pure.next.parser.ParserExtension}
 * (section-body parsing). They are orthogonal:
 * {@code GrammarExtension} = "I parse a string into an ANTLR tree";
 * {@code ParserExtension} = "I parse a {@code ###Name} section body into
 * a list of protocol PDOs".</p>
 */
public interface GrammarExtension
{
    /**
     * The grammar identifier — the string Pure code passes to
     * {@code parseAntlr($source, $grammarName, ...)}. Must be unique across
     * registered extensions; a duplicate name throws at registration time.
     */
    String grammarName();

    /**
     * Parse {@code source} with this grammar. Errors must throw with
     * {@code sourceId} + (line+lineOffset) folded into the message so the
     * user sees file-relative coordinates when the call is nested inside a
     * section body (the section's first line is {@code lineOffset} lines
     * into the file).
     */
    ParserRuleContext parse(String source, String sourceId, int lineOffset);
}
