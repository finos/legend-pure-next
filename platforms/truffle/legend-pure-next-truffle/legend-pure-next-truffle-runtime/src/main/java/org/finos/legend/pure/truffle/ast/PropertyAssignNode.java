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

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

/**
 * Encapsulates a single property assignment: evaluates a value expression
 * and writes the result to a named property on a target object.
 *
 * <p>Used as {@code @Children} in NewWithKeysNode and CopyWithKeysNode,
 * replacing the previous parallel arrays of property names, value
 * expressions, and writers.</p>
 */
public final class PropertyAssignNode extends Node
{
    @CompilationFinal
    private final String propertyName;

    @CompilationFinal
    private final boolean isAdd;

    /** Global slot for {@link #propertyName} — final so PE folds the array
     *  offset. {@code -1} for dotted-path names that can't be slot-resolved. */
    private final int boundSlot;

    @Child
    private PureNode valueExpr;

    @Child
    private PropertyWriteNode writer = new PropertyWriteNode();

    @Child
    private PropertyReadNode reader;

    public PropertyAssignNode(String propertyName, PureNode valueExpr, boolean isAdd)
    {
        this.propertyName = propertyName;
        this.valueExpr = valueExpr;
        this.isAdd = isAdd;
        this.reader = isAdd ? new PropertyReadNode(propertyName) : null;
        this.boundSlot = propertyName.contains(".")
                ? -1
                : org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot(propertyName);
    }

    public String propertyName()
    {
        return propertyName;
    }

    public boolean isAdd()
    {
        return isAdd;
    }

    public Object execute(VirtualFrame frame, Object target)
    {
        return executeWithValue(valueExpr.executeGeneric(frame), target);
    }

    /** Evaluate just the right-hand-side value; no read/merge/write. */
    public Object evaluateValue(VirtualFrame frame)
    {
        return valueExpr.executeGeneric(frame);
    }

    /**
     * Apply a pre-evaluated value to {@code target}. Used by callers that
     * evaluated the RHS in PE-friendly code and now want to do the read /
     * merge / write across a {@code @TruffleBoundary} (so the heavy
     * reflective writer doesn't expand into PE).
     */
    /** Non-deopting per-classInfo cache for the property's coercion target.
     *  Plain fields (no {@code @CompilationFinal}) — a miss is a regular
     *  branch + HashMap.get, NOT a {@code transferToInterpreterAndInvalidate}.
     *  Polymorphic receivers (e.g. {@code ^$fi(...)} where $fi is statically
     *  FunctionApplication but concretely FunctionInvocation/DotApplication)
     *  update the cache fields without driving the call site into Graal's
     *  deopt-cycle threshold — eliminating the permanent compile failures
     *  that pinned tier-2 compilation on the dominant Pure compiler hot
     *  lambdas. */
    private org.finos.legend.pure.truffle.runtime.dynobj.PureClassInfo cachedClassInfo;
    private Class<?> cachedPropType;

    public Object executeWithValue(Object value, Object target)
    {
        if (isAdd)
        {
            Object existing = reader.execute(target, propertyName);
            java.util.List<Object> merged = new java.util.ArrayList<>();
            addToMergedList(merged, existing);
            addToMergedList(merged, value);
            value = new org.finos.legend.pure.truffle.types.ObjectSequence(merged.toArray());
        }
        if (boundSlot < 0)
        {
            return value;
        }
        if (target instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdo
                && boundSlot < pdo.slots.length)
        {
            var info = pdo.classInfo;
            Class<?> propType = cachedPropType;
            if (info != cachedClassInfo)
            {
                propType = info.propTypes().get(propertyName);
                cachedClassInfo = info;
                cachedPropType = propType;
            }
            Object coerced = propType != null
                    ? org.finos.legend.pure.truffle.runtime.dynobj.PropertyCoercion.coerce(value, propType)
                    : value;
            pdo.slots[boundSlot] = coerced;
            return value;
        }
        writer.execute(target, propertyName, value);
        return value;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void addToMergedList(java.util.List<Object> list, Object value)
    {
        if (value == null || (value instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty()))
        {
            return;
        }
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence ps)
        {
            for (int i = 0; i < ps.size(); i++)
            {
                list.add(ps.getBoxed(i));
            }
        }
        else
        {
            list.add(value);
        }
    }
}
