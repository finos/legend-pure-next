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
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
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
        natives.put("size_Any_MANY__Integer_1_", (args, eval, fe) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            return _E_ValueSpecification.wrap((long) list.size(), fe._genericType(), fe._multiplicity());
        });

        // isEmpty(Any[*]) : Boolean[1]
        natives.put("isEmpty_Any_MANY__Boolean_1_", (args, eval, fe) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            return _E_ValueSpecification.wrap(list.isEmpty(), fe._genericType(), fe._multiplicity());
        });

        // toOne(T[*]) : T[1]
        natives.put("toOne_T_MANY__T_1_", (args, eval, fe) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            if (list.size() != 1)
            {
                throw new RuntimeException("toOne expected exactly 1 element, got " + list.size());
            }
            return _E_ValueSpecification.wrap(list.get(0), fe._genericType(), fe._multiplicity());
        });

        // toOneMany(T[*]) : T[1..*]
        natives.put("toOneMany_T_MANY__T_$1_MANY$_", (args, eval, fe) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            if (list.isEmpty())
            {
                throw new RuntimeException("toOneMany expected at least 1 element, got 0");
            }
            return _E_ValueSpecification.wrap(list.size() == 1 ? list.get(0) : new ArrayList<>(list), fe._genericType(), fe._multiplicity());
        });

        // concatenate(T[*], T[*]) : T[*]
        natives.put("concatenate_T_MANY__T_MANY__T_MANY_", (args, eval, fe) ->
        {
            List<Object> result = new ArrayList<>();
            result.addAll((List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver)));
            result.addAll((List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(1), resolver)));
            return _E_ValueSpecification.wrap(result, fe._genericType(), fe._multiplicity());
        });

        // at(T[*], Integer[1]) : T[1]
        natives.put("at_T_MANY__Integer_1__T_1_", (args, eval, fe) ->
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

        // sort(T[m], Function<{T[1],T[1]->Integer[1]}>[0..1], Function<{T[1]->U[1]}>[0..1]) : T[m]
        natives.put("sort_T_m__Function_$0_1$__Function_$0_1$__T_m_", (args, eval, fe) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            List<Object> sorted = new ArrayList<>(list);
            sorted.sort((a, b) ->
            {
                if (a instanceof Comparable c && a.getClass().isInstance(b))
                {
                    return c.compareTo(b);
                }
                // Different types: sort by class name first, then by string representation
                int typeCompare = a.getClass().getName().compareTo(b.getClass().getName());
                if (typeCompare != 0)
                {
                    return typeCompare;
                }
                return String.valueOf(a).compareTo(String.valueOf(b));
            });
            return _E_ValueSpecification.wrap(sorted, fe._genericType(), fe._multiplicity());
        });

        // map(T[*], Function<{T[1]->V[*]}>[1]) : V[*]
        natives.put("map_T_MANY__Function_1__V_MANY_", (args, eval, fe) ->
        {
            Object fn = _E_ValueSpecification.unwrap(args.get(1));
            List<Object> results = new ArrayList<>();
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            for (Object item : list)
            {
                ValueSpecification wrappedItem = _E_ValueSpecification.wrap(item, args.get(0)._genericType(), args.get(0)._multiplicity());
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
            return _E_ValueSpecification.wrap(results, fe._genericType(), fe._multiplicity());
        });

        // map(T[m], Function<{T[1]->V[m]}>[1]) : V[m]  — generic multiplicity overload
        natives.put("map_T_m__Function_1__V_m_", natives.get("map_T_MANY__Function_1__V_MANY_"));

        // map(T[0..1], Function<{T[1]->V[0..1]}>[1]) : V[0..1]
        natives.put("map_T_$0_1$__Function_1__V_$0_1$_", (args, eval, fe) ->
        {
            Object val = _E_ValueSpecification.unwrap(args.get(0));
            Object fn = _E_ValueSpecification.unwrap(args.get(1));
            if (val != null)
            {
                ValueSpecification wrappedItem = _E_ValueSpecification.wrap(val, args.get(0)._genericType(), args.get(0)._multiplicity());
                return eval.executeFunction(fn, List.of(wrappedItem));
            }
            return _E_ValueSpecification.wrap(null, fe._genericType(), fe._multiplicity());
        });

        // filter(T[*], Function<{T[1]->Boolean[1]}>[1]) : T[*]
        natives.put("filter_T_MANY__Function_1__T_MANY_", (args, eval, fe) ->
        {
            Object fn = _E_ValueSpecification.unwrap(args.get(1));
            List<Object> results = new ArrayList<>();
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            for (Object item : list)
            {
                ValueSpecification wrappedItem = _E_ValueSpecification.wrap(item, args.get(0)._genericType(), args.get(0)._multiplicity());
                ValueSpecification result = eval.executeFunction(fn, List.of(wrappedItem));
                if (Boolean.TRUE.equals(_E_ValueSpecification.unwrap(result)))
                {
                    results.add(item);
                }
            }
            return _E_ValueSpecification.wrap(results, fe._genericType(), fe._multiplicity());
        });

        // first(T[*]) : T[0..1]
        natives.put("first_T_MANY__T_$0_1$_", (args, eval, fe) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            return list.isEmpty() ? _E_ValueSpecification.wrap(null, fe._genericType(), fe._multiplicity()) : _E_ValueSpecification.wrap(list.get(0), fe._genericType(), fe._multiplicity());
        });

        // init(T[*]) : T[*] — all but the last element
        natives.put("init_T_MANY__T_MANY_", (args, eval, fe) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            return _E_ValueSpecification.wrap(list.size() <= 1 ? new ArrayList<>() : new ArrayList<>(list.subList(0, list.size() - 1)), fe._genericType(), fe._multiplicity());
        });

        // tail(T[*]) : T[*] — all but the first element
        natives.put("tail_T_MANY__T_MANY_", (args, eval, fe) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            return _E_ValueSpecification.wrap(list.size() <= 1 ? new ArrayList<>() : new ArrayList<>(list.subList(1, list.size())), fe._genericType(), fe._multiplicity());
        });

        // last(T[*]) : T[0..1]
        natives.put("last_T_MANY__T_$0_1$_", (args, eval, fe) ->
        {
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            return list.isEmpty() ? _E_ValueSpecification.wrap(null, fe._genericType(), fe._multiplicity()) : _E_ValueSpecification.wrap(list.get(list.size() - 1), fe._genericType(), fe._multiplicity());
        });

        // range(Integer[1], Integer[1], Integer[1]) : Integer[*]
        natives.put("range_Integer_1__Integer_1__Integer_1__Integer_MANY_", (args, eval, fe) ->
        {
            long start = (Long) _E_ValueSpecification.unwrap(args.get(0));
            long stop = (Long) _E_ValueSpecification.unwrap(args.get(1));
            long step = (Long) _E_ValueSpecification.unwrap(args.get(2));
            List<Long> result = new ArrayList<>();
            for (long i = start; step > 0 ? i < stop : i > stop; i += step)
            {
                result.add(i);
            }
            return _E_ValueSpecification.wrap(result, fe._genericType(), fe._multiplicity());
        });
    }
}
