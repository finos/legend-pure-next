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
import meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.ValueSpecificationEvaluator;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.execution.natives.meta.MetaNatives;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code cast(Any[m], GenericTypeAndMultiplicityHolder[1]) : T[m]}.
 *
 * <p>Sets the classifierGenericType on the target. Validates type compatibility
 * and constraints. Returns the re-stamped ValueSpecification.</p>
 */
@NodeInfo(shortName = "cast")
public final class CastNode extends PureNode
{
    @Child
    private PureNode inputChild;

    @Child
    private PureNode targetChild;

    public CastNode(PureNode inputChild, PureNode targetChild)
    {
        this.inputChild = inputChild;
        this.targetChild = targetChild;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object inputResult = inputChild.executeGeneric(frame);
        Object targetResult = targetChild.executeGeneric(frame);
        return doCast(inputResult, targetResult);
    }

    @TruffleBoundary
    private static ValueSpecification doCast(Object inputResult, Object targetResult)
    {
        ValueSpecification inputVs = ValueAdapter.ensureVS(inputResult);
        ValueSpecification targetVs = ValueAdapter.ensureVS(targetResult);
        MetadataAccess resolver = EvaluatorHolder.current().natives().resolver();
        ValueSpecificationEvaluator eval = EvaluatorHolder.current();

        // Resolve the target GenericType from the GenericTypeAndMultiplicityHolder
        GenericType targetGT = null;
        meta.pure.metamodel.type.Type targetType = null;
        if (targetVs instanceof GenericTypeAndMultiplicityHolder gtmh
                && gtmh._genericType() != null
                && _GenericType.typeArguments(gtmh._genericType()) != null
                && _GenericType.typeArguments(gtmh._genericType()).notEmpty())
        {
            targetGT = _GenericType.typeArguments(gtmh._genericType()).getFirst();
            targetType = _GenericType.type(targetGT);
        }
        else if (targetVs._genericType() != null)
        {
            targetGT = targetVs._genericType();
            targetType = _GenericType.type(targetGT);
        }

        // Validate type compatibility for scalar values
        Object value = _E_ValueSpecification.unwrap(inputVs);
        if (value != null
                && !(inputVs instanceof meta.pure.metamodel.valuespecification.Collection)
                && targetType instanceof PackageableElement targetPe
                && !(value instanceof meta.pure.metamodel.type.generics.TypeParameter)
                && !(value instanceof meta.pure.metamodel.multiplicity.MultiplicityParameter))
        {
            String targetPath = _PackageableElement.path(targetPe);
            if (!"meta::pure::metamodel::type::Any".equals(targetPath))
            {
                meta.pure.metamodel.type.Type sourceType = _E_ValueSpecification.getValueOriginalType(inputVs, resolver);
                if (sourceType instanceof PackageableElement sourcePe)
                {
                    String sourcePath = _PackageableElement.path(sourcePe);
                    if (!"meta::pure::metamodel::type::Nil".equals(sourcePath))
                    {
                        boolean related = _Type.subtypeOf(sourceType, targetType, resolver)
                                || _Type.subtypeOf(targetType, sourceType, resolver);
                        if (!related)
                        {
                            throw new RuntimeException("Cast exception: " + sourcePe._name() + " cannot be cast to " + targetPe._name());
                        }
                    }
                }
            }
        }

        // Validate constraints on the target type
        if (value != null && targetType instanceof meta.pure.metamodel.extension.ElementWithConstraints)
        {
            MetaNatives.validateConstraints(targetType, targetGT, value, eval, resolver);
        }

        // Restamp the wrapper's genericType to the target type
        if (targetGT == null)
        {
            return inputVs;
        }
        if (inputVs instanceof meta.pure.metamodel.valuespecification.CollectionImpl col)
        {
            meta.pure.metamodel.valuespecification.CollectionImpl result = col._copy();
            result._genericType(targetGT);
            return result;
        }
        return _E_ValueSpecification.wrap(value, targetGT, inputVs._multiplicity(), resolver);
    }
}
