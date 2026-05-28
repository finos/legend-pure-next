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

/**
 * {@code drainCompiledSources() : String[*]}
 *
 * <p>Returns (and clears) the buffer of TS module sources captured by
 * {@link TypeScriptCompileNatives#compile} since the last drain. The
 * translation gallery calls this right after running a test through the PCT
 * adapter so it can display the EXACT source that was compiled + executed,
 * keeping the displayed TypeScript 100% in sync with what the PCT run ran.</p>
 */
@NodeInfo(shortName = "drainCompiledSources")
public final class DrainSourcesNode extends PureNode
{
    public DrainSourcesNode()
    {
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return doDrain();
    }

    @CompilerDirectives.TruffleBoundary
    private static Object doDrain()
    {
        return TypeScriptCompileNatives.drainCapturedSources();
    }
}
