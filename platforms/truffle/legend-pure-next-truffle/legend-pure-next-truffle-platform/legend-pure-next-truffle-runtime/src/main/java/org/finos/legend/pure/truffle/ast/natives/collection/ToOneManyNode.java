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

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;

/**
 * {@code toOneMany(T[*]) : T[1..*]} — asserts at least one element,
 * returns the single element or the collection as-is.
 */
@NodeInfo(shortName = "toOneMany")
public final class ToOneManyNode extends PureNode
{
    @Child
    private PureNode arg;

    public ToOneManyNode(PureNode arg)
    {
        this.arg = arg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = arg.executeGeneric(frame);
        int size = CollectionHelper.size(v);
        if (size == 0)
        {
            CompilerDirectives.transferToInterpreter();
            throw new RuntimeException("toOneMany expected at least 1 element, got 0");
        }
        if (size == 1)
        {
            return CollectionHelper.at(v, 0);
        }
        return v;
    }
}
