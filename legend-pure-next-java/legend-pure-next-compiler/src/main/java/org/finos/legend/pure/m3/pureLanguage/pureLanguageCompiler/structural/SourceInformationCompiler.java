package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural;

import meta.pure.metamodel.SourceInformationImpl;

/**
 * Compiles/converts between grammar-level and metamodel-level SourceInformation.
 */
public final class SourceInformationCompiler
{
    private SourceInformationCompiler()
    {
    }

    /**
     * Convert grammar-level SourceInformation to metamodel-level SourceInformation.
     */
    public static SourceInformationImpl compile(meta.pure.protocol.grammar.SourceInformation src)
    {
        if (src == null)
        {
            return null;
        }
        return new SourceInformationImpl()
                ._sourceId(src._sourceId())
                ._startLine(src._startLine())
                ._startColumn(src._startColumn())
                ._endLine(src._endLine())
                ._endColumn(src._endColumn());
    }
}
