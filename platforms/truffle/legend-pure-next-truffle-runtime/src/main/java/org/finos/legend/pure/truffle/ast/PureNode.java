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

package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Base class for all Pure Truffle AST nodes.
 *
 * <p>Each node carries an optional {@link SourceSection} derived from the
 * PDB's {@code SourceInformation}. Truffle uses this to build guest-language
 * stack traces automatically when exceptions propagate.</p>
 */
@NodeInfo(language = "pure")
public abstract class PureNode extends Node
{
    @CompilationFinal
    private SourceSection pureSourceSection;

    /**
     * Set the Pure source location for this node. Called by the AST builder
     * after node creation, before the node is adopted.
     */
    public void setPureSourceSection(SourceSection section)
    {
        this.pureSourceSection = section;
    }

    @Override
    public SourceSection getSourceSection()
    {
        return pureSourceSection;
    }

    /**
     * Evaluate this node in the given Truffle frame.
     */
    public abstract Object executeGeneric(VirtualFrame frame);

    /**
     * Access the evaluator via the Truffle language context.
     */
    protected final org.finos.legend.pure.truffle.StandaloneEvaluator getEvaluator()
    {
        return org.finos.legend.pure.truffle.PureLanguage.get(this).getEvaluator();
    }

    /**
     * Access the metadata resolver from this node.
     */
    protected final org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess getResolver()
    {
        return getEvaluator().resolver();
    }
}
