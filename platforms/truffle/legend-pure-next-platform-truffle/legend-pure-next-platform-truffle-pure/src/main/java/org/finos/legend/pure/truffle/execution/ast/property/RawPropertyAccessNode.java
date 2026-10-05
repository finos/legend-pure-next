// Copyright 2024 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.truffle.execution.ast.property;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.execution.ast.PureNode;
import org.finos.legend.pure.truffle.compiler.module.MetadataAccess;

/**
 * Property access node -- evaluates target and optional args, then dispatches
 * via the evaluator's accessProperty() for simple properties or
 * executeFunction() for qualified properties.
 */
@NodeInfo(shortName = "propertyAccess")
public final class RawPropertyAccessNode extends PureNode
{

    private static final int SLOT_DEFAULT_VALUE = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("defaultValue");
    private static final int SLOT_EXPRESSION_SEQUENCE = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("expressionSequence");
    private static final int SLOT_FUNC = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("func");
    private static final int SLOT_FUNCTION_NAME = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("functionName");
    private static final int SLOT_NAME = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("name");
    private static final int SLOT_PROPERTIES = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("properties");
    private static final int SLOT_VALUE = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("value");
    private static final int SLOT_VALUES = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("values");
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
        Object func = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(fe, SLOT_FUNC);
        this.isQualifiedProperty = org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(func,
                "meta::pure::metamodel::function::property::QualifiedProperty");
        if (!isQualifiedProperty)
        {
            Object nameObj = func != null
                    ? org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(func, SLOT_NAME)
                    : null;
            String name = nameObj instanceof String s ? s : null;
            if (name == null)
            {
                Object fnName = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(fe, SLOT_FUNCTION_NAME);
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
            Object func = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(fe, SLOT_FUNC);
            return getContext().executeFunction(func, argValues);
        }

        String propName = propertyName;
        if (propName != null && argValues.length > 0)
        {
            Object target = argValues[0];
            if (target == null || (target instanceof org.finos.legend.pure.truffle.execution.types.PureSequence ps && ps.isEmpty()))
            {
                return org.finos.legend.pure.truffle.execution.types.PureSequence.EMPTY;
            }
            // Enumerations have BOTH metaclass properties (_name, _package,
            // _values, ...) AND enum values (FIRST, SECOND, ...).
            // Resolution order:
            //   1. metaclass property via reader (handles _name, _package, ...)
            //   2. `values` traversal (PDB-loaded and dynamically built enums)
            //   3. `properties`-as-defaultValue traversal (newEnumeration path)
            //   4. throw — better than silently returning empty (which is what
            //      hid testIsEnum / testEqualEnum / testNewEnumeration for so long)
            // Enumeration branch — slow path, only triggered when target is
            // a Pure Enumeration. pureTypeIs is class-keyed-cached so the
            // hot non-Enumeration path avoids the lookup cost entirely.
            if (org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(target,
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
                if (viaProp != org.finos.legend.pure.truffle.execution.ast.property.PropertyReadNode.ABSENT)
                {
                    return viaProp;
                }
                // Enum-value lookup is a SLOW PATH behind a TruffleBoundary:
                // partial evaluation must NOT explode the sequence walks into
                // every inlined property-access site. (The pre-refactor code
                // got this barrier for free from the since-removed
                // coerceToJavaEnum call, which was @TruffleBoundary; when
                // that call was deleted the parser-mapping mega-functions'
                // graphs grew to whatever MaximumGraalGraphSize allowed and
                // bailed with GraphTooBig — the walks below were being
                // PE-explored at every access site.)
                Object fromValues = enumValueFromValues(target, propName);
                if (fromValues != null)
                {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    cachedEnumTarget = target;
                    cachedEnumValue = fromValues;
                    cachedPropName = propName;
                    return fromValues;
                }
                Object fromProps = enumValueFromProperties(target, propName);
                if (fromProps != org.finos.legend.pure.truffle.execution.ast.property.PropertyReadNode.ABSENT)
                {
                    return fromProps;
                }
                throw noEnumValue(target, propName, getResolver());
            }
            return reader.execute(target, propName);
        }

        throw new RuntimeException("Cannot access property: " + propName);
    }

    /**
     * Enumeration values live in `values` (typed Enum list). Walk that first
     * — covers all enums (PDB-loaded and dynamically constructed). Returns
     * null when no value matches (caller falls through to the legacy
     * `properties`-as-defaultValue path for newEnumeration()-built enums
     * that don't yet populate `values`).
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object enumValueFromValues(Object target, String propName)
    {
        Object valuesObj = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(target, SLOT_VALUES);
        if (valuesObj instanceof org.finos.legend.pure.truffle.execution.types.PureSequence values)
        {
            for (int i = 0; i < values.size(); i++)
            {
                Object enumValue = values.getBoxed(i);
                if (enumValue != null && propName.equals(
                        org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(enumValue, SLOT_NAME)))
                {
                    return enumValue;
                }
            }
        }
        return null;
    }

    /**
     * Runtime enum (no `values` populated): values live on _properties() as
     * Property instances whose default-value lambda wraps the Enum. Walk the
     * properties looking for a matching name. Returns {@link
     * org.finos.legend.pure.truffle.execution.ast.property.PropertyReadNode#ABSENT} when no
     * property matches — a matched AtomicValue may legitimately hold null,
     * so null can't double as the not-found marker.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object enumValueFromProperties(Object target, String propName)
    {
        Object propsObj = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(target, SLOT_PROPERTIES);
        if (propsObj instanceof org.finos.legend.pure.truffle.execution.types.PureSequence properties)
        {
            for (int i = 0; i < properties.size(); i++)
            {
                Object prop = properties.getBoxed(i);
                if (prop != null && propName.equals(org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(prop, SLOT_NAME)))
                {
                    // The default-value lambda's expressionSequence[0]
                    // is an AtomicValue whose _value() is the Enum.
                    Object dv = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(prop, SLOT_DEFAULT_VALUE);
                    if (dv == null) continue;
                    Object dvSeqObj = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(dv, SLOT_EXPRESSION_SEQUENCE);
                    if (!(dvSeqObj instanceof org.finos.legend.pure.truffle.execution.types.PureSequence dvSeq) || dvSeq.isEmpty())
                    {
                        continue;
                    }
                    Object expr = dvSeq.getBoxed(0);
                    if (org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(expr,
                            "meta::pure::metamodel::valuespecification::AtomicValue"))
                    {
                        return org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(expr, SLOT_VALUE);
                    }
                }
            }
        }
        return org.finos.legend.pure.truffle.execution.ast.property.PropertyReadNode.ABSENT;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static RuntimeException noEnumValue(Object target, String propName,
            MetadataAccess resolver)
    {
        String enPath = org.finos.legend.pure.truffle.compiler.helper._PackageableElement.path(target, resolver);
        return new RuntimeException("No property or enum value '" + propName + "' on enumeration '"
                + (enPath != null ? enPath : target.toString()) + "'");
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
