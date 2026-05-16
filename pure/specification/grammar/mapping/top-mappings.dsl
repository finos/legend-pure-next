# Visitor-mapping DSL for the TopParser grammar (sections + imports + content
# tokens). Compiles via PureLanguageVisitorMappingGenerator into a TopLevelProtocolBuilder
# whose build methods produce the PureFile / Section protocol PDOs. Both the
# protocol target and the Truffle target consume this file.
#
# The `grammar TopParser;` directive picks the ANTLR class (so context types
# emit as `TopParser.XContext`); the `noScaffolding;` directive skips the
# M3-specific scaffolding (elements accumulator, parseElements entry point,
# lineOffset field, operatorTokenAt).
#
# Section dispatch goes through a user-supplied `parserExtensions` map (auto-
# emitted constructor argument). The same constructor takes `sourceId` and
# `syntheticHeader`; the rule bodies reference them as bare identifiers, which
# resolve to those fields.

grammar TopParser;
noScaffolding;

# ----------------------------------------------------------------------
# Document → PureFile { sourceId, sections }
# ----------------------------------------------------------------------
rule document as PureFile {
    return newImpl(PureFile,
                   sourceId = sourceId,
                   sections = mapList($ctx.section, buildSection))
}

# ----------------------------------------------------------------------
# Section → Section { parserName, imports, elements }
#
# A section starts with `###Name`, then optional imports, then the body. The
# body is concatenated from raw content tokens and dispatched to a registered
# parser extension by section name.
# ----------------------------------------------------------------------
rule section as Section {
    let String sectionName = stripPrefix($ctx.SECTION_HEADER.text, "###")
    let MutableList<String> imports = mapList($ctx.importStatement, buildImport)
    let String content = ifPresent($ctx.sectionContent,
                                   concatTokens($ctx.sectionContent.contentToken),
                                   "")
    let int lineOffset = firstNonNewlineLine($ctx.sectionContent, syntheticHeader)
    let MutableList<PackageableElement> elements = dispatchSection(sectionName, content, sourceId, lineOffset)
    return newImpl(Section,
                   parserName = sectionName,
                   imports = imports,
                   elements = elements)
}

# ----------------------------------------------------------------------
# ImportStatement → "path::to::pkg::*"
#
# The IMPORT_STATEMENT token captures `import X` (no semicolon). Strip the
# leading `import ` and trim trailing whitespace.
# ----------------------------------------------------------------------
helper buildImport(TopParser.ImportStatementContext ctx) as String {
    return stripPrefix($ctx.IMPORT_STATEMENT.text, "import ").trim()
}
