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

import org.finos.legend.pure.m3.module.ModuleManifest;
import org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveReader;

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
public final class TrufflePdbLoader implements TruffleModule
{
    private final String name;
    private final java.util.List<String> dependencies;
    private final CompressedArchiveReader archive;
    private final Map<String, Object> cache;
    private final java.util.IdentityHashMap<Object, String> reverseCache;
    private final org.finos.legend.pure.truffle.runtime.helper.TypeCache typeCache =
            new org.finos.legend.pure.truffle.runtime.helper.TypeCache();
    private TruffleMetadataAccess resolver = this; // default: self. Set to composite for multi-module.

    /**
     * Open a PDB and adopt the identity declared in its embedded manifest.
     */
    public TrufflePdbLoader(Path pdbPath) throws IOException
    {
        this.archive = new CompressedArchiveReader(pdbPath);
        ModuleManifest manifest = archive.readManifest();
        if (manifest == null)
        {
            throw new IOException("PDB archive at " + pdbPath + " has no module manifest section. "
                    + "It must be rebuilt with a writer that embeds one.");
        }
        this.name = manifest.name();
        this.dependencies = manifest.dependencies();
        int elementCount = archive.elementPaths().size();
        int capacity = (int) (elementCount / 0.75) + 1;
        this.cache = new HashMap<>(capacity);
        this.reverseCache = new java.util.IdentityHashMap<>(elementCount);
    }

    @Override
    public String name()
    {
        return name;
    }

    @Override
    public java.util.List<String> dependencies()
    {
        return dependencies;
    }

    /**
     * Pre-load all elements from the PDB into the cache.
     * Call after setResolver() to ensure FBWs get the composite resolver.
     * Eliminates lazy deserialization during execution.
     */
    public void preloadAll()
    {
        for (String path : archive.elementPaths())
        {
            getElement(path);
        }
    }

    /**
     * Set the resolver used by FlatBuffer wrappers for cross-module resolution.
     * Must be called before any getElement() to ensure FBWs get the composite resolver.
     */
    public void setResolver(TruffleMetadataAccess compositeResolver)
    {
        this.resolver = compositeResolver;
    }

    private static final Object ABSENT = new Object(); // sentinel for negative cache

    /**
     * Stage 1 bridge for the {@link org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject}
     * migration: returns the loaded element wrapped in a PureDynamicObject so
     * call sites can be migrated to {@code DynamicObjectLibrary} access
     * incrementally. Until every site is migrated and we flip the loader's
     * default, both APIs coexist — wrapping is safe because the underlying FBW
     * already implements {@code PropertyAccessor.readProperty}, which the
     * dynobj layer uses as its decoder backend.
     */
    public org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject getElementAsDynamic(String path)
    {
        Object elem = getElement(path);
        if (elem == null)
        {
            return null;
        }
        if (elem instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdo)
        {
            return pdo;
        }
        String pureTypePath = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.derivePureTypePathFrom(elem);
        if (pureTypePath == null)
        {
            return null;
        }
        return new org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject(
                org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.classInfoFor(pureTypePath, this.resolver),
                /*fb=*/elem, /*resolver=*/this.resolver, /*parent=*/null);
    }

    @Override
    public Object getElement(String path)
    {
        Object cached = cache.get(path);
        if (cached != null)
        {
            return cached == ABSENT ? null : cached;
        }

        if (!archive.hasElement(path))
        {
            cache.put(path, ABSENT);
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
            throw new RuntimeException("[LOADER] null for type=" + typeName + " path=" + path);
        }
        else
        {
            cache.put(path, element);
            reverseCache.put(element, path);
        }
        return element;
    }

