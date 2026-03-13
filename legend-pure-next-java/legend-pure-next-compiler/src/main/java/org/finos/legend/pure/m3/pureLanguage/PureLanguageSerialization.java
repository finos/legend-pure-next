package org.finos.legend.pure.m3.pureLanguage;

import com.google.flatbuffers.FlatBufferBuilder;
import meta.pure.metamodel.PackageFlatBufferWrapper;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.extension.ProfileFlatBufferWrapper;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.function.NativeFunction;
import meta.pure.metamodel.function.NativeFunctionFlatBufferWrapper;
import meta.pure.metamodel.function.UserDefinedFunction;
import meta.pure.metamodel.function.UserDefinedFunctionFlatBufferWrapper;
import meta.pure.metamodel.multiplicity.ConcretePackageableMultiplicityFlatBufferWrapper;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import meta.pure.metamodel.multiplicity.PackageableInferredMultiplicityFlatBufferWrapper;
import meta.pure.metamodel.relationship.AssociationFlatBufferWrapper;
import meta.pure.metamodel.type.ClassFlatBufferWrapper;
import meta.pure.metamodel.type.EnumerationFlatBufferWrapper;
import meta.pure.metamodel.type.MeasureFlatBufferWrapper;
import meta.pure.metamodel.type.PrimitiveTypeFlatBufferWrapper;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.pdbModule.archive.PDBArchiveSection;
import org.finos.legend.pure.m3.module.pdbModule.archive.PDBExtension;
import org.finos.legend.pure.m3.module.pdbModule.fbs.AssociationDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ClassDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ConcretePackageableMultiplicityDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.EnumerationDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.FunctionIndex;
import org.finos.legend.pure.m3.module.pdbModule.fbs.MeasureDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.NativeFunctionDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.PackageDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.PackageableInferredMultiplicityDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.PrimitiveTypeDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ProfileDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.UserDefinedFunctionDef;
import org.finos.legend.pure.m3.pureLanguage.metadata.FunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.metadata.PureLanguageMetadata;

import java.nio.ByteBuffer;
import java.util.List;

public class PureLanguageSerialization implements PDBExtension
{
    // ========================================================================
    // PDBExtension — element deserialization
    // ========================================================================

    @Override
    public PackageableElement deserialize(String typeName, byte[] data, MetadataAccess resolver)
    {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        return switch (typeName)
        {
            case "Class" -> new ClassFlatBufferWrapper(ClassDef.getRootAsClassDef(buffer), resolver);
            case "Enumeration" -> new EnumerationFlatBufferWrapper(EnumerationDef.getRootAsEnumerationDef(buffer), resolver);
            case "Association" -> new AssociationFlatBufferWrapper(AssociationDef.getRootAsAssociationDef(buffer), resolver);
            case "Profile" -> new ProfileFlatBufferWrapper(ProfileDef.getRootAsProfileDef(buffer), resolver);
            case "Measure" -> new MeasureFlatBufferWrapper(MeasureDef.getRootAsMeasureDef(buffer), resolver);
            case "UserDefinedFunction" -> new UserDefinedFunctionFlatBufferWrapper(UserDefinedFunctionDef.getRootAsUserDefinedFunctionDef(buffer), resolver);
            case "NativeFunction" -> new NativeFunctionFlatBufferWrapper(NativeFunctionDef.getRootAsNativeFunctionDef(buffer), resolver);
            case "PrimitiveType" -> new PrimitiveTypeFlatBufferWrapper(PrimitiveTypeDef.getRootAsPrimitiveTypeDef(buffer), resolver);
            case "ConcretePackageableMultiplicity" -> new ConcretePackageableMultiplicityFlatBufferWrapper(ConcretePackageableMultiplicityDef.getRootAsConcretePackageableMultiplicityDef(buffer), resolver);
            case "PackageableInferredMultiplicity" -> new PackageableInferredMultiplicityFlatBufferWrapper(PackageableInferredMultiplicityDef.getRootAsPackageableInferredMultiplicityDef(buffer), resolver);
            case "Package" -> new PackageFlatBufferWrapper(PackageDef.getRootAsPackageDef(buffer), resolver);
            default -> null;
        };
    }

    // ========================================================================
    // PDBExtension — element serialization
    // ========================================================================

    @Override
    public PDBArchiveSection serialize(PackageableElement element)
    {
        String typeName = elementTypeName(element);
        if (typeName == null)
        {
            return null;
        }
        FlatBufferBuilder builder = new FlatBufferBuilder(1024);
        GeneratedFlatBufferWriter writer = new GeneratedFlatBufferWriter(builder);
        int offset = dispatchWrite(writer, element);
        if (offset < 0)
        {
            return null;
        }
        builder.finish(offset);
        return new PDBArchiveSection(typeName, builder.sizedByteArray());
    }

