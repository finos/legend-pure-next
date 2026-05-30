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
 * {@code genericType(Any[*]) : GenericTypeValue[1]}.
 * Returns the GenericType of the value as a raw object.
 */
@NodeInfo(shortName = "genericType")
public final class GenericTypeNode extends PureNode
{
    @Child
    private PureNode child;

    private final Object genericType;
    private final Object multiplicity;

    public GenericTypeNode(PureNode child, Object genericType, Object multiplicity)
    {
        this.child = child;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object result = child.executeGeneric(frame);
        return doGenericType(result, getResolver());
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object doGenericType(Object result, TruffleMetadataAccess resolver)
    {
        Object gt = MetaHelper.getRawGenericType(result, resolver);
        return gt != null ? gt : result;
    }
}
