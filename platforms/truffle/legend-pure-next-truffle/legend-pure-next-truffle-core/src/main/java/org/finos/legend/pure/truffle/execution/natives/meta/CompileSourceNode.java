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

package org.finos.legend.pure.truffle.execution.natives.meta;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.execution.PureContext;
import org.finos.legend.pure.truffle.execution.ast.PureNode;
import org.finos.legend.pure.truffle.execution.types.PureSequence;
import org.finos.legend.pure.truffle.execution.TruffleInstanceFactory;
import org.finos.legend.pure.truffle.compiler.helper._Any;

import java.util.ArrayList;
import java.util.List;

/**
 * compileSource(file: PureFile[1], dependencies: String[*]): CompileSourceResult[1]
 *
 * <p>THE dynamic-evaluation primitive: compiles an already-parsed PureFile by
 * dispatching into the INTERPRETED Pure compiler ({@code meta::pure::compiler::
 * compile} from compiler.pdb — the same Pure code the JavaScript platform runs
 * translated), and repackages the {@code CompilationResult} into a
 * {@code CompileSourceResult} VALUE. Deliberately mutates no global state: the
 * module registry is immutable after boot, the compiled elements are valid
 * only as the returned values of this call, and compile diagnostics are DATA
 * on the result (a failed compile is a normal outcome), not exceptions.</p>
 *
 * <p>{@code dependencies} names boot-registered modules (the in-process
 * equivalent of the CLI's --pdb flags); an unknown name is a caller bug and
 * throws — the message is part of the native's cross-platform contract,
 * pinned by the PCT tests.</p>
 */
@NodeInfo(shortName = "compileSource")
public final class CompileSourceNode extends PureNode
{
    private static final String COMPILE_FN = "meta::pure::compiler::compile_PureFile_MANY__CompilationResult_1_";
    private static final String RESULT_CLASS = "meta::pure::functions::meta::CompileSourceResult";

    @Child
    private PureNode fileNode;
    @Child
    private PureNode dependenciesNode;

    public CompileSourceNode(PureNode fileNode, PureNode dependenciesNode)
    {
        this.fileNode = fileNode;
        this.dependenciesNode = dependenciesNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object file = fileNode.executeGeneric(frame);
        Object dependencies = dependenciesNode.executeGeneric(frame);
        return doCompile(getContext(), file, dependencies);
    }

    @CompilerDirectives.TruffleBoundary
    private static Object doCompile(PureContext ctx, Object file, Object dependencies)
    {
        for (Object dep : asList(dependencies))
        {
            String name = String.valueOf(dep);
            if (ctx.modules().module(name) == null)
            {
                // Contract message — pinned by testCompileSourceUnknownDependencyFails.
                throw new RuntimeException("compileSource: dependency module '" + name + "' is not loaded");
            }
        }
        Object compileFn = ctx.modules().getElement(COMPILE_FN);
        if (compileFn == null)
        {
            throw new RuntimeException("compileSource: the compiler module is not loaded (need " + COMPILE_FN + ")");
        }

        Object elements;
        Object errors;
        try
        {
            Object result = ctx.executeFunction(compileFn, new Object[]{file});
            elements = _Any.read(result, "elements");
            errors = _Any.read(result, "errors");
        }
        catch (RuntimeException e)
        {
            // Compile diagnostics are DATA, not exceptions: surface the failure
            // on the result, mirroring the compiler's own error channel.
            elements = PureSequence.EMPTY;
            errors = e.getMessage() == null ? e.toString() : e.getMessage();
        }

        Object out = TruffleInstanceFactory.createInstance(RESULT_CLASS, ctx.modules());
        if (out == null)
        {
            throw new RuntimeException("compileSource: cannot instantiate " + RESULT_CLASS);
        }
        boolean failed = !asList(errors).isEmpty();
        _Any.write(out, "elements", failed ? PureSequence.EMPTY : elements);
        _Any.write(out, "errors", errors);
        return out;
    }

    private static List<Object> asList(Object v)
    {
        List<Object> out = new ArrayList<>();
        if (v == null)
        {
            return out;
        }
        if (v instanceof PureSequence seq)
        {
            out.addAll(seq.toList());
            return out;
        }
        if (v instanceof java.util.List<?> list)
        {
            out.addAll(list);
            return out;
        }
        if (v instanceof Object[] arr)
        {
            java.util.Collections.addAll(out, arr);
            return out;
        }
        out.add(v);
        return out;
    }
}
