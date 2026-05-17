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

package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.string.StringHelper;
import org.finos.legend.pure.truffle.runtime.helper._PackageableElement;

/**
 * {@code elementToPath(PackageableElement[1]) : String[1]} and
 * {@code elementToPath(PackageableElement[1], String[1]) : String[1]}.
 * Returns the path string of a PackageableElement.
 */
@NodeInfo(shortName = "elementToPath")
public final class ElementToPathNode extends PureNode
{
    @Children
    private PureNode[] children;

    private final Object genericType;
    private final Object multiplicity;

    public ElementToPathNode(PureNode[] children, Object genericType, Object multiplicity)
    {
        this.children = children;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] values = evaluateChildren(frame);
        return doElementToPath(values, getResolver());
    }

    @com.oracle.truffle.api.nodes.ExplodeLoop
    private Object[] evaluateChildren(VirtualFrame frame)
    {
        Object[] values = new Object[children.length];
        for (int i = 0; i < children.length; i++)
        {
            values[i] = children[i].executeGeneric(frame);
        }
        return values;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String doElementToPath(Object[] values,
            org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        Object element = values[0];
        String separator = values.length > 1 ? StringHelper.asString(values[1], "elementToPath") : "::";

        // TempCompilerPointer carries its canonical path directly; don't walk
        // the package chain (its package/name fields are unset).
        if (element != null && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(
                element, "meta::pure::metamodel::pointer::TempCompilerPointer", resolver))
        {
            Object pathObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(
                    element, org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("path"));
            String path = pathObj != null ? pathObj.toString() : "";
            if (path.isEmpty())
            {
                return "";
            }
            return "::".equals(separator) ? path : path.replace("::", separator);
        }

        // Try _PackageableElement.path for any Pure metamodel object — works
        // for both legacy XPDBHelper and PureDynamicObject. The path helper walks
        // the package chain via PureObj.read; the typed `instanceof
        // PackageableElement` guard wouldn't match a PDO post-loader-flip.
        if (element != null && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(element) != null)
        {
            String path = _PackageableElement.path(element, resolver);
            if (path == null || path.isEmpty() || "Root".equals(path) || "::".equals(path))
            {
                return "";
            }
            if ("::".equals(separator))
            {
                return path;
            }
            return path.replace("::", separator);
        }
        if ("::".equals(String.valueOf(element)))
        {
            return "";
        }
        return String.valueOf(element);
    }
}
