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

/** {@code groupBy} with key body inlined as {@code @Child}. Subframe pattern
 *  — see {@link InlineFoldNode}. Reuses the rest of the {@link GroupByNode}
 *  machinery (Map / List construction) via a delegating call to its static
 *  helpers — only the per-element key extraction is inlined. */
@NodeInfo(shortName = "inlineGroupBy")
public final class InlineGroupByNode extends PureNode
{
    @Child private PureNode collection;
    @Children private final PureNode[] body;
    @CompilationFinal private final FrameDescriptor lambdaDescriptor;
    @CompilationFinal private final int elemSlot;
    @CompilationFinal(dimensions = 1) private final int[] callerSlots;
    @CompilationFinal(dimensions = 1) private final int[] lambdaSlots;

    private static final Object[] EMPTY_ARGS = new Object[0];

    public InlineGroupByNode(PureNode collection, PureNode[] body,
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
        if (sz == 0)
        {
            return GroupByNode.buildEmpty(getResolver());
        }
        VirtualFrame sub = Truffle.getRuntime().createVirtualFrame(EMPTY_ARGS, lambdaDescriptor);
        bindCaptures(frame, sub);
        Object[] items = new Object[sz];
        Object[] keys = new Object[sz];
        for (int i = 0; i < sz; i++)
        {
            Object item = CollectionHelper.at(col, i);
            items[i] = item;
            sub.setObject(elemSlot, item);
            Object key = null;
            for (int b = 0; b < body.length; b++)
            {
                key = body[b].executeGeneric(sub);
            }
            keys[i] = key;
        }
        return GroupByNode.buildFromKeys(items, keys, sz, getResolver());
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
