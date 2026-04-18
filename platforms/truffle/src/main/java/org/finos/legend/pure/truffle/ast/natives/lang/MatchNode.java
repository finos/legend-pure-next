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

package org.finos.legend.pure.truffle.ast.natives.lang;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code match(Any[*], Function<{...}>[1..*]) : T[m]} and the two-parameter
 * variant with an extra parameter {@code P[o]}.
 *
 * <p>Evaluates all children, then delegates to a {@link TruffleBoundary} method
 * that performs type-based dispatch against the match function collection. The
 * matching logic mirrors {@code LangNatives.matchesBranch} and accesses the
 * resolver for subtype checks.</p>
 */
@NodeInfo(shortName = "match")
public final class MatchNode extends PureNode
{
    @Children
    private PureNode[] children;

    public MatchNode(PureNode[] children)
    {
        this.children = children;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] values = new Object[children.length];
        for (int i = 0; i < children.length; i++)
        {
            values[i] = children[i].executeGeneric(frame);
        }
        return invokeMatch(values);
    }

    @TruffleBoundary
    private static Object invokeMatch(Object[] values)
    {
        // values[0] = value to match
        // values[1] = match functions collection
        // values[2] = optional extra parameter (P[o])
        ValueSpecification valueVS = ValueAdapter.ensureVS(values[0]);
        ValueSpecification matchFnsVS = ValueAdapter.ensureVS(values[1]);

        org.finos.legend.pure.execution.ValueSpecificationEvaluator eval = EvaluatorHolder.current();
        org.finos.legend.pure.m3.module.MetadataAccess resolver = eval.natives().resolver();

        meta.pure.metamodel.type.Type valueType =
                org.finos.legend.pure.execution._E_ValueSpecification.getValueOriginalType(valueVS, resolver);
        int valueCount = getValueCount(org.finos.legend.pure.execution._E_ValueSpecification.unwrap(valueVS));

        meta.pure.metamodel.valuespecification.Collection matchFuncCol =
                org.finos.legend.pure.execution._E_ValueSpecification.toCollection(matchFnsVS, resolver);

        for (ValueSpecification mfVS : matchFuncCol._values())
        {
            Object mf = org.finos.legend.pure.execution._E_ValueSpecification.unwrap(mfVS);
            if (!matchesBranch(mf, valueType, valueCount, resolver))
            {
                continue;
            }
            List<ValueSpecification> args = new ArrayList<>(2);
            args.add(valueVS);
            if (values.length > 2 && mf instanceof meta.pure.metamodel.function.FunctionDefinition fd
                    && fd._parameters() != null && fd._parameters().size() >= 2)
            {
                args.add(ValueAdapter.ensureVS(values[2]));
            }
            return eval.executeFunction(mfVS, args);
        }
        throw new RuntimeException("No match function matched the value: "
                + org.finos.legend.pure.execution._E_ValueSpecification.unwrap(valueVS));
    }

    private static int getValueCount(Object value)
    {
        if (value == null)
        {
            return 0;
        }
        if (value instanceof java.util.List<?> list)
        {
            return list.size();
        }
        return 1;
    }

    private static boolean matchesBranch(Object mf,
                                         meta.pure.metamodel.type.Type valueType,
                                         int valueCount,
                                         org.finos.legend.pure.m3.module.MetadataAccess resolver)
    {
        if (!(mf instanceof meta.pure.metamodel.function.FunctionDefinition fd)
                || fd._parameters() == null || fd._parameters().isEmpty())
        {
            return true;
        }

        meta.pure.metamodel.valuespecification.VariableExpression param = fd._parameters().getFirst();

        if (param._genericType() != null)
        {
            meta.pure.metamodel.type.Type paramType =
                    org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(param._genericType());
            if (paramType != null
                    && !org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type.subtypeOf(valueType, paramType, resolver))
            {
                return false;
            }
        }

        if (param._multiplicity() != null)
        {
            if (!multiplicityAccepts(param._multiplicity(), valueCount))
            {
                return false;
            }
        }

        return true;
    }

    private static boolean multiplicityAccepts(meta.pure.metamodel.multiplicity.Multiplicity mult, int count)
    {
        if (!(mult instanceof meta.pure.metamodel.multiplicity.ConcreteMultiplicity cm))
        {
            return true;
        }

        long lower = 0;
        if (cm._lowerBound() != null)
        {
            lower = cm._lowerBound()._value();
        }

        long upper = -1;
        if (cm._upperBound() != null)
        {
            upper = cm._upperBound()._value();
        }

        if (count < lower)
        {
            return false;
        }
        if (upper != -1 && count > upper)
        {
            return false;
        }
        return true;
    }
}
