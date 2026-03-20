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
        return compile(src, null);
    }

    /**
     * Convert grammar-level SourceInformation to metamodel-level SourceInformation,
     * using the fallback sourceId if the grammar-level one is absent.
     */
    public static SourceInformationImpl compile(meta.pure.protocol.grammar.SourceInformation src, String fallbackSourceId)
    {
        if (src == null)
        {
            return null;
        }
        String sourceId = src._sourceId() != null ? src._sourceId() : fallbackSourceId;
        return new SourceInformationImpl()
                ._sourceId(sourceId)
                ._startLine(src._startLine())
                ._startColumn(src._startColumn())
                ._endLine(src._endLine())
                ._endColumn(src._endColumn());
    }
}
