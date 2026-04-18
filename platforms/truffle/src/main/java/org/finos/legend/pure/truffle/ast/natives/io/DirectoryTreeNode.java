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

package org.finos.legend.pure.truffle.ast.natives.io;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.string.StringHelper;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.List;

/**
 * {@code directoryTree(String[1]) : String[*]} — lists all file paths under a directory.
 * Delegates to the bridged native for the filesystem walk.
 */
@NodeInfo(shortName = "dirTree")
public final class DirectoryTreeNode extends PureNode
{
    private static final String SIG = "directoryTree_String_1__String_MANY_";

    @Child
    private PureNode pathArg;

    public DirectoryTreeNode(PureNode pathArg)
    {
        this.pathArg = pathArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String path = StringHelper.asString(pathArg.executeGeneric(frame), SIG);
        return walk(path);
    }

    @TruffleBoundary
    private static ValueSpecification walk(String path)
    {
        ValueSpecification pathVS = ValueAdapter.ensureVS(path);
        return EvaluatorHolder.current().natives().execute(
                SIG,
                List.of(pathVS),
                EvaluatorHolder.current(),
                null,
                null);
    }
}
