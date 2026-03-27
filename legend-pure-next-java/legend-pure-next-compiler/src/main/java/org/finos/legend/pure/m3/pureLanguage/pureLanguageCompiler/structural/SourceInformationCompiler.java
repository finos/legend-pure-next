package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural;

import meta.pure.metamodel.SourceInformationImpl;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Any;

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
     * This overload does NOT set classifierGenericType — use only for error-context
     * SourceInformation that will not be serialized to FlatBuffer.
     */
    public static SourceInformationImpl compile(meta.pure.protocol.grammar.SourceInformation src)
    {
        return compile(src, (String) null);
    }

    /**
     * Convert grammar-level SourceInformation to metamodel-level SourceInformation
     * with classifierGenericType properly set.
     */
    public static SourceInformationImpl compile(meta.pure.protocol.grammar.SourceInformation src, MetadataAccess model)
    {
        return compile(src, null, model);
    }

    /**
     * Convert grammar-level SourceInformation to metamodel-level SourceInformation,
     * using the fallback sourceId if the grammar-level one is absent.
     * This overload does NOT set classifierGenericType.
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

    /**
     * Convert grammar-level SourceInformation to metamodel-level SourceInformation,
     * using the fallback sourceId if the grammar-level one is absent.
     * Sets classifierGenericType for proper serialization.
     */
    public static SourceInformationImpl compile(meta.pure.protocol.grammar.SourceInformation src, String fallbackSourceId, MetadataAccess model)
    {
        if (src == null)
        {
            return null;
        }
        String sourceId = src._sourceId() != null ? src._sourceId() : fallbackSourceId;
        return _Any.withClassifierGenericType(
                new SourceInformationImpl(model)
                        ._sourceId(sourceId)
                        ._startLine(src._startLine())
                        ._startColumn(src._startColumn())
                        ._endLine(src._endLine())
                        ._endColumn(src._endColumn()),
                "meta::pure::metamodel::SourceInformation",
                model);
    }
}
