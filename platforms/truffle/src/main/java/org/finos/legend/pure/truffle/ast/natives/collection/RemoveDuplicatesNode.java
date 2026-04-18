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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.execution.NativeRepository;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code removeDuplicates(T[*], Function[0..1], Function[0..1]) : T[*]}
 * — removes duplicate elements, with optional key extractor and equality functions.
 */
@NodeInfo(shortName = "removeDuplicates")
public final class RemoveDuplicatesNode extends PureNode
{
    @Child
    private PureNode collectionArg;

    @Child
    private PureNode keyFnArg;

    @Child
    private PureNode eqlFnArg;

    public RemoveDuplicatesNode(PureNode collectionArg, PureNode keyFnArg, PureNode eqlFnArg)
    {
        this.collectionArg = collectionArg;
        this.keyFnArg = keyFnArg;
        this.eqlFnArg = eqlFnArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collectionArg.executeGeneric(frame);
        Object keyFn = keyFnArg != null ? keyFnArg.executeGeneric(frame) : null;
        Object eqlFn = eqlFnArg != null ? eqlFnArg.executeGeneric(frame) : null;
        return doRemoveDuplicates(col, keyFn, eqlFn);
    }

    @TruffleBoundary
    private static ValueSpecification doRemoveDuplicates(Object col, Object keyFn, Object eqlFn)
    {
        MutableList<ValueSpecification> values = CollectionHelper.values(col);
        ValueSpecification keyFnVS = keyFn != null ? ValueAdapter.ensureVS(keyFn) : null;
        ValueSpecification eqlFnVS = eqlFn != null ? ValueAdapter.ensureVS(eqlFn) : null;
        boolean hasKeyFn = keyFnVS != null && !CollectionHelper.isEmpty(keyFnVS);
        boolean hasEqlFn = eqlFnVS != null && !CollectionHelper.isEmpty(eqlFnVS);

        List<ValueSpecification> result = new ArrayList<>();
        List<ValueSpecification> resultKeys = new ArrayList<>();

        for (int i = 0; i < values.size(); i++)
        {
            ValueSpecification itemVS = values.get(i);
            ValueSpecification itemKey = hasKeyFn
                    ? EvaluatorHolder.current().executeFunction(keyFnVS, List.of(itemVS))
                    : itemVS;
            boolean found = false;
            for (ValueSpecification existingKey : resultKeys)
            {
                if (hasEqlFn)
                {
                    Object eqlResult = _E_ValueSpecification.unwrap(
                            EvaluatorHolder.current().executeFunction(eqlFnVS, List.of(existingKey, itemKey)));
                    if (Boolean.TRUE.equals(eqlResult))
                    {
                        found = true;
                        break;
                    }
                }
                else
                {
                    if (NativeRepository.pureEquals(existingKey, itemKey))
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
        return CollectionHelper.makeCollection(result);
    }
}
