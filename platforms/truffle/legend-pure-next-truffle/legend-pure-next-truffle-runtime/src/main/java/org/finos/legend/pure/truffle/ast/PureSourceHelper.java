package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SourceInformation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Creates Truffle {@link SourceSection} objects from PDB {@link SourceInformation}.
 * Sources are cached by sourceId to avoid duplicate Source objects.
 *
 * <p>Source content can be embedded by registering one or more source roots via
 * {@link #addSourceRoot(Path)}. When a sourceId is resolved, each root is probed
 * for {@code <root>/<sourceId>}; the first hit is read and embedded so tools like
 * {@code --cpu-sampler} can show actual Pure source lines. If no root matches the
 * sourceId, the {@link Source} is built with {@link Source#CONTENT_NONE}.</p>
 */
public final class PureSourceHelper
{
    private static final ConcurrentHashMap<String, Source> SOURCE_CACHE = new ConcurrentHashMap<>();
    private static final List<Path> SOURCE_ROOTS = new CopyOnWriteArrayList<>();

    private PureSourceHelper() {}

    /**
     * Register a source root. Roots are probed in registration order to resolve
     * a sourceId to a file path. Must be called before any AST node is built —
     * once a Source is cached for a sourceId, later root additions are ignored
     * for that id.
     */
    public static void addSourceRoot(Path root)
    {
        if (root != null && !SOURCE_ROOTS.contains(root))
        {
            SOURCE_ROOTS.add(root);
        }
    }

    /**
     * Drop all registered source roots and the Source cache. Test helper —
     * production code should not need this.
     */
    public static void resetForTest()
    {
        SOURCE_ROOTS.clear();
        SOURCE_CACHE.clear();
    }

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

        Source source = SOURCE_CACHE.computeIfAbsent(sourceId, PureSourceHelper::buildSource);

        // Defensive clamp: SourceInformation may point past the end of the loaded
        // content (e.g. mismatched checked-in PDB). Truffle's createSection throws
        // on out-of-range values; degrade to a sectionless lookup so a single bad
        // SI doesn't abort compilation.
        try
        {
            return source.createSection(startLine, startCol, endLine, endCol);
        }
        catch (IllegalArgumentException ignored)
        {
            return source.createUnavailableSection();
        }
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Source buildSource(String sourceId)
    {
        for (Path root : SOURCE_ROOTS)
        {
            Path candidate = root.resolve(sourceId);
            if (Files.isRegularFile(candidate))
            {
                try
                {
                    String content = Files.readString(candidate, StandardCharsets.UTF_8);
                    return Source.newBuilder("pure", content, sourceId)
                            .uri(candidate.toUri())
                            .build();
                }
                catch (IOException ignored)
                {
                    // fall through to next root / content-less fallback
                }
            }
        }
        return Source.newBuilder("pure", "", sourceId)
                .content(Source.CONTENT_NONE)
                .build();
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
