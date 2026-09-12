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
import org.finos.legend.pure.execution.PureVariant;
import org.finos.legend.pure.truffle.interpreter.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.module.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * {@code to(Variant[0..1], GenericTypeAndMultiplicityHolder<T|?>[1]) : T[0..1]}
 * -- coerces the variant's JSON value to the target type T (read from the
 * holder at runtime, same pattern as CastNode).
 */
@NodeInfo(shortName = "variantTo")
public final class ToNode extends PureNode
{
    @Child
    private PureNode variantArg;

    @Child
    private PureNode typeArg;

    public ToNode(PureNode variantArg, PureNode typeArg)
    {
        this.variantArg = variantArg;
        this.typeArg = typeArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object variant = variantArg.executeGeneric(frame);
        Object holder = typeArg.executeGeneric(frame);
        return doTo(variant, holder, getResolver());
    }

    @TruffleBoundary
    private static Object doTo(Object variant, Object holder, TruffleMetadataAccess resolver)
    {
        PureVariant pureVariant = (PureVariant) VariantHelper.scalarOf(variant);
        if (pureVariant == null)
        {
            return PureSequence.EMPTY;
        }
        Object targetGT = VariantHelper.targetGenericType(holder);
        Object result = VariantHelper.jsonToPure(pureVariant.getValue(), targetGT, resolver);
        return result != null ? result : PureSequence.EMPTY;
    }
}
