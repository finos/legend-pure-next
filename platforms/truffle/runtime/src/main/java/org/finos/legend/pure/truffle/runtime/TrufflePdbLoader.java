// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.truffle.runtime;

import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;

import org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveReader;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Truffle PDB loader — reads .pdb archives and creates truffle-namespaced
 * FlatBuffer wrappers. No bootstrap wrapper dependency.
 *
 * <p>Implements {@link TruffleMetadataAccess} so it can be passed directly to
 * {@link org.finos.legend.pure.truffle.StandaloneEvaluator}.</p>
 */
public final class TrufflePdbLoader implements TruffleMetadataAccess
{
    private final CompressedArchiveReader archive;
    private final Map<String, Object> cache = new HashMap<>();

    public TrufflePdbLoader(Path pdbPath) throws IOException
    {
        this.archive = new CompressedArchiveReader(pdbPath);
        System.err.println("[LOADER] Opened " + pdbPath + " with " + archive.elementPaths().size() + " elements");
    }

    @Override
    public Object getElement(String path)
    {
        Object cached = cache.get(path);
        if (cached != null)
        {
            return cached;
        }

        if (!archive.hasElement(path))
        {
            return null;
        }

        String typeName = archive.getElementType(path);
        byte[] data = archive.readEntryBytes(path);
        if (typeName == null || data == null)
        {
            return null;
        }

        Object element = deserialize(typeName, data);
        if (element == null)
        {
            System.err.println("[LOADER] null for type=" + typeName + " path=" + path);
        }
        else
        {
            cache.put(path, element);
            if (element instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition)
            {
                System.err.println("[LOADER] FD: " + path + " -> " + element.getClass().getSimpleName());
            }
        }
        return element;
    }

    @Override
    public boolean hasElement(String path)
    {
        return archive.hasElement(path);
    }

    @Override
    public Set<String> elementPaths()
    {
        return archive.elementPaths();
    }


    /**
     * Deserialize a FlatBuffer element into a truffle wrapper.
     * The typeName is the FBS table name (e.g. "UserDefinedFunction", "Class").
     */
    private Object deserialize(String typeName, byte[] data)
    {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        String defClassName = "org.finos.legend.pure.m3.module.pdbModule.fbs." + typeName + "Def";
        String wrapperClassName = resolveWrapperClassName(typeName);

        if (wrapperClassName == null)
        {
            return null;
        }

        try
        {
            // Create the FBS Def from the byte buffer
            Class<?> defClass = Class.forName(defClassName);
            var getRootMethod = defClass.getMethod("getRootAs" + typeName + "Def", ByteBuffer.class);
            Object def = getRootMethod.invoke(null, bb);

            // Create the truffle wrapper
            Class<?> wrapperClass = Class.forName(wrapperClassName);
            return wrapperClass.getConstructor(defClass, TruffleMetadataAccess.class).newInstance(def, this);
        }
        catch (Exception e)
        {
            // Fallback: try without "Def" suffix in getRootAs method
            try
            {
                Class<?> defClass = Class.forName(defClassName);
                Object def = defClass.getMethod("getRootAs" + typeName + "Def", ByteBuffer.class, defClass)
                        .invoke(null, bb, defClass.getDeclaredConstructor().newInstance());
                Class<?> wrapperClass = Class.forName(wrapperClassName);
                return wrapperClass.getConstructor(defClass, TruffleMetadataAccess.class).newInstance(def, this);
            }
            catch (Exception e2)
            {
                return null;
            }
        }
    }

    /**
     * Map a FBS type name to the truffle wrapper FQN.
     */
    private static String resolveWrapperClassName(String typeName)
    {
        // The PdbJavaGenerator generates wrappers in the truffle namespace.
        // The mapping from FBS type name to package follows the Pure metamodel structure.
        return TYPE_TO_WRAPPER.get(typeName);
    }

    // Pre-built mapping from FBS type names to truffle wrapper FQNs.
    // This mirrors the PdbJavaGenerator output.
    private static final Map<String, String> TYPE_TO_WRAPPER = buildTypeMap();

