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
import org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;
import org.finos.legend.pure.truffle.runtime.helper._PackageableElement;
import org.finos.legend.pure.truffle.types.ObjectSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code elementPath(PackageableElement[1]) : PackageableElement[1..*]}.
 * Returns the ancestry chain from root package to the given element.
 */
@NodeInfo(shortName = "elementPath")
public final class ElementPathNode extends PureNode
{

    private static final int SLOT_NAME = PureClassRegistry.globalSlot("name");
    private static final int SLOT_PACKAGE = PureClassRegistry.globalSlot("package");
    @Child
    private PureNode child;

    public ElementPathNode(PureNode child)
    {
        this.child = child;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object element = child.executeGeneric(frame);
        return buildPath(element, getResolver());
    }

    @TruffleBoundary
    private static Object buildPath(Object element, TruffleMetadataAccess resolver)
    {
        // PackageableElement-ness is established by carrying a package + name
        // pair; collectAncestors() reads via PureObj which gracefully no-ops
        // on non-PE values, so an upfront type check would just be redundant.
        List<Object> path = new ArrayList<>();
        collectAncestors(element, path, resolver);
        return new ObjectSequence(path.toArray());
    }

    @TruffleBoundary
    private static void collectAncestors(Object pe, List<Object> path, TruffleMetadataAccess resolver)
    {
        // Deref pointer ancestors — compile-pure wraps Class.package and Package
        // children as pointers, but the walk needs the live element's
        // name/package slots which are intentionally unset on a pointer.
        Object live = _PackageableElement.derefPointer(pe, resolver);
        Object parent = PureObj.readBySlot(live, SLOT_PACKAGE);
        if (parent != null)
        {
            collectAncestors(parent, path, resolver);
        }
        Object nameObj = PureObj.readBySlot(live, SLOT_NAME);
        if (nameObj instanceof String name && !name.isEmpty())
        {
            String elPath = _PackageableElement.path(live, resolver);
            if (!"::".equals(elPath))
            {
                path.add(live);
            }
        }
    }
}
