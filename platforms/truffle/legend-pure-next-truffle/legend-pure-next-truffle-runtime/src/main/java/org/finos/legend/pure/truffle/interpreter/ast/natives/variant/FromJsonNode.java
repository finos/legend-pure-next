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

package org.finos.legend.pure.truffle.interpreter.ast.natives.variant;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.execution.JsonValue;
import org.finos.legend.pure.execution.PureVariant;
import org.finos.legend.pure.truffle.interpreter.ast.PureNode;

/**
 * {@code fromJson(String[1]) : Variant[1]} -- parses a JSON string into a Variant.
 */
@NodeInfo(shortName = "fromJson")
public final class FromJsonNode extends PureNode
{
    @Child
    private PureNode jsonArg;

    public FromJsonNode(PureNode jsonArg)
    {
        this.jsonArg = jsonArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object json = jsonArg.executeGeneric(frame);
        return doParse(json);
    }

    @TruffleBoundary
    private static Object doParse(Object json)
    {
        return new PureVariant(JsonValue.parse((String) VariantHelper.scalarOf(json)));
    }
}
