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

        // Multi-clause if(Pair<{->Bool[1]}, {->T[m]}>[*], {->T[m]}[1]) : T[m]
        // Walks the pair list, calling each pair's first lambda; the first
        // one returning true wins and we run its second lambda. If none
        // match, run the default. Native (not Pure-side `find`) so the
        // Truffle/AOT lowering doesn't have to dispatch through `find`'s
        // closure — see if.pure.
        //
        // Pair stores raw (unwrapped) values — pair.get("first") returns
        // the bare Closure/LambdaFunction, not a ValueSpecification — so
        // we re-wrap before handing to executeFunction.
        natives.put("if_Pair_MANY__Function_1__T_m_", (args, eval, genericType, multiplicity) ->
        {
            for (ValueSpecification pairVS : _E_ValueSpecification.toCollection(args.get(0), resolver)._values())
            {
                Object pairUnwrapped = _E_ValueSpecification.unwrap(pairVS);
                if (!(pairUnwrapped instanceof DynamicInstance pair))
                {
                    continue;
                }
                Object firstRaw = pair.get("first");
                Object secondRaw = pair.get("second");
                ValueSpecification condFn = _E_ValueSpecification.wrap(firstRaw,
                        org.finos.legend.pure.execution.PureTypeResolver.getClassifierGenericType(firstRaw, resolver),
                        null, resolver);
                ValueSpecification bodyFn = _E_ValueSpecification.wrap(secondRaw,
                        org.finos.legend.pure.execution.PureTypeResolver.getClassifierGenericType(secondRaw, resolver),
                        null, resolver);
                ValueSpecification condResult = eval.executeFunction(condFn, List.of());
                if (Boolean.TRUE.equals(_E_ValueSpecification.unwrap(condResult)))
                {
                    return eval.executeFunction(bodyFn, List.of());
                }
            }
            return eval.executeFunction(args.get(1), List.of());
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
                if (item instanceof AtomicValue av && av._value() instanceof DynamicInstance di)
                {
                    Object valuesRaw = di.get("values");
                    if (valuesRaw instanceof Iterable<?> it)
                    {
                        List<ValueSpecification> wrappedList = new ArrayList<>();
                        for (Object v : it)
                        {
                            wrappedList.add(_E_ValueSpecification.wrap(v, org.finos.legend.pure.execution.PureTypeResolver.getClassifierGenericType(v, resolver), null, resolver));
                        }
                        fnArgs.add(org.finos.legend.pure.execution.natives.collection.CollectionNatives.makeCollection(wrappedList, resolver));
                    }
                    else
                    {
                        ValueSpecification valuesVS = _E_ValueSpecification.wrap(valuesRaw, org.finos.legend.pure.execution.PureTypeResolver.getClassifierGenericType(valuesRaw, resolver), null, resolver);
                        if (valuesVS != null)
                        {
                            fnArgs.add(valuesVS);
                        }
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
            Object val = _E_ValueSpecification.unwrap(args.get(0));
            StringBuilder diag = new StringBuilder("No match function matched the value: ").append(val);
            diag.append("\n  Java class: ").append(val == null ? "null" : val.getClass().getName());
            diag.append("\n  valueType: ");
            if (valueType == null) diag.append("null");
            else
            {
                diag.append(valueType.getClass().getName());
                if (valueType instanceof meta.pure.metamodel.PackageableElement vpe) diag.append(" name=").append(vpe._name());
            }
            int idx = 0;
            for (ValueSpecification mfVS : matchFuncCol._values())
            {
                Object mf = _E_ValueSpecification.unwrap(mfVS);
                diag.append("\n  arm[").append(idx++).append("] ");
                if (mf instanceof meta.pure.metamodel.function.FunctionDefinition fd
                        && fd._parameters() != null && !fd._parameters().isEmpty())
                {
                    var p = fd._parameters().getFirst();
                    var pt = p._genericType() == null ? null : _GenericType.type(p._genericType());
                    diag.append("paramType=")
                            .append(pt == null ? "null"
                                    : pt.getClass().getName() + (pt instanceof meta.pure.metamodel.PackageableElement ppe ? " name=" + ppe._name() : ""))
                            .append(" subtypeOf=")
                            .append(pt == null || valueType == null ? "n/a" : _Type.subtypeOf(valueType, pt, resolver));
                }
            }
            throw new RuntimeException(diag.toString());
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

