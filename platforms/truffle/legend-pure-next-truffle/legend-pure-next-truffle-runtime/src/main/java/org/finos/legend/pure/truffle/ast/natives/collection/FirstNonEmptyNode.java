// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.ast.natives.collection;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * Native for {@code firstNonEmpty<T>(thunks:Function<{->T[*]}>[*]):T[*]}.
 *
 * <p>Pure body was a {@code fold} over the thunks with an {@code if}/{@code eval}
 * accumulator. As a native we just walk the thunks linearly, evaluate each
 * via {@link RawLambdaCallNode}, and return the first non-empty result.
 * Short-circuit semantics preserved — later thunks aren't evaluated.</p>
 *
 * <p>Skips both the fold dispatch and the per-iter closure-allocation that
 * the Pure body's fold-accumulator captured. ~2.85M calls per
 * metamodel_factories compile.</p>
 */
@NodeInfo(shortName = "firstNonEmpty")
public final class FirstNonEmptyNode extends PureNode
{
    @Child
    private PureNode thunksArg;

    @Child
    private RawLambdaCallNode callNode = new RawLambdaCallNode();

    public FirstNonEmptyNode(PureNode thunksArg)
    {
        this.thunksArg = thunksArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object thunks = thunksArg.executeGeneric(frame);
        int n = CollectionHelper.size(thunks);
        for (int i = 0; i < n; i++)
        {
            Object thunk = CollectionHelper.at(thunks, i);
            Object result = callNode.call(thunk);
            if (!CollectionHelper.isEmpty(result))
            {
                return result;
            }
        }
        return PureSequence.EMPTY;
    }
}
