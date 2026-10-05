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

package org.finos.legend.pure.platform.java.grammar;

import org.antlr.v4.runtime.ParserRuleContext;

/**
 * THE JAVA PLATFORM'S OWN COPY. Platforms do not depend on one another; each owns
 * the grammar plumbing it needs. Keep in step with the bootstrap interface of the
 * same name ({@code org.finos.legend.pure.next.parser.GrammarExtension}) — the
 * contract is what the {@code parseAntlr} native passes and expects back, and the
 * grammar corpus pins it.
 *
 * <p>Plug-in point for an ANTLR grammar. Each registered extension answers to a
 * single {@code grammarName} ({@code "M3Parser"}, {@code "TopParser"}) and produces
 * a root {@link ParserRuleContext} from a source string. Pure code calls
 * {@code parseAntlr($source, 'M3Parser', $sourceId, $lineOffset)}; the native
 * resolves the matching extension and invokes {@link #parse}.</p>
 */
public interface GrammarExtension
{
    /**
     * The grammar identifier — the string Pure code passes to
     * {@code parseAntlr($source, $grammarName, ...)}. Unique across registered
     * extensions.
     */
    String grammarName();

    /**
     * Parse {@code source} with this grammar. Errors throw with {@code sourceId} and
     * (line + lineOffset) folded into the message, so the coordinates are file-relative
     * even when the call is nested inside a section body.
     */
    ParserRuleContext parse(String source, String sourceId, int lineOffset);
}
