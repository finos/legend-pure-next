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
 * {@code compileAndExecute(source:String[1], fnName:String[1], args:Any[*]) : Any[*]}
 *
 * <p>Transpiles the TypeScript source via the bundled {@code typescript.js}
 * compiler (loaded into a long-lived GraalJS {@link org.graalvm.polyglot.Context}),
 * evaluates the resulting JavaScript, and invokes a named top-level function
 * exported by that script.</p>
 *
 * <p>Return-value mapping follows the same {@code Any[*]} widening pattern as
 * the Java {@code compileAndExecute} native: scalars flow as-is, {@code null}
 * / undefined map to the empty Pure sequence. Pure's polymorphic slot-widening
 * adapts the {@code Any[*]} return into the caller's concrete return type at
 * the call site.</p>
 *
 * <p>The transpile + execute logic lives in {@link TypeScriptCompileNatives}.</p>
 */
@NodeInfo(shortName = "compileAndExecute")
public final class CompileAndExecuteNode extends PureNode
{
    private static final String SIG = "compileAndExecute_String_1__String_1__Any_MANY__Any_MANY_";

    @Child
    private PureNode sourceArg;
    @Child
    private PureNode fnNameArg;
    @Child
    private PureNode argsArg;

    public CompileAndExecuteNode(PureNode sourceArg, PureNode fnNameArg, PureNode argsArg)
    {
        this.sourceArg = sourceArg;
        this.fnNameArg = fnNameArg;
        this.argsArg = argsArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String source  = StringHelper.asString(sourceArg.executeGeneric(frame), SIG);
        String fnName  = StringHelper.asString(fnNameArg.executeGeneric(frame), SIG);
        Object argsRaw = argsArg.executeGeneric(frame);
        return doInvoke(source, fnName, argsRaw);
    }

    @CompilerDirectives.TruffleBoundary
    private static Object doInvoke(String source, String fnName, Object argsRaw)
    {
        int sz = CollectionHelper.size(argsRaw);
        Object[] jsArgs = new Object[sz];
        for (int i = 0; i < sz; i++)
        {
            jsArgs[i] = CollectionHelper.at(argsRaw, i);
        }
        return TypeScriptCompileNatives.compileAndInvoke(source, fnName, jsArgs);
    }
}
