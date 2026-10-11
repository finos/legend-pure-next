/*
 * Copyright 2024 Goldman Sachs
 * ©2026 JP Morgan Chase & Co. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.finos.legend.pure.next.parser.topLevel;

import platform.pure.protocol.PureFile;
import platform.pure.protocol.Section;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.finos.legend.pure.next.parser.ParserExtension;
import org.finos.legend.pure.next.parser.TopLexer;
import org.finos.legend.pure.next.parser.TopParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level parser that splits source text into sections delimited by
 * {@code ###SectionName} headers and dispatches each section's content
 * to the appropriate parser.
 *
 * <p>Drives ANTLR ({@link TopLexer}/{@link TopParser}) and delegates the
 * section + import + content-token → {@link Section}/{@link PureFile}
 * construction to the code-generated {@link TopLevelProtocolBuilder} (compiled
 * from {@code top-mappings.dsl}). Custom section types are supplied as
 * {@link ParserExtension}s; the generated dispatcher routes by section name.</p>
 *
 * <p>Content before the first {@code ###} header is the grammar's implicit
 * section, built as a {@code Pure} section — unless it is only comments and blank
 * lines above a header (a licence header), which is no section at all.</p>
 */
public final class TopLevelParser
{
    private TopLevelParser()
    {
    }

    /**
     * Parse source text into a {@link PureFile} containing sections.
     *
     * @param source     the full source text
     * @param sourceId   identifier for the source (e.g. file path)
     * @param extensions parser extensions for custom section types
     */
    public static PureFile parse(
            final String source,
            final String sourceId,
            final List<? extends ParserExtension> extensions)
    {
        Map<String, ParserExtension> parsers = new LinkedHashMap<>();
        for (ParserExtension ext : extensions)
        {
            parsers.put(ext.sectionName(), ext);
        }

        TopLexer lexer = new TopLexer(CharStreams.fromString(source));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        TopParser parser = new TopParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new org.antlr.v4.runtime.BaseErrorListener()
        {
            @Override
            public void syntaxError(
                    org.antlr.v4.runtime.Recognizer<?, ?> recognizer,
                    Object offendingSymbol,
                    int line, int charPositionInLine,
                    String msg,
                    org.antlr.v4.runtime.RecognitionException e)
            {
                throw new RuntimeException(
                        "Top-level parse error in file " + sourceId + " at line " + line + ":"
                                + charPositionInLine + " - " + msg);
            }
        });

        TopParser.DocumentContext document = parser.document();
        return new TopLevelProtocolBuilder(parsers, sourceId).buildDocument(document);
    }
}
