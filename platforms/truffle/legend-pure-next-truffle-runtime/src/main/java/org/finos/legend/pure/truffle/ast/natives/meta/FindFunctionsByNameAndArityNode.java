// Copyright 2026 Goldman Sachs
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
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.math.IntegerHelper;
import org.finos.legend.pure.truffle.ast.natives.string.StringHelper;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.List;

/**
 * {@code findFunctionsByNameAndArity(name:String[1], arity:Integer[1]) : PackageableFunction<Any>[*]}.
 *
 * <p>Searches the resolver's element paths for functions whose name and
 * parameter count match.  Pure function paths encode the signature:
 * {@code pkg::name_T1_M1__T2_M2__RetT_RetM_} where {@code __} separates
 * parameter groups.  Arity = number of groups &minus; 1 (excluding the
 * return group).</p>
 */
@NodeInfo(shortName = "findFunctionsByNameAndArity")
public final class FindFunctionsByNameAndArityNode extends PureNode
{
    private static final String SIG = "findFunctionsByNameAndArity_String_1__Integer_1__PackageableFunction_MANY_";

    @Child
    private PureNode nameNode;
    @Child
    private PureNode arityNode;

    public FindFunctionsByNameAndArityNode(PureNode nameNode, PureNode arityNode)
    {
        this.nameNode = nameNode;
        this.arityNode = arityNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String name = StringHelper.asString(nameNode.executeGeneric(frame), SIG);
        long arity = IntegerHelper.asLong(arityNode.executeGeneric(frame), SIG);
        return findFunctions(name, (int) arity, getResolver());
    }

    private static Object findFunctions(String name, int arity, TruffleMetadataAccess resolver)
    {
        // O(1) lookup via the registry's (name, arity) index — built once on
        //   first access from every module's elementPaths. No fallback: this
        //   native is a compile hot path (13.5K calls / 8.5% of self-host
        //   CPU before indexing), and bypassing the index would re-introduce
        //   the linear scan.
        if (!(resolver instanceof TruffleModuleRegistry registry))
        {
            throw new IllegalStateException(
                    "findFunctionsByNameAndArity requires a TruffleModuleRegistry resolver, got "
                            + (resolver == null ? "null" : resolver.getClass().getName()));
        }
        List<Object> matches = registry.functionsByNameAndArity(name, arity);
        if (matches.isEmpty())
        {
            return PureSequence.EMPTY;
        }
        return new ObjectSequence(matches.toArray());
    }
}
