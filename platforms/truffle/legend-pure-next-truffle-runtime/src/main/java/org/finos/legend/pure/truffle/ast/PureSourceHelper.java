package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SourceInformation;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates Truffle {@link SourceSection} objects from PDB {@link SourceInformation}.
 * Sources are cached by sourceId to avoid duplicate Source objects.
 */
public final class PureSourceHelper
{
    private static final ConcurrentHashMap<String, Source> SOURCE_CACHE = new ConcurrentHashMap<>();

    private PureSourceHelper() {}

    /**
     * Create a SourceSection from a PDB SourceInformation.
     * Returns null if sourceInfo is null or lacks required fields.
     */
    public static SourceSection createSourceSection(SourceInformation si)
    {
        if (si == null || si._sourceId() == null || si._startLine() == null)
        {
            return null;
        }
        String sourceId = si._sourceId();
        int startLine = si._startLine().intValue();
        int startCol = si._startColumn() != null ? si._startColumn().intValue() : 1;
        int endLine = si._endLine() != null ? si._endLine().intValue() : startLine;
        int endCol = si._endColumn() != null ? si._endColumn().intValue() : startCol;

        Source source = SOURCE_CACHE.computeIfAbsent(sourceId, id ->
                Source.newBuilder("pure", "", id)
                        .content(Source.CONTENT_NONE)
                        .build());

        return source.createSection(startLine, startCol, endLine, endCol);
    }

    /**
     * Set the source section on a PureNode from a PDB ValueSpecification's sourceInformation.
     * Returns the node for chaining.
     */
    public static <T extends PureNode> T withSource(T node, Object vs)
    {
        if (vs instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.ValueSpecification vsTyped)
        {
            try
            {
                SourceSection section = createSourceSection(vsTyped._sourceInformation());
                if (section != null)
                {
                    node.setPureSourceSection(section);
                }
            }
            catch (Exception ignored)
            {
                // SourceInformation may not be available on all VS types
            }
        }
        return node;
    }
}
