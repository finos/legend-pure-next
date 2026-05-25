// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.ast.natives.collection;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

/** {@code filter} with predicate body inlined as {@code @Child}. Subframe
 *  pattern — see {@link InlineFoldNode}. */
@NodeInfo(shortName = "inlineFilter")
public final class InlineFilterNode extends PureNode
{
    @Child private PureNode collection;
    @Children private final PureNode[] body;
    @CompilationFinal private final FrameDescriptor lambdaDescriptor;
    @CompilationFinal private final int elemSlot;
    @CompilationFinal(dimensions = 1) private final int[] callerSlots;
    @CompilationFinal(dimensions = 1) private final int[] lambdaSlots;

    private static final Object[] EMPTY_ARGS = new Object[0];

    public InlineFilterNode(PureNode collection, PureNode[] body,
                            FrameDescriptor lambdaDescriptor, int elemSlot,
                            int[] callerSlots, int[] lambdaSlots)
    {
        this.collection = collection;
        this.body = body;
        this.lambdaDescriptor = lambdaDescriptor;
        this.elemSlot = elemSlot;
        this.callerSlots = callerSlots;
        this.lambdaSlots = lambdaSlots;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collection.executeGeneric(frame);
        int sz = CollectionHelper.size(col);
        if (sz == 0) return PureSequence.EMPTY;
        VirtualFrame sub = Truffle.getRuntime().createVirtualFrame(EMPTY_ARGS, lambdaDescriptor);
        bindCaptures(frame, sub);
        java.util.ArrayList<Object> out = new java.util.ArrayList<>(sz);
        for (int i = 0; i < sz; i++)
        {
            Object item = CollectionHelper.at(col, i);
            sub.setObject(elemSlot, item);
            Object result = null;
            for (int b = 0; b < body.length; b++)
            {
                result = body[b].executeGeneric(sub);
            }
            if (result instanceof Boolean bl && bl) out.add(item);
        }
        return new ObjectSequence(out.toArray());
    }

    @ExplodeLoop
    private void bindCaptures(VirtualFrame from, VirtualFrame to)
    {
        for (int i = 0; i < callerSlots.length; i++)
        {
            to.setObject(lambdaSlots[i], from.getObject(callerSlots[i]));
        }
    }
}
