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
/**
 * Property access node -- evaluates target and optional args, then dispatches
 * via the evaluator's accessProperty() for simple properties or
 * executeFunction() for qualified properties.
 */
@NodeInfo(shortName = "propertyAccess")
public final class RawPropertyAccessNode extends PureNode
{

    private static final int SLOT_DEFAULT_VALUE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("defaultValue");
    private static final int SLOT_EXPRESSION_SEQUENCE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("expressionSequence");
    private static final int SLOT_FUNC = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("func");
    private static final int SLOT_FUNCTION_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("functionName");
    private static final int SLOT_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");
    private static final int SLOT_PROPERTIES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("properties");
    private static final int SLOT_VALUE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("value");
    private static final int SLOT_VALUES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("values");
    private final Object fe;
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

    public RawPropertyAccessNode(Object fe, PureNode[] argNodes)
    {
        this.fe = fe;
        this.argNodes = argNodes;

        // Pre-resolve property name and kind at construction time
        Object func = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_FUNC);
        this.isQualifiedProperty = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(func,
                "meta::pure::metamodel::function::property::QualifiedProperty");
        if (!isQualifiedProperty)
        {
            Object nameObj = func != null
                    ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(func, SLOT_NAME)
                    : null;
            String name = nameObj instanceof String s ? s : null;
            if (name == null)
            {
                Object fnName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_FUNCTION_NAME);
                if (fnName instanceof String s) name = s;
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
            Object func = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(fe, SLOT_FUNC);
            return getContext().executeFunction(func, argValues);
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
            // Enumeration branch — slow path, only triggered when target is
            // a Pure Enumeration. pureTypeIs is class-keyed-cached so the
            // hot non-Enumeration path avoids the lookup cost entirely.
            if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(target,
                    "meta::pure::metamodel::type::Enumeration"))
            {
                if (cachedEnumTarget == target && cachedEnumValue != null
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
                // We already gated on pureTypeIs(target, Enumeration), so
                // target is a Pure Enumeration value (typed XPDBHelper pre-flip,
                // PureDynamicObject post-flip). coerceToJavaEnum accepts
                // Object — it resolves the path generically.
                Object enumVal = getContext().coerceToJavaEnum(target, propName);
                if (enumVal != null)
                {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    cachedEnumTarget = target;
                    cachedEnumValue = enumVal;
                    cachedPropName = propName;
                    return enumVal;
                }
                // Enumeration values live in `values` (typed Enum list). Walk
                // that first — covers all enums (PDB-loaded and dynamically
                // constructed). The legacy `properties`-as-defaultValue path
                // below is for newEnumeration()-built ones that don't yet
                // populate `values`.
                Object valuesObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(target, SLOT_VALUES);
                if (valuesObj instanceof org.finos.legend.pure.truffle.types.PureSequence values)
                {
                    for (int i = 0; i < values.size(); i++)
                    {
                        Object enumValue = values.getBoxed(i);
                        if (enumValue != null && propName.equals(
                                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(enumValue, SLOT_NAME)))
                        {
                            CompilerDirectives.transferToInterpreterAndInvalidate();
                            cachedEnumTarget = target;
                            cachedEnumValue = enumValue;
                            cachedPropName = propName;
                            return enumValue;
                        }
                    }
                }
                // Runtime enum (no Java class): values live on _properties()
                // as Property instances whose default-value lambda wraps the
                // Enum. Walk the properties looking for a matching name.
                Object propsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(target, SLOT_PROPERTIES);
                if (propsObj instanceof org.finos.legend.pure.truffle.types.PureSequence properties)
                {
                    for (int i = 0; i < properties.size(); i++)
                    {
                        Object prop = properties.getBoxed(i);
                        if (prop != null && propName.equals(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(prop, SLOT_NAME)))
                        {
                            // The default-value lambda's expressionSequence[0]
                            // is an AtomicValue whose _value() is the Enum.
                            Object dv = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(prop, SLOT_DEFAULT_VALUE);
                            if (dv == null) continue;
                            Object dvSeqObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(dv, SLOT_EXPRESSION_SEQUENCE);
                            if (!(dvSeqObj instanceof org.finos.legend.pure.truffle.types.PureSequence dvSeq) || dvSeq.isEmpty())
                            {
                                continue;
                            }
                            Object expr = dvSeq.getBoxed(0);
                            if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(expr,
                                    "meta::pure::metamodel::valuespecification::AtomicValue"))
                            {
                                return org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(expr, SLOT_VALUE);
                            }
                        }
                    }
                }
                String enPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(target);
                throw new RuntimeException("No property or enum value '" + propName + "' on enumeration '"
                        + (enPath != null ? enPath : target.toString()) + "'");
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
