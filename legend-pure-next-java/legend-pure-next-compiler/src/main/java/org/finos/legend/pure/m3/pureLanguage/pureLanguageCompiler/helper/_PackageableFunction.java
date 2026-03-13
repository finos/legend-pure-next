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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.function.PackageableFunction;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.valuespecification.VariableExpression;

/**
 * Helper methods for {@link PackageableFunction}.
 */
public class _PackageableFunction
{
    private _PackageableFunction()
    {
        // static utility
    }

    /**
     * Return the full function signature, e.g.
     * {@code "pack::myFunc(param1: String[1], param2: Integer[*]): Boolean[1]"}.
     */
    public static String getFunctionSignature(PackageableFunction fn)
    {
        StringBuilder sb = new StringBuilder();

        // Qualified name: use _functionName() for human-readable short name
        String fullPath = _PackageableElement.path(fn);
        int lastSep = fullPath.lastIndexOf("::");
        String pkgPath = lastSep > 0 ? fullPath.substring(0, lastSep) : "";
        sb.append(pkgPath.isEmpty() ? fn._functionName() : pkgPath + "::" + fn._functionName());

        // Parameters
        sb.append('(');
        if (fn._parameters() != null)
        {
            boolean first = true;
            for (VariableExpression param : fn._parameters())
            {
                if (!first)
                {
                    sb.append(", ");
                }
                first = false;
                sb.append(param._name());
                sb.append(": ");
                sb.append(_GenericType.print(param._genericType(), false));
                sb.append(formatMultiplicity(param._multiplicity()));
            }
        }
        sb.append(')');

        // Return type
        sb.append(": ");
        sb.append(_GenericType.print(fn._returnGenericType(), false));
        sb.append(formatMultiplicity(fn._returnMultiplicity()));

        return sb.toString();
    }




    private static String formatMultiplicity(Multiplicity m)
    {
        if (m == null)
        {
            return "[*]";
        }
        if (m._multiplicityParameter() != null)
        {
            return "[" + m._multiplicityParameter() + "]";
        }
        long lower = m._lowerBound() != null ? m._lowerBound()._value() : 0;
        Long upper = m._upperBound() != null ? m._upperBound()._value() : null;
        if (upper == null)
        {
            return lower == 0 ? "[*]" : "[" + lower + "..*]";
        }
        if (lower == upper)
        {
            return "[" + lower + "]";
        }
        return "[" + lower + ".." + upper + "]";
    }
}
