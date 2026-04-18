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
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@NodeInfo(shortName = "split")
public final class SplitNode extends PureNode
{
    private static final String SIG = "split_String_1__String_1__String_MANY_";

    @Child
    private PureNode strArg;

    @Child
    private PureNode delimArg;

    @CompilationFinal
    private final GenericType genericType;

    @CompilationFinal
    private final Multiplicity multiplicity;

    public SplitNode(PureNode strArg, PureNode delimArg, GenericType genericType, Multiplicity multiplicity)
    {
        this.strArg = strArg;
        this.delimArg = delimArg;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String s = StringHelper.asString(strArg.executeGeneric(frame), SIG);
        String delimiter = StringHelper.asString(delimArg.executeGeneric(frame), SIG);
        return split(s, delimiter);
    }

    @TruffleBoundary
    private ValueSpecification split(String s, String delimiter)
    {
        org.finos.legend.pure.m3.module.MetadataAccess resolver = EvaluatorHolder.current().natives().resolver();
        String[] parts = s.split(Pattern.quote(delimiter), -1);
        List<ValueSpecification> result = new ArrayList<>(parts.length);
        for (String part : parts)
        {
            result.add(_E_ValueSpecification.wrap(part, genericType, null, resolver));
        }
        int size = parts.length;
        GenericType gt = result.isEmpty() ? null : result.get(0)._genericType();
        return new meta.pure.metamodel.valuespecification.CollectionImpl(resolver)
                ._values(org.eclipse.collections.api.factory.Lists.mutable.withAll(result))
                ._genericType(gt)
                ._multiplicity(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity.concreteMultiplicity(size, size, resolver));
    }
}
