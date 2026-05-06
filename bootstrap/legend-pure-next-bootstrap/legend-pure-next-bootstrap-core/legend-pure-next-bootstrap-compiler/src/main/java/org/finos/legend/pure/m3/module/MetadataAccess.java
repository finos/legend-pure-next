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

package org.finos.legend.pure.m3.module;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.type.Type;
import org.eclipse.collections.api.list.MutableList;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import java.util.function.Function;

/**
 * Provides access to compiled Pure elements and indices.
 *
 * <p>This is the read-side contract for a module — retrieving
 * elements by path, listing known paths, and accessing
 * the function and element indices.</p>
 */
public interface MetadataAccess
{
    /**
     * Get an element by its fully-qualified path.
     * May load lazily from the underlying storage.
     */
    PackageableElement getElement(String path);

    /**
     * Check if an element exists.
     */
    boolean hasElement(String path);

    /**
     * Get all element paths.
     */
    Set<String> elementPaths();

    /**
     * @return the top type (Any)
     */
    default Type any()
    {
        PackageableElement e = getElement("meta::pure::metamodel::type::Any");
        return e instanceof Type t ? t : null;
    }

    /**
     * @return the bottom type (Nil)
     */
    default Type nil()
    {
        PackageableElement e = getElement("meta::pure::metamodel::type::Nil");
        return e instanceof Type t ? t : null;
    }

    <T extends MetadataAccessExtension> MutableList<T> getMetadataAccessExtension(Class<T> clz);

    default Map<Type, MutableList<Type>> linearizationCache()
    {
        return new IdentityHashMap<>();
    }

    /**
     * Per-resolver cache for equality-key property names (the names of
     * properties stereotyped {@code <<meta::pure::profiles::equality.Key>>}
     * on a Type and its supertypes). Walks the same generalization chain
     * as {@link #linearizationCache()}; without caching it re-walks on
     * every {@code pureEquals} call against a DynamicInstance. Concrete
     * implementations override to provide a persistent {@link IdentityHashMap}.
     */
    default Map<Type, java.util.List<String>> equalityKeyPropertiesCache()
    {
        return new IdentityHashMap<>();
    }

    /**
     * Per-resolver cache for {@code _PackageableElement.path(element)}.
     * Walks the parent chain and concatenates names; without caching it
     * re-walks on every call (JFR flagged it at 2–8% of self-host CPU).
     * Identity-keyed because the FBW caches its parent Package wrapper,
     * so the same {@code PackageableElement} instance returns the same
     * computed path. Concrete implementations override to provide a
     * persistent {@link IdentityHashMap}.
     */
    default Map<PackageableElement, String> elementPathCache()
    {
        return new IdentityHashMap<>();
    }
}
