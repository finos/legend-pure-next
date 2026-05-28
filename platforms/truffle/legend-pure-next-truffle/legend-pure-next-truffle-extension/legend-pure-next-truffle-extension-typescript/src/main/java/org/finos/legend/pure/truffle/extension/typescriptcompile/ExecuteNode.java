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

package org.finos.legend.pure.truffle.extension.typescriptcompile;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.ast.natives.string.StringHelper;

/**
 * {@code execute(ctx, fnName, args, pureReturnType, pureMultiplicity, graph) : Any[*]}
 *
 * <p>Looks up the named export on a {@link TypeScriptCompilationContext}
 * (returned earlier by {@link CompileNode}) and invokes it with {@code args}.
 * Return values map back to Pure: scalars flow as-is, self-describing JS
 * objects (those carrying {@code classifierGenericType.rawType.path}) lift
 * into PDOs of that class.</p>
 *
 * <p>{@code graph} (optional) is an in-memory PDO injected for the duration
 * of this call. The translator emits uniform {@code __pureResolve(address)}
 * calls; addresses targeting the injected graph (sentinel prefix
 * {@code __local::root}) route to {@code graph} instead of the resolver.
 * Used by adapter flows that translate a canonical lambda built in memory
 * (no PDB backing). Cleaned up after invoke returns.</p>
 *
 * <p>{@code pureReturnType} / {@code pureMultiplicity} are accepted for
 * future use (shape coercion) but currently ignored.</p>
 */
@NodeInfo(shortName = "execute")
public final class ExecuteNode extends PureNode
{
    private static final String SIG = "execute_Any_1__String_1__Any_MANY__GenericType_$0_1$__Multiplicity_$0_1$__Any_$0_1$__Any_MANY_";

    @Child
    private PureNode ctxArg;
    @Child
    private PureNode fnNameArg;
    @Child
    private PureNode argsArg;
    @Child
    private PureNode pureReturnTypeArg;
    @Child
    private PureNode pureMultiplicityArg;
    @Child
    private PureNode graphArg;

    public ExecuteNode(PureNode ctxArg, PureNode fnNameArg, PureNode argsArg,
                       PureNode pureReturnTypeArg, PureNode pureMultiplicityArg,
                       PureNode graphArg)
    {
        this.ctxArg = ctxArg;
        this.fnNameArg = fnNameArg;
        this.argsArg = argsArg;
        this.pureReturnTypeArg = pureReturnTypeArg;
        this.pureMultiplicityArg = pureMultiplicityArg;
        this.graphArg = graphArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object ctxRaw   = ctxArg.executeGeneric(frame);
        String fnName   = StringHelper.asString(fnNameArg.executeGeneric(frame), SIG);
        Object argsRaw  = argsArg.executeGeneric(frame);
        pureReturnTypeArg.executeGeneric(frame);
        pureMultiplicityArg.executeGeneric(frame);
        Object graphRaw = graphArg.executeGeneric(frame);
        Object graph    = CollectionHelper.size(graphRaw) == 0 ? null : CollectionHelper.at(graphRaw, 0);
        if (!(ctxRaw instanceof TypeScriptCompilationContext compCtx))
        {
            throw new RuntimeException("execute: ctx is not a TypeScriptCompilationContext (got "
                    + (ctxRaw == null ? "null" : ctxRaw.getClass().getName()) + ")");
        }
        return doInvoke(compCtx, fnName, argsRaw, graph);
    }

    @CompilerDirectives.TruffleBoundary
    private static Object doInvoke(TypeScriptCompilationContext compCtx, String fnName, Object argsRaw, Object graph)
    {
        int sz = CollectionHelper.size(argsRaw);
        Object[] jsArgs = new Object[sz];
        for (int i = 0; i < sz; i++)
        {
            jsArgs[i] = CollectionHelper.at(argsRaw, i);
        }
        return TypeScriptCompileNatives.invoke(compCtx, fnName, jsArgs, graph);
    }
}
