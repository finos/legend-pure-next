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
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.function.property.QualifiedProperty;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;

/**
 * Property access node -- evaluates target and optional args, then dispatches
 * via StandaloneEvaluator.accessProperty() for simple properties or
 * StandaloneEvaluator.executeFunction() for qualified properties.
 */
@NodeInfo(shortName = "propertyAccess")
public final class RawPropertyAccessNode extends PureNode
{
    private final FunctionExpression fe;

    @Children
    private PureNode[] argNodes;

    public RawPropertyAccessNode(FunctionExpression fe, PureNode[] argNodes)
    {
        this.fe = fe;
        this.argNodes = argNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] argValues = new Object[argNodes.length];
        for (int i = 0; i < argNodes.length; i++)
        {
            argValues[i] = argNodes[i].executeGeneric(frame);
        }
        return doAccess(fe, argValues);
    }

    private static Object doAccess(FunctionExpression fe, Object[] argValues)
    {
        meta.pure.metamodel.function.Function func;
        try
        {
            func = fe._func();
        }
        catch (RuntimeException e)
        {
            // DotApplication lazy resolution: derive property name from functionName
            String funcName = fe._functionName();
            if (funcName != null && argValues.length > 0)
            {
                return StandaloneEvaluatorHolder.current().accessProperty(argValues[0], funcName);
            }
            throw e;
        }

        if (func instanceof QualifiedProperty qp)
        {
            return StandaloneEvaluatorHolder.current().executeFunction((FunctionDefinition) qp, argValues);
        }

        // Simple property: derive property name
        String propName = null;
        if (func instanceof meta.pure.metamodel.function.property.Property prop)
        {
            propName = prop._name();
        }
        if (propName == null)
        {
            propName = fe._functionName();
        }
        if (propName == null && func instanceof meta.pure.metamodel.PackageableElement pe)
        {
            propName = pe._name();
        }
        if (propName != null && argValues.length > 0)
        {
            Object target = argValues[0];
            if (target == null || target instanceof org.finos.legend.pure.truffle.types.PureSequence _ps1 && _ps1.isEmpty())
            {
                return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
            }
            // Enum value access: look up enum value by searching the
            // Enumeration's properties for one matching propName and
            // extracting its defaultValue expression.
            if (target instanceof meta.pure.metamodel.type.Enumeration en)
            {
                if (en._properties() != null)
                {
                    String enumPropName = propName;
                    var prop = en._properties().detect(p -> enumPropName.equals(p._name()));
                    if (prop != null && prop._defaultValue() != null
                            && prop._defaultValue()._expressionSequence() != null
                            && !prop._defaultValue()._expressionSequence().isEmpty())
                    {
                        meta.pure.metamodel.valuespecification.ValueSpecification vs =
                                prop._defaultValue()._expressionSequence().getFirst();
                        if (vs instanceof meta.pure.metamodel.valuespecification.AtomicValue av)
                        {
                            Object enumVal = av._value();
                            if (enumVal != null)
                            {
                                return enumVal;
                            }
                        }
                        // If not AtomicValue, try lowering and executing the VS
                        org.finos.legend.pure.truffle.ast.PureNode node =
                                StandaloneEvaluatorHolder.current().astBuilder().lower(vs);
                        com.oracle.truffle.api.frame.VirtualFrame tmpFrame =
                                com.oracle.truffle.api.Truffle.getRuntime().createVirtualFrame(
                                        new Object[0],
                                        com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build());
                        return node.executeGeneric(tmpFrame);
                    }
                }
                // Fall through to try regular property access
            }
            return StandaloneEvaluatorHolder.current().accessProperty(target, propName);
        }

        throw new RuntimeException("Cannot access property: func=" + (func == null ? "null" : func.getClass().getName()));
    }
}
