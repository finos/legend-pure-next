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
import org.finos.legend.pure.truffle.ast.natives.collection.MapImpl;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.helper.PointerGraphImmutableResolver;
import org.finos.legend.pure.truffle.types.ObjectSequence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code resolveAndReturnGraph(elements:Map<String, PackageableElement>[1]) : PackageableElement[*]}.
 *
 * <p>Returns a deep copy of every value in {@code elements} with every
 * {@code TempCompilerPointer} resolved to its live target. The input map
 * and its element PDOs are not modified. See
 * {@link PointerGraphImmutableResolver} for the walk contract.</p>
 */
@NodeInfo(shortName = "resolveAndReturnGraph")
public final class ResolveAndReturnGraphNode extends PureNode
{
    @Child
    private PureNode elementsNode;

    public ResolveAndReturnGraphNode(PureNode elementsNode)
    {
        this.elementsNode = elementsNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object arg = elementsNode.executeGeneric(frame);
        if (!(arg instanceof MapImpl map))
        {
            throw new IllegalArgumentException(
                    "resolveAndReturnGraph: expected MapImpl argument, got "
                            + (arg == null ? "null" : arg.getClass().getName()));
        }
        return doResolve(map, getResolver());
    }

    @TruffleBoundary
    private static Object doResolve(MapImpl map, TruffleMetadataAccess resolver)
    {
        Map<String, Object> byPath = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : map.getMap().entrySet())
        {
            byPath.put((String) e.getKey(), e.getValue());
        }
        List<Object> copies = PointerGraphImmutableResolver.resolveAllImmutable(byPath, resolver);
        return new ObjectSequence(copies.toArray());
    }
}
