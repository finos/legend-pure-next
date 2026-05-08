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
import com.oracle.truffle.api.nodes.ExplodeLoop;
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
    private PropertyReadNode reader;

    // Cached enum value — monomorphic cache by (Enumeration identity, propName)
    @CompilationFinal
    private Object cachedEnumTarget;

    @CompilationFinal
    private Object cachedEnumValue;

    @CompilationFinal
    private String cachedPropName;

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
        // Bind the property name into the reader at construction so PE
        // baked-in constant-folds the per-class readProperty(name) switch
        // to the direct typed accessor (e.g. _rawType()) — equivalent to
        // a field load after PE. QPs have no static name, so an unbound
        // reader handles their dynamic-name case.
        this.reader = (this.propertyName != null)
                ? new PropertyReadNode(this.propertyName)
                : new PropertyReadNode();
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] argValues = evaluateArgs(frame);

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
            // Enumerations have BOTH metaclass properties (_name, _package,
            // _values, ...) AND enum values (FIRST, SECOND, ...).
            // Resolution order:
            //   1. metaclass property via reader (handles _name, _package, ...)
            //   2. Java enum constant (handles declared enums, fast path)
            //   3. runtime _values() traversal (handles enums made via newEnumeration)
            //   4. throw — better than silently returning empty (which is what
            //      hid testIsEnum / testEqualEnum / testNewEnumeration for so long)
            if (target instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enumeration en)
            {
                if (cachedEnumTarget == en && cachedEnumValue != null
                        && cachedPropName != null && cachedPropName.equals(propName))
                {
                    return cachedEnumValue;
                }
                // Use executeOrAbsent so we can tell "metaclass property
                // exists and is empty" (return as-is) from "property doesn't
                // exist on this enum" (fall through to enum-value lookup).
                Object viaProp = reader.executeOrAbsent(target, propName);
                if (viaProp != org.finos.legend.pure.truffle.ast.PropertyReadNode.ABSENT)
                {
                    return viaProp;
                }
                Object enumVal = getContext().coerceToJavaEnum(en, propName);
                if (enumVal != null)
                {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    cachedEnumTarget = en;
                    cachedEnumValue = enumVal;
                    cachedPropName = propName;
                    return enumVal;
                }
                // Runtime enum (no Java class): values live on _properties()
                // as Property instances whose default-value lambda wraps the
                // Enum. Walk the properties looking for a matching name.
                org.finos.legend.pure.truffle.types.PureSequence properties = en._properties();
                if (properties != null)
                {
                    for (int i = 0; i < properties.size(); i++)
                    {
                        Object p = properties.getBoxed(i);
                        if (p instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property prop
                                && propName.equals(prop._name()))
                        {
                            // The default-value lambda's expressionSequence[0]
                            // is an AtomicValue whose _value() is the Enum.
                            var dv = prop._defaultValue();
                            if (dv != null && dv._expressionSequence() != null && !dv._expressionSequence().isEmpty())
                            {
                                Object expr = dv._expressionSequence().getBoxed(0);
                                if (expr instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue av)
                                {
                                    return av._value();
                                }
                            }
                        }
                    }
                }
                throw new RuntimeException("No property or enum value '" + propName + "' on enumeration '"
                        + (en instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement enpe
                                ? org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(enpe)
                                : en.toString())
                        + "'");
            }
            return reader.execute(target, propName);
        }

        throw new RuntimeException("Cannot access property: " + propName);
    }

    /**
     * Evaluate child argument nodes. Pulled into its own method so {@link
     * ExplodeLoop} can fully unroll the {@code @Children} iteration —
     * argNodes.length is compilation-final, but only when the loop is in
     * a method whose only loop has a known bound.
     */
    @ExplodeLoop
    private Object[] evaluateArgs(VirtualFrame frame)
    {
        Object[] argValues = new Object[argNodes.length];
        for (int i = 0; i < argNodes.length; i++)
        {
            argValues[i] = argNodes[i].executeGeneric(frame);
        }
        return argValues;
    }
}
