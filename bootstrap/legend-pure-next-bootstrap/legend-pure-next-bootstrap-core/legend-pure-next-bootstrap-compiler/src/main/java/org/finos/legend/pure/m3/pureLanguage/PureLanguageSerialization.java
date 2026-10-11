// Copyright 2026 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.pure.m3.pureLanguage;

import com.google.flatbuffers.FlatBufferBuilder;
import platform.pure.metamodel.PackageFlatBufferWrapper;
import platform.pure.metamodel.PackageableElement;
import platform.pure.metamodel.extension.ProfileFlatBufferWrapper;
import platform.pure.metamodel.function.LambdaFunction;
import platform.pure.metamodel.function.NativeFunction;
import platform.pure.metamodel.function.NativeFunctionFlatBufferWrapper;
import platform.pure.metamodel.function.UserDefinedFunction;
import platform.pure.metamodel.function.UserDefinedFunctionFlatBufferWrapper;
import platform.pure.metamodel.multiplicity.InferredPackageableMultiplicityFlatBufferWrapper;
import platform.pure.metamodel.multiplicity.UserDefinedPackageableMultiplicityFlatBufferWrapper;
import platform.pure.metamodel.relationship.AssociationFlatBufferWrapper;
import platform.pure.metamodel.type.ClassFlatBufferWrapper;
import platform.pure.metamodel.type.EnumerationFlatBufferWrapper;
import platform.pure.metamodel.type.PrimitiveTypeFlatBufferWrapper;
import platform.pure.metamodel.type.generics.UserDefinedPackageableGenericTypeFlatBufferWrapper;
import platform.pure.metamodel.valuespecification.AtomicValue;
import platform.pure.metamodel.valuespecification.Collection;
import platform.pure.metamodel.valuespecification.FunctionExpression;
import platform.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.sourceModule.SourceModule;
import org.finos.legend.pure.m3.module.pdbModule.archive.PDBArchiveSection;
import org.finos.legend.pure.m3.module.pdbModule.archive.PDBExtension;
import org.finos.legend.pure.m3.module.pdbModule.fbs.AssociationDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ClassDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.EnumerationDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.FunctionIndex;
import org.finos.legend.pure.m3.module.pdbModule.fbs.InferredPackageableMultiplicityDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.NativeFunctionDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.PackageDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.PrimitiveTypeDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ProfileDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.UserDefinedFunctionDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.UserDefinedPackageableGenericTypeDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.UserDefinedPackageableMultiplicityDef;
import org.finos.legend.pure.m3.pureLanguage.metadata.PureLanguageMetadata;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.NativeFunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;

import java.nio.ByteBuffer;
import java.util.List;

public class PureLanguageSerialization
{
    // Must run before the first FlatBuffer table is built here: Table captures
    // Utf8.getDefault() per instance. See PdbUtf8 for why we don't use
    // FlatBuffers' own decoder.
    static
    {
        org.finos.legend.pure.m3.module.pdbModule.PdbUtf8.install();
    }

    // ========================================================================
    // PDBExtension — element deserialization
    // ========================================================================

