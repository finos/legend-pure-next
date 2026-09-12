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

package org.finos.legend.pure.truffle.runtime.module.pdbModule;

import org.finos.legend.pure.truffle.runtime.module.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.module.TruffleModule;

import org.finos.legend.pure.m3.module.ModuleManifest;
import org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveReader;
import org.finos.legend.pure.truffle.runtime.module.TruffleTypeCache;
import org.finos.legend.pure.truffle.runtime.module.TypeCache;

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
    private final TypeCache typeCache =
            new TypeCache();
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
     * Stage 1 bridge for the {@link org.finos.legend.pure.truffle.runtime.pdo.PureDynamicObject}
     * migration: returns the loaded element wrapped in a PureDynamicObject so
     * call sites can be migrated to {@code DynamicObjectLibrary} access
     * incrementally. Until every site is migrated and we flip the loader's
     * default, both APIs coexist — wrapping is safe because the underlying FBW
     * already implements {@code PropertyAccessor.readProperty}, which the
     * dynobj layer uses as its decoder backend.
     */
    public org.finos.legend.pure.truffle.runtime.pdo.PureDynamicObject getElementAsDynamic(String path)
    {
        Object elem = getElement(path);
        if (elem == null)
        {
            return null;
        }
        // Post-loader-flip getElement only ever returns PureDynamicObjects;
        // the legacy wrap-a-typed-wrapper path is gone with the codegen.
        return elem instanceof org.finos.legend.pure.truffle.runtime.pdo.PureDynamicObject pdo ? pdo : null;
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
            // Reentrancy guard, first-wins: deserialize itself resolves
            // pointers (class-info population walks superclasses and property
            // types), which can re-enter getElement for the SAME path — e.g.
            // loading primitives::String populates PrimitiveType, whose
            // `name : String[1]` property type resolution loads String again.
            // The inner load completes first and its object is already
            // referenced downstream; the outer frame must adopt it instead of
            // clobbering the cache with a second identity (observed: two
            // primitives::String PDOs → identity-based instanceOf/assertIs
            // failures in the in-memory PCT suite).
            Object raced = cache.get(path);
            if (raced != null && raced != ABSENT)
            {
                return raced;
            }
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
    public TruffleTypeCache typeCache()
    {
        return typeCache;
    }


    /**
     * Deserialize a FlatBuffer element into a resolver-backed
     * {@link org.finos.legend.pure.truffle.runtime.pdo.PureDynamicObject}.
     * The typeName is the FBS table name (e.g. "UserDefinedFunction", "Class");
     * its Pure class path comes from the same schema-driven mapping the
     * generic decoder uses for nested tables — no hardcoded type table.
     */
    // TruffleBoundary: cache-miss-only path of getElement, which IS
    // inlined into compiled graphs at every pointer-resolution site.
    // The purePathForDef call below carries a scan loop that must not
    // be partial-evaluated into all of them.
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object deserialize(String typeName, byte[] data)
    {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        String defClassName = "org.finos.legend.pure.m3.module.pdbModule.fbs." + typeName + "Def";
        String purePath = GenericFbDecoder
                .purePathForDef(typeName + "Def", resolver);
        try
        {
            Class<?> defClass = Class.forName(defClassName);
            var getRootMethod = defClass.getMethod("getRootAs" + typeName + "Def", ByteBuffer.class);
            Object def = getRootMethod.invoke(null, bb);
            return new org.finos.legend.pure.truffle.runtime.pdo.PureDynamicObject(
                    org.finos.legend.pure.truffle.runtime.module.PureClassRegistry.classInfoFor(purePath, resolver),
                    def, resolver, null);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to deserialize element type=" + typeName + ": " + e.getMessage(), e);
        }
    }

}
