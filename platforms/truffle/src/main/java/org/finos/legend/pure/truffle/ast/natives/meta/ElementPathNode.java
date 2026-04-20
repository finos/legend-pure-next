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

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.truffle.ast.PureNode;
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
        return buildPath(element);
    }

    private static Object buildPath(Object element)
    {
        if (!(element instanceof PackageableElement pe))
        {
            return element;
        }
        List<Object> path = new ArrayList<>();
        collectAncestors(pe, path);
        return new ObjectSequence(path.toArray());
    }

    private static void collectAncestors(PackageableElement pe, List<Object> path)
    {
        if (pe._package() instanceof PackageableElement parent)
        {
            collectAncestors(parent, path);
        }
        String name = pe._name();
        if (name != null && !name.isEmpty())
        {
            String elPath = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
            if (!"::".equals(elPath))
            {
                path.add(pe);
            }
        }
    }
}
