// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.truffle.execution.natives.lang;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.compiler.helper._Any;
import org.finos.legend.pure.truffle.execution.PropertyAccessor;
import org.finos.legend.pure.truffle.execution.PureDynamicObject;
import org.finos.legend.pure.truffle.execution.ast.PureNode;
import org.finos.legend.pure.truffle.execution.types.PureSequence;

/**
 * rawEvalProperty(property, object): what the object holds for the property — the values it was given (or
 * decoded from its PDB), empty when it holds none. A plain read computes the property's default for an
 * unset slot; this never does. See lang/eval/rawEvalProperty.pure.
 */
@NodeInfo(shortName = "rawEvalProperty")
public final class RawEvalPropertyNode extends PureNode
{
    @Child
    private PureNode property;
    @Child
    private PureNode object;

    public RawEvalPropertyNode(PureNode[] args)
    {
        this.property = args[0];
        this.object = args[1];
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return stored(property.executeGeneric(frame), object.executeGeneric(frame));
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object stored(Object property, Object object)
    {
        Object name = _Any.read(property, "name");
        if (!(name instanceof String n))
        {
            return PureSequence.EMPTY;
        }
        Object value = object instanceof PureDynamicObject pdo
                ? pdo.readProperty(n)
                : object instanceof PropertyAccessor pa ? pa.readProperty(n) : null;
        return value == null || value == PropertyAccessor.ABSENT ? PureSequence.EMPTY : value;
    }
}
