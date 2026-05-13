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

package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.ast.PureNode;

/**
 * {@code instanceOf(Any[1], Type[1]) : Boolean[1]}.
 */
@NodeInfo(shortName = "instanceOf")
public final class InstanceOfNode extends PureNode
{
    @Child
    private PureNode value;

    @Child
    private PureNode typeArg;

    public InstanceOfNode(PureNode value, PureNode typeArg)
    {
        this.value = value;
        this.typeArg = typeArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object valResult = value.executeGeneric(frame);
        Object typeResult = typeArg.executeGeneric(frame);
        return doInstanceOf(valResult, typeResult, getResolver());
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean doInstanceOf(Object rawVal, Object typeResult, TruffleMetadataAccess resolver)
    {

        // Resolve the target type
        Object targetType = typeResult;

        if (rawVal == null || (rawVal instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty()))
        {
            return false;
        }
        if (targetType == null)
        {
            return true;
        }

        // Get the value's runtime type
        Object valueType = MetaHelper.getRawValueType(rawVal, resolver);
        if (valueType == null)
        {
            return false;
        }
        return org.finos.legend.pure.truffle.runtime.helper._Type.subtypeOf(valueType, targetType, resolver);
    }
}
