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

// REFERENCE PARSER — committed source, hand-maintained until Pure-platform codegen lands.
//
// Canonical source of truth for the top-level visitor mapping is
// pure/specification/grammar/mapping/mappings-pure/mapping_top.pure
// (Pure code, compiled to shared/parser-mappings.pdb, validated continuously
// by PureParserMatchesJavaParserTest against the output of this file).
//
// Top-level parser visitor: reuses the bootstrap TopLexer/TopParser ANTLR
// classes; build methods produce a PureFile PDO with its Sections.
//
// Truffle does NOT use this file: it parses the top-level document directly
// via the Pure-side interpreter driven from parser-mappings.pdb (see
// TrufflePureParser).
package org.finos.legend.pure.next.parser.topLevel;

import platform.pure.protocol.PureFile;
import platform.pure.protocol.PureFileImpl;
import platform.pure.protocol.Section;
import platform.pure.protocol.SectionImpl;
import platform.pure.protocol.grammar.PackageableElement;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.list.mutable.ListAdapter;
import org.finos.legend.pure.next.parser.TopParser;

public class TopLevelProtocolBuilder
{
    private final java.util.Map<String, org.finos.legend.pure.next.parser.ParserExtension> parserExtensions;
    private final String sourceId;

    public TopLevelProtocolBuilder(java.util.Map<String, org.finos.legend.pure.next.parser.ParserExtension> parserExtensions, String sourceId)
    {
        this.parserExtensions = parserExtensions;
        this.sourceId = sourceId;
    }

    /** Look up the section parser by name and apply it to the section body. */
    protected org.eclipse.collections.api.list.MutableList<platform.pure.protocol.grammar.PackageableElement> dispatchSection(String name, String content, String sourceId, int lineOffset)
    {
        if (content == null || content.isEmpty()) return org.eclipse.collections.impl.factory.Lists.mutable.empty();
        org.finos.legend.pure.next.parser.ParserExtension ext = parserExtensions == null ? null : parserExtensions.get(name);
        if (ext == null) throw new RuntimeException("No parser registered for section: ###" + name);
        return org.eclipse.collections.impl.factory.Lists.mutable.withAll(ext.parseSection(content, sourceId, lineOffset));
    }

    /** Line number (0-based, source-relative) of the first non-NEWLINE child of {@code ctx}. */
    protected int computeFirstNonNewlineLine(org.antlr.v4.runtime.ParserRuleContext ctx)
    {
        if (ctx == null) return 0;
        for (int i = 0; i < ctx.getChildCount(); i++)
        {
            org.antlr.v4.runtime.tree.ParseTree child = ctx.getChild(i);
            if (child instanceof org.antlr.v4.runtime.tree.TerminalNode tn
                    && tn.getSymbol().getType() == org.antlr.v4.runtime.Token.EOF) continue;
            String text = child.getText();
            if (text != null && text.trim().isEmpty()) continue;
            int line = (child instanceof org.antlr.v4.runtime.tree.TerminalNode tn2)
                    ? tn2.getSymbol().getLine()
                    : ((org.antlr.v4.runtime.ParserRuleContext) child).getStart().getLine();
            return line - 1;
        }
        return 0;
    }

    /** The implicit section (text before the first header) is Pure, and is dropped only when it is
     *  comments and blank lines above a header: a file naming no section is always one section. */
    protected PureFile buildDocument(final TopParser.DocumentContext ctx)
    {
        TopParser.ImplicitSectionContext implicit = ctx.implicitSection();
        boolean keepImplicit = ctx.section().isEmpty() || !implicit.importStatement().isEmpty() || !sectionText(implicit.sectionContent()).isEmpty();
        MutableList<Section> sections = keepImplicit
                ? Lists.mutable.with(buildSectionBody("Pure", implicit.importStatement(), implicit.sectionContent()))
                : Lists.mutable.empty();
        ListAdapter.adapt(ctx.section()).collect(this::buildSection, sections);
        return new PureFileImpl()._sourceId(sourceId)._sections(sections);
    }

    protected Section buildSection(final TopParser.SectionContext ctx)
    {
        String sectionName = ctx.SECTION_HEADER().getText().substring("###".length());
        return buildSectionBody(sectionName, ctx.importStatement(), ctx.sectionContent());
    }

    protected Section buildSectionBody(String sectionName, java.util.List<TopParser.ImportStatementContext> importStatements, TopParser.SectionContentContext sectionContent)
    {
        MutableList<String> imports = ListAdapter.adapt(importStatements).collect(this::buildImport);
        int lineOffset = computeFirstNonNewlineLine(sectionContent);
        MutableList<PackageableElement> elements = dispatchSection(sectionName, sectionText(sectionContent), sourceId, lineOffset);
        return new SectionImpl()._parserName(sectionName)._imports(imports)._elements(elements);
    }

    protected String sectionText(TopParser.SectionContentContext sectionContent)
    {
        return sectionContent != null ? ListAdapter.adapt(sectionContent.contentToken()).collect(__n -> __n.getText()).makeString("").trim() : "";
    }

    protected String buildImport(final TopParser.ImportStatementContext ctx)
    {
        return ctx.IMPORT_STATEMENT().getText().substring("import ".length()).trim();
    }

}
