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

package org.finos.legend.pure.truffle.ast.natives.boolean_;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.multiplicity.Multiplicity;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code compare(T[1], T[1]) : Integer[1]}.
 *
 * <p>Compares two values and returns {@code -1}, {@code 0}, or {@code 1}.
 * For numbers, converts both to {@link java.math.BigDecimal} and uses
 * {@code compareTo}. For dates, normalizes and compares as strings.
 * For all other types, uses string representation comparison.</p>
 *
 * <p>This node delegates to a {@link TruffleBoundary} method that accesses
 * the resolver for type checking (Number vs Date subtype tests).</p>
 */
@NodeInfo(shortName = "compare")
public final class CompareNode extends PureNode
{
    @Child
    private PureNode left;

    @Child
    private PureNode right;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public CompareNode(PureNode left, PureNode right, GenericType genericType, Multiplicity multiplicity)
    {
        this.left = left;
        this.right = right;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object a = left.executeGeneric(frame);
        Object b = right.executeGeneric(frame);
        return doCompare(a, b);
    }

    @TruffleBoundary
    private static long doCompare(Object rawA, Object rawB)
    {
        meta.pure.metamodel.valuespecification.ValueSpecification vsA = ValueAdapter.ensureVS(rawA);
        meta.pure.metamodel.valuespecification.ValueSpecification vsB = ValueAdapter.ensureVS(rawB);

        Object a = org.finos.legend.pure.execution._E_ValueSpecification.unwrap(vsA);
        Object b = org.finos.legend.pure.execution._E_ValueSpecification.unwrap(vsB);

        org.finos.legend.pure.m3.module.MetadataAccess resolver =
                org.finos.legend.pure.truffle.runtime.EvaluatorHolder.current().natives().resolver();

        meta.pure.metamodel.type.Type typeA =
                org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(vsA._genericType());
        meta.pure.metamodel.type.Type typeB =
                org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(vsB._genericType());

        meta.pure.metamodel.type.Type pureNumber =
                (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::Number");
        meta.pure.metamodel.type.Type pureDate =
                (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::Date");

        boolean isNumA = typeA != null
                && org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type.subtypeOf(typeA, pureNumber, resolver);
        boolean isNumB = typeB != null
                && org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type.subtypeOf(typeB, pureNumber, resolver);

        int cmp;
        if (isNumA && isNumB)
        {
            java.math.BigDecimal bdA = a instanceof java.math.BigDecimal
                    ? (java.math.BigDecimal) a
                    : new java.math.BigDecimal(a.toString());
            java.math.BigDecimal bdB = b instanceof java.math.BigDecimal
                    ? (java.math.BigDecimal) b
                    : new java.math.BigDecimal(b.toString());
            cmp = bdA.compareTo(bdB);
        }
        else
        {
            boolean isDateA = typeA != null
                    && org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type.subtypeOf(typeA, pureDate, resolver);
            boolean isDateB = typeB != null
                    && org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type.subtypeOf(typeB, pureDate, resolver);

            if (isDateA && isDateB)
            {
                cmp = comparePureDates(String.valueOf(a), String.valueOf(b));
            }
            else if (typeA != typeB && !java.util.Objects.equals(typeA, typeB))
            {
                String pathA = (typeA instanceof meta.pure.metamodel.PackageableElement peA)
                        ? org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(peA)
                        : "Unknown";
                String pathB = (typeB instanceof meta.pure.metamodel.PackageableElement peB)
                        ? org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(peB)
                        : "Unknown";
                cmp = pathA.compareTo(pathB);
                if (cmp == 0)
                {
                    cmp = String.valueOf(a).compareTo(String.valueOf(b));
                }
            }
            else
            {
                cmp = String.valueOf(a).compareTo(String.valueOf(b));
            }
        }
        return (long) Math.signum(cmp);
    }

    private static int comparePureDates(String d1, String d2)
    {
        String n1 = org.finos.legend.pure.execution.natives.string.StringNatives.normalizePureDate(d1);
        String n2 = org.finos.legend.pure.execution.natives.string.StringNatives.normalizePureDate(d2);

        int dash1 = n1.indexOf('-', n1.startsWith("-") ? 1 : 0);
        int dash2 = n2.indexOf('-', n2.startsWith("-") ? 1 : 0);

        String y1 = dash1 > 0 ? n1.substring(0, dash1) : n1;
        String y2 = dash2 > 0 ? n2.substring(0, dash2) : n2;

        try
        {
            int year1 = Integer.parseInt(y1);
            int year2 = Integer.parseInt(y2);
            if (year1 != year2)
            {
                return Integer.compare(year1, year2);
            }
            String rest1 = dash1 > 0 ? n1.substring(dash1) : "";
            String rest2 = dash2 > 0 ? n2.substring(dash2) : "";
            return rest1.compareTo(rest2);
        }
        catch (NumberFormatException e)
        {
            return n1.compareTo(n2);
        }
    }
}
