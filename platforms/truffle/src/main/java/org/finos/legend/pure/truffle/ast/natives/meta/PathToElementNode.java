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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code pathToElement(String[1], String[1]) : PackageableElement[1]}.
 *
 * <p>Looks up a PackageableElement in the resolver by path and separator.
 * The 2-arg variant uses a user-specified separator; the 1-arg variant
 * defaults to "::".</p>
 */
@NodeInfo(shortName = "pathToElement")
public final class PathToElementNode extends PureNode
{
    @Children
    private PureNode[] children;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public PathToElementNode(PureNode[] children, GenericType genericType, Multiplicity multiplicity)
    {
        this.children = children;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] values = new Object[children.length];
        for (int i = 0; i < children.length; i++)
        {
            values[i] = children[i].executeGeneric(frame);
        }
        return doPathToElement(values, genericType, multiplicity);
    }

    @TruffleBoundary
    private static ValueSpecification doPathToElement(Object[] values, GenericType genericType, Multiplicity multiplicity)
    {
        MetadataAccess resolver = EvaluatorHolder.current().natives().resolver();

        String path = (String) ValueAdapter.toRaw(values[0]);
        String separator = values.length > 1 ? (String) ValueAdapter.toRaw(values[1]) : "::";

        if (!"::".equals(separator))
        {
            path = path.replace(separator, "::");
        }
        if (path.isEmpty() || "::".equals(path))
        {
            if (resolver != null)
            {
                PackageableElement rootPkg = resolver.getElement("");
                if (rootPkg == null)
                {
                    rootPkg = resolver.getElement("::");
                }
                if (rootPkg != null)
                {
                    return _E_ValueSpecification.wrap(rootPkg, genericType, multiplicity, resolver);
                }
            }
            return _E_ValueSpecification.wrap("::", genericType, multiplicity, resolver);
        }
        if (resolver == null)
        {
            throw new RuntimeException("pathToElement not connected to model: " + path);
        }
        PackageableElement element = resolver.getElement(path);
        if (element == null)
        {
            throw new RuntimeException("Element not found: " + path);
        }
        return _E_ValueSpecification.wrap(element, genericType, multiplicity, resolver);
    }
}
