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

package org.finos.legend.pure.truffle.ast.natives.collection;

import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;

/**
 * Registers specialized Truffle nodes for the hot, no-lambda collection
 * natives — the ones that just inspect or reshape existing values without
 * invoking a user function. Lambda-driven natives ({@code map},
 * {@code filter}, {@code fold}, {@code exists}, {@code forAll}) stay on
 * the bridge until Phase 7, which needs {@code DirectCallNode} +
 * inline-cache plumbing to be worth doing.
 */
public final class CollectionNodeFactories
{
    private CollectionNodeFactories()
    {
    }

    public static void registerAll(NativeNodeRegistry registry)
    {
        registry.register("size_Any_MANY__Integer_1_",
                (args, gt, mul, fe) -> new SizeNode(args[0]));
        registry.register("isEmpty_Any_MANY__Boolean_1_",
                (args, gt, mul, fe) -> new IsEmptyNode(args[0]));
        registry.register("toOne_T_MANY__T_1_",
                (args, gt, mul, fe) -> new ToOneNode(args[0]));
        registry.register("toOneMany_T_MANY__T_$1_MANY$_",
                (args, gt, mul, fe) -> new ToOneManyNode(args[0]));
        registry.register("at_T_MANY__Integer_1__T_1_",
                (args, gt, mul, fe) -> new AtNode(args[0], args[1]));
        registry.register("head_T_MANY__T_$0_1$_",
                (args, gt, mul, fe) -> new HeadNode(args[0], gt, mul));
        registry.register("last_T_MANY__T_$0_1$_",
                (args, gt, mul, fe) -> new LastNode(args[0], gt, mul));
        registry.register("concatenate_T_MANY__T_MANY__T_MANY_",
                (args, gt, mul, fe) -> new ConcatenateNode(args[0], args[1]));
        registry.register("init_T_MANY__T_MANY_",
                (args, gt, mul, fe) -> new InitNode(args[0]));
        registry.register("tail_T_MANY__T_MANY_",
                (args, gt, mul, fe) -> new TailNode(args[0]));
        registry.register("take_T_MANY__Integer_1__T_MANY_",
                (args, gt, mul, fe) -> new TakeNode(args[0], args[1]));
        registry.register("drop_T_MANY__Integer_1__T_MANY_",
                (args, gt, mul, fe) -> new DropNode(args[0], args[1]));
        registry.register("slice_T_MANY__Integer_1__Integer_1__T_MANY_",
                (args, gt, mul, fe) -> new SliceNode(args[0], args[1], args[2]));
        registry.register("reverse_T_m__T_m_",
                (args, gt, mul, fe) -> new ReverseNode(args[0]));
        registry.register("range_Integer_1__Integer_1__Integer_1__Integer_MANY_",
                (args, gt, mul, fe) -> new RangeNode(args[0], args[1], args[2], gt));

        // add — append and insert-at-index
        registry.register("add_T_MANY__T_1__T_$1_MANY$_",
                (args, gt, mul, fe) -> new AddNode(args[0], args[1], null));
        registry.register("add_T_MANY__Integer_1__T_1__T_$1_MANY$_",
                (args, gt, mul, fe) -> new AddNode(args[0], args[1], args[2]));

        // indexOf (collection)
        registry.register("indexOf_T_MANY__T_1__Integer_1_",
                (args, gt, mul, fe) -> new IndexOfCollectionNode(args[0], args[1]));

        // removeAll — set difference
        registry.register("removeAll_T_MANY__T_MANY__T_MANY_",
                (args, gt, mul, fe) -> new RemoveAllNode(args[0], args[1]));

        // zip — pair two collections
        registry.register("zip_T_MANY__U_MANY__Pair_MANY_",
                (args, gt, mul, fe) -> new ZipNode(args[0], args[1]));

        // Lambda-driven natives
        registry.register("map_T_MANY__Function_1__V_MANY_",
                (args, gt, mul, fe) -> new MapNode(args[0], args[1]));
        registry.register("map_T_m__Function_1__V_m_",
                (args, gt, mul, fe) -> new MapNode(args[0], args[1]));
        registry.register("map_T_$0_1$__Function_1__V_$0_1$_",
                (args, gt, mul, fe) -> new MapOptionalNode(args[0], args[1]));
        registry.register("filter_T_MANY__Function_1__T_MANY_",
                (args, gt, mul, fe) -> new FilterNode(args[0], args[1]));
        registry.register("fold_T_MANY__Function_1__V_MANY__V_MANY_",
                (args, gt, mul, fe) -> new FoldNode(args[0], args[1], args[2]));
        registry.register("fold_T_MANY__Function_1__V_1__V_1_",
                (args, gt, mul, fe) -> new FoldNode(args[0], args[1], args[2]));
        registry.register("exists_T_MANY__Function_1__Boolean_1_",
                (args, gt, mul, fe) -> new ExistsNode(args[0], args[1]));
        registry.register("forAll_T_MANY__Function_1__Boolean_1_",
                (args, gt, mul, fe) -> new ForAllNode(args[0], args[1]));
        registry.register("find_T_MANY__Function_1__T_$0_1$_",
                (args, gt, mul, fe) -> new FindNode(args[0], args[1]));

        // sort — with optional key/comparator lambdas
        registry.register("sort_T_m__Function_$0_1$__Function_$0_1$__T_m_",
                (args, gt, mul, fe) -> new SortNode(args[0], args[1], args[2]));

        // removeDuplicates — with optional key/equality functions
        registry.register("removeDuplicates_T_MANY__Function_$0_1$__Function_$0_1$__T_MANY_",
                (args, gt, mul, fe) -> new RemoveDuplicatesNode(args[0], args[1], args[2]));

        // groupBy — group by key function
        registry.register("groupBy_X_MANY__Function_1__Map_1_",
                (args, gt, mul, fe) -> new GroupByNode(args[0], args[1]));

        // ===== Map API =====
        registry.register("newMap_Pair_MANY__Map_1_",
                (args, gt, mul, fe) -> new NewMapNode("newMap_Pair_MANY__Map_1_", args[0], null));
        registry.register("newMap_Pair_MANY__Property_MANY__Map_1_",
                (args, gt, mul, fe) -> new NewMapNode("newMap_Pair_MANY__Property_MANY__Map_1_", args[0], args[1]));
        registry.register("put_Map_1__U_1__V_1__Map_1_",
                (args, gt, mul, fe) -> new MapPutNode(args[0], args[1], args[2]));
        registry.register("get_Map_1__U_1__V_$0_1$_",
                (args, gt, mul, fe) -> new MapGetNode(args[0], args[1]));
        registry.register("keys_Map_1__U_MANY_",
                (args, gt, mul, fe) -> new MapKeysNode(args[0]));
        registry.register("values_Map_1__V_MANY_",
                (args, gt, mul, fe) -> new MapValuesNode(args[0]));
        registry.register("keyValues_Map_1__Pair_MANY_",
                (args, gt, mul, fe) -> new MapKeyValuesNode(args[0]));
        registry.register("putAll_Map_1__Pair_MANY__Map_1_",
                (args, gt, mul, fe) -> new PutAllNode("putAll_Map_1__Pair_MANY__Map_1_", args[0], args[1]));
        registry.register("putAll_Map_1__Map_1__Map_1_",
                (args, gt, mul, fe) -> new PutAllNode("putAll_Map_1__Map_1__Map_1_", args[0], args[1]));
    }
}
