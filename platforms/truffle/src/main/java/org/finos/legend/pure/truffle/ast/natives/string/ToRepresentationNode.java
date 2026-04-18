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

package org.finos.legend.pure.truffle.ast.natives.string;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.List;

/**
 * {@code toRepresentation(Any[1]) : String[1]} — returns the Pure-syntax
 * representation of a value (with quoting for strings, % prefix for dates, etc.).
 *
 * <p>Delegates to the bridged StringNatives implementation since the logic
 * involves DynamicInstance inspection and type-based formatting.</p>
 */
@NodeInfo(shortName = "toRepr")
public final class ToRepresentationNode extends PureNode
{
    private static final String SIG = "toRepresentation_Any_1__String_1_";

    @Child
    private PureNode arg;

    @CompilationFinal
    private final GenericType genericType;

    @CompilationFinal
    private final Multiplicity multiplicity;

    public ToRepresentationNode(PureNode arg, GenericType genericType, Multiplicity multiplicity)
    {
        this.arg = arg;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = arg.executeGeneric(frame);
        return convert(v);
    }

    @TruffleBoundary
    private ValueSpecification convert(Object v)
    {
        ValueSpecification vs = ValueAdapter.ensureVS(v);
        return EvaluatorHolder.current().natives().execute(
                SIG,
                List.of(vs),
                EvaluatorHolder.current(),
                genericType,
                multiplicity);
    }
}
