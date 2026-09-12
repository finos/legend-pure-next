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
import org.finos.legend.pure.truffle.runtime.module.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code toVariant(Any[*]) : Variant[1]} -- converts a Pure value to its
 * Variant (JSON) representation. Empty → null, one value → its JSON form,
 * many values → a JSON array.
 */
@NodeInfo(shortName = "toVariant")
public final class ToVariantNode extends PureNode
{
    @Child
    private PureNode valueArg;

    public ToVariantNode(PureNode valueArg)
    {
        this.valueArg = valueArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object value = valueArg.executeGeneric(frame);
        return doToVariant(value, getResolver());
    }

    @TruffleBoundary
    private static Object doToVariant(Object value, TruffleMetadataAccess resolver)
    {
        JsonValue json;
        if (value instanceof PureSequence seq)
        {
            if (seq.isEmpty())
            {
                json = JsonValue.JsonNull.INSTANCE;
            }
            else if (seq.size() == 1)
            {
                json = VariantHelper.valueToJson(seq.getBoxed(0), resolver);
            }
            else
            {
                List<JsonValue> elements = new ArrayList<>(seq.size());
                for (int i = 0; i < seq.size(); i++)
                {
                    elements.add(VariantHelper.valueToJson(seq.getBoxed(i), resolver));
                }
                json = new JsonValue.JsonArray(elements);
            }
        }
        else
        {
            json = VariantHelper.valueToJson(value, resolver);
        }
        return new PureVariant(json);
    }
}
