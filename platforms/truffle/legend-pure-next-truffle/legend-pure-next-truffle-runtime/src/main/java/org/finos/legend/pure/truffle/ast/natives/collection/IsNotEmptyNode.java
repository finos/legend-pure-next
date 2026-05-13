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

/** Native for {@code isNotEmpty(p:Any[*]):Boolean[1]} (and the {@code [0..1]}
 *  overload). Just negates {@link CollectionHelper#isEmpty} — skips the
 *  user-function dispatch the Pure body would do for the ~8M calls per
 *  metamodel_factories compile. */
@NodeInfo(shortName = "isNotEmpty")
public final class IsNotEmptyNode extends PureNode
{
    @Child
    private PureNode arg;

    public IsNotEmptyNode(PureNode arg)
    {
        this.arg = arg;
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame)
    {
        return !CollectionHelper.isEmpty(arg.executeGeneric(frame));
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return executeBoolean(frame);
    }
}
