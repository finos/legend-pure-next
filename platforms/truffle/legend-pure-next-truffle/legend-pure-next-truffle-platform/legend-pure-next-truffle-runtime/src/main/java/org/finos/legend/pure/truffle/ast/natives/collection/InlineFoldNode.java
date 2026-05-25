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

/**
 * {@code fold} specialization with the lambda body inlined as a
 * {@code @Child}. The body executes against a fresh sub-frame built from
 * the lambda's own {@link FrameDescriptor} — Truffle PE virtualizes the
 * sub-frame when it doesn't escape, so the slot writes become Java
 * locals in the JIT'd hot loop.
 *
 * <p>No CallTarget, no Object[] args alloc, no per-iter
 * {@code OptimizedCallTarget.callBoundary}. The body's
 * {@code FrameVariableReadNode}s use the lambda's own slot indices, so
 * we reuse the body as already lowered for the lambda's normal CallTarget
 * path — no re-lowering, no slot-override stack.</p>
 */
@NodeInfo(shortName = "inlineFold")
public final class InlineFoldNode extends PureNode
{
    @Child private PureNode collection;
    @Child private PureNode seed;
    @Children private final PureNode[] body;

    @CompilationFinal private final FrameDescriptor lambdaDescriptor;
    @CompilationFinal private final int elemSlot;
    @CompilationFinal private final int accSlot;

    /** Lambda open-var slot (in lambda's descriptor) paired with the
     *  caller-frame slot it sources from. Bound once before the loop. */
    @CompilationFinal(dimensions = 1) private final int[] callerSlots;
    @CompilationFinal(dimensions = 1) private final int[] lambdaSlots;

    private static final Object[] EMPTY_ARGS = new Object[0];

    public InlineFoldNode(PureNode collection,
                          PureNode seed,
                          PureNode[] body,
                          FrameDescriptor lambdaDescriptor,
                          int elemSlot,
                          int accSlot,
                          int[] callerSlots,
                          int[] lambdaSlots)
    {
        this.collection = collection;
        this.seed = seed;
        this.body = body;
        this.lambdaDescriptor = lambdaDescriptor;
        this.elemSlot = elemSlot;
        this.accSlot = accSlot;
        this.callerSlots = callerSlots;
        this.lambdaSlots = lambdaSlots;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collection.executeGeneric(frame);
        int sz = CollectionHelper.size(col);
        Object acc = seed.executeGeneric(frame);
        if (sz == 0)
        {
            return acc;
        }
        VirtualFrame sub = Truffle.getRuntime().createVirtualFrame(EMPTY_ARGS, lambdaDescriptor);
        bindCaptures(frame, sub);
        for (int i = 0; i < sz; i++)
        {
            sub.setObject(elemSlot, CollectionHelper.at(col, i));
            sub.setObject(accSlot, acc);
            Object result = null;
            for (int b = 0; b < body.length; b++)
            {
                result = body[b].executeGeneric(sub);
            }
            acc = result;
        }
        return acc;
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
