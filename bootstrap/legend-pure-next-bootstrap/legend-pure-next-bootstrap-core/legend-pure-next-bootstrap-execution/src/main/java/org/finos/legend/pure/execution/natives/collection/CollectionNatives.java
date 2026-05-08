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

import meta.pure.metamodel.valuespecification.AtomicValueImpl;
import meta.pure.metamodel.valuespecification.CollectionImpl;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution.PureMap;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;

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
            _E_ValueSpecification.wrap((long) _E_ValueSpecification.toCollection(args.get(0), resolver)._values().size(), genericType, multiplicity, resolver)
        );

        // isEmpty(Any[*]) : Boolean[1]
        natives.put("isEmpty_Any_MANY__Boolean_1_", (args, eval, genericType, multiplicity) ->
             _E_ValueSpecification.wrap(_E_ValueSpecification.toCollection(args.get(0), resolver)._values().isEmpty(), genericType, multiplicity, resolver)
        );

        // toOne(T[*]) : T[1]
        natives.put("toOne_T_MANY__T_1_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            if (col._values().size() != 1)
            {
                throw new RuntimeException("toOne expected exactly 1 element, got " + col._values().size());
            }
            return col._values().get(0);
        });

        // toOneMany(T[*]) : T[1..*]
        natives.put("toOneMany_T_MANY__T_$1_MANY$_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            if (col._values().isEmpty())
            {
                throw new RuntimeException("toOneMany expected at least 1 element, got 0");
            }
            return col._values().size() == 1 ? col._values().get(0) : col;
        });

        // concatenate(T[*], T[*]) : T[*]
        natives.put("concatenate_T_MANY__T_MANY__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection colA = _E_ValueSpecification.toCollection(args.get(0), resolver);
            meta.pure.metamodel.valuespecification.Collection colB = _E_ValueSpecification.toCollection(args.get(1), resolver);
            List<ValueSpecification> result = new ArrayList<>(colA._values());
            result.addAll(colB._values());
            return makeCollection(result, resolver);
        });

        // at(T[*], Integer[1]) : T[1]
        natives.put("at_T_MANY__Integer_1__T_1_", (args, eval, genericType, multiplicity) ->
        {
            long index = (Long) _E_ValueSpecification.unwrap(args.get(1));
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            if (index < 0 || index >= col._values().size())
            {
                throw new RuntimeException("The system is trying to get an element at offset " + index + " where the collection is of size " + col._values().size());
            }
            return col._values().get((int) index);
        });
        // short-name alias for null-func fallback
        natives.put("at", natives.get("at_T_MANY__Integer_1__T_1_"));

        // sort(T[m], Function<{T[1]->U[1]}>[0..1], Function<{U[1],U[1]->Integer[1]}>[0..1]) : T[m]
        // arg[0]=col, arg[1]=key extractor, arg[2]=comparator on keys
        natives.put("sort_T_m__Function_$0_1$__Function_$0_1$__T_m_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            List<ValueSpecification> sorted = new ArrayList<>(col._values());
            ValueSpecification keyFnVS = args.size() > 1 ? args.get(1) : null;
            ValueSpecification compFnVS = args.size() > 2 ? args.get(2) : null;
            boolean hasKeyFn = keyFnVS != null
                    && !_E_ValueSpecification.toCollection(keyFnVS, resolver)._values().isEmpty();
            boolean hasCompFn = compFnVS != null
                    && !_E_ValueSpecification.toCollection(compFnVS, resolver)._values().isEmpty();
            sorted.sort((aVS, bVS) ->
            {
                ValueSpecification kA = aVS;
                ValueSpecification kB = bVS;
                if (hasKeyFn)
                {
                    kA = eval.executeFunction(keyFnVS, List.of(aVS));
                    kB = eval.executeFunction(keyFnVS, List.of(bVS));
                }
                if (hasCompFn)
                {
                    ValueSpecification cmpResult = eval.executeFunction(compFnVS, List.of(kA, kB));
                    return ((Number) _E_ValueSpecification.unwrap(cmpResult)).intValue();
                }
                // Default: natural order on raw values
                Object rawA = _E_ValueSpecification.unwrap(kA);
                Object rawB = _E_ValueSpecification.unwrap(kB);
                if (rawA instanceof Number nA && rawB instanceof Number nB)
                {
                    return Double.compare(nA.doubleValue(), nB.doubleValue());
                }
                if (rawA instanceof Comparable c && rawA.getClass().isInstance(rawB))
                {
                    return c.compareTo(rawB);
                }
                int typeCmp = rawA.getClass().getSimpleName().compareTo(rawB.getClass().getSimpleName());
                if (typeCmp != 0)
                {
                    return typeCmp;
                }
                return String.valueOf(rawA).compareTo(String.valueOf(rawB));
            });
            // Return a collection of the already-typed VS items
            return new CollectionImpl(resolver)
                    ._values(org.eclipse.collections.api.factory.Lists.mutable.withAll(sorted))
                    ._genericType(col._genericType())
                    ._multiplicity(col._multiplicity());
        });

        // map(T[*], Function<{T[1]->V[*]}>[1]) : V[*]
        natives.put("map_T_MANY__Function_1__V_MANY_", (args, eval, genericType, multiplicity) ->
        {
            List<ValueSpecification> results = new ArrayList<>();
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            for (ValueSpecification itemVS : col._values())
            {
                ValueSpecification result = eval.executeFunction(args.get(1), List.of(itemVS));
                // Pure map is flatMap — if result is a Collection, splice in its VS items
                if (result instanceof meta.pure.metamodel.valuespecification.Collection resultCol)
                {
                    results.addAll(resultCol._values());
                }
                else if (!_E_ValueSpecification.toCollection(result, resolver)._values().isEmpty())
                {
                    results.add(result);
                }
            }
            return makeCollection(results, resolver);
        });

        // map(T[m], Function<{T[1]->V[m]}>[1]) : V[m]  — generic multiplicity overload
        natives.put("map_T_m__Function_1__V_m_", natives.get("map_T_MANY__Function_1__V_MANY_"));

        // map(T[0..1], Function<{T[1]->V[0..1]}>[1]) : V[0..1]
        natives.put("map_T_$0_1$__Function_1__V_$0_1$_", (args, eval, genericType, multiplicity) ->
        {
            // For 0..1: pass the arg VS directly if non-empty; function returns the new VS
            if (!_E_ValueSpecification.toCollection(args.get(0), resolver)._values().isEmpty())
            {
                return eval.executeFunction(args.get(1), List.of(args.get(0)));
            }
            return _E_ValueSpecification.wrap(null, genericType, multiplicity, resolver);
        });

        // filter(T[*], Function<{T[1]->Boolean[1]}>[1]) : T[*]
        natives.put("filter_T_MANY__Function_1__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            List<ValueSpecification> results = new ArrayList<>();
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            for (ValueSpecification itemVS : col._values())
            {
                if (Boolean.TRUE.equals(_E_ValueSpecification.unwrap(eval.executeFunction(args.get(1), List.of(itemVS)))))
                {
                    results.add(itemVS);
                }
            }
            return makeCollection(results, resolver);
        });

        // head(T[*]) : T[0..1] — canonical native
        natives.put("head_T_MANY__T_$0_1$_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            return col._values().isEmpty()
                    ? _E_ValueSpecification.wrap(null, genericType, multiplicity, resolver)
                    : col._values().get(0);
        });

        // init(T[*]) : T[*] — all but the last element
        natives.put("init_T_MANY__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            int sz = col._values().size();
            return makeCollection(sz <= 1 ? new ArrayList<>() : new ArrayList<>(col._values().subList(0, sz - 1)), resolver);
        });

        // tail(T[*]) : T[*] — all but the first element
        natives.put("tail_T_MANY__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            int sz = col._values().size();
            return makeCollection(sz <= 1 ? new ArrayList<>() : new ArrayList<>(col._values().subList(1, sz)), resolver);
        });

        // last(T[*]) : T[0..1]
        natives.put("last_T_MANY__T_$0_1$_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            return col._values().isEmpty()
                    ? _E_ValueSpecification.wrap(null, genericType, multiplicity, resolver)
                    : col._values().get(col._values().size() - 1);
        });

        // range(Integer[1], Integer[1], Integer[1]) : Integer[*]
        natives.put("range_Integer_1__Integer_1__Integer_1__Integer_MANY_", (args, eval, genericType, multiplicity) ->
        {
            long start = (Long) _E_ValueSpecification.unwrap(args.get(0));
            long stop = (Long) _E_ValueSpecification.unwrap(args.get(1));
            long step = (Long) _E_ValueSpecification.unwrap(args.get(2));
            List<ValueSpecification> result = new ArrayList<>();
            for (long i = start; step > 0 ? i < stop : i > stop; i += step)
            {
                result.add(_E_ValueSpecification.wrap(i, genericType, null, resolver));
            }
            return makeCollection(result, resolver);
        });

        // add(T[*], T[1]) : T[1..*]
        natives.put("add_T_MANY__T_1__T_$1_MANY$_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            List<ValueSpecification> result = new ArrayList<>(col._values());
            result.add(args.get(1));
            return makeCollection(result, resolver);
        });

        // add(T[*], Integer[1], T[1]) : T[1..*]
        natives.put("add_T_MANY__Integer_1__T_1__T_$1_MANY$_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            int index = ((Long) _E_ValueSpecification.unwrap(args.get(1))).intValue();
            List<ValueSpecification> result = new ArrayList<>(col._values());
            result.add(index, args.get(2));
            return makeCollection(result, resolver);
        });

        // find(T[*], Function<{T[1]->Boolean[1]}>[1]) : T[0..1]
        natives.put("find_T_MANY__Function_1__T_$0_1$_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            for (ValueSpecification itemVS : col._values())
            {
                if (Boolean.TRUE.equals(_E_ValueSpecification.unwrap(eval.executeFunction(args.get(1), List.of(itemVS)))))
                {
                    return itemVS;
                }
            }
            return _E_ValueSpecification.wrap(null, genericType, multiplicity, resolver);
        });

        // fold(T[*], Function<{T[1],V[*]->V[*]}>[1], V[*]) : V[*]
        natives.put("fold_T_MANY__Function_1__V_MANY__V_MANY_", (args, eval, genericType, multiplicity) ->
        {
            ValueSpecification accumulator = args.get(2);
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            for (ValueSpecification itemVS : col._values())
            {
                accumulator = eval.executeFunction(args.get(1), List.of(itemVS, accumulator));
            }
            return accumulator;
        });

        // fold(T[*], Function<{T[1],V[1]->V[1]}>[1], V[1]) : V[1]
        natives.put("fold_T_MANY__Function_1__V_1__V_1_", natives.get("fold_T_MANY__Function_1__V_MANY__V_MANY_"));

        // removeDuplicates(T[*], Function[0..1], Function[0..1]) : T[*]
        natives.put("removeDuplicates_T_MANY__Function_$0_1$__Function_$0_1$__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            ValueSpecification keyFnVS = args.size() > 1 ? args.get(1) : null;
            ValueSpecification eqlFnVS = args.size() > 2 ? args.get(2) : null;
            boolean hasKeyFn = keyFnVS != null
                    && !_E_ValueSpecification.toCollection(keyFnVS, resolver)._values().isEmpty();
            boolean hasEqlFn = eqlFnVS != null
                    && !_E_ValueSpecification.toCollection(eqlFnVS, resolver)._values().isEmpty();
            List<ValueSpecification> result = new ArrayList<>();
            // Keys kept as ValueSpecification — no unwrap/wrap cycle needed
            List<ValueSpecification> resultKeys = new ArrayList<>();
            for (ValueSpecification itemVS : col._values())
            {
                // Key is either the result of the key function or the item VS itself
                ValueSpecification itemKey = hasKeyFn
                        ? eval.executeFunction(keyFnVS, List.of(itemVS))
                        : itemVS;
                boolean found = false;
                for (ValueSpecification existingKey : resultKeys)
                {
                    if (hasEqlFn)
                    {
                        // Pass key VSes directly to the equality function — no re-wrap needed
                        Object eqlResult = _E_ValueSpecification.unwrap(
                                eval.executeFunction(eqlFnVS, List.of(existingKey, itemKey)));
                        if (Boolean.TRUE.equals(eqlResult))
                        {
                            found = true;
                            break;
                        }
                    }
                    else
                    {
                        // pureEquals auto-unwraps ValueSpecification arguments
                        if (org.finos.legend.pure.execution.NativeRepository.pureEquals(existingKey, itemKey, resolver))
                        {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found)
                {
                    result.add(itemVS);
                    resultKeys.add(itemKey);
                }
            }
            return makeCollection(result, resolver);
        });

        // indexOf(T[*], T[1]) : Integer[1]
        natives.put("indexOf_T_MANY__T_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            Object rawList = _E_ValueSpecification.unwrap(args.get(0));
            Object target = _E_ValueSpecification.unwrap(args.get(1));
            if (rawList instanceof String && target instanceof String)
            {
                return _E_ValueSpecification.wrap((long) ((String) rawList).indexOf((String) target), genericType, multiplicity, resolver);
            }
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            for (int i = 0; i < col._values().size(); i++)
            {
                // pureEquals auto-unwraps both VS arguments
                if (org.finos.legend.pure.execution.NativeRepository.pureEquals(col._values().get(i), args.get(1), resolver))
                {
                    return _E_ValueSpecification.wrap((long) i, genericType, multiplicity, resolver);
                }
            }
            return _E_ValueSpecification.wrap(-1L, genericType, multiplicity, resolver);
        });
        // drop(T[*], Integer[1]) : T[*]
        natives.put("drop_T_MANY__Integer_1__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            long count = (Long) _E_ValueSpecification.unwrap(args.get(1));
            int from = (int) Math.min(Math.max(count, 0), col._values().size());
            return makeCollection(new ArrayList<>(col._values().subList(from, col._values().size())), resolver);
        });

        // take(T[*], Integer[1]) : T[*]
        natives.put("take_T_MANY__Integer_1__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            long count = (Long) _E_ValueSpecification.unwrap(args.get(1));
            int to = (int) Math.min(Math.max(count, 0), col._values().size());
            return makeCollection(new ArrayList<>(col._values().subList(0, to)), resolver);
        });

        // slice(T[*], Integer[1], Integer[1]) : T[*]
        natives.put("slice_T_MANY__Integer_1__Integer_1__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            int from = (int) Math.max(0, (Long) _E_ValueSpecification.unwrap(args.get(1)));
            int to = (int) Math.min(col._values().size(), (Long) _E_ValueSpecification.unwrap(args.get(2)));
            from = Math.min(from, col._values().size());
            to = Math.max(from, to);
            return makeCollection(new ArrayList<>(col._values().subList(from, to)), resolver);
        });

        // reverse(T[m]) : T[m]
        natives.put("reverse_T_m__T_m_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            List<ValueSpecification> reversed = new ArrayList<>(col._values());
            java.util.Collections.reverse(reversed);
            return makeCollection(reversed, resolver);
        });

        // zip(T[*], U[*]) : Pair<T,U>[*]
        natives.put("zip_T_MANY__U_MANY__Pair_MANY_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection colA = _E_ValueSpecification.toCollection(args.get(0), resolver);
            meta.pure.metamodel.valuespecification.Collection colB = _E_ValueSpecification.toCollection(args.get(1), resolver);
            int size = Math.min(colA._values().size(), colB._values().size());
            List<ValueSpecification> result = new ArrayList<>();
            for (int i = 0; i < size; i++)
            {
                ValueSpecification aVS = colA._values().get(i);
                ValueSpecification bVS = colB._values().get(i);
                DynamicInstance pair = makePair(
                        aVS, bVS,
                        aVS._genericType(), bVS._genericType(), resolver);
                result.add(new AtomicValueImpl(resolver)
                        ._value(pair)
                        ._genericType(pair.getClassifierGenericType())
                        ._multiplicity(_Multiplicity.concreteMultiplicity(1, 1, resolver)));
            }
            return makeCollection(result, resolver);
        });

        // removeAll(T[*], T[*]) : T[*]
        natives.put("removeAll_T_MANY__T_MANY__T_MANY_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection setCol = _E_ValueSpecification.toCollection(args.get(0), resolver);
            meta.pure.metamodel.valuespecification.Collection otherCol = _E_ValueSpecification.toCollection(args.get(1), resolver);
            List<ValueSpecification> result = new ArrayList<>();
            for (ValueSpecification vs : setCol._values())
            {
                boolean found = false;
                for (ValueSpecification other : otherCol._values())
                {
                    // pureEquals auto-unwraps both VS arguments
                    if (org.finos.legend.pure.execution.NativeRepository.pureEquals(vs, other, resolver))
                    {
                        found = true;
                        break;
                    }
                }
                if (!found)
                {
                    result.add(vs);
                }
            }
            return makeCollection(result, resolver);
        });

        // ===== Map API =====
        // newMap(Pair<U,V>[*]) : Map<U,V>[1]
        natives.put("newMap_Pair_MANY__Map_1_", (args, eval, genericType, multiplicity) ->
        {
            java.util.LinkedHashMap<ValueSpecification, ValueSpecification> map = new java.util.LinkedHashMap<>();
            for (ValueSpecification pairVS : _E_ValueSpecification.toCollection(args.get(0), resolver)._values())
            {
                DynamicInstance pair = (DynamicInstance) _E_ValueSpecification.unwrap(pairVS);
                ValueSpecification kVS = _E_ValueSpecification.wrap(pair.get("first"), org.finos.legend.pure.execution.PureTypeResolver.getClassifierGenericType(pair.get("first"), resolver), null, resolver);
                ValueSpecification vVS = _E_ValueSpecification.wrap(pair.get("second"), org.finos.legend.pure.execution.PureTypeResolver.getClassifierGenericType(pair.get("second"), resolver), null, resolver);
                map.put(kVS, vVS);
            }
            return _E_ValueSpecification.wrap(new PureMap(map), genericType, multiplicity, resolver);
        });

        // newMap(Pair<U,V>[*], Property<U,Any|1>[*]) : Map<U,V>[1]  — key-function overload (ignore property arg, use equality)
        natives.put("newMap_Pair_MANY__Property_MANY__Map_1_", (args, eval, genericType, multiplicity) ->
                natives.get("newMap_Pair_MANY__Map_1_").apply(args, eval, genericType, multiplicity));

        // put(Map<U,V>[1], key:U[1], value:V[1]) : Map<U,V>[1]
        natives.put("put_Map_1__U_1__V_1__Map_1_", (args, eval, genericType, multiplicity) ->
        {
            PureMap original = (PureMap) _E_ValueSpecification.unwrap(args.get(0));
            ValueSpecification keyVS = args.get(1);
            ValueSpecification valueVS = args.get(2);
            java.util.LinkedHashMap<ValueSpecification, ValueSpecification> m = new java.util.LinkedHashMap<>(original.getMap());
            // remove existing structurally-equal key first
            m.keySet().removeIf(k -> org.finos.legend.pure.execution.NativeRepository.pureEquals(k, keyVS, resolver));
            m.put(keyVS, valueVS);
            return _E_ValueSpecification.wrap(new PureMap(m), genericType, multiplicity, resolver);
        });

        // remove(Map<U,V>[1], key:U[1]) : Map<U,V>[1]
        natives.put("remove_Map_1__U_1__Map_1_", (args, eval, genericType, multiplicity) ->
        {
            PureMap original = (PureMap) _E_ValueSpecification.unwrap(args.get(0));
            ValueSpecification keyVS = args.get(1);
            java.util.LinkedHashMap<ValueSpecification, ValueSpecification> m = new java.util.LinkedHashMap<>(original.getMap());
            m.keySet().removeIf(k -> org.finos.legend.pure.execution.NativeRepository.pureEquals(k, keyVS, resolver));
            return _E_ValueSpecification.wrap(new PureMap(m), genericType, multiplicity, resolver);
        });

        // get(Map<U,V>[1], key:U[1]) : V[0..1]
        natives.put("get_Map_1__U_1__V_$0_1$_", (args, eval, genericType, multiplicity) ->
        {
            PureMap pureMap = (PureMap) _E_ValueSpecification.unwrap(args.get(0));
            ValueSpecification keyVS = args.get(1);
            for (Map.Entry<ValueSpecification, ValueSpecification> e : pureMap.getMap().entrySet())
            {
                if (org.finos.legend.pure.execution.NativeRepository.pureEquals(e.getKey(), keyVS, resolver))
                {
                    // Return the stored VS directly — type info is already embedded
                    return e.getValue();
                }
            }
            return _E_ValueSpecification.wrap(null, genericType, multiplicity, resolver);
        });

        // keys(Map<U,V>[1]) : U[*]
        natives.put("keys_Map_1__U_MANY_", (args, eval, genericType, multiplicity) ->
        {
            PureMap pureMap = (PureMap) _E_ValueSpecification.unwrap(args.get(0));
            // Keys are already stored as ValueSpecification — return them directly
            return makeCollection(new ArrayList<>(pureMap.getMap().keySet()), resolver);
        });

        // values(Map<U,V>[1]) : V[*]
        natives.put("values_Map_1__V_MANY_", (args, eval, genericType, multiplicity) ->
        {
            PureMap pureMap = (PureMap) _E_ValueSpecification.unwrap(args.get(0));
            // Values are already stored as ValueSpecification — return them directly
            return makeCollection(new ArrayList<>(pureMap.getMap().values()), resolver);
        });

        // keyValues(Map<U,V>[1]) : Pair<U,V>[*]
        natives.put("keyValues_Map_1__Pair_MANY_", (args, eval, genericType, multiplicity) ->
        {
            PureMap pureMap = (PureMap) _E_ValueSpecification.unwrap(args.get(0));
            List<ValueSpecification> pairs = new ArrayList<>();
            for (Map.Entry<ValueSpecification, ValueSpecification> entry : pureMap.getMap().entrySet())
            {
                DynamicInstance pair = makePair(
                        entry.getKey(), entry.getValue(),
                        entry.getKey()._genericType(), entry.getValue()._genericType(), resolver);
                pairs.add(new AtomicValueImpl(resolver)
                        ._value(pair)
                        ._genericType(pair.getClassifierGenericType())
                        ._multiplicity(_Multiplicity.concreteMultiplicity(1, 1, resolver)));
            }
            return makeCollection(pairs, resolver);
        });

        // putAll(Map<U,V>[1], Pair<U,V>[*]) : Map<U,V>[1]
        natives.put("putAll_Map_1__Pair_MANY__Map_1_", (args, eval, genericType, multiplicity) ->
        {
            PureMap original = (PureMap) _E_ValueSpecification.unwrap(args.get(0));
            java.util.LinkedHashMap<ValueSpecification, ValueSpecification> m = new java.util.LinkedHashMap<>(original.getMap());
            for (ValueSpecification pairVS : _E_ValueSpecification.toCollection(args.get(1), resolver)._values())
            {
                DynamicInstance pair = (DynamicInstance) _E_ValueSpecification.unwrap(pairVS);
                ValueSpecification kVS = _E_ValueSpecification.wrap(pair.get("first"), org.finos.legend.pure.execution.PureTypeResolver.getClassifierGenericType(pair.get("first"), resolver), null, resolver);
                ValueSpecification vVS = _E_ValueSpecification.wrap(pair.get("second"), org.finos.legend.pure.execution.PureTypeResolver.getClassifierGenericType(pair.get("second"), resolver), null, resolver);
                m.keySet().removeIf(key -> org.finos.legend.pure.execution.NativeRepository.pureEquals(key, kVS, resolver));
                m.put(kVS, vVS);
            }
            return _E_ValueSpecification.wrap(new PureMap(m), genericType, multiplicity, resolver);
        });

        // putAll_Map_1__Map_1__Map_1_ — merge two maps
        natives.put("putAll_Map_1__Map_1__Map_1_", (args, eval, genericType, multiplicity) ->
        {
            PureMap m1 = (PureMap) _E_ValueSpecification.unwrap(args.get(0));
            PureMap m2 = (PureMap) _E_ValueSpecification.unwrap(args.get(1));
            java.util.LinkedHashMap<ValueSpecification, ValueSpecification> m = new java.util.LinkedHashMap<>(m1.getMap());
            for (Map.Entry<ValueSpecification, ValueSpecification> e : m2.getMap().entrySet())
            {
                m.keySet().removeIf(k -> org.finos.legend.pure.execution.NativeRepository.pureEquals(k, e.getKey(), resolver));
                m.put(e.getKey(), e.getValue());
            }
            return _E_ValueSpecification.wrap(new PureMap(m), genericType, multiplicity, resolver);
        });

        // exists(T[*], Function<{T[1]->Boolean[1]}>[1]) : Boolean[1]
        natives.put("exists_T_MANY__Function_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            for (ValueSpecification itemVS : col._values())
            {
                if (Boolean.TRUE.equals(_E_ValueSpecification.unwrap(eval.executeFunction(args.get(1), List.of(itemVS)))))
                {
                    return _E_ValueSpecification.wrap(true, genericType, multiplicity, resolver);
                }
            }
            return _E_ValueSpecification.wrap(false, genericType, multiplicity, resolver);
        });

        // forAll(T[*], Function<{T[1]->Boolean[1]}>[1]) : Boolean[1]
        natives.put("forAll_T_MANY__Function_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            for (ValueSpecification itemVS : col._values())
            {
                if (!Boolean.TRUE.equals(_E_ValueSpecification.unwrap(eval.executeFunction(args.get(1), List.of(itemVS)))))
                {
                    return _E_ValueSpecification.wrap(false, genericType, multiplicity, resolver);
                }
            }
            return _E_ValueSpecification.wrap(true, genericType, multiplicity, resolver);
        });

        // groupBy(X[*], Function<{X[1]->K[1]}>[1]) : Map<K,List<X>>[1]
        // Pure's List<X> has a 'values' property that contains the grouped elements
        natives.put("groupBy_X_MANY__Function_1__Map_1_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.valuespecification.Collection col = _E_ValueSpecification.toCollection(args.get(0), resolver);
            java.util.LinkedHashMap<ValueSpecification, List<ValueSpecification>> grouped = new java.util.LinkedHashMap<>();
            for (ValueSpecification itemVS : col._values())
            {
                ValueSpecification key = eval.executeFunction(args.get(1), List.of(itemVS));
                ValueSpecification canonicalKey = null;
                for (ValueSpecification k : grouped.keySet())
                {
                    if (org.finos.legend.pure.execution.NativeRepository.pureEquals(k, key, resolver))
                    {
                        canonicalKey = k;
                        break;
                    }
                }
                if (canonicalKey == null)
                {
                    canonicalKey = key;
                    grouped.put(canonicalKey, new ArrayList<>());
                }
                grouped.get(canonicalKey).add(itemVS);
            }
            java.util.LinkedHashMap<ValueSpecification, ValueSpecification> resultMap = new java.util.LinkedHashMap<>();
            for (Map.Entry<ValueSpecification, List<ValueSpecification>> e : grouped.entrySet())
            {
                DynamicInstance listInstance = new DynamicInstance("meta::pure::functions::collection::List");
                listInstance.put("values", makeCollection(e.getValue(), resolver));
                resultMap.put(e.getKey(), _E_ValueSpecification.wrap(listInstance, null, null, resolver));
            }
            return _E_ValueSpecification.wrap(new PureMap(resultMap), genericType, multiplicity, resolver);
        });
    }

    /**
     * Build a CollectionImpl from a list of already-typed ValueSpecification items.
     * Computes _genericType as the most common type of the elements (via findCommonGenericType)
     * and _multiplicity from the actual size — never uses a call-site type annotation.
     */
    public static CollectionImpl makeCollection(List<ValueSpecification> items, MetadataAccess resolver)
    {
        MutableList<ValueSpecification> vs = Lists.mutable.withAll(items);
        MutableList<meta.pure.metamodel.type.generics.GenericType> elementTypes =
                vs.collect(ValueSpecification::_genericType).select(gt -> gt != null);
        meta.pure.metamodel.type.generics.GenericType gt = elementTypes.notEmpty()
                ? _GenericType.findCommonGenericType(elementTypes, resolver)
                : (meta.pure.metamodel.type.generics.GenericType) resolver.getElement(
                        "meta::pure::metamodel::type::generics::optimization::GenericType_Nil");
        int size = vs.size();
        return new CollectionImpl(resolver)
                ._values(vs)
                ._genericType(gt)
                ._multiplicity(_Multiplicity.concreteMultiplicity(size, size, resolver));
    }

    /**
     * Creates a DynamicInstance representing a Pure Pair with its classifierGenericType
     * built from the actual element types of the two input collections.
     */
    private static DynamicInstance makePair(ValueSpecification first, ValueSpecification second,
            meta.pure.metamodel.type.generics.GenericType firstGT,
            meta.pure.metamodel.type.generics.GenericType secondGT,
            org.finos.legend.pure.m3.module.MetadataAccess resolver)
    {
        DynamicInstance pair = new DynamicInstance("meta::pure::functions::collection::Pair");
        pair.put("first", first);
        pair.put("second", second);
        meta.pure.metamodel.type.Type pairClass =
                (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::functions::collection::Pair");
        pair.setClassifierGenericType(
                new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(resolver)
                        ._type(pairClass)
                        ._typeArguments(org.eclipse.collections.api.factory.Lists.mutable.with(firstGT, secondGT)));
        return pair;
    }
}
