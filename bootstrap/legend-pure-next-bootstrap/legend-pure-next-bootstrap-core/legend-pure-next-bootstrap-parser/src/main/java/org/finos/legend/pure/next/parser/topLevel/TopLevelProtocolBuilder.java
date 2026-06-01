// REFERENCE PARSER — committed source, hand-maintained until Pure-platform codegen lands.
//
// Canonical source of truth for the top-level visitor mapping is
// pure/specification/grammar/mapping/mappings-pure/mapping_top.pure
// (Pure code, compiled to shared/parser-mappings.pdb, validated continuously
// by TopMappingsInterpreterValidatorTest against the output of this file).
//
// Top-level parser visitor: reuses the bootstrap TopLexer/TopParser ANTLR
// classes; build methods produce a PureFile PDO with its Sections.
//
// Truffle does NOT use this file: it parses the top-level document directly
// via the Pure-side interpreter driven from parser-mappings.pdb (see
// TrufflePureParser).
package org.finos.legend.pure.next.parser.topLevel;

import meta.pure.protocol.PureFile;
import meta.pure.protocol.PureFileImpl;
import meta.pure.protocol.Section;
import meta.pure.protocol.SectionImpl;
import meta.pure.protocol.grammar.PackageableElement;
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
    private final boolean syntheticHeader;

    public TopLevelProtocolBuilder(java.util.Map<String, org.finos.legend.pure.next.parser.ParserExtension> parserExtensions, String sourceId, boolean syntheticHeader)
    {
        this.parserExtensions = parserExtensions;
        this.sourceId = sourceId;
        this.syntheticHeader = syntheticHeader;
    }

    /** Look up the section parser by name and apply it to the section body. */
    protected org.eclipse.collections.api.list.MutableList<meta.pure.protocol.grammar.PackageableElement> dispatchSection(String name, String content, String sourceId, int lineOffset)
    {
        if (content == null || content.isEmpty()) return org.eclipse.collections.impl.factory.Lists.mutable.empty();
        org.finos.legend.pure.next.parser.ParserExtension ext = parserExtensions == null ? null : parserExtensions.get(name);
        if (ext == null) throw new RuntimeException("No parser registered for section: ###" + name);
        return org.eclipse.collections.impl.factory.Lists.mutable.withAll(ext.parseSection(content, sourceId, lineOffset));
    }

    /** Line number (0-based, source-relative) of the first non-NEWLINE child of {@code ctx}.
     *  Subtracts an extra 1 when {@code syntheticHeader} is true (i.e. the {@code ###Pure}
     *  header was prepended by the caller and shouldn't count toward the offset). */
    protected int computeFirstNonNewlineLine(org.antlr.v4.runtime.ParserRuleContext ctx, boolean syntheticHeader)
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
            int offset = line - 1;
            if (syntheticHeader) offset -= 1;
            return offset;
        }
        return 0;
    }

    protected PureFile buildDocument(final TopParser.DocumentContext ctx)
    {
        return new PureFileImpl()._sourceId(sourceId)._sections(ListAdapter.adapt(ctx.section()).collect(this::buildSection));
    }

    protected Section buildSection(final TopParser.SectionContext ctx)
    {
        String sectionName = ctx.SECTION_HEADER().getText().substring("###".length());
        MutableList<String> imports = ListAdapter.adapt(ctx.importStatement()).collect(this::buildImport);
        String content = (ctx.sectionContent() != null ? ListAdapter.adapt(ctx.sectionContent().contentToken()).collect(__n -> __n.getText()).makeString("").trim() : "");
        int lineOffset = computeFirstNonNewlineLine(ctx.sectionContent(), syntheticHeader);
        MutableList<PackageableElement> elements = dispatchSection(sectionName, content, sourceId, lineOffset);
        return new SectionImpl()._parserName(sectionName)._imports(imports)._elements(elements);
    }

    protected String buildImport(final TopParser.ImportStatementContext ctx)
    {
        return ctx.IMPORT_STATEMENT().getText().substring("import ".length()).trim();
    }

}
