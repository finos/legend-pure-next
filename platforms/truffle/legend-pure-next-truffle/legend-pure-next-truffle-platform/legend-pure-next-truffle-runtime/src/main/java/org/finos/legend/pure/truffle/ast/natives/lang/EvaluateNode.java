// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.ast.natives.lang;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code evaluate(Function[1], List[*]) : Any[*]}.
 *
 * <p>Unlike {@link EvalNode} which passes args directly, this node
 * unwraps each {@code List<Any>} argument to extract its {@code values}
 * before invoking the function. This is needed because the Pure
 * {@code evaluate} function wraps each argument in a {@code List<Any>}.</p>
 */
@NodeInfo(shortName = "evaluate")
public final class EvaluateNode extends PureNode
{

    private static final int SLOT_VALUES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("values");
    @Children
    private PureNode[] children;

    @Child
    private org.finos.legend.pure.truffle.ast.PropertyReadNode dispatchReader = new org.finos.legend.pure.truffle.ast.PropertyReadNode();

    public EvaluateNode(PureNode[] children)
    {
        this.children = children;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] values = evaluateChildren(frame);
        return invokeEvaluate(getContext(), values, dispatchReader);
    }

    @ExplodeLoop
    private Object[] evaluateChildren(VirtualFrame frame)
    {
        Object[] values = new Object[children.length];
        for (int i = 0; i < children.length; i++)
        {
            values[i] = children[i].executeGeneric(frame);
        }
        return values;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object invokeEvaluate(org.finos.legend.pure.truffle.PureContext evaluator, Object[] values,
                                          org.finos.legend.pure.truffle.ast.PropertyReadNode propertyReader)
    {
        Object fn = values[0];
        if (fn == null || (fn instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty()))
        {
            return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        }

        // values[1] is the List<Any>[*] collection — unwrap each List to its values
        List<Object> unwrappedArgs = new ArrayList<>();
        if (values.length > 1)
        {
            Object listsArg = values[1];
            int listCount = CollectionHelper.size(listsArg);
            for (int i = 0; i < listCount; i++)
            {
                Object listObj = CollectionHelper.at(listsArg, i);
                unwrapListValues(listObj, unwrappedArgs);
            }
        }

        Object[] args = unwrappedArgs.toArray();
        return EvalNode.dispatch(evaluator, fn, args, propertyReader);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void unwrapListValues(Object listObj, List<Object> out)
    {
        // ListImpl has _values() returning collection (Object / ObjectSequence / MutableList)
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(listObj,
                "meta::pure::functions::collection::List"))
        {
            Object vals = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(listObj, SLOT_VALUES);
            int sz = org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper.size(vals);
            if (sz == 0)
            {
                out.add(org.finos.legend.pure.truffle.types.PureSequence.EMPTY);
            }
            else if (sz == 1)
            {
                out.add(org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper.at(vals, 0));
            }
            else
            {
                Object[] arr = new Object[sz];
                for (int i = 0; i < sz; i++)
                {
                    arr[i] = org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper.at(vals, i);
                }
                out.add(new org.finos.legend.pure.truffle.types.ObjectSequence(arr));
            }
        }
        else
        {
            // Fallback: pass the arg through as-is
            out.add(listObj);
        }
    }
}