    public PackageableElement deserialize(String typeName, byte[] data, MetadataAccess resolver)
    {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        return switch (typeName)
        {
            case "Class" -> new ClassFlatBufferWrapper(ClassDef.getRootAsClassDef(buffer), resolver);
            case "Enumeration" -> new EnumerationFlatBufferWrapper(EnumerationDef.getRootAsEnumerationDef(buffer), resolver);
            case "Association" -> new AssociationFlatBufferWrapper(AssociationDef.getRootAsAssociationDef(buffer), resolver);
            case "Profile" -> new ProfileFlatBufferWrapper(ProfileDef.getRootAsProfileDef(buffer), resolver);
            case "UserDefinedFunction" -> new UserDefinedFunctionFlatBufferWrapper(UserDefinedFunctionDef.getRootAsUserDefinedFunctionDef(buffer), resolver);
            case "NativeFunction" -> new NativeFunctionFlatBufferWrapper(NativeFunctionDef.getRootAsNativeFunctionDef(buffer), resolver);
            case "PrimitiveType" -> new PrimitiveTypeFlatBufferWrapper(PrimitiveTypeDef.getRootAsPrimitiveTypeDef(buffer), resolver);
            case "UserDefinedPackageableMultiplicity" -> new UserDefinedPackageableMultiplicityFlatBufferWrapper(UserDefinedPackageableMultiplicityDef.getRootAsUserDefinedPackageableMultiplicityDef(buffer), resolver);
            case "InferredPackageableMultiplicity" -> new InferredPackageableMultiplicityFlatBufferWrapper(InferredPackageableMultiplicityDef.getRootAsInferredPackageableMultiplicityDef(buffer), resolver);
            case "UserDefinedPackageableGenericType" -> new UserDefinedPackageableGenericTypeFlatBufferWrapper(UserDefinedPackageableGenericTypeDef.getRootAsUserDefinedPackageableGenericTypeDef(buffer), resolver);
            case "Package" -> new PackageFlatBufferWrapper(PackageDef.getRootAsPackageDef(buffer), resolver);
            default -> null;
        };
    }

    // ========================================================================
    // PDBExtension — element serialization
    // ========================================================================

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
        if (element instanceof platform.pure.metamodel.type.Class cls)
        {
            return writer.writeClass(cls);
        }
        else if (element instanceof platform.pure.metamodel.type.Enumeration enm)
        {
            return writer.writeEnumeration(enm);
        }
        else if (element instanceof platform.pure.metamodel.relationship.Association assoc)
        {
            return writer.writeAssociation(assoc);
        }
        else if (element instanceof platform.pure.metamodel.extension.Profile profile)
        {
            return writer.writeProfile(profile);
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
        else if (element instanceof platform.pure.metamodel.type.PrimitiveType pt)
        {
            return writer.writePrimitiveType(pt);
        }
        else if (element instanceof platform.pure.metamodel.multiplicity.InferredPackageableMultiplicity pim)
        {
            return writer.writeInferredPackageableMultiplicity(pim);
        }
        else if (element instanceof platform.pure.metamodel.multiplicity.UserDefinedPackageableMultiplicity cpm)
        {
            return writer.writeUserDefinedPackageableMultiplicity(cpm);
        }
        else if (element instanceof platform.pure.metamodel.type.generics.UserDefinedPackageableGenericType pgt)
        {
            return writer.writeUserDefinedPackageableGenericType(pgt);
        }
        else if (element instanceof platform.pure.metamodel.Package pkg)
        {
            return writer.writePackage(pkg);
        }
        return -1;
    }

    private String elementTypeName(PackageableElement element)
    {
        if (element instanceof platform.pure.metamodel.type.Class) return "Class";
        if (element instanceof platform.pure.metamodel.type.Enumeration) return "Enumeration";
        if (element instanceof platform.pure.metamodel.relationship.Association) return "Association";
        if (element instanceof platform.pure.metamodel.extension.Profile) return "Profile";
        if (element instanceof UserDefinedFunction) return "UserDefinedFunction";
        if (element instanceof NativeFunction) return "NativeFunction";
        if (element instanceof platform.pure.metamodel.type.PrimitiveType) return "PrimitiveType";
        if (element instanceof platform.pure.metamodel.multiplicity.InferredPackageableMultiplicity) return "InferredPackageableMultiplicity";
        if (element instanceof platform.pure.metamodel.multiplicity.UserDefinedPackageableMultiplicity) return "UserDefinedPackageableMultiplicity";
        if (element instanceof platform.pure.metamodel.type.generics.UserDefinedPackageableGenericType) return "UserDefinedPackageableGenericType";
        if (element instanceof platform.pure.metamodel.Package) return "Package";
        return null;
    }

    // ========================================================================
    // PDBExtension — archive sections
    // ========================================================================

    public List<PDBArchiveSection> archiveSections(Module module)
    {
        return archiveSections(module, null);
    }

