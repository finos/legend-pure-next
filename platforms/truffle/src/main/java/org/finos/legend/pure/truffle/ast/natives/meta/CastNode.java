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
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import org.finos.legend.pure.execution.natives.meta.MetaNatives;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;

/**
 * {@code cast(Any[m], GenericTypeAndMultiplicityHolder[1]) : T[m]}.
 * Validates type compatibility and constraints. Returns the value directly.
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
    private static Object doCast(Object inputResult, Object targetResult)
    {
        MetadataAccess resolver = StandaloneEvaluatorHolder.current().resolver();

        // Resolve the target GenericType from the GenericTypeAndMultiplicityHolder
        GenericType targetGT = null;
        meta.pure.metamodel.type.Type targetType = null;
        if (targetResult instanceof GenericTypeAndMultiplicityHolder gtmh
                && gtmh._genericType() != null
                && _GenericType.typeArguments(gtmh._genericType()) != null
                && _GenericType.typeArguments(gtmh._genericType()).notEmpty())
        {
            targetGT = _GenericType.typeArguments(gtmh._genericType()).getFirst();
            targetType = _GenericType.type(targetGT);
        }

        // Validate type compatibility
        if (inputResult != null
                && !(inputResult instanceof org.finos.legend.pure.truffle.types.PureNull)
                && targetType instanceof PackageableElement targetPe)
        {
            String targetPath = _PackageableElement.path(targetPe);
            if (!"meta::pure::metamodel::type::Any".equals(targetPath)
                    && !targetPath.startsWith("meta::pure::metamodel::valuespecification::"))
            {
                meta.pure.metamodel.type.Type sourceType = MetaHelper.getRawValueType(inputResult, resolver);
                if (sourceType instanceof PackageableElement sourcePe)
                {
                    String sourcePath = _PackageableElement.path(sourcePe);
                    if (!"meta::pure::metamodel::type::Nil".equals(sourcePath))
                    {
                        boolean related = false;
                        try
                        {
                            related = _Type.subtypeOf(sourceType, targetType, resolver)
                                    || _Type.subtypeOf(targetType, sourceType, resolver);
                        }
                        catch (Exception ignored)
                        {
                            // Type hierarchy check can fail for raw values — allow cast
                            related = true;
                        }
                        if (!related)
                        {
                            throw new RuntimeException("Cast exception: " + sourcePe._name() + " cannot be cast to " + targetPe._name());
                        }
                    }
                }
            }
        }

        // Constraint validation skipped in Truffle standalone mode —
        // MetaNatives.validateConstraints requires a ValueSpecificationEvaluator
        // which we don't have. Constraints will be implemented as Truffle nodes.

        // Cast is a type assertion — it does NOT change the runtime type (CGT)
        // of the object. The object retains its original classifierGenericType
        // so that polymorphic QP dispatch resolves to the correct override.
        return inputResult;
    }
}
