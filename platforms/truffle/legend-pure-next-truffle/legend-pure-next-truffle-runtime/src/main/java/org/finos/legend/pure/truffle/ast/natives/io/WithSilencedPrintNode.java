// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.ast.natives.io;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;

/**
 * {@code withSilencedPrint(closure : Function<{->T[m]}>[1]) : T[m]}
 *
 * <p>Push {@link PrintNode#SILENCED} for the duration of the closure, restore
 * the prior value in finally. Test runners wrap each test body in this so
 * Pure {@code print} calls inside tests don't pollute stdout while the
 * runner's own progress prints (called outside the test body) remain
 * visible.</p>
 */
@NodeInfo(shortName = "withSilencedPrint")
public final class WithSilencedPrintNode extends PureNode
{
    @Child
    private PureNode closureArg;

    @Child
    private RawLambdaCallNode call = new RawLambdaCallNode();

    public WithSilencedPrintNode(PureNode closureArg)
    {
        this.closureArg = closureArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object closure = closureArg.executeGeneric(frame);
        return doRun(closure);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object doRun(Object closure)
    {
        Boolean prev = PrintNode.SILENCED.get();
        PrintNode.SILENCED.set(Boolean.TRUE);
        try
        {
            return call.callWithArgs(closure, new Object[0]);
        }
        finally
        {
            PrintNode.SILENCED.set(prev);
        }
    }
}