    @Override
    public String pathOf(Object element)
    {
        return reverseCache.get(element);
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

    @Override
    public org.finos.legend.pure.truffle.runtime.TruffleTypeCache typeCache()
    {
        return typeCache;
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

        // Loader flip: construct PureDynamicObject backed by the raw FB Def.
        // The Shape's dynamic type is the Pure-class path (derived from the
        // wrapperClassName); per-class decoders registered by each generated
        // XPDBHelper's static{} block in PureFbDecoderRegistry handle property reads.
        // Each XPDBHelper's class is loaded lazily on first read (via Class.forName
        // in PureFbDecoderRegistry.lazyLoad), which triggers static-init and
        // registers the decoder.
        String purePath = wrapperClassName
                .replace("org.finos.legend.pure.truffle.pdb.", "")
                .replaceFirst("PDBHelper$", "")
                .replace(".", "::");
        try
        {
            Class<?> defClass = Class.forName(defClassName);
            var getRootMethod = defClass.getMethod("getRootAs" + typeName + "Def", ByteBuffer.class);
            Object def = getRootMethod.invoke(null, bb);
            return new org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject(
                    org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.classInfoFor(purePath, resolver),
                    def, resolver, null);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to deserialize element type=" + typeName + ": " + e.getMessage(), e);
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
        Map<String, String> m = new HashMap<>(64);
        String p = "org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.";

        // Functions
        m.put("UserDefinedFunction", p + "function.UserDefinedFunctionPDBHelper");
        m.put("NativeFunction", p + "function.NativeFunctionPDBHelper");
        m.put("LambdaFunction", p + "function.LambdaFunctionPDBHelper");

        // Types
        m.put("Class", p + "type.ClassPDBHelper");
        m.put("Enumeration", p + "type.EnumerationPDBHelper");
        m.put("PrimitiveType", p + "type.PrimitiveTypePDBHelper");
        m.put("FunctionType", p + "type.FunctionTypePDBHelper");

        // Properties
        m.put("Property", p + "function.property.PropertyPDBHelper");
        m.put("QualifiedProperty", p + "function.property.QualifiedPropertyPDBHelper");

        // ValueSpecifications
        String vs = p + "valuespecification.";
        m.put("ArrowInvocation", vs + "ArrowInvocationPDBHelper");
        m.put("AtomicValue", vs + "AtomicValuePDBHelper");
        m.put("Collection", vs + "CollectionPDBHelper");
        m.put("DotApplication", vs + "DotApplicationPDBHelper");
        m.put("FunctionInvocation", vs + "FunctionInvocationPDBHelper");
        m.put("VariableExpression", vs + "VariableExpressionPDBHelper");
        m.put("GenericTypeAndMultiplicityHolder", vs + "GenericTypeAndMultiplicityHolderPDBHelper");
        m.put("UserDefinedGenericTypeAndMultiplicityHolder", vs + "UserDefinedGenericTypeAndMultiplicityHolderPDBHelper");
        m.put("CompilerGenericTypeAndMultiplicityHolder", vs + "CompilerGenericTypeAndMultiplicityHolderPDBHelper");

        // Generics
        String gt = p + "type.generics.";
        m.put("UserDefinedGenericType", gt + "UserDefinedGenericTypePDBHelper");
        m.put("UserDefinedPackageableGenericType", gt + "UserDefinedPackageableGenericTypePDBHelper");
        m.put("InferredGenericType", gt + "InferredGenericTypePDBHelper");
        m.put("InferredPackageableGenericType", gt + "InferredPackageableGenericTypePDBHelper");
        m.put("UndefinedGenericType", gt + "UndefinedGenericTypePDBHelper");
        m.put("CompilerNotSetGenericType", gt + "CompilerNotSetGenericTypePDBHelper");
        m.put("TypeParameter", gt + "TypeParameterPDBHelper");
        m.put("ResolvedTypeParameter", gt + "ResolvedTypeParameterPDBHelper");
        m.put("ResolvedMultiplicityParameter", gt + "ResolvedMultiplicityParameterPDBHelper");
        m.put("GenericTypeOperation", p + "relation.GenericTypeOperationPDBHelper");

        // Multiplicities
        String mu = p + "multiplicity.";
        m.put("UserDefinedAdHocMultiplicity", mu + "UserDefinedAdHocMultiplicityPDBHelper");
        m.put("UserDefinedPackageableMultiplicity", mu + "UserDefinedPackageableMultiplicityPDBHelper");
        m.put("UserDefinedMultiplicityParameter", mu + "UserDefinedMultiplicityParameterPDBHelper");
        m.put("InferredAdHocMultiplicity", mu + "InferredAdHocMultiplicityPDBHelper");
        m.put("InferredPackageableMultiplicity", mu + "InferredPackageableMultiplicityPDBHelper");
        m.put("InferredMultiplicityParameter", mu + "InferredMultiplicityParameterPDBHelper");
        m.put("UndefinedMultiplicity", mu + "UndefinedMultiplicityPDBHelper");
        m.put("CompilerNotSetMultiplicity", mu + "CompilerNotSetMultiplicityPDBHelper");
        m.put("MultiplicityValue", mu + "MultiplicityValuePDBHelper");

        // Other
        m.put("Package", p + "PackagePDBHelper");
        m.put("Association", p + "relationship.AssociationPDBHelper");
        m.put("Generalization", p + "relationship.GeneralizationPDBHelper");
        m.put("Constraint", p + "constraint.ConstraintPDBHelper");
        m.put("Profile", p + "extension.ProfilePDBHelper");
        m.put("Enum", p + "type.EnumPDBHelper");
        m.put("Stereotype", p + "extension.StereotypePDBHelper");
        m.put("Tag", p + "extension.TagPDBHelper");
        m.put("TaggedValue", p + "extension.TaggedValuePDBHelper");
        m.put("Annotation", p + "extension.AnnotationPDBHelper");
        m.put("SourceInformation", p + "SourceInformationPDBHelper");
        m.put("ConstraintsGetterOverride", p + "constraint.ConstraintsGetterOverridePDBHelper");
        m.put("Relation", p + "relation.RelationPDBHelper");
        m.put("RelationElementAccessor", p + "relation.RelationElementAccessorPDBHelper");
        m.put("RelationType", p + "relation.RelationTypePDBHelper");
        m.put("Column", p + "relation.ColumnPDBHelper");
        m.put("Nil", p + "type.NilPDBHelper");
        m.put("Test", p + "testable.TestPDBHelper");

        return m;
    }
}
