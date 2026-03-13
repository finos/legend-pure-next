/*
 * Copyright 2024 Goldman Sachs
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

import meta.pure.protocol.PureFile;
import meta.pure.protocol.PureFileImpl;
import meta.pure.protocol.Section;
import meta.pure.protocol.SectionImpl;
import meta.pure.protocol.grammar.PackageableElement;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.list.mutable.ListAdapter;
import org.finos.legend.pure.next.parser.ParserExtension;
import org.finos.legend.pure.next.parser.TopLexer;
import org.finos.legend.pure.next.parser.TopParser;
import org.finos.legend.pure.next.parser.m3.PureLanguageParser;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level parser that splits source text into sections delimited by
 * {@code ###SectionName} headers and dispatches each section's content
 * to the appropriate parser.
 *
 * <p>Imports are parsed at this level (section concern) and placed
 * on each {@link Section}'s imports list. The remaining content
 * is dispatched to the parser for that section.</p>
 *
 * <p>Content before the first {@code ###} header is treated as a
 * default "Pure" section.</p>
 *
 * <p>Custom section types are supported via {@link ParserExtension}s.</p>
 *
 * <p>Usage:
 * <pre>
 * PureFile file = TopLevelProtocolBuilder.parse(source, "test.pure");
 * </pre>
 * </p>
 */
public final class TopLevelProtocolBuilder
{
    private static final String DEFAULT_SECTION_NAME = "Pure";

    private TopLevelProtocolBuilder()
    {
    }

    /**
     * Parse source text into a PureFile containing sections.
     *
     * @param source     the full source text
     * @param sourceId   identifier for the source (e.g. file path)
     * @param extensions parser extensions for custom section types
     * @return PureFile with parsed sections and sourceId
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

        // If the source has no ### header, treat it as implicit ###Pure
        boolean syntheticHeader = !source.startsWith("###");
        String effectiveSource = syntheticHeader
                ? "###Pure\n" + source
                : source;

        TopLexer lexer = new TopLexer(CharStreams.fromString(effectiveSource));
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
                        "Top-level parse error at line " + line + ":"
                                + charPositionInLine + " - " + msg);
            }
        });

        TopParser.DocumentContext document = parser.document();

        // All sections (including default Pure section, prepended above)
        MutableList<Section> sections = ListAdapter.adapt(document.section()).collect(sectionCtx ->
        {
            String sectionName = sectionCtx.SECTION_HEADER().getText().substring(3);
            return buildSection(sectionName, sourceId, sectionCtx.importStatement(), sectionCtx.sectionContent(), parsers, syntheticHeader);
        });

        return new PureFileImpl()
                ._sourceId(sourceId)
                ._sections(sections);
    }

    private static Section buildSection(
            final String sectionName,
            final String sourceId,
            final List<TopParser.ImportStatementContext> importStatements,
            final TopParser.SectionContentContext contentCtx,
            final Map<String, ParserExtension> parsers,
            final boolean syntheticHeader)
    {
        // Extract imports
        MutableList<String> imports = importStatements == null
                ? Lists.mutable.empty()
                : ListAdapter.adapt(importStatements).collect(importCtx ->
                        importCtx.IMPORT_STATEMENT().getText().substring("import ".length()).trim());

        // Compute line offset: the line where actual (non-whitespace) content starts
        // in the original file. We skip leading NEWLINE content tokens since
        // extractContent() trims them. ANTLR lines are 1-based; subtract 1 so
        // offset is 0-based (i.e., content line 1 + offset = actual file line).
        int lineOffset = 0;
        if (contentCtx != null && !contentCtx.contentToken().isEmpty())
        {
            for (TopParser.ContentTokenContext tok : contentCtx.contentToken())
            {
                if (tok.NEWLINE() == null)
                {
                    lineOffset = tok.getStart().getLine() - 1;
                    // If ###Pure was synthetically added, subtract 1 to compensate
                    if (syntheticHeader)
                    {
                        lineOffset -= 1;
                    }
                    break;
                }
            }
        }

        // Extract content
        String content = extractContent(contentCtx);

        // Dispatch to registered parser
        ParserExtension parserExtension = parsers.get(sectionName);
        if (parserExtension == null)
        {
            throw new RuntimeException(
                    "No parser registered for section: ###" + sectionName);
        }

        List<PackageableElement> elements = content.isEmpty()
                ? Lists.mutable.empty()
                : parserExtension.parseSection(content, sourceId, lineOffset);

        return new SectionImpl()
                ._parserName(sectionName)
                ._imports(imports)
                ._elements(Lists.mutable.withAll(elements));
    }

    private static String extractContent(final TopParser.SectionContentContext ctx)
    {
        if (ctx == null || ctx.contentToken().isEmpty())
        {
            return "";
        }
        return ListAdapter.adapt(ctx.contentToken())
                .injectInto(new StringBuilder(), (sb, token) -> sb.append(token.getText()))
                .toString()
                .trim();
    }
}
