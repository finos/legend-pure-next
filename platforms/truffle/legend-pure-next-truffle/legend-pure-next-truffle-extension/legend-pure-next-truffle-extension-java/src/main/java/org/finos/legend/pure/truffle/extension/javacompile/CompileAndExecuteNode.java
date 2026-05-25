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

package org.finos.legend.pure.truffle.extension.javacompile;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.ast.natives.string.StringHelper;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code executeJavaSource(String[1], String[1], String[1], Any[*]) : Any[*]}
 *
 * <p>Compiles a Java source string in-process via {@code javax.tools.JavaCompiler},
 * loads the resulting class through a throw-away {@code ClassLoader}, and
 * invokes a named static method on it. Used by the Pure→Java translator's
 * round-trip / PCT tests to actually run the generated Java and compare
 * against the Pure baseline.</p>
 *
 * <p>The actual javac + classload + reflective-invoke logic lives in
 * {@link JavaCompileNatives#compileAndInvoke}.</p>
 */
@NodeInfo(shortName = "compileAndExecute")
public final class CompileAndExecuteNode extends PureNode
{
    private static final String SIG = "compileAndExecute_String_1__String_1__String_1__Any_MANY__Any_MANY_";

    @Child
    private PureNode sourceArg;
    @Child
    private PureNode classNameArg;
    @Child
    private PureNode methodNameArg;
    @Child
    private PureNode argsArg;

    public CompileAndExecuteNode(PureNode sourceArg, PureNode classNameArg, PureNode methodNameArg, PureNode argsArg)
    {
        this.sourceArg = sourceArg;
        this.classNameArg = classNameArg;
        this.methodNameArg = methodNameArg;
        this.argsArg = argsArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String source     = StringHelper.asString(sourceArg.executeGeneric(frame), SIG);
        String className  = StringHelper.asString(classNameArg.executeGeneric(frame), SIG);
        String methodName = StringHelper.asString(methodNameArg.executeGeneric(frame), SIG);
        Object argsRaw    = argsArg.executeGeneric(frame);
        return doInvoke(source, className, methodName, argsRaw);
    }

    @CompilerDirectives.TruffleBoundary
    private static Object doInvoke(String source, String className, String methodName, Object argsRaw)
    {
        int sz = CollectionHelper.size(argsRaw);
        List<Object> javaArgs = new ArrayList<>(sz);
        for (int i = 0; i < sz; i++)
        {
            javaArgs.add(CollectionHelper.at(argsRaw, i));
        }
        Object result = JavaCompileNatives.compileAndInvoke(source, className, methodName, javaArgs);
        // Pure return type is Any[*]; null means empty sequence. Bare scalars
        // (Long, Double, Boolean, String) flow as-is — Truffle accepts a single
        // value for a [*] slot the same way the engine relational adapter
        // returns a single value from a `[*]`-typed function.
        return result == null ? PureSequence.EMPTY : result;
    }
}
