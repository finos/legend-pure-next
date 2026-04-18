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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.valuespecification.AtomicValue;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;
import org.finos.legend.pure.truffle.types.RawClosure;

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
    @Children
    private PureNode[] children;

    public EvaluateNode(PureNode[] children)
    {
        this.children = children;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] values = new Object[children.length];
        for (int i = 0; i < children.length; i++)
        {
            values[i] = children[i].executeGeneric(frame);
        }
        return invokeEvaluate(values);
    }

    @TruffleBoundary
    private static Object invokeEvaluate(Object[] values)
    {
        Object fn = values[0];
        if (fn instanceof AtomicValue av)
        {
            fn = av._value();
        }
        if (fn == null || fn instanceof org.finos.legend.pure.truffle.types.PureNull)
        {
            return org.finos.legend.pure.truffle.types.PureNull.INSTANCE;
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
        return EvalNode.dispatch(fn, args);
    }

    private static void unwrapListValues(Object listObj, List<Object> out)
    {
        // Unwrap AtomicValue if needed
        if (listObj instanceof AtomicValue av)
        {
            listObj = av._value();
        }
        // ListImpl has _values() returning MutableList<Object>
        if (listObj instanceof meta.pure.functions.collection.List listInst)
        {
            var vals = listInst._values();
            if (vals != null)
            {
                for (Object v : vals)
                {
                    if (v instanceof AtomicValue av)
                    {
                        out.add(av._value() != null ? av._value() : v);
                    }
                    else
                    {
                        out.add(v);
                    }
                }
            }
        }
        else
        {
            // Fallback: pass the arg through as-is
            out.add(listObj);
        }
    }
}
