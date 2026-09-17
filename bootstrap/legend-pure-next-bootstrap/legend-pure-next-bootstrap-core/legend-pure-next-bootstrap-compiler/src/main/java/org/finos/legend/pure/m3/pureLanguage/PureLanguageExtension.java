package org.finos.legend.pure.m3.pureLanguage;

import org.finos.legend.pure.m3.module.inMemoryModule.InMemoryModule;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.type.FunctionTypeFlatBufferWrapper;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.MetadataAccessExtension;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.sourceModule.SourceModule;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilerContextExtension;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.IndexEntry;
import org.finos.legend.pure.m3.module.pdbModule.PdbModule;
import org.finos.legend.pure.m3.module.pdbModule.archive.PDBArchiveSection;
import org.finos.legend.pure.m3.module.pdbModule.fbs.FunctionIndex;
import org.finos.legend.pure.m3.pureLanguage.metadata.PureLanguageMetadata;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.NativeFunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.UserDefinedFunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerExtension;
import org.finos.legend.pure.next.parser.pureLanguage.PureLanguageParser;

import java.util.List;

public class PureLanguageExtension implements LanguageExtension
{
    // Must run before the first FlatBuffer table is built: Table and
    // StringVector capture Utf8.getDefault() per instance. Every entry point
    // (CLI, binary builders, IDE, tests) constructs this extension before any
    // PDB is read. See PdbUtf8 for why we don't use FlatBuffers' own decoder.
    static
    {
        org.finos.legend.pure.m3.module.pdbModule.PdbUtf8.install();
    }

    PureLanguageCompilerExtension pureLanguageCompilerExtension = new PureLanguageCompilerExtension();
    PureLanguageParser parser = new PureLanguageParser();
    PureLanguageSerialization serialization = new PureLanguageSerialization();

    // ========================================================================
    // Metadata extension
    // ========================================================================

    @Override
    public MetadataAccessExtension buildMetadataExtensionForModule(Module module)
    {
        if (module instanceof SourceModule)
        {
            return new PureLanguageMetadata();
        }
        if (module instanceof PdbModule pdb)
        {
            return buildFromPDB(pdb);
        }
        if (module instanceof InMemoryModule inMemory)
        {
            return new PureLanguageMetadata(new PureLanguageCompilerExtension()
                    .buildFunctionIndex(Lists.mutable.withAll(inMemory.elements()), inMemory.resolver()));
        }
        return null;
    }

    private PureLanguageMetadata buildFromPDB(PdbModule pdb)
    {
//        if (pdb.mode() != PdbModule.Mode.COMPILATION)
//        {
//            return new PureLanguageMetadata();
//        }
        byte[] sectionData = pdb.archive().readSection("functionIndex");
        if (sectionData == null)
        {
            return new PureLanguageMetadata();
        }
        FunctionIndex index = FunctionIndex.getRootAsFunctionIndex(java.nio.ByteBuffer.wrap(sectionData));
        MetadataAccess resolver = pdb.resolver();
        MutableMap<String, MutableMap<Integer, MutableList<FunctionIndexEntry>>> functionIndex = Maps.mutable.empty();
        for (int i = 0; i < index.entriesLength(); i++)
        {
            org.finos.legend.pure.m3.module.pdbModule.fbs.FunctionIndexEntry fbEntry = index.entries(i);
            if (fbEntry != null)
            {
                meta.pure.metamodel.type.FunctionType functionType =
                        new FunctionTypeFlatBufferWrapper(fbEntry.functionType(), resolver);
                FunctionIndexEntry entry = fbEntry.isNative()
                        ? new NativeFunctionIndexEntry(fbEntry.fullPath(), fbEntry.functionName(), functionType, resolver)
                        : new UserDefinedFunctionIndexEntry(fbEntry.fullPath(), fbEntry.functionName(), functionType, resolver);

                int paramCount = functionType._parameters() != null
                        ? functionType._parameters().size() : 0;

                functionIndex
                        .getIfAbsentPut(entry._functionName(), Maps.mutable::empty)
                        .getIfAbsentPut(paramCount, Lists.mutable::empty)
                        .add(entry);
            }
        }
        return new PureLanguageMetadata(functionIndex);
    }

    // ========================================================================
    // PDBExtension — element deserialization
    // ========================================================================

    @Override
    public List<PDBArchiveSection> archiveSections(Module module)
    {
        return serialization.archiveSections(module);
    }

    @Override
    public List<PDBArchiveSection> archiveSections(Module module, java.util.Set<String> keepPaths)
    {
        return serialization.archiveSections(module, keepPaths);
    }

    /**
     * Build archive sections (function index) directly from a list of
     * function-index entries. Used by builders that don't go through a
     * {@link SourceModule} (e.g. the compile-via-pure path).
     */
    public List<PDBArchiveSection> archiveSections(List<FunctionIndexEntry> entries)
    {
        return serialization.archiveSections(entries);
    }

    @Override
    public PackageableElement deserialize(String typeName, byte[] data, MetadataAccess resolver)
    {
        return serialization.deserialize(typeName, data, resolver);
    }

    @Override
    public PDBArchiveSection serialize(PackageableElement element)
    {
        return serialization.serialize(element);
    }


    // ========================================================================
    // Compiler extension
    // ========================================================================

    @Override
    public CompilerContextExtension buildCompilerContextExtension()
    {
        return pureLanguageCompilerExtension.buildCompilerContextExtension();
    }

    @Override
    public PackageableElement firstPass(meta.pure.protocol.grammar.PackageableElement grammar, MetadataAccess model, CompilationContext context)
    {
        return pureLanguageCompilerExtension.firstPass(grammar, model, context);
    }

    @Override
    public PackageableElement secondPass(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        return pureLanguageCompilerExtension.secondPass(entry, model, context);
    }

    @Override
    public PackageableElement thirdPass(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        return pureLanguageCompilerExtension.thirdPass(entry, model, context);
    }

    // ========================================================================
    // Parser extension
    // ========================================================================

    @Override
    public String sectionName()
    {
        return parser.sectionName();
    }

    @Override
    public List<meta.pure.protocol.grammar.PackageableElement> parseSection(String content, String sourceId, int lineOffset)
    {
        return parser.parseSection(content, sourceId, lineOffset);
    }
}
