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

package org.finos.legend.pure.truffle.execution.natives.variant;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.execution.JsonValue;
import org.finos.legend.pure.execution.PureVariant;
import org.finos.legend.pure.truffle.execution.ast.PureNode;
import org.finos.legend.pure.truffle.compiler.module.MetadataAccess;
import org.finos.legend.pure.truffle.execution.types.ObjectSequence;
import org.finos.legend.pure.truffle.execution.types.PureSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code toMany(Variant[0..1], GenericTypeAndMultiplicityHolder<T|?>[1]) : T[*]}
 * -- coerces an array variant's elements to the target type T.
 */
@NodeInfo(shortName = "variantToMany")
public final class ToManyNode extends PureNode
{
    @Child
    private PureNode variantArg;

    @Child
    private PureNode typeArg;

    public ToManyNode(PureNode variantArg, PureNode typeArg)
    {
        this.variantArg = variantArg;
        this.typeArg = typeArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object variant = variantArg.executeGeneric(frame);
        Object holder = typeArg.executeGeneric(frame);
        return doToMany(variant, holder, getResolver());
    }

    @TruffleBoundary
    private static Object doToMany(Object variant, Object holder, MetadataAccess resolver)
    {
        PureVariant pureVariant = (PureVariant) VariantHelper.scalarOf(variant);
        if (pureVariant == null)
        {
            return PureSequence.EMPTY;
        }
        JsonValue json = pureVariant.getValue();
        if (!(json instanceof JsonValue.JsonArray array))
        {
            throw new RuntimeException("Expect variant that contains an 'ARRAY', but got '" + json.typeName() + "'");
        }
        Object targetGT = VariantHelper.targetGenericType(holder);
        List<Object> results = new ArrayList<>(array.values().size());
        for (JsonValue element : array.values())
        {
            Object result = VariantHelper.jsonToPure(element, targetGT, resolver);
            if (result != null)
            {
                results.add(result);
            }
        }
        return new ObjectSequence(results.toArray());
    }
}