    /**
     * Same as {@link #archiveSections(Module)} but restricted to function
     * entries whose full path is in {@code keepPaths}. Used by writers that
     * filter the element list (lean / tests-only splits) so the embedded
     * function index matches the elements actually written to that PDB
     * — otherwise lean PDBs would carry test-only function entries from
     * the module's full metadata.
     */
    public List<PDBArchiveSection> archiveSections(Module module, java.util.Set<String> keepPaths)
    {
        if (module instanceof SourceModule localModule)
        {
            MutableList<PureLanguageMetadata> metas = localModule.getMetadataAccessExtension(PureLanguageMetadata.class);
            if (metas.notEmpty())
            {
                List<FunctionIndexEntry> all = metas.getFirst().getAllFunctionHeaders();
                if (keepPaths == null)
                {
                    return archiveSections(all);
                }
                // Order the index by keepPaths iteration (the caller's element
                // WRITE order — see CompressedArchiveWriter's LinkedHashSet),
                // not by the metadata map's internal order, so the emitted
                // functionIndex bytes are deterministic and match what the
                // ports produce from the same element list.
                java.util.Map<String, FunctionIndexEntry> byPath = new java.util.LinkedHashMap<>();
                for (FunctionIndexEntry e : all)
                {
                    byPath.putIfAbsent(e.fullPath(), e);
                }
                List<FunctionIndexEntry> filtered = new java.util.ArrayList<>();
                for (String path : keepPaths)
                {
                    FunctionIndexEntry e = byPath.get(path);
                    if (e != null)
                    {
                        filtered.add(e);
                    }
                }
                return archiveSections(filtered);
            }
        }
        return List.of();
    }

    /**
     * Build archive sections directly from a function-index entry list.
     * Used by writers that don't have a {@link SourceModule} to lean on
     * (e.g. the compile-via-pure path).
     */
    public List<PDBArchiveSection> archiveSections(List<FunctionIndexEntry> entries)
    {
        if (entries.isEmpty())
        {
            return List.of();
        }
        return List.of(new PDBArchiveSection("functionIndex", serializeFunctionIndex(entries)));
    }

    public byte[] serializeFunctionIndex(List<FunctionIndexEntry> entries)
    {
        FlatBufferBuilder builder = new FlatBufferBuilder(4096);
        GeneratedFlatBufferWriter writer = new GeneratedFlatBufferWriter(builder);

        int[] entryOffsets = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++)
        {
            FunctionIndexEntry entry = entries.get(i);
            int fullPathOffset = builder.createString(entry.fullPath());
            int functionNameOffset = builder.createString(entry._functionName());
            int functionTypeOffset = writer.writeFunctionType(entry.functionType());
            boolean isNative = entry instanceof NativeFunctionIndexEntry;
            entryOffsets[i] = org.finos.legend.pure.m3.module.pdbModule.fbs.FunctionIndexEntry
                    .createFunctionIndexEntry(builder, fullPathOffset, functionNameOffset, functionTypeOffset, isNative);
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
            // Parent-reference chain steps (`~.~`, `~.~.~`, …) parse as
            // DotApplication(functionName="~", parametersValues=[...]) — "~"
            // isn't a real function, so {@code _func} is intentionally null.
            // The type is stamped from the construction stack at compile time
            // and the receiver is the chain itself; no function ref to validate.
            boolean isParentRefStep = vs instanceof platform.pure.metamodel.valuespecification.DotApplication
                    && "~".equals(fe._functionName());
            if (fe._func() == null && !isParentRefStep)
            {
                throw new IllegalStateException(
                        "FunctionExpression '" + fe._functionName() + "' has null _func() in " + funcPath
                                + " at " + (fe._sourceInformation() != null
                                ? fe._sourceInformation()._sourceId() + ":" + fe._sourceInformation()._startLine() + "c" + fe._sourceInformation()._startColumn()
                                        + "-" + fe._sourceInformation()._endLine() + "c" + fe._sourceInformation()._endColumn()
                                : "unknown"));
            }
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

