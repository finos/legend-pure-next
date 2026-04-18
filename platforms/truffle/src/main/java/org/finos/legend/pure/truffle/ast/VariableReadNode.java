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

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;

import java.util.Map;

/**
 * Reads a variable by name from the evaluator's current scope.
 *
 * <p>Phase B uses the existing {@code ValueSpecificationEvaluator.varStack}
 * via the thread-local {@link EvaluatorHolder} so the whole reflective
 * scope machinery is reused. Phase C replaces this with Truffle FrameSlots
 * where the variable binding is statically known at AST-build time.</p>
 */
@NodeInfo(shortName = "varRead")
public final class VariableReadNode extends PureNode
{
    @CompilationFinal
    private final String name;

    public VariableReadNode(String name)
    {
        this.name = name;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Map<String, ValueSpecification> scope = EvaluatorHolder.current().currentScope();
        ValueSpecification v = scope.get(name);
        if (v == null)
        {
            CompilerDirectives.transferToInterpreter();
            throw new RuntimeException("Unknown variable: " + name);
        }
        return v;
    }
}
