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

package org.finos.legend.pure.execution.natives.boolean_;

import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.NativeRepository;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.util.Map;

public class BooleanNatives
{
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        natives.put("equal_Any_MANY__Any_MANY__Boolean_1_", (args, eval, genericType, multiplicity) ->
        {
            Object a = _E_ValueSpecification.unwrap(args.get(0));
            Object b = _E_ValueSpecification.unwrap(args.get(1));
            return _E_ValueSpecification.wrap(NativeRepository.pureEquals(a, b), genericType, multiplicity, resolver);
        });

        natives.put("not_Boolean_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(!((Boolean) _E_ValueSpecification.unwrap(args.get(0))), genericType, multiplicity, resolver));

        // and/or — short-circuit: registered as lazy natives
        lazyNatives.put("and_Boolean_1__Boolean_1__Boolean_1_", (fe, eval) ->
        {
            boolean first = (Boolean) _E_ValueSpecification.unwrap(eval.evaluate(fe._parametersValues().get(0)));
            if (!first)
            {
                return _E_ValueSpecification.wrap(false, fe._genericType(), fe._multiplicity(), resolver);
            }
            boolean second = (Boolean) _E_ValueSpecification.unwrap(eval.evaluate(fe._parametersValues().get(1)));
            return _E_ValueSpecification.wrap(second, fe._genericType(), fe._multiplicity(), resolver);
        });

        lazyNatives.put("or_Boolean_1__Boolean_1__Boolean_1_", (fe, eval) ->
        {
            boolean first = (Boolean) _E_ValueSpecification.unwrap(eval.evaluate(fe._parametersValues().get(0)));
            if (first)
            {
                return _E_ValueSpecification.wrap(true, fe._genericType(), fe._multiplicity(), resolver);
            }
            boolean second = (Boolean) _E_ValueSpecification.unwrap(eval.evaluate(fe._parametersValues().get(1)));
            return _E_ValueSpecification.wrap(second, fe._genericType(), fe._multiplicity(), resolver);
        });

        // is — identity comparison
        natives.put("is_Any_1__Any_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
        {
            Object a = _E_ValueSpecification.unwrap(args.get(0));
            Object b = _E_ValueSpecification.unwrap(args.get(1));
            boolean result;
            if (a == b)
            {
                result = true;
            }
            else if (a instanceof Number && b instanceof Number || a instanceof String && b instanceof String || a instanceof Boolean && b instanceof Boolean || a instanceof meta.pure.metamodel.type.Enum && b instanceof meta.pure.metamodel.type.Enum)
            {
                result = NativeRepository.pureEquals(a, b);
            }
            else
            {
                result = false;
            }
            return _E_ValueSpecification.wrap(result, genericType, multiplicity, resolver);
        });

        // compare — order comparison
        natives.put("compare_T_1__T_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            Object a = _E_ValueSpecification.unwrap(args.get(0));
            Object b = _E_ValueSpecification.unwrap(args.get(1));
            int cmp;

            meta.pure.metamodel.type.Type typeA = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(args.get(0)._genericType());
            meta.pure.metamodel.type.Type typeB = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(args.get(1)._genericType());

            meta.pure.metamodel.type.Type pureNumber = (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::Number");
            meta.pure.metamodel.type.Type pureDate = (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::Date");

            boolean isNumA = typeA != null && org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type.subtypeOf(typeA, pureNumber, resolver);
            boolean isNumB = typeB != null && org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type.subtypeOf(typeB, pureNumber, resolver);

            if (isNumA && isNumB)
            {
                java.math.BigDecimal bdA = a instanceof java.math.BigDecimal ? (java.math.BigDecimal) a : new java.math.BigDecimal(a.toString());
                java.math.BigDecimal bdB = b instanceof java.math.BigDecimal ? (java.math.BigDecimal) b : new java.math.BigDecimal(b.toString());
                cmp = bdA.compareTo(bdB);
            }
            else
            {
                boolean isDateA = typeA != null && org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type.subtypeOf(typeA, pureDate, resolver);
                boolean isDateB = typeB != null && org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type.subtypeOf(typeB, pureDate, resolver);

                if (isDateA && isDateB)
                {
                    cmp = comparePureDates(String.valueOf(a), String.valueOf(b));
                }
                else if (typeA != typeB && !java.util.Objects.equals(typeA, typeB))
                {
                    String pathA = (typeA instanceof meta.pure.metamodel.PackageableElement peA) ? org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(peA) : "Unknown";
                    String pathB = (typeB instanceof meta.pure.metamodel.PackageableElement peB) ? org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(peB) : "Unknown";
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
            return _E_ValueSpecification.wrap((long) Math.signum(cmp), genericType, multiplicity, resolver);
        });
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
