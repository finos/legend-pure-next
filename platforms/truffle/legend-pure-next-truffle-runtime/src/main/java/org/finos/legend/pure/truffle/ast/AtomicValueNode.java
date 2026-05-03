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
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Returns a compile-time constant value. The raw payload is extracted
 * from the IR's {@code AtomicValue._value()} at lower time by
 * {@code PureASTBuilder.lowerAtomicValue} — this node never touches
 * {@code ValueSpecification} types.
 *
 * <p>For non-primitive payloads (lambdas, metamodel objects, strings,
 * BigDecimal, sequences) this class is used directly. For primitives the
 * builder picks one of the typed subclasses ({@link LongConstantNode},
 * {@link DoubleConstantNode}, {@link BooleanConstantNode}) so consumers
 * calling the typed entry points (e.g. {@code executeLong}) skip the box /
 * unbox pair.</p>
 *
 * <p>Subclasses still expose the boxed value through {@link #value()} so
 * AST-time inspection (e.g. constant-folding helpers in
 * {@code MetaNodeFactories}) continues to work without an instanceof
 * cascade.</p>
 */
@NodeInfo(shortName = "atomic")
public class AtomicValueNode extends PureNode
{
    @CompilationFinal
    private final Object value;

    public AtomicValueNode(Object value)
    {
        this.value = value;
    }

    public Object value() { return value; }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return value;
    }
}
