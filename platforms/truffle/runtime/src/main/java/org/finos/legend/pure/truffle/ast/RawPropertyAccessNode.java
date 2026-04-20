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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.FunctionExpression;
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

    @TruffleBoundary
    private static Object doAccess(FunctionExpression fe, Object[] argValues)
    {
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.Function func = fe._func();
        if (func == null)
        {
            throw new RuntimeException("_func() returned null for FunctionExpression: " + fe._functionName() + " class=" + fe.getClass().getSimpleName());
        }

        if (func instanceof QualifiedProperty qp)
        {
            return StandaloneEvaluatorHolder.current().executeFunction((FunctionDefinition) qp, argValues);
        }

        // Simple property: derive property name
        String propName = null;
        if (func instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property prop)
        {
            propName = prop._name();
        }
        if (propName == null)
        {
            propName = fe._functionName();
        }
        if (propName == null && func instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe)
        {
            propName = pe._name();
        }
        if (propName != null && argValues.length > 0)
        {
            Object target = argValues[0];
            if (target == null || target instanceof org.finos.legend.pure.truffle.types.PureNull)
            {
                return org.finos.legend.pure.truffle.types.PureNull.INSTANCE;
            }
            // Enum value access: look up enum value by searching the
            // Enumeration's properties for one matching propName and
            // extracting its defaultValue expression.
            if (target instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enumeration en)
            {
                if (en._properties() != null)
                {
                    org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property foundProp = null;
                    for (Object pObj : en._properties().toBoxedArray())
                    {
                        if (pObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property p
                                && propName.equals(p._name()))
                        {
                            foundProp = p;
                            break;
                        }
                    }
                    if (foundProp != null && foundProp._defaultValue() != null
                            && foundProp._defaultValue()._expressionSequence() != null
                            && !foundProp._defaultValue()._expressionSequence().isEmpty())
                    {
                        Object vsObj = foundProp._defaultValue()._expressionSequence().getBoxed(0);
                        // Lower and execute the VS expression
                        if (vsObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.ValueSpecification vs)
                        {
                            org.finos.legend.pure.truffle.ast.PureNode node =
                                    StandaloneEvaluatorHolder.current().astBuilder().lower(vs);
                            com.oracle.truffle.api.frame.VirtualFrame tmpFrame =
                                    com.oracle.truffle.api.Truffle.getRuntime().createVirtualFrame(
                                            new Object[0],
                                            com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build());
                            return node.executeGeneric(tmpFrame);
                        }
                    }
                }
                // Fall through to try regular property access
            }
            return StandaloneEvaluatorHolder.current().accessProperty(target, propName);
        }

        throw new RuntimeException("Cannot access property: func=" + (func == null ? "null" : func.getClass().getName()));
    }
}
