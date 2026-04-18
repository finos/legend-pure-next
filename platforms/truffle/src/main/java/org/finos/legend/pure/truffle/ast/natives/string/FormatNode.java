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
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.execution.natives.string.StringNatives;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.List;

/**
 * {@code format(String[1], Any[*]) : String[1]} — printf-style formatting
 * with Pure format specifiers (%s, %d, %r, %f, %t, etc.).
 *
 * <p>Delegates to the bridged StringNatives implementation via
 * {@code NativeRepository.execute} since the format logic is complex and
 * involves DynamicInstance inspection, date formatting, and type checking.</p>
 */
@NodeInfo(shortName = "format")
public final class FormatNode extends PureNode
{
    private static final String SIG = "format_String_1__Any_MANY__String_1_";

    @Child
    private PureNode formatArg;

    @Child
    private PureNode argsArg;

    @CompilationFinal
    private final GenericType genericType;

    @CompilationFinal
    private final Multiplicity multiplicity;

    public FormatNode(PureNode formatArg, PureNode argsArg, GenericType genericType, Multiplicity multiplicity)
    {
        this.formatArg = formatArg;
        this.argsArg = argsArg;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object fmt = formatArg.executeGeneric(frame);
        Object args = argsArg.executeGeneric(frame);
        return doFormat(fmt, args);
    }

    @TruffleBoundary
    private ValueSpecification doFormat(Object fmt, Object args)
    {
        ValueSpecification fmtVS = ValueAdapter.ensureVS(fmt);
        ValueSpecification argsVS = ValueAdapter.ensureVS(args);
        return EvaluatorHolder.current().natives().execute(
                SIG,
                List.of(fmtVS, argsVS),
                EvaluatorHolder.current(),
                genericType,
                multiplicity);
    }
}
