// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.extension.javascriptcompile;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.interpreter.ast.PureNode;
import org.finos.legend.pure.truffle.interpreter.ast.natives.string.StringHelper;

/**
 * {@code compile(source:String[1]) : Any[1]}
 *
 * <p>Evaluates the (plain JavaScript) source as a CommonJS module — once. The
 * returned handle is a {@link JavaScriptCompilationContext} wrapping the
 * module's {@code exports}. The handle flows back through Pure as an opaque
 * {@code Any[1]} and feeds {@link ExecuteNode} on the other side.</p>
 */
@NodeInfo(shortName = "compile")
public final class CompileNode extends PureNode
{
    private static final String SIG = "compile_String_1__Any_1_";

    @Child
    private PureNode sourceArg;

    public CompileNode(PureNode sourceArg)
    {
        this.sourceArg = sourceArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String source = StringHelper.asString(sourceArg.executeGeneric(frame), SIG);
        return doCompile(source);
    }

    @CompilerDirectives.TruffleBoundary
    private static Object doCompile(String source)
    {
        return JavaScriptCompileNatives.compile(source);
    }
}
