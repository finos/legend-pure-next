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

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Base class for all Pure Truffle AST nodes.
 *
 * <p>Each subclass mirrors one dispatch arm of the Java tree-walking
 * interpreter {@code ValueSpecificationEvaluator.evaluate(...)}.</p>
 *
 * <p>Node values are plain Java {@code Object}s carrying whatever the
 * original Pure interpreter uses:
 * <ul>
 *   <li>{@code Long} / {@code Double} / {@code String} / {@code Boolean}
 *       for primitives (wrapped in {@code ValueSpecification} only when
 *       crossing into the native bridge — see {@code BridgedNativeCallNode})</li>
 *   <li>{@code DynamicInstance} for user-defined object instances</li>
 *   <li>{@code Closure} for lambdas with captured scope</li>
 *   <li>{@code List<Object>} for collections (flattened like Pure semantics)</li>
 * </ul>
 * </p>
 */
@NodeInfo(language = "pure")
public abstract class PureNode extends Node
{
    /**
     * Evaluate this node in the given Truffle frame.
     */
    public abstract Object executeGeneric(VirtualFrame frame);
}