    private int dispatchWrite(GeneratedFlatBufferWriter writer, PackageableElement element)
    {
        if (element instanceof meta.pure.metamodel.type.Class cls)
        {
            return writer.writeClass(cls);
        }
        else if (element instanceof meta.pure.metamodel.type.Enumeration enm)
        {
            return writer.writeEnumeration(enm);
        }
        else if (element instanceof meta.pure.metamodel.relationship.Association assoc)
        {
            return writer.writeAssociation(assoc);
        }
        else if (element instanceof meta.pure.metamodel.extension.Profile profile)
        {
            return writer.writeProfile(profile);
        }
        else if (element instanceof meta.pure.metamodel.type.Measure measure)
        {
            return writer.writeMeasure(measure);
        }
        else if (element instanceof UserDefinedFunction udf)
        {
            validateFunctionExpressions(udf);
            return writer.writeUserDefinedFunction(udf);
        }
        else if (element instanceof NativeFunction nf)
        {
            return writer.writeNativeFunction(nf);
        }
        else if (element instanceof meta.pure.metamodel.type.PrimitiveType pt)
        {
            return writer.writePrimitiveType(pt);
        }
        else if (element instanceof meta.pure.metamodel.multiplicity.PackageableInferredMultiplicity pim)
        {
            return writer.writePackageableInferredMultiplicity(pim);
        }
        else if (element instanceof meta.pure.metamodel.multiplicity.ConcretePackageableMultiplicity cpm)
        {
            return writer.writeConcretePackageableMultiplicity(cpm);
        }
        else if (element instanceof meta.pure.metamodel.Package pkg)
        {
            return writer.writePackage(pkg);
        }
        return -1;
    }

    private String elementTypeName(PackageableElement element)
    {
        if (element instanceof meta.pure.metamodel.type.Class) return "Class";
        if (element instanceof meta.pure.metamodel.type.Enumeration) return "Enumeration";
        if (element instanceof meta.pure.metamodel.relationship.Association) return "Association";
        if (element instanceof meta.pure.metamodel.extension.Profile) return "Profile";
        if (element instanceof meta.pure.metamodel.type.Measure) return "Measure";
        if (element instanceof UserDefinedFunction) return "UserDefinedFunction";
        if (element instanceof NativeFunction) return "NativeFunction";
        if (element instanceof meta.pure.metamodel.type.PrimitiveType) return "PrimitiveType";
        if (element instanceof meta.pure.metamodel.multiplicity.PackageableInferredMultiplicity) return "PackageableInferredMultiplicity";
        if (element instanceof meta.pure.metamodel.multiplicity.ConcretePackageableMultiplicity) return "ConcretePackageableMultiplicity";
        if (element instanceof meta.pure.metamodel.Package) return "Package";
        return null;
    }

    // ========================================================================
    // PDBExtension — archive sections
    // ========================================================================

    @Override
    public List<PDBArchiveSection> archiveSections(Module module)
    {
        if (module instanceof LocalModule localModule)
        {
            MutableList<PureLanguageMetadata> metas = localModule.getMetadataAccessExtension(PureLanguageMetadata.class);
            if (metas.notEmpty())
            {
                MutableList<FunctionIndexEntry> entries = metas.getFirst().getAllFunctionHeaders();
                if (entries.notEmpty())
                {
                    return List.of(new PDBArchiveSection("functionIndex", serializeFunctionIndex(entries)));
                }
            }
        }
        return List.of();
    }

    private byte[] serializeFunctionIndex(List<FunctionIndexEntry> entries)
    {
        FlatBufferBuilder builder = new FlatBufferBuilder(4096);
        GeneratedFlatBufferWriter writer = new GeneratedFlatBufferWriter(builder);

        int[] entryOffsets = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++)
        {
            FunctionIndexEntry entry = entries.get(i);
            int fullPathOffset = builder.createString(entry.fullPath());
            int functionNameOffset = builder.createString(entry.functionName());
            int functionTypeOffset = writer.writeFunctionType(entry.functionType());
            entryOffsets[i] = org.finos.legend.pure.m3.module.pdbModule.fbs.FunctionIndexEntry
                    .createFunctionIndexEntry(builder, fullPathOffset, functionNameOffset, functionTypeOffset);
        }

        int entriesVectorOffset = FunctionIndex
                .createEntriesVector(builder, entryOffsets);
        int indexOffset = FunctionIndex
                .createFunctionIndex(builder, entriesVectorOffset);
        builder.finish(indexOffset);
        return builder.sizedByteArray();
    }

    // ========================================================================
    // Validation — ensure FunctionExpression._func() is never null
    // ========================================================================

    private void validateFunctionExpressions(UserDefinedFunction udf)
    {
        String funcPath = _PackageableElement.path(udf);
        if (udf._expressionSequence() != null)
        {
            udf._expressionSequence().forEach(vs -> validateValueSpecification(vs, funcPath));
        }
    }

    private void validateValueSpecification(ValueSpecification vs, String funcPath)
    {
        if (vs instanceof FunctionExpression fe)
        {
            if (fe._parametersValues() != null)
            {
                fe._parametersValues().forEach(pv -> validateValueSpecification(pv, funcPath));
            }
        }
        else if (vs instanceof AtomicValue av && av._value() instanceof LambdaFunction lambda)
        {
            if (lambda._expressionSequence() != null)
            {
                lambda._expressionSequence().forEach(inner -> validateValueSpecification(inner, funcPath));
            }
        }
        else if (vs instanceof Collection col && col._values() != null)
        {
            col._values().forEach(v -> validateValueSpecification(v, funcPath));
        }
    }
}

