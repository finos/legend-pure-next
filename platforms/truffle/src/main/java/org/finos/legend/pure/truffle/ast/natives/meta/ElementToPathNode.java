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
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.ValueSpecificationEvaluator;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.execution.natives.meta.ElementPathNatives;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code elementToPath(PackageableElement[1]) : String[1]} and
 * {@code elementToPath(PackageableElement[1], String[1]) : String[1]}.
 *
 * <p>Converts a PackageableElement to its path string representation,
 * optionally using a custom separator instead of "::".</p>
 */
@NodeInfo(shortName = "elementToPath")
public final class ElementToPathNode extends PureNode
{
    @Children
    private PureNode[] children;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public ElementToPathNode(PureNode[] children, GenericType genericType, Multiplicity multiplicity)
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
        return doElementToPath(values, genericType, multiplicity);
    }

    @TruffleBoundary
    private static ValueSpecification doElementToPath(Object[] values, GenericType genericType, Multiplicity multiplicity)
    {
        MetadataAccess resolver = EvaluatorHolder.current().natives().resolver();

        Object element = ValueAdapter.toRaw(values[0]);
        String separator = values.length > 1 ? (String) ValueAdapter.toRaw(values[1]) : "::";

        if (element instanceof PackageableElement pe)
        {
            return _E_ValueSpecification.wrap(
                    ElementPathNatives.elementToPathString(pe, separator),
                    genericType, multiplicity, resolver);
        }
        if (element instanceof DynamicInstance)
        {
            // For DynamicInstance, delegate to the full native which handles
            // dynamic path building via the evaluator
            ValueSpecification elementVS = ValueAdapter.ensureVS(values[0]);
            ValueSpecificationEvaluator eval = EvaluatorHolder.current();
            java.util.List<ValueSpecification> args = new java.util.ArrayList<>();
            args.add(elementVS);
            if (values.length > 1)
            {
                args.add(ValueAdapter.ensureVS(values[1]));
            }
            String sig = values.length > 1
                    ? "elementToPath_PackageableElement_1__String_1__String_1_"
                    : "elementToPath_PackageableElement_1__String_1_";
            return eval.natives().execute(sig, args, eval, genericType, multiplicity);
        }
        if ("::".equals(String.valueOf(element)))
        {
            return _E_ValueSpecification.wrap("", genericType, multiplicity, resolver);
        }
        return _E_ValueSpecification.wrap(String.valueOf(element), genericType, multiplicity, resolver);
    }
}
