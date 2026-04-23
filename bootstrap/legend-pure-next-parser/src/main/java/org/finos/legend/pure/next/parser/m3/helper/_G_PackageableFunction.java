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
import meta.pure.protocol.grammar.multiplicity.ConcreteMultiplicity;
import meta.pure.protocol.grammar.multiplicity.Multiplicity;
import meta.pure.protocol.grammar.multiplicity.MultiplicityParameter;
import meta.pure.protocol.grammar.multiplicity.Multiplicity_Pointer;
import meta.pure.protocol.grammar.multiplicity.Multiplicity_Protocol;
import meta.pure.protocol.grammar.type.Type_Pointer;
import meta.pure.protocol.grammar.type.generics.GenericType;
import meta.pure.protocol.grammar.type.generics.GenericTypeValue;
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
        return switch (gt)
        {
            case meta.pure.protocol.grammar.type.generics.UndefinedGenericType ignored -> "UNDEFINED";
            case GenericTypeValue gtv ->
            {
                if (gtv._type() instanceof meta.pure.protocol.grammar.type.generics.TypeParameter tp && tp._name() != null)
                {
                    yield tp._name();
                }
                if (gtv._type() instanceof Type_Pointer tp && tp._value() != null)
                {
                    String fullPath = tp._value();
                    yield fullPath.contains("::") ? fullPath.substring(fullPath.lastIndexOf("::") + 2) : fullPath;
                }
                yield "UNKNOWN";
            }
            default -> "UNKNOWN";
        };
    }

    private static String getMultiplicitySignature(Multiplicity_Protocol mp)
    {
        if (mp == null)
        {
            return "MANY";
        }
        if (mp instanceof Multiplicity m)
        {
            return getMultiplicitySignatureDirect(m);
        }
        if (mp instanceof Multiplicity_Pointer ptr)
        {
            String path = ptr._value();
            String name = path != null && path.contains("::") ? path.substring(path.lastIndexOf("::") + 2) : path;
            return switch (name != null ? name : "")
            {
                case "PureOne" -> "1";
                case "ZeroOne" -> "$0_1$";
                case "ZeroMany" -> "MANY";
                case "OneMany" -> "$1_MANY$";
                default -> "MANY";
            };
        }
        return "MANY";
    }

    private static String getMultiplicitySignatureDirect(Multiplicity m)
    {
        if (m == null)
        {
            return "MANY";
        }
        if (m instanceof meta.pure.protocol.grammar.multiplicity.UndefinedMultiplicity)
        {
            return "UNDEFINED";
        }
        if (m instanceof MultiplicityParameter mp)
        {
            return mp._name();
        }
        if (m instanceof ConcreteMultiplicity cm)
        {
            long lower = cm._lowerBound() != null ? cm._lowerBound()._value() : 0;
            Long upper = cm._upperBound() != null ? cm._upperBound()._value() : null;
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
        return "MANY";
    }
}
