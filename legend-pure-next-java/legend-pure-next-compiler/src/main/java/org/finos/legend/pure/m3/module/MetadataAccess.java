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

import java.util.Set;

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
}
