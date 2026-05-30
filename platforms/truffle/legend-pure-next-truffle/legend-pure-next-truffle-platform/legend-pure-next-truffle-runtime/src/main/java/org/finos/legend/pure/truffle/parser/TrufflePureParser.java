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

package org.finos.legend.pure.truffle.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.finos.legend.pure.next.parser.TopLexer;
import org.finos.legend.pure.next.parser.TopParser;
import org.finos.legend.pure.truffle.parser.pureLanguage.TrufflePureLanguageProtocolBuilder;
import org.finos.legend.pure.truffle.parser.topLevel.TruffleParserExtension;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-source parser that returns a {@code PureDynamicObject} directly,
 * bypassing the {@link org.finos.legend.pure.truffle.runtime.dynobj.PureObj}
 * protocol-Impl → PDO copy.
 *
 * <p>Drives ANTLR ({@link TopLexer}/{@link TopParser}) and delegates the
 * section + import + content-token → {@code PureFile}/{@code Section}
 * construction to the code-generated {@link TruffleTopLevelProtocolBuilder}
 * (compiled from {@code top-mappings.dsl} via the Truffle target). The
 * {@code ###Pure} section's body is in turn parsed via
 * {@link TrufflePureLanguageProtocolBuilder} — the M3 grammar visitor compiled from
 * {@code pure-language-mappings.dsl}.</p>
 */
public final class TrufflePureParser
{
    private static final String DEFAULT_SECTION_NAME = "Pure";

    private final TruffleMetadataAccess resolver;
    private final Map<String, TruffleParserExtension> extensions;

    private TrufflePureParser(TruffleMetadataAccess resolver, Map<String, TruffleParserExtension> extensions)
    {
        this.resolver = resolver;
        this.extensions = extensions;
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public Object parse(String sourceId, String content)
    {
        // If the source has no ### header, treat it as implicit ###Pure (matches bootstrap TopLevelParser).
        boolean syntheticHeader = !content.startsWith("###");
        String effectiveSource = syntheticHeader ? "###Pure\n" + content : content;

        TopLexer lexer = new TopLexer(CharStreams.fromString(effectiveSource));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        TopParser parser = new TopParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener()
        {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg, RecognitionException e)
            {
                throw new RuntimeException("Top-level parse error in file " + sourceId + " at line "
                        + line + ":" + charPositionInLine + " - " + msg);
            }
        });

        TopParser.DocumentContext document = parser.document();
        // Compose the section parser map: ###Pure goes to TrufflePureLanguageProtocolBuilder,
        // other sections (CompiledGraph / CompilerStats / Error) come from the registered extensions.
        Map<String, TruffleParserExtension> all = new LinkedHashMap<>(extensions);
        all.put(DEFAULT_SECTION_NAME, new PureSectionTruffleExtension(resolver));
        return new TruffleTopLevelProtocolBuilder(all, resolver, sourceId, syntheticHeader)
                .buildDocument(document);
    }

    /**
     * Adapter exposing {@link TrufflePureLanguageProtocolBuilder} as a {@link TruffleParserExtension}
     * so the generated dispatcher routes {@code ###Pure} through it like any other
     * section type — no special case in the parser itself.
     */
    private static final class PureSectionTruffleExtension implements TruffleParserExtension
    {
        private final TruffleMetadataAccess resolver;

        PureSectionTruffleExtension(TruffleMetadataAccess resolver) { this.resolver = resolver; }

        @Override public String sectionName() { return DEFAULT_SECTION_NAME; }

        @Override
        public List<Object> parseSection(String content, String sourceId, int lineOffset, TruffleMetadataAccess r)
        {
            return new TrufflePureLanguageProtocolBuilder(this.resolver).parseElements(content, lineOffset);
        }
    }

    public static final class Builder
    {
        private TruffleMetadataAccess resolver;
        private final Map<String, TruffleParserExtension> extensions = new LinkedHashMap<>();

        private Builder() {}

        public Builder resolver(TruffleMetadataAccess resolver)
        {
            this.resolver = resolver;
            return this;
        }

        /**
         * Register {@link TruffleParserExtension}s for non-{@code Pure} sections (e.g.
         * {@code ###CompiledGraph}). {@code ###Pure} is handled internally and must NOT
         * be passed in.
         */
        public Builder withNonPureExtensions(List<? extends TruffleParserExtension> exts)
        {
            for (TruffleParserExtension ext : exts)
            {
                if (DEFAULT_SECTION_NAME.equals(ext.sectionName()))
                {
                    throw new IllegalArgumentException(
                            "TrufflePureParser handles ###Pure internally — do not register a Pure-section "
                                    + "extension. Got: " + ext);
                }
                extensions.put(ext.sectionName(), ext);
            }
            return this;
        }

        public TrufflePureParser build()
        {
            return new TrufflePureParser(resolver, Map.copyOf(extensions));
        }
    }
}
