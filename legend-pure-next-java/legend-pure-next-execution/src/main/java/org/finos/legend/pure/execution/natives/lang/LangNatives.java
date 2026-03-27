package org.finos.legend.pure.execution.natives.lang;

import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
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
        natives.put("letFunction_String_1__T_m__T_m_", (args, eval, genericType, multiplicity) ->
        {
            String varName = (String) _E_ValueSpecification.unwrap(args.get(0));
            ValueSpecification value = args.get(1);
            eval.currentScope().put(varName, value);
            return value;
        });

        // if — branches return their own typed VS; pass through unchanged
        natives.put("if_Boolean_1__Function_1__Function_1__T_m_", (args, eval, genericType, multiplicity) ->
        {
            boolean condition = (Boolean) _E_ValueSpecification.unwrap(args.get(0));
            Object branch = _E_ValueSpecification.unwrap(condition ? args.get(1) : args.get(2));
            return eval.executeFunction(branch, List.of());
        });

        // eval natives — return VS directly from the evaluator
        natives.put("eval_Function_1__V_m_", (args, eval, genericType, multiplicity) ->
        {
            Object fn = _E_ValueSpecification.unwrap(args.get(0));
            return eval.executeFunction(fn, List.of());
        });

        natives.put("eval_Function_1__T_n__U_p__V_m_", (args, eval, genericType, multiplicity) ->
        {
            Object fn = _E_ValueSpecification.unwrap(args.get(0));
            return eval.executeFunction(fn, args.subList(1, args.size()));
        });

        natives.put("eval_Function_1__T_n__V_m_", (args, eval, genericType, multiplicity) ->
        {
            Object fn = _E_ValueSpecification.unwrap(args.get(0));
            return eval.executeFunction(fn, args.subList(1, args.size()));
        });

        // evaluate(Function[1], List[*]) : Any[*]
        natives.put("evaluate_Function_1__List_MANY__Any_MANY_", (args, eval, genericType, multiplicity) ->
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
                        fnArgs.add(_E_ValueSpecification.wrap(valList, args.get(1)._genericType(), args.get(1)._multiplicity(), resolver));
                    }
                    else if (values != null)
                    {
                        fnArgs.add(_E_ValueSpecification.wrap(values, args.get(1)._genericType(), args.get(1)._multiplicity(), resolver));
                    }
                }
                else if (item instanceof ValueSpecification vs)
                {
                    fnArgs.add(vs);
                }
                else
                {
                    fnArgs.add(_E_ValueSpecification.wrap(item, args.get(1)._genericType(), args.get(1)._multiplicity(), resolver));
                }
            }
            return eval.executeFunction(fn, fnArgs);
        });

        // match — type- and multiplicity-based dispatch
        natives.put("match_Any_MANY__Function_$1_MANY$__T_m_", (args, eval, genericType, multiplicity) ->
        {
            Object value = _E_ValueSpecification.unwrap(args.get(0));
            List<?> matchFuncs = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(1), resolver));
            meta.pure.metamodel.type.Type valueType = _E_ValueSpecification.getValueOriginalType(args.get(0));
            int valueCount = getValueCount(value);

            for (Object mf : matchFuncs)
            {
                if (!matchesBranch(mf, valueType, valueCount, resolver))
                {
                    continue;
                }
                ValueSpecification wrappedValue = _E_ValueSpecification.wrap(value, args.get(0)._genericType(), args.get(0)._multiplicity(), resolver);
                return eval.executeFunction(mf, List.of(wrappedValue));
            }
            throw new RuntimeException("No match function matched the value: " + value);
        });

        // match with extra parameter — match(Any[*], Function[1..*], P[o]) : T[m]
        natives.put("match_Any_MANY__Function_$1_MANY$__P_o__T_m_", (args, eval, genericType, multiplicity) ->
        {
            Object value = _E_ValueSpecification.unwrap(args.get(0));
            List<?> matchFuncs = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(1), resolver));
            meta.pure.metamodel.type.Type valueType = _E_ValueSpecification.getValueOriginalType(args.get(0));
            int valueCount = getValueCount(value);

            for (Object mf : matchFuncs)
            {
                if (!matchesBranch(mf, valueType, valueCount, resolver))
                {
                    continue;
                }
                ValueSpecification wrappedValue = _E_ValueSpecification.wrap(value, args.get(0)._genericType(), args.get(0)._multiplicity(), resolver);
                if (mf instanceof meta.pure.metamodel.function.FunctionDefinition fd2
                        && fd2._parameters() != null && fd2._parameters().size() >= 2)
                {
                    return eval.executeFunction(mf, List.of(wrappedValue, args.get(2)));
                }
                return eval.executeFunction(mf, List.of(wrappedValue));
            }
            throw new RuntimeException("No match function matched the value: " + value);
        });
    }

    /**
     * Returns the count of values for multiplicity matching.
     */
    private static int getValueCount(Object value)
    {
        if (value == null)
        {
            return 0;
        }
        if (value instanceof List<?> list)
        {
            return list.size();
        }
        return 1;
    }

    /**
     * Check if a match branch (function) accepts the given value type and count.
     * A branch matches if:
     * 1. The value's type is a subtype of the parameter's declared type
     * 2. The value count is within the parameter's declared multiplicity bounds
     */
    private static boolean matchesBranch(Object mf,
                                         meta.pure.metamodel.type.Type valueType,
                                         int valueCount,
                                         MetadataAccess resolver)
    {
        if (!(mf instanceof meta.pure.metamodel.function.FunctionDefinition fd)
                || fd._parameters() == null || fd._parameters().isEmpty())
        {
            return true; // No parameters — matches anything
        }

        meta.pure.metamodel.valuespecification.VariableExpression param = fd._parameters().getFirst();

        // Check type
        if (param._genericType() != null)
        {
            meta.pure.metamodel.type.Type paramType = _GenericType.type(param._genericType());
            if (paramType != null && !_Type.subtypeOf(valueType, paramType, resolver))
            {
                return false;
            }
        }

        // Check multiplicity
        if (param._multiplicity() != null)
        {
            if (!multiplicityAccepts(param._multiplicity(), valueCount))
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if a multiplicity accepts the given count.
     * ConcreteMultiplicity has lowerBound and upperBound (null upper = unbounded).
     */
    private static boolean multiplicityAccepts(meta.pure.metamodel.multiplicity.Multiplicity mult, int count)
    {
        if (!(mult instanceof meta.pure.metamodel.multiplicity.ConcreteMultiplicity cm))
        {
            // MultiplicityParameter or unknown — accept anything
            return true;
        }

        // Get lower bound
        long lower = 0;
        if (cm._lowerBound() != null)
        {
            lower = cm._lowerBound()._value();
        }

        // Get upper bound (-1 means unbounded)
        long upper = -1;
        if (cm._upperBound() != null)
        {
            upper = cm._upperBound()._value();
        }

        if (count < lower)
        {
            return false;
        }
        if (upper != -1 && count > upper)
        {
            return false;
        }
        return true;
    }
}

