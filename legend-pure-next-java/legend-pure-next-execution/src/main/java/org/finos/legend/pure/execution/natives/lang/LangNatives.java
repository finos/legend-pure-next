package org.finos.legend.pure.execution.natives.lang;

import meta.pure.metamodel.valuespecification.AtomicValue;
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
            ValueSpecification branch = condition ? args.get(1) : args.get(2);
            return eval.executeFunction(branch, List.of());
        });

        // eval natives — return VS directly from the evaluator
        natives.put("eval_Function_1__V_m_", (args, eval, genericType, multiplicity) ->
            eval.executeFunction(args.get(0), List.of())
        );

        natives.put("eval_Function_1__T_n__U_p__V_m_", (args, eval, genericType, multiplicity) ->
            eval.executeFunction(args.get(0), args.subList(1, args.size()))
        );

        natives.put("eval_Function_1__T_n__U_p__W_q__V_m_", (args, eval, genericType, multiplicity) ->
            eval.executeFunction(args.get(0), args.subList(1, args.size()))
        );

        natives.put("eval_Function_1__T_n__V_m_", (args, eval, genericType, multiplicity) ->
            eval.executeFunction(args.get(0), args.subList(1, args.size()))
        );

        // evaluate(Function[1], List[*]) : Any[*]
        natives.put("evaluate_Function_1__List_MANY__Any_MANY_", (args, eval, genericType, multiplicity) ->
        {
            List<ValueSpecification> fnArgs = new ArrayList<>();
            for (ValueSpecification item : _E_ValueSpecification.toCollection(args.get(1), resolver)._values())
            {
                // Each item is a Pure List DynamicInstance — extract its 'values' property
                if (item instanceof AtomicValue av && av._value() instanceof DynamicInstance di)
                {
                    ValueSpecification valuesVS = di.get("values");

                    if (valuesVS instanceof meta.pure.metamodel.valuespecification.Collection col)
                    {
                        // Multi-valued: the Collection VS already has the right type metadata
                        fnArgs.add(col);
                    }
                    else if (valuesVS != null)
                    {
                        // Single-valued: pass the scalar VS directly
                        fnArgs.add(valuesVS);
                    }
                }
                else
                {
                    fnArgs.add(item);
                }
            }
            return eval.executeFunction(args.get(0), fnArgs);
        });

        // match — type- and multiplicity-based dispatch
        natives.put("match_Any_MANY__Function_$1_MANY$__T_m_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.type.Type valueType = _E_ValueSpecification.getValueOriginalType(args.get(0), resolver);
            int valueCount = getValueCount(_E_ValueSpecification.unwrap(args.get(0)));
            meta.pure.metamodel.valuespecification.Collection matchFuncCol = _E_ValueSpecification.toCollection(args.get(1), resolver);

            for (ValueSpecification mfVS : matchFuncCol._values())
            {
                Object mf = _E_ValueSpecification.unwrap(mfVS);
                if (!matchesBranch(mf, valueType, valueCount, resolver))
                {
                    continue;
                }
                return eval.executeFunction(mfVS, List.of(args.get(0)));
            }
            throw new RuntimeException("No match function matched the value: " + _E_ValueSpecification.unwrap(args.get(0)));
        });

        // match with extra parameter — match(Any[*], Function[1..*], P[o]) : T[m]
        natives.put("match_Any_MANY__Function_$1_MANY$__P_o__T_m_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.type.Type valueType = _E_ValueSpecification.getValueOriginalType(args.get(0), resolver);
            int valueCount = getValueCount(_E_ValueSpecification.unwrap(args.get(0)));
            meta.pure.metamodel.valuespecification.Collection matchFuncCol = _E_ValueSpecification.toCollection(args.get(1), resolver);

            for (ValueSpecification mfVS : matchFuncCol._values())
            {
                Object mf = _E_ValueSpecification.unwrap(mfVS);
                if (!matchesBranch(mf, valueType, valueCount, resolver))
                {
                    continue;
                }
                if (mf instanceof meta.pure.metamodel.function.FunctionDefinition fd2
                        && fd2._parameters() != null && fd2._parameters().size() >= 2)
                {
                    return eval.executeFunction(mfVS, List.of(args.get(0), args.get(2)));
                }
                return eval.executeFunction(mfVS, List.of(args.get(0)));
            }
            throw new RuntimeException("No match function matched the value: " + _E_ValueSpecification.unwrap(args.get(0)));
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

