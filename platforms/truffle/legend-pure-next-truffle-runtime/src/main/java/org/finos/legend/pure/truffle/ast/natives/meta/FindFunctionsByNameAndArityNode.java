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
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.ArrayList;
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
        // The suffix pattern after the function name: name_T1_M1__T2_M2__...__RetT_RetM_
        // We look for paths ending with ::name_... or paths that are just name_...
        String nameSuffix = name + "_";

        List<Object> matches = new ArrayList<>();
        for (String path : resolver.elementPaths())
        {
            // Check if this path is for the requested function name
            int lastSep = path.lastIndexOf("::");
            String localPart = (lastSep >= 0) ? path.substring(lastSep + 2) : path;

            if (!localPart.startsWith(nameSuffix))
            {
                continue;
            }

            // Count arity from the signature: groups separated by __ (double underscore)
            // Format: name_T1_M1__T2_M2__RetT_RetM_
            String sigPart = localPart.substring(name.length());
            int groups = sigPart.split("__").length;
            int funcArity = groups - 1; // subtract return type group

            if (funcArity != arity)
            {
                continue;
            }

            Object element = resolver.getElement(path);
            if (element instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.PackageableFunction)
            {
                matches.add(element);
            }
        }

        if (matches.isEmpty())
        {
            return PureSequence.EMPTY;
        }
        return new ObjectSequence(matches.toArray());
    }
}
