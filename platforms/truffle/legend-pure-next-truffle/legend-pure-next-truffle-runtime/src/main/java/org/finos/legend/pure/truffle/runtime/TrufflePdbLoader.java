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

    public TrufflePdbLoader(Path pdbPath) throws IOException
    {
        this(pdbPath, defaultName(pdbPath), java.util.List.of());
    }

    public TrufflePdbLoader(Path pdbPath, String name, java.util.List<String> dependencies) throws IOException
    {
        this.name = name;
        this.dependencies = java.util.List.copyOf(dependencies);
        this.archive = new CompressedArchiveReader(pdbPath);
        int elementCount = archive.elementPaths().size();
        // Pre-size to avoid resize: capacity = count / 0.75 + 1
        int capacity = (int) (elementCount / 0.75) + 1;
        this.cache = new HashMap<>(capacity);
        this.reverseCache = new java.util.IdentityHashMap<>(elementCount);
    }

    /**
     * Derive a module name from the PDB filename when one isn't given —
     * keeps the no-arg constructor backwards-compatible while still
     * giving the resulting module a stable identity.
     */
    private static String defaultName(Path pdbPath)
    {
        String fileName = pdbPath.getFileName().toString();
        return fileName.endsWith(".pdb")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
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
        // XImpl's static{} block in PureFbDecoderRegistry handle property reads.
        // Each XImpl's class is loaded lazily on first read (via Class.forName
        // in PureFbDecoderRegistry.lazyLoad), which triggers static-init and
        // registers the decoder.
        String purePath = wrapperClassName
                .replace("org.finos.legend.pure.truffle.pdb.", "")
                .replaceFirst("Impl$", "")
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
        m.put("UserDefinedFunction", p + "function.UserDefinedFunctionImpl");
        m.put("NativeFunction", p + "function.NativeFunctionImpl");
        m.put("LambdaFunction", p + "function.LambdaFunctionImpl");

        // Types
        m.put("Class", p + "type.ClassImpl");
        m.put("Enumeration", p + "type.EnumerationImpl");
        m.put("PrimitiveType", p + "type.PrimitiveTypeImpl");
        m.put("FunctionType", p + "type.FunctionTypeImpl");

        // Properties
        m.put("Property", p + "function.property.PropertyImpl");
        m.put("QualifiedProperty", p + "function.property.QualifiedPropertyImpl");

        // ValueSpecifications
        String vs = p + "valuespecification.";
        m.put("ArrowInvocation", vs + "ArrowInvocationImpl");
        m.put("AtomicValue", vs + "AtomicValueImpl");
        m.put("Collection", vs + "CollectionImpl");
        m.put("DotApplication", vs + "DotApplicationImpl");
        m.put("FunctionInvocation", vs + "FunctionInvocationImpl");
        m.put("VariableExpression", vs + "VariableExpressionImpl");
        m.put("GenericTypeAndMultiplicityHolder", vs + "GenericTypeAndMultiplicityHolderImpl");
        m.put("UserDefinedGenericTypeAndMultiplicityHolder", vs + "UserDefinedGenericTypeAndMultiplicityHolderImpl");
        m.put("CompilerGenericTypeAndMultiplicityHolder", vs + "CompilerGenericTypeAndMultiplicityHolderImpl");

        // Generics
        String gt = p + "type.generics.";
        m.put("UserDefinedGenericType", gt + "UserDefinedGenericTypeImpl");
        m.put("UserDefinedPackageableGenericType", gt + "UserDefinedPackageableGenericTypeImpl");
        m.put("InferredGenericType", gt + "InferredGenericTypeImpl");
        m.put("InferredPackageableGenericType", gt + "InferredPackageableGenericTypeImpl");
        m.put("UndefinedGenericType", gt + "UndefinedGenericTypeImpl");
        m.put("CompilerNotSetGenericType", gt + "CompilerNotSetGenericTypeImpl");
        m.put("TypeParameter", gt + "TypeParameterImpl");
        m.put("ResolvedTypeParameter", gt + "ResolvedTypeParameterImpl");
        m.put("ResolvedMultiplicityParameter", gt + "ResolvedMultiplicityParameterImpl");
        m.put("GenericTypeOperation", p + "relation.GenericTypeOperationImpl");

        // Multiplicities
        String mu = p + "multiplicity.";
        m.put("UserDefinedAdHocMultiplicity", mu + "UserDefinedAdHocMultiplicityImpl");
        m.put("UserDefinedPackageableMultiplicity", mu + "UserDefinedPackageableMultiplicityImpl");
        m.put("UserDefinedMultiplicityParameter", mu + "UserDefinedMultiplicityParameterImpl");
        m.put("InferredAdHocMultiplicity", mu + "InferredAdHocMultiplicityImpl");
        m.put("InferredPackageableMultiplicity", mu + "InferredPackageableMultiplicityImpl");
        m.put("InferredMultiplicityParameter", mu + "InferredMultiplicityParameterImpl");
        m.put("UndefinedMultiplicity", mu + "UndefinedMultiplicityImpl");
        m.put("CompilerNotSetMultiplicity", mu + "CompilerNotSetMultiplicityImpl");
        m.put("MultiplicityValue", mu + "MultiplicityValueImpl");

        // Other
        m.put("Package", p + "PackageImpl");
        m.put("Association", p + "relationship.AssociationImpl");
        m.put("Generalization", p + "relationship.GeneralizationImpl");
        m.put("Constraint", p + "constraint.ConstraintImpl");
        m.put("Profile", p + "extension.ProfileImpl");
        m.put("Enum", p + "type.EnumImpl");
        m.put("Stereotype", p + "extension.StereotypeImpl");
        m.put("Tag", p + "extension.TagImpl");
        m.put("TaggedValue", p + "extension.TaggedValueImpl");
        m.put("Annotation", p + "extension.AnnotationImpl");
        m.put("SourceInformation", p + "SourceInformationImpl");
        m.put("ConstraintsGetterOverride", p + "constraint.ConstraintsGetterOverrideImpl");
        m.put("Relation", p + "relation.RelationImpl");
        m.put("RelationElementAccessor", p + "relation.RelationElementAccessorImpl");
        m.put("RelationType", p + "relation.RelationTypeImpl");
        m.put("Column", p + "relation.ColumnImpl");
        m.put("Nil", p + "type.NilImpl");
        m.put("Test", p + "testable.TestImpl");

        return m;
    }
}
