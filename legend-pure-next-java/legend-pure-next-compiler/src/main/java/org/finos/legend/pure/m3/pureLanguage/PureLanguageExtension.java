package org.finos.legend.pure.m3.pureLanguage;

import com.google.flatbuffers.FlatBufferBuilder;
import meta.pure.metamodel.PackageFlatBufferWrapper;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.extension.ProfileFlatBufferWrapper;
import meta.pure.metamodel.function.NativeFunction;
import meta.pure.metamodel.function.NativeFunctionFlatBufferWrapper;
import meta.pure.metamodel.function.UserDefinedFunction;
import meta.pure.metamodel.function.UserDefinedFunctionFlatBufferWrapper;
import meta.pure.metamodel.multiplicity.ConcretePackageableMultiplicityFlatBufferWrapper;
import meta.pure.metamodel.multiplicity.PackageableInferredMultiplicityFlatBufferWrapper;
import meta.pure.metamodel.relationship.AssociationFlatBufferWrapper;
import meta.pure.metamodel.type.ClassFlatBufferWrapper;
import meta.pure.metamodel.type.EnumerationFlatBufferWrapper;
import meta.pure.metamodel.type.FunctionTypeFlatBufferWrapper;
import meta.pure.metamodel.type.MeasureFlatBufferWrapper;
import meta.pure.metamodel.type.PrimitiveTypeFlatBufferWrapper;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.MetadataAccessExtension;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilerContextExtension;
import org.finos.legend.pure.m3.module.localModule.topLevel.IndexEntry;
import org.finos.legend.pure.m3.module.pdbModule.archive.PDBArchiveSection;

import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.module.pdbModule.fbs.*;
import org.finos.legend.pure.m3.pureLanguage.metadata.FunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.metadata.PureLanguageMetadata;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerExtension;
import org.finos.legend.pure.next.parser.m3.PureLanguageParser;

import java.util.List;

public class PureLanguageExtension implements LanguageExtension
{
    PureLanguageCompilerExtension pureLanguageCompilerExtension = new PureLanguageCompilerExtension();
    PureLanguageParser parser = new PureLanguageParser();
    PureLanguageSerialization serialization = new PureLanguageSerialization();

    // ========================================================================
    // Metadata extension
    // ========================================================================

    @Override
    public MetadataAccessExtension buildMetadataExtensionForModule(Module module)
    {
        if (module instanceof LocalModule)
        {
            return new PureLanguageMetadata();
        }
        if (module instanceof PDBModule pdb)
        {
            return buildFromPDB(pdb);
        }
        return null;
    }

    private PureLanguageMetadata buildFromPDB(PDBModule pdb)
    {
        if (pdb.mode() != PDBModule.Mode.COMPILATION)
        {
            return new PureLanguageMetadata();
        }
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
                FunctionIndexEntry entry = new FunctionIndexEntry(
                        fbEntry.fullPath(), fbEntry.functionName(), functionType);

                int paramCount = functionType._parameters() != null
                        ? functionType._parameters().size() : 0;

                functionIndex
                        .getIfAbsentPut(entry.functionName(), Maps.mutable::empty)
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
    public PackageableElement firstPass(meta.pure.protocol.grammar.PackageableElement grammar)
    {
        return pureLanguageCompilerExtension.firstPass(grammar);
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
