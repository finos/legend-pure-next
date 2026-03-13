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

package org.finos.legend.pure.next.parser.m3.helper;

import meta.pure.protocol.grammar.function.PackageableFunction;
import meta.pure.protocol.grammar.multiplicity.Multiplicity;
import meta.pure.protocol.grammar.type.Type_Pointer;
import meta.pure.protocol.grammar.type.generics.GenericType;
import meta.pure.protocol.grammar.valuespecification.VariableExpression;

/**
 * Helper methods for grammar protocol {@link PackageableFunction}.
 * <p>
 * Builds the unique function ID following the same convention as Legend Engine's
 * {@code HelperModelBuilder.getSignature}:
 * <pre>
 *   functionName_ParamType1_Mult1__ParamType2_Mult2__ReturnType_ReturnMult_
 * </pre>
 */
public class _G_PackageableFunction
{
    private _G_PackageableFunction()
    {
        // static utility
    }

    /**
     * Build the unique function ID used in the Pure graph.
     */
    public static String buildId(PackageableFunction fn)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(fn._functionName());

        boolean hasParams = fn._parameters() != null && fn._parameters().notEmpty();
        if (hasParams)
        {
            sb.append('_');
            boolean first = true;
            for (VariableExpression param : fn._parameters())
            {
                if (!first)
                {
                    sb.append("__");
                }
                first = false;
                sb.append(getTypeSignature(param._genericType()));
                sb.append('_');
                sb.append(getMultiplicitySignature(param._multiplicity()));
            }
        }

        sb.append("__");
        sb.append(getTypeSignature(fn._returnGenericType()));
        sb.append('_');
        sb.append(getMultiplicitySignature(fn._returnMultiplicity()));
        sb.append('_');

        return sb.toString();
    }

    private static String getTypeSignature(GenericType gt)
    {
        if (gt == null)
        {
            return "UNKNOWN";
        }
        if (gt._typeParameter() != null && gt._typeParameter()._name() != null)
        {
            return gt._typeParameter()._name();
        }
        if (gt._rawType() instanceof Type_Pointer tp && tp._pointerValue() != null)
        {
            String fullPath = tp._pointerValue();
            return fullPath.contains("::") ? fullPath.substring(fullPath.lastIndexOf("::") + 2) : fullPath;
        }
        return "UNKNOWN";
    }

    private static String getMultiplicitySignature(Multiplicity m)
    {
        if (m == null)
        {
            return "MANY";
        }
        if (m._multiplicityParameter() != null)
        {
            return m._multiplicityParameter();
        }
        long lower = m._lowerBound() != null ? m._lowerBound()._value() : 0;
        Long upper = m._upperBound() != null ? m._upperBound()._value() : null;
        if (upper == null)
        {
            return lower == 0 ? "MANY" : "$" + lower + "_MANY$";
        }
        if (lower == upper)
        {
            return String.valueOf(lower);
        }
        return "$" + lower + "_" + upper + "$";
    }
}
