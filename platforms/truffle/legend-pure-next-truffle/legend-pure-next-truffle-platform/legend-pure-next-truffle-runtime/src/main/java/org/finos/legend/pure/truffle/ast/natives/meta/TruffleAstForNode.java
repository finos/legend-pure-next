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

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;
import org.finos.legend.pure.truffle.PureContext;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawClosure;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;

/**
 * {@code truffleAstFor(LambdaFunction<Any>[1]) : String[1]}.
 *
 * <p>Lowers the given lambda to a Truffle {@link RootNode} (reusing the
 * standard runtime path via {@link PureContext#callTargetForLambda}) and
 * returns a focused tree dump via {@link TruffleAstPrinter}. Powers the
 * Truffle gallery, which renders each PCT lambda's AST side-by-side with
 * its Pure source.</p>
 *
 * <p>Accepts either a {@link RawClosure} (when reached at runtime through a
 * captured lambda variable) or a metamodel {@code LambdaFunction} PDO (the
 * typical gallery path, which walks the test's static expression sequence
 * and never executes those lambdas before passing them in). Throws for
 * anything else — callers are expected to upstream-filter via
 * {@code instanceOf LambdaFunction}.</p>
 */
@NodeInfo(shortName = "truffleAstFor")
public final class TruffleAstForNode extends PureNode
{
    private static final String LAMBDA_FUNCTION_PATH =
            "meta::pure::metamodel::function::LambdaFunction";

    @Child
    private PureNode lambdaArg;

    public TruffleAstForNode(PureNode lambdaArg)
    {
        this.lambdaArg = lambdaArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object lambda = lambdaArg.executeGeneric(frame);
        return dumpTree(lambda, getContext());
    }

    @CompilerDirectives.TruffleBoundary
    private static String dumpTree(Object lambdaRaw, PureContext ctx)
    {
        RootCallTarget ct;
        if (lambdaRaw instanceof RawClosure rc && rc.callTarget() != null)
        {
            ct = rc.callTarget();
        }
        else if (PureObj.pureTypeIs(lambdaRaw, LAMBDA_FUNCTION_PATH))
        {
            ct = ctx.callTargetForLambda(lambdaRaw);
        }
        else
        {
            throw new RuntimeException("truffleAstFor: expected LambdaFunction or RawClosure, got "
                    + (lambdaRaw == null ? "null" : lambdaRaw.getClass().getName()));
        }
        RootNode root = (RootNode) ct.getRootNode();
        return TruffleAstPrinter.print(root);
    }
}
