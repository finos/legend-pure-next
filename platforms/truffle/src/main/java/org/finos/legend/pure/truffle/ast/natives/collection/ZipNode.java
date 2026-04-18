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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.List;

/**
 * {@code zip(T[*], U[*]) : Pair<T,U>[*]} — zips two collections into pairs.
 * Delegates to the bridged native since Pair construction requires
 * DynamicInstance creation with correct classifierGenericType.
 */
@NodeInfo(shortName = "zip")
public final class ZipNode extends PureNode
{
    private static final String SIG = "zip_T_MANY__U_MANY__Pair_MANY_";

    @Child
    private PureNode leftArg;

    @Child
    private PureNode rightArg;

    public ZipNode(PureNode leftArg, PureNode rightArg)
    {
        this.leftArg = leftArg;
        this.rightArg = rightArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object left = leftArg.executeGeneric(frame);
        Object right = rightArg.executeGeneric(frame);
        return doZip(left, right);
    }

    @TruffleBoundary
    private static ValueSpecification doZip(Object left, Object right)
    {
        ValueSpecification leftVS = ValueAdapter.ensureVS(left);
        ValueSpecification rightVS = ValueAdapter.ensureVS(right);
        return EvaluatorHolder.current().natives().execute(
                SIG,
                List.of(leftVS, rightVS),
                EvaluatorHolder.current(),
                null,
                null);
    }
}
