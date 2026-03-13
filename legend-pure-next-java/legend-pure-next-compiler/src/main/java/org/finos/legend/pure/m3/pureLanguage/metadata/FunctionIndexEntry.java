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

package org.finos.legend.pure.m3.pureLanguage.metadata;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Sets;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type;

import java.util.Comparator;

/**
 * An entry in the function index, containing a FunctionType (the signature)
 * and the fully qualified path to the function element.
 * <p>
 * The FunctionType captures parameters (with types and multiplicities),
 * return type, and return multiplicity — everything needed for function
 * matching and specificity ordering.
 * </p>
 */
public class FunctionIndexEntry
{
    private final FunctionType functionType;
    private final String fullPath;
    private final String functionName;
    private final int typeParamCount;
    private final int multiplicityParamCount;

    public FunctionIndexEntry(String fullPath, String functionName, FunctionType functionType)
    {
        this.functionType = functionType;
        this.fullPath = fullPath;
        this.functionName = functionName;
        this.typeParamCount = computeTypeParamCount(functionType);
        this.multiplicityParamCount = computeMultiplicityParamCount(functionType);
    }

    public FunctionType functionType()
    {
        return functionType;
    }

    public String fullPath()
    {
        return fullPath;
    }

    public String functionName()
    {
        return functionName;
    }

    public int typeParamCount()
    {
        return typeParamCount;
    }

    public int multiplicityParamCount()
    {
        return multiplicityParamCount;
    }

    private static int computeTypeParamCount(FunctionType functionType)
    {
        MutableSet<String> names = Sets.mutable.empty();
        if (functionType._parameters() != null)
        {
            functionType._parameters().forEach(p -> _GenericType.collectReferencedTypeParameterNames(p._genericType(), names));
        }
        _GenericType.collectReferencedTypeParameterNames(functionType._returnType(), names);
        return names.size();
    }

    private static int computeMultiplicityParamCount(FunctionType functionType)
    {
        MutableSet<String> names = Sets.mutable.empty();
        if (functionType._parameters() != null)
        {
            functionType._parameters().forEach(p ->
            {
                if (p._multiplicity() != null && p._multiplicity()._multiplicityParameter() != null)
                {
                    names.add(p._multiplicity()._multiplicityParameter());
                }
            });
        }
        if (functionType._returnMultiplicity() != null && functionType._returnMultiplicity()._multiplicityParameter() != null)
        {
            names.add(functionType._returnMultiplicity()._multiplicityParameter());
        }
        return names.size();
    }

    // ========================================================================
    // Specificity comparator
    // ========================================================================

    /**
     * Comparator that orders entries from most specific to least specific.
     * Concrete parameter types are considered more specific than type parameters.
     * When types are equal, tighter multiplicities (e.g. [1] vs [*]) are more specific.
     * When parameter types are equal, fewer type parameters = more constrained = more specific.
     */
    public static Comparator<FunctionIndexEntry> mostSpecificFirst(MetadataAccess model)
    {
        return (e1, e2) ->
        {
            int cmp = compareFunctionTypes(e1.functionType(), e2.functionType(), model);
            if (cmp != 0)
            {
                return cmp;
            }
            // Tiebreaker: fewer type parameters = more constrained = more specific
            int typeParamCmp = Integer.compare(e1.typeParamCount(), e2.typeParamCount());
            if (typeParamCmp != 0)
            {
                return typeParamCmp;
            }
            // Tiebreaker: fewer multiplicity parameters = more constrained = more specific
            return Integer.compare(e1.multiplicityParamCount(), e2.multiplicityParamCount());
        };
    }

    /**
     * Compare two FunctionTypes by parameter specificity.
     */
    public static int compareFunctionTypes(FunctionType f1, FunctionType f2, MetadataAccess model)
    {
        MutableList<VariableExpression> params1 = f1._parameters();
        MutableList<VariableExpression> params2 = f2._parameters();
        int count = Math.min(params1.size(), params2.size());

        for (int i = 0; i < count; i++)
        {
            Type t1 = resolvedRawType(params1.get(i));
            Type t2 = resolvedRawType(params2.get(i));

            // Concrete types are more specific than type parameters
            if (t1 != null && t2 == null)
            {
                return -1;
            }
            if (t1 == null && t2 != null)
            {
                return 1;
            }

            if (t1 != null && t1 != t2)
            {
                MutableList<Type> lin1 = _Type.linearize(t1, model);
                if (lin1.contains(t2))
                {
                    return -1;
                }

                MutableList<Type> lin2 = _Type.linearize(t2, model);
                if (lin2.contains(t1))
                {
                    return 1;
                }

                String name1 = (t1 instanceof PackageableElement pe1) ? pe1._name() : "";
                String name2 = (t2 instanceof PackageableElement pe2) ? pe2._name() : "";
                int nameCmp = name1.compareTo(name2);
                if (nameCmp != 0)
                {
                    return nameCmp;
                }
            }

            Multiplicity m1 = params1.get(i)._multiplicity();
            Multiplicity m2 = params2.get(i)._multiplicity();

            // Multiplicity parameter (e.g. [m]) is less specific than any concrete multiplicity.
            // Must handle explicitly before subsumption to maintain transitivity.
            boolean m1IsParam = m1 != null && m1._multiplicityParameter() != null;
            boolean m2IsParam = m2 != null && m2._multiplicityParameter() != null;
            if (m1IsParam && !m2IsParam)
            {
                return 1; // m2 concrete is more specific
            }
            if (!m1IsParam && m2IsParam)
            {
                return -1; // m1 concrete is more specific
            }

            if (!m1IsParam && !m2IsParam)
            {
                boolean m1SubsumesM2 = _Multiplicity.subsumes(m1, m2);
                boolean m2SubsumesM1 = _Multiplicity.subsumes(m2, m1);
                if (m1SubsumesM2 && !m2SubsumesM1)
                {
                    return 1;
                }
                if (m2SubsumesM1 && !m1SubsumesM2)
                {
                    return -1;
                }
            }
        }

        return 0;
    }

    private static Type resolvedRawType(ValueSpecification vs)
    {
        GenericType gt = vs._genericType();
        return gt != null ? gt._rawType() : null;
    }

    // ========================================================================
    // Signature
    // ========================================================================

    /**
     * Build a human-readable signature from the FunctionType and path,
     * e.g. {@code "pack::myFunc(param1: String[1], param2: Integer[*]): Boolean[1]"}.
     * This avoids loading the full PackageableFunction element.
     */
    public String signature()
    {
        StringBuilder sb = new StringBuilder();

        // Use the human-readable function name with package prefix
        int lastSep = fullPath.lastIndexOf("::");
        String pkgPrefix = lastSep > 0 ? fullPath.substring(0, lastSep) + "::" : "";
        sb.append(pkgPrefix).append(functionName);

        // Parameters
        sb.append('(');
        if (functionType._parameters() != null)
        {
            boolean first = true;
            for (VariableExpression param : functionType._parameters())
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
        sb.append(_GenericType.print(functionType._returnType(), false));
        sb.append(formatMultiplicity(functionType._returnMultiplicity()));

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
