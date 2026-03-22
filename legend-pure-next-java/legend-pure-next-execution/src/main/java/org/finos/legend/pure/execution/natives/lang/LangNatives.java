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

package org.finos.legend.pure.execution.natives.lang;

import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LangNatives
{
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        // letFunction — binds a variable in the current scope
        natives.put("letFunction_String_1__T_m__T_m_", (args, eval, fe) ->
        {
            String varName = (String) _E_ValueSpecification.unwrap(args.get(0));
            ValueSpecification value = args.get(1);
            eval.currentScope().put(varName, value);
            return value;
        });

        // if — branches return their own typed VS; pass through unchanged
        natives.put("if_Boolean_1__Function_1__Function_1__T_m_", (args, eval, fe) ->
        {
            boolean condition = (Boolean) _E_ValueSpecification.unwrap(args.get(0));
            Object branch = _E_ValueSpecification.unwrap(condition ? args.get(1) : args.get(2));
            return eval.executeFunction(branch, List.of());
        });

        // eval natives — return VS directly from the evaluator
        natives.put("eval_Function_1__V_m_", (args, eval, fe) ->
        {
            Object fn = _E_ValueSpecification.unwrap(args.get(0));
            return eval.executeFunction(fn, List.of());
        });

        natives.put("eval_Function_1__T_n__U_p__V_m_", (args, eval, fe) ->
        {
            Object fn = _E_ValueSpecification.unwrap(args.get(0));
            return eval.executeFunction(fn, args.subList(1, args.size()));
        });

        natives.put("eval_Function_1__T_n__V_m_", (args, eval, fe) ->
        {
            Object fn = _E_ValueSpecification.unwrap(args.get(0));
            return eval.executeFunction(fn, args.subList(1, args.size()));
        });

        // evaluate(Function[1], List[*]) : Any[*]
        natives.put("evaluate_Function_1__List_MANY__Any_MANY_", (args, eval, fe) ->
        {
            Object fn = _E_ValueSpecification.unwrap(args.get(0));
            List<ValueSpecification> fnArgs = new ArrayList<>();
            List<?> lists = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(1), resolver));
            for (Object item : lists)
            {
                // Each item is a Pure List DynamicInstance — extract its 'values' property
                if (item instanceof DynamicInstance di)
                {
                    Object values = di.get("values");
                    if (values instanceof List<?> valList)
                    {
                        // Multi-valued: wrap as collection
                        fnArgs.add(_E_ValueSpecification.wrap(valList, args.get(1)._genericType(), args.get(1)._multiplicity()));
                    }
                    else if (values != null)
                    {
                        fnArgs.add(_E_ValueSpecification.wrap(values, args.get(1)._genericType(), args.get(1)._multiplicity()));
                    }
                }
                else if (item instanceof ValueSpecification vs)
                {
                    fnArgs.add(vs);
                }
                else
                {
                    fnArgs.add(_E_ValueSpecification.wrap(item, args.get(1)._genericType(), args.get(1)._multiplicity()));
                }
            }
            return eval.executeFunction(fn, fnArgs);
        });

        // match — type-based dispatch
        natives.put("match_Any_MANY__Function_$1_MANY$__T_m_", (args, eval, fe) ->
        {
            Object value = _E_ValueSpecification.unwrap(args.get(0));
            List<?> matchFuncs = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(1), resolver));

            // Determine the runtime type of the value
            meta.pure.metamodel.type.Type valueType = _E_ValueSpecification.getValueOriginalType(args.get(0));

            for (Object mf : matchFuncs)
            {
                if (mf instanceof meta.pure.metamodel.function.FunctionDefinition fd
                        && fd._parameters() != null && fd._parameters().notEmpty())
                {
                    meta.pure.metamodel.valuespecification.VariableExpression param = fd._parameters().getFirst();
                    if (param._genericType() != null)
                    {
                        meta.pure.metamodel.type.Type paramType = _GenericType.type(param._genericType());
                        if (paramType != null && !_Type.subtypeOf(valueType, paramType, resolver))
                        {
                            continue; // Value type doesn't match this branch
                        }
                    }
                }
                ValueSpecification wrappedValue = _E_ValueSpecification.wrap(value, args.get(0)._genericType(), args.get(0)._multiplicity());
                return eval.executeFunction(mf, List.of(wrappedValue));
            }
            throw new RuntimeException("No match function matched the value: " + value);
        });
    }
}