    private static Map<String, String> buildTypeMap()
    {
        Map<String, String> m = new HashMap<>();
        String p = "org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.";

        // Functions
        m.put("UserDefinedFunction", p + "function.UserDefinedFunctionFlatBufferWrapper");
        m.put("NativeFunction", p + "function.NativeFunctionFlatBufferWrapper");
        m.put("LambdaFunction", p + "function.LambdaFunctionFlatBufferWrapper");

        // Types
        m.put("Class", p + "type.ClassFlatBufferWrapper");
        m.put("Enumeration", p + "type.EnumerationFlatBufferWrapper");
        m.put("PrimitiveType", p + "type.PrimitiveTypeFlatBufferWrapper");
        m.put("FunctionType", p + "type.FunctionTypeFlatBufferWrapper");

        // Properties
        m.put("Property", p + "function.property.PropertyFlatBufferWrapper");
        m.put("QualifiedProperty", p + "function.property.QualifiedPropertyFlatBufferWrapper");

        // ValueSpecifications
        String vs = p + "valuespecification.";
        m.put("ArrowInvocation", vs + "ArrowInvocationFlatBufferWrapper");
        m.put("AtomicValue", vs + "AtomicValueFlatBufferWrapper");
        m.put("Collection", vs + "CollectionFlatBufferWrapper");
        m.put("DotApplication", vs + "DotApplicationFlatBufferWrapper");
        m.put("FunctionInvocation", vs + "FunctionInvocationFlatBufferWrapper");
        m.put("VariableExpression", vs + "VariableExpressionFlatBufferWrapper");
        m.put("GenericTypeAndMultiplicityHolder", vs + "GenericTypeAndMultiplicityHolderFlatBufferWrapper");
        m.put("UserDefinedGenericTypeAndMultiplicityHolder", vs + "UserDefinedGenericTypeAndMultiplicityHolderFlatBufferWrapper");
        m.put("CompilerGenericTypeAndMultiplicityHolder", vs + "CompilerGenericTypeAndMultiplicityHolderFlatBufferWrapper");

        // Generics
        String gt = p + "type.generics.";
        m.put("UserDefinedGenericType", gt + "UserDefinedGenericTypeFlatBufferWrapper");
        m.put("UserDefinedPackageableGenericType", gt + "UserDefinedPackageableGenericTypeFlatBufferWrapper");
        m.put("InferredGenericType", gt + "InferredGenericTypeFlatBufferWrapper");
        m.put("InferredPackageableGenericType", gt + "InferredPackageableGenericTypeFlatBufferWrapper");
        m.put("UndefinedGenericType", gt + "UndefinedGenericTypeFlatBufferWrapper");
        m.put("CompilerNotSetGenericType", gt + "CompilerNotSetGenericTypeFlatBufferWrapper");
        m.put("TypeParameter", gt + "TypeParameterFlatBufferWrapper");
        m.put("ResolvedTypeParameter", gt + "ResolvedTypeParameterFlatBufferWrapper");
        m.put("ResolvedMultiplicityParameter", gt + "ResolvedMultiplicityParameterFlatBufferWrapper");
        m.put("GenericTypeOperation", p + "relation.GenericTypeOperationFlatBufferWrapper");

        // Multiplicities
        String mu = p + "multiplicity.";
        m.put("UserDefinedAdHocMultiplicity", mu + "UserDefinedAdHocMultiplicityFlatBufferWrapper");
        m.put("UserDefinedPackageableMultiplicity", mu + "UserDefinedPackageableMultiplicityFlatBufferWrapper");
        m.put("UserDefinedMultiplicityParameter", mu + "UserDefinedMultiplicityParameterFlatBufferWrapper");
        m.put("InferredAdHocMultiplicity", mu + "InferredAdHocMultiplicityFlatBufferWrapper");
        m.put("InferredPackageableMultiplicity", mu + "InferredPackageableMultiplicityFlatBufferWrapper");
        m.put("InferredMultiplicityParameter", mu + "InferredMultiplicityParameterFlatBufferWrapper");
        m.put("UndefinedMultiplicity", mu + "UndefinedMultiplicityFlatBufferWrapper");
        m.put("CompilerNotSetMultiplicity", mu + "CompilerNotSetMultiplicityFlatBufferWrapper");
        m.put("MultiplicityValue", mu + "MultiplicityValueFlatBufferWrapper");

        // Other
        m.put("Package", p + "PackageFlatBufferWrapper");
        m.put("Association", p + "relationship.AssociationFlatBufferWrapper");
        m.put("Generalization", p + "relationship.GeneralizationFlatBufferWrapper");
        m.put("Constraint", p + "constraint.ConstraintFlatBufferWrapper");
        m.put("Profile", p + "extension.ProfileFlatBufferWrapper");
        m.put("Enum", p + "type.EnumFlatBufferWrapper");
        m.put("Stereotype", p + "extension.StereotypeFlatBufferWrapper");
        m.put("Tag", p + "extension.TagFlatBufferWrapper");
        m.put("TaggedValue", p + "extension.TaggedValueFlatBufferWrapper");
        m.put("Annotation", p + "extension.AnnotationFlatBufferWrapper");
        m.put("SourceInformation", p + "SourceInformationFlatBufferWrapper");
        m.put("ConstraintsGetterOverride", p + "constraint.ConstraintsGetterOverrideFlatBufferWrapper");
        m.put("Relation", p + "relation.RelationFlatBufferWrapper");
        m.put("RelationElementAccessor", p + "relation.RelationElementAccessorFlatBufferWrapper");
        m.put("RelationType", p + "relation.RelationTypeFlatBufferWrapper");
        m.put("Column", p + "relation.ColumnFlatBufferWrapper");
        m.put("Nil", p + "type.NilFlatBufferWrapper");
        m.put("Test", p + "testable.TestFlatBufferWrapper");

        return m;
    }
}
