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
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.Multiplicity;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.string.StringHelper;

/**
 * {@code pathToElement(String[1], String[1]) : PackageableElement[1]}.
 * Looks up a PackageableElement in the resolver by path.
 */
@NodeInfo(shortName = "pathToElement")
public final class PathToElementNode extends PureNode
{
    @Children
    private PureNode[] children;

    private final GenericType genericType;
    private final Multiplicity multiplicity;
    private final boolean lenient;

    public PathToElementNode(PureNode[] children, GenericType genericType, Multiplicity multiplicity)
    {
        this(children, genericType, multiplicity, false);
    }

    public PathToElementNode(PureNode[] children, GenericType genericType, Multiplicity multiplicity, boolean lenient)
    {
        this.children = children;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
        this.lenient = lenient;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] values = evaluateChildren(frame);
        return doPathToElement(values, lenient, getResolver());
    }

    @ExplodeLoop
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
    private static Object doPathToElement(Object[] values, boolean lenient, TruffleMetadataAccess resolver)
    {

        String path = StringHelper.asString(values[0], "pathToElement");
        String separator = values.length > 1 ? StringHelper.asString(values[1], "pathToElement") : "::";

        if (!"::".equals(separator))
        {
            path = path.replace(separator, "::");
        }
        if (path.isEmpty() || "::".equals(path))
        {
            if (resolver != null)
            {
                Object rootPkg = resolver.getElement("");
                if (rootPkg == null)
                {
                    rootPkg = resolver.getElement("::");
                }
                if (rootPkg != null)
                {
                    return rootPkg;
                }
            }
            return "::";
        }
        if (resolver == null)
        {
            throw new RuntimeException("pathToElement not connected to model: " + path);
        }
        Object element = resolver.getElement(path);
        if (element == null)
        {
            if (lenient)
            {
                return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
            }
            throw new RuntimeException("Element not found: " + path);
        }
        return element;
    }
}
