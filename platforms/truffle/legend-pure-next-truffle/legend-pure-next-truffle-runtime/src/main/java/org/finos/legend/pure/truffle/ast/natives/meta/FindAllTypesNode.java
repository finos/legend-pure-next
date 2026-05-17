// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code findAllTypes() : Type[*]} — enumerates every PE Type known to the
 * resolver across all loaded modules.
 *
 * <p>Walking from a single module's universe Root only reaches types
 * registered in that module's package tree; sibling modules' contributions
 * (e.g. {@code FuncColSpec} in {@code functions-relation} when the anchor
 * lives in {@code core}) are invisible. This native bypasses the package
 * tree entirely by iterating {@link TruffleMetadataAccess#elementPaths()}
 * (the union of every module's path index) and resolving each path through
 * the composite resolver.</p>
 *
 * <p>Used by {@code buildLinearizationCache} to seed the cache with every
 * Type-typed PE in the universe so that runtime {@code linearize} lookups
 * hit even for cross-module types.</p>
 */
@NodeInfo(shortName = "findAllTypes")
public final class FindAllTypesNode extends PureNode
{
    public FindAllTypesNode()
    {
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return collect(getResolver());
    }

    @TruffleBoundary
    private static Object collect(TruffleMetadataAccess resolver)
    {
        List<Object> types = new ArrayList<>();
        for (String path : resolver.elementPaths())
        {
            Object element = resolver.getElement(path);
            // Filter by Pure type via isType so this works for both legacy
            // XPDBHelper and PureDynamicObject post-loader-flip. The typed
            // `instanceof Type` Java guard would silently exclude every PDO,
            // leaving primitive types out of buildLinearizationCache and
            // tripping the Pure-side LINEARIZE MISS assert.
            if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(element,
                    "meta::pure::metamodel::type::Type", resolver))
            {
                types.add(element);
            }
        }
        if (types.isEmpty())
        {
            return PureSequence.EMPTY;
        }
        return new ObjectSequence(types.toArray());
    }
}
