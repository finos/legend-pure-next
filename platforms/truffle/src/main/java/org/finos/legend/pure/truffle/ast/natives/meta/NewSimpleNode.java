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
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.Any;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.GenericTypeValue;
import meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.ValueSpecificationEvaluator;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.execution.natives.meta.MetaNatives;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code new(GenericTypeAndMultiplicityHolder[1]) : T[1]}.
 *
 * <p>Constructs an instance with no property assignments. Extracts the class
 * path from the GTMH, creates the instance, sets its classifierGenericType,
 * and validates constraints.</p>
 */
@NodeInfo(shortName = "newSimple")
public final class NewSimpleNode extends PureNode
{
    @Child
    private PureNode child;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public NewSimpleNode(PureNode child, GenericType genericType, Multiplicity multiplicity)
    {
        this.child = child;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object result = child.executeGeneric(frame);
        return doNew(result, genericType, multiplicity);
    }

    @TruffleBoundary
    private static ValueSpecification doNew(Object result, GenericType genericType, Multiplicity multiplicity)
    {
        ValueSpecification vs = ValueAdapter.ensureVS(result);
        MetadataAccess resolver = EvaluatorHolder.current().natives().resolver();
        ValueSpecificationEvaluator eval = EvaluatorHolder.current();

        Object unwrapped = _E_ValueSpecification.unwrap(vs);
        if (!(unwrapped instanceof GenericTypeAndMultiplicityHolder gtmh))
        {
            throw new RuntimeException("new(GenericTypeAndMultiplicityHolder[1]) requires a GenericTypeAndMultiplicityHolder argument, got: "
                    + (unwrapped == null ? "null" : unwrapped.getClass().getSimpleName()));
        }

        String classPath = "Unknown";
        if (gtmh._genericType() != null
                && _GenericType.typeArguments(gtmh._genericType()) != null
                && _GenericType.typeArguments(gtmh._genericType()).notEmpty())
        {
            GenericType heldGT = _GenericType.typeArguments(gtmh._genericType()).getFirst();
            if (_GenericType.type(heldGT) instanceof meta.pure.metamodel.PackageableElement pe)
            {
                classPath = _PackageableElement.path(pe);
                if (classPath.isEmpty())
                {
                    classPath = pe._name() != null ? pe._name() : "Unknown";
                }
            }
        }

        Object instance = MetaNatives.createInstance(classPath, gtmh);

        if (gtmh._genericType() != null
                && _GenericType.typeArguments(gtmh._genericType()) != null
                && _GenericType.typeArguments(gtmh._genericType()).notEmpty())
        {
            GenericType cgt = _GenericType.typeArguments(gtmh._genericType()).getFirst();
            if (instance instanceof Any any)
            {
                any._classifierGenericType((GenericTypeValue) cgt);
            }
            else if (instance instanceof DynamicInstance di)
            {
                di.setClassifierGenericType((GenericTypeValue) cgt);
            }

            meta.pure.metamodel.type.Type targetType = _GenericType.type(cgt);
            if (targetType instanceof meta.pure.metamodel.extension.ElementWithConstraints)
            {
                MetaNatives.validateConstraints(targetType, cgt, instance, eval, resolver);
            }
        }

        return _E_ValueSpecification.wrap(instance, genericType, multiplicity, resolver);
    }
}
