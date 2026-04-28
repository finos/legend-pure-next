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
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.FunctionExpression;
/**
 * Property access node -- evaluates target and optional args, then dispatches
 * via the evaluator's accessProperty() for simple properties or
 * executeFunction() for qualified properties.
 */
@NodeInfo(shortName = "propertyAccess")
public final class RawPropertyAccessNode extends PureNode
{
    private final FunctionExpression fe;
    private final boolean isQualifiedProperty;
    private final String propertyName;

    @Children
    private PureNode[] argNodes;

    @Child
    private PropertyReadNode reader = new PropertyReadNode();

    // Cached enum value — monomorphic cache by Enumeration identity
    @CompilationFinal
    private Object cachedEnumTarget;

    @CompilationFinal
    private Object cachedEnumValue;

    public RawPropertyAccessNode(FunctionExpression fe, PureNode[] argNodes)
    {
        this.fe = fe;
        this.argNodes = argNodes;

        // Pre-resolve property name and kind at construction time
        var func = fe._func();
        this.isQualifiedProperty = func instanceof QualifiedProperty;
        if (!isQualifiedProperty)
        {
            String name = null;
            if (func instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property prop)
            {
                name = prop._name();
            }
            if (name == null) name = fe._functionName();
            if (name == null && func instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe)
            {
                name = pe._name();
            }
            this.propertyName = name;
        }
        else
        {
            this.propertyName = null;
        }
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] argValues = new Object[argNodes.length];
        for (int i = 0; i < argNodes.length; i++)
        {
            argValues[i] = argNodes[i].executeGeneric(frame);
        }

        if (isQualifiedProperty)
        {
            return getContext().executeFunction((FunctionDefinition) fe._func(), argValues);
        }

        String propName = propertyName;
        if (propName != null && argValues.length > 0)
        {
            Object target = argValues[0];
            if (target == null || (target instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty()))
            {
                return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
            }
            // Enum value access — cached per Enumeration identity
            if (target instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enumeration en)
            {
                if (cachedEnumTarget == en && cachedEnumValue != null)
                {
                    return cachedEnumValue;
                }
                Object enumVal = getContext().coerceToJavaEnum(en, propName);
                if (enumVal != null)
                {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    cachedEnumTarget = en;
                    cachedEnumValue = enumVal;
                    return enumVal;
                }
                // Fall through to regular property access
            }
            return reader.execute(target, propName);
        }

        throw new RuntimeException("Cannot access property: " + propName);
    }
}
