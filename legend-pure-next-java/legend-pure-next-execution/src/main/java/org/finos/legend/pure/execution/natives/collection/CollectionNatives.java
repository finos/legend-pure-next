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

package org.finos.legend.pure.execution.natives.collection;

import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CollectionNatives
{
    @SuppressWarnings("unchecked")
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        // size(Any[*]) : Integer[1]
        natives.put("size_Any_MANY__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            return _E_ValueSpecification.wrap((long) list.size(), genericType, multiplicity, resolver);
        });

        // isEmpty(Any[*]) : Boolean[1]
        natives.put("isEmpty_Any_MANY__Boolean_1_", (args, eval, genericType, multiplicity) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            return _E_ValueSpecification.wrap(list.isEmpty(), genericType, multiplicity, resolver);
        });

        // toOne(T[*]) : T[1]
        natives.put("toOne_T_MANY__T_1_", (args, eval, genericType, multiplicity) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            if (list.size() != 1)
            {
                throw new RuntimeException("toOne expected exactly 1 element, got " + list.size());
            }
            return _E_ValueSpecification.wrap(list.get(0), genericType, multiplicity, resolver);
        });

        // toOneMany(T[*]) : T[1..*]
        natives.put("toOneMany_T_MANY__T_$1_MANY$_", (args, eval, genericType, multiplicity) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            if (list.isEmpty())
            {
                throw new RuntimeException("toOneMany expected at least 1 element, got 0");
            }
            return _E_ValueSpecification.wrap(list.size() == 1 ? list.get(0) : new ArrayList<>(list), genericType, multiplicity, resolver);
        });

        // concatenate(T[*], T[*]) : T[*]
        natives.put("concatenate_T_MANY__T_MANY__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            List<Object> result = new ArrayList<>();
            result.addAll((List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver)));
            result.addAll((List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(1), resolver)));
            return _E_ValueSpecification.wrap(result, genericType, multiplicity, resolver);
        });

        // at(T[*], Integer[1]) : T[1]
        natives.put("at_T_MANY__Integer_1__T_1_", (args, eval, genericType, multiplicity) ->
        {
            long index = (Long) _E_ValueSpecification.unwrap(args.get(1));
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            if (index < 0 || index >= list.size())
            {
                throw new RuntimeException("The system is trying to get an element at offset " + index + " where the collection is of size " + list.size());
            }
            return list.get((int) index);
        });
        // short-name alias for null-func fallback
        natives.put("at", natives.get("at_T_MANY__Integer_1__T_1_"));

        // sort(T[m], Function<{T[1]->U[1]}>[0..1], Function<{U[1],U[1]->Integer[1]}>[0..1]) : T[m]
        // arg[0]=col, arg[1]=key extractor, arg[2]=comparator on keys
        natives.put("sort_T_m__Function_$0_1$__Function_$0_1$__T_m_", (args, eval, genericType, multiplicity) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            List<Object> sorted = new ArrayList<>(list);
            Object keyFn = args.size() > 1 ? _E_ValueSpecification.unwrap(args.get(1)) : null;
            Object compFn = args.size() > 2 ? _E_ValueSpecification.unwrap(args.get(2)) : null;
            sorted.sort((a, b) ->
            {
                // Extract keys if key function provided
                Object kA = a;
                Object kB = b;
                if (keyFn instanceof meta.pure.metamodel.function.FunctionDefinition)
                {
                    ValueSpecification wA = _E_ValueSpecification.wrap(a, args.get(0)._genericType(), args.get(0)._multiplicity(), resolver);
                    ValueSpecification wB = _E_ValueSpecification.wrap(b, args.get(0)._genericType(), args.get(0)._multiplicity(), resolver);
                    kA = _E_ValueSpecification.unwrap(eval.executeFunction(keyFn, List.of(wA)));
                    kB = _E_ValueSpecification.unwrap(eval.executeFunction(keyFn, List.of(wB)));
                }
                // Use comparator on keys if provided
                if (compFn instanceof meta.pure.metamodel.function.FunctionDefinition)
                {
                    ValueSpecification wkA = _E_ValueSpecification.wrap(kA, null, null, resolver);
                    ValueSpecification wkB = _E_ValueSpecification.wrap(kB, null, null, resolver);
                    ValueSpecification cmpResult = eval.executeFunction(compFn, List.of(wkA, wkB));
                    return ((Number) _E_ValueSpecification.unwrap(cmpResult)).intValue();
                }
                // Default: natural order
                // Handle mixed numeric types (e.g., Integer and Float)
                if (kA instanceof Number nA && kB instanceof Number nB)
                {
                    return Double.compare(nA.doubleValue(), nB.doubleValue());
                }
                if (kA instanceof Comparable c && kA.getClass().isInstance(kB))
                {
                    return c.compareTo(kB);
                }
                // Different types: sort by type name first (e.g., Integer before String),
                // then by string value within the same type
                int typeCmp = kA.getClass().getSimpleName().compareTo(kB.getClass().getSimpleName());
                if (typeCmp != 0)
                {
                    return typeCmp;
                }
                return String.valueOf(kA).compareTo(String.valueOf(kB));
            });
            return _E_ValueSpecification.wrap(sorted, args.get(0)._genericType(), args.get(0)._multiplicity(), resolver);
        });

        // map(T[*], Function<{T[1]->V[*]}>[1]) : V[*]
        natives.put("map_T_MANY__Function_1__V_MANY_", (args, eval, genericType, multiplicity) ->
        {
            Object fn = _E_ValueSpecification.unwrap(args.get(1));
            List<Object> results = new ArrayList<>();
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            for (Object item : list)
            {
                ValueSpecification wrappedItem = _E_ValueSpecification.wrap(item, args.get(0)._genericType(), args.get(0)._multiplicity(), resolver);
                ValueSpecification result = eval.executeFunction(fn, List.of(wrappedItem));
                Object unwrapped = _E_ValueSpecification.unwrap(result);
                // Pure map is flatMap — flatten list results
                if (unwrapped instanceof List<?> innerList)
                {
                    results.addAll(innerList);
                }
                else if (unwrapped != null)
                {
                    results.add(unwrapped);
                }
            }
            return _E_ValueSpecification.wrap(results, genericType, multiplicity, resolver);
        });

        // map(T[m], Function<{T[1]->V[m]}>[1]) : V[m]  — generic multiplicity overload
        natives.put("map_T_m__Function_1__V_m_", natives.get("map_T_MANY__Function_1__V_MANY_"));

        // map(T[0..1], Function<{T[1]->V[0..1]}>[1]) : V[0..1]
        natives.put("map_T_$0_1$__Function_1__V_$0_1$_", (args, eval, genericType, multiplicity) ->
        {
            Object val = _E_ValueSpecification.unwrap(args.get(0));
            Object fn = _E_ValueSpecification.unwrap(args.get(1));
            if (val != null)
            {
                ValueSpecification wrappedItem = _E_ValueSpecification.wrap(val, args.get(0)._genericType(), args.get(0)._multiplicity(), resolver);
                return eval.executeFunction(fn, List.of(wrappedItem));
            }
            return _E_ValueSpecification.wrap(null, genericType, multiplicity, resolver);
        });

        // filter(T[*], Function<{T[1]->Boolean[1]}>[1]) : T[*]
        natives.put("filter_T_MANY__Function_1__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            Object fn = _E_ValueSpecification.unwrap(args.get(1));
            List<Object> results = new ArrayList<>();
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            for (Object item : list)
            {
                ValueSpecification wrappedItem = _E_ValueSpecification.wrap(item, args.get(0)._genericType(), args.get(0)._multiplicity(), resolver);
                ValueSpecification result = eval.executeFunction(fn, List.of(wrappedItem));
                if (Boolean.TRUE.equals(_E_ValueSpecification.unwrap(result)))
                {
                    results.add(item);
                }
            }
            return _E_ValueSpecification.wrap(results, genericType, multiplicity, resolver);
        });

        // first(T[*]) : T[0..1]
        natives.put("first_T_MANY__T_$0_1$_", (args, eval, genericType, multiplicity) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            return list.isEmpty() ? _E_ValueSpecification.wrap(null, genericType, multiplicity, resolver) : _E_ValueSpecification.wrap(list.get(0), genericType, multiplicity, resolver);
        });

        // init(T[*]) : T[*] — all but the last element
        natives.put("init_T_MANY__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            return _E_ValueSpecification.wrap(list.size() <= 1 ? new ArrayList<>() : new ArrayList<>(list.subList(0, list.size() - 1)), genericType, multiplicity, resolver);
        });

        // tail(T[*]) : T[*] — all but the first element
        natives.put("tail_T_MANY__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            return _E_ValueSpecification.wrap(list.size() <= 1 ? new ArrayList<>() : new ArrayList<>(list.subList(1, list.size())), genericType, multiplicity, resolver);
        });

        // last(T[*]) : T[0..1]
        natives.put("last_T_MANY__T_$0_1$_", (args, eval, genericType, multiplicity) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            return list.isEmpty() ? _E_ValueSpecification.wrap(null, genericType, multiplicity, resolver) : _E_ValueSpecification.wrap(list.get(list.size() - 1), genericType, multiplicity, resolver);
        });

        // range(Integer[1], Integer[1], Integer[1]) : Integer[*]
        natives.put("range_Integer_1__Integer_1__Integer_1__Integer_MANY_", (args, eval, genericType, multiplicity) ->
        {
            long start = (Long) _E_ValueSpecification.unwrap(args.get(0));
            long stop = (Long) _E_ValueSpecification.unwrap(args.get(1));
            long step = (Long) _E_ValueSpecification.unwrap(args.get(2));
            List<Long> result = new ArrayList<>();
            for (long i = start; step > 0 ? i < stop : i > stop; i += step)
            {
                result.add(i);
            }
            return _E_ValueSpecification.wrap(result, genericType, multiplicity, resolver);
        });

        // add(T[*], T[1]) : T[1..*]
        natives.put("add_T_MANY__T_1__T_$1_MANY$_", (args, eval, genericType, multiplicity) ->
        {
            List<Object> result = new ArrayList<>((List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver)));
            result.add(_E_ValueSpecification.unwrap(args.get(1)));
            return _E_ValueSpecification.wrap(result, genericType, multiplicity, resolver);
        });

        // add(T[*], Integer[1], T[1]) : T[1..*]
        natives.put("add_T_MANY__Integer_1__T_1__T_$1_MANY$_", (args, eval, genericType, multiplicity) ->
        {
            List<Object> result = new ArrayList<>((List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver)));
            int index = ((Long) _E_ValueSpecification.unwrap(args.get(1))).intValue();
            result.add(index, _E_ValueSpecification.unwrap(args.get(2)));
            return _E_ValueSpecification.wrap(result, genericType, multiplicity, resolver);
        });

        // find(T[*], Function<{T[1]->Boolean[1]}>[1]) : T[0..1]
        natives.put("find_T_MANY__Function_1__T_$0_1$_", (args, eval, genericType, multiplicity) ->
        {
            Object fn = _E_ValueSpecification.unwrap(args.get(1));
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            for (Object item : list)
            {
                ValueSpecification wrappedItem = _E_ValueSpecification.wrap(item, args.get(0)._genericType(), args.get(0)._multiplicity(), resolver);
                ValueSpecification result = eval.executeFunction(fn, List.of(wrappedItem));
                if (Boolean.TRUE.equals(_E_ValueSpecification.unwrap(result)))
                {
                    return _E_ValueSpecification.wrap(item, genericType, multiplicity, resolver);
                }
            }
            return _E_ValueSpecification.wrap(null, genericType, multiplicity, resolver);
        });

        // fold(T[*], Function<{T[1],V[m]->V[m]}>[1], V[m]) : V[m]
        natives.put("fold_T_MANY__Function_1__V_m__V_m_", (args, eval, genericType, multiplicity) ->
        {
            Object fn = _E_ValueSpecification.unwrap(args.get(1));
            ValueSpecification accumulator = args.get(2);
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            for (Object item : list)
            {
                ValueSpecification wrappedItem = _E_ValueSpecification.wrap(item, args.get(0)._genericType(), args.get(0)._multiplicity(), resolver);
                accumulator = eval.executeFunction(fn, List.of(wrappedItem, accumulator));
            }
            return accumulator;
        });

        // removeDuplicates(T[*], Function[0..1], Function[0..1]) : T[*]
        natives.put("removeDuplicates_T_MANY__Function_$0_1$__Function_$0_1$__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            Object keyFn = args.size() > 1 ? _E_ValueSpecification.unwrap(args.get(1)) : null;
            Object eqlFn = args.size() > 2 ? _E_ValueSpecification.unwrap(args.get(2)) : null;
            List<Object> result = new ArrayList<>();
            List<Object> resultKeys = new ArrayList<>(); // parallel list of keys for key-based dedup
            for (Object item : list)
            {
                // Extract key if key function provided
                Object itemKey = item;
                if (keyFn instanceof meta.pure.metamodel.function.FunctionDefinition)
                {
                    ValueSpecification wItem = _E_ValueSpecification.wrap(item, args.get(0)._genericType(), args.get(0)._multiplicity(), resolver);
                    itemKey = _E_ValueSpecification.unwrap(eval.executeFunction(keyFn, List.of(wItem)));
                }
                boolean found = false;
                for (Object existingKey : resultKeys)
                {
                    if (eqlFn instanceof meta.pure.metamodel.function.FunctionDefinition)
                    {
                        // Use custom equality function: eql(existing, new)
                        ValueSpecification wA = _E_ValueSpecification.wrap(existingKey, null, null, resolver);
                        ValueSpecification wB = _E_ValueSpecification.wrap(itemKey, null, null, resolver);
                        Object eqlResult = _E_ValueSpecification.unwrap(eval.executeFunction(eqlFn, List.of(wA, wB)));
                        if (Boolean.TRUE.equals(eqlResult))
                        {
                            found = true;
                            break;
                        }
                    }
                    else
                    {
                        if (org.finos.legend.pure.execution.NativeRepository.pureEquals(itemKey, existingKey))
                        {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found)
                {
                    result.add(item);
                    resultKeys.add(itemKey);
                }
            }
            return _E_ValueSpecification.wrap(result, genericType, multiplicity, resolver);
        });

        // indexOf(T[*], T[1]) : Integer[1]
        natives.put("indexOf_T_MANY__T_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            Object rawList = _E_ValueSpecification.unwrap(args.get(0));
            Object target = _E_ValueSpecification.unwrap(args.get(1));
            if (rawList instanceof String && target instanceof String) {
                return _E_ValueSpecification.wrap((long) ((String) rawList).indexOf((String) target), genericType, multiplicity, resolver);
            }
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            for (int i = 0; i < list.size(); i++)
            {
                if (org.finos.legend.pure.execution.NativeRepository.pureEquals(list.get(i), target))
                {
                    return _E_ValueSpecification.wrap((long) i, genericType, multiplicity, resolver);
                }
            }
            return _E_ValueSpecification.wrap(-1L, genericType, multiplicity, resolver);
        });
    }
}
