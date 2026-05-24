// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.runtime.dynobj;

import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * Value coercion for typed property setters generated on each {@code XPDBHelper}.
 * Pure-side property writes deliver values whose actual type may differ from
 * the declared field type — a single-element {@link PureSequence} for a
 * scalar slot, an {@link Enum} for an interface-typed enum field, etc. This
 * helper performs the same coercions that {@code PropertyWriteNode} used to
 * do via {@code MethodHandle}-based reflection, but in plain typed Java so
 * Graal can inline through it without dragging in {@code SignatureParser}
 * machinery.
 */
public final class PropertyCoercion
{
    private PropertyCoercion()
    {
    }

    /**
     * Coerce {@code value} to fit a setter parameter of type {@code paramType}.
     * Returns {@code null} for empty sequences (Pure {@code []}); unwraps a
     * single-element sequence to its scalar; wraps a scalar in a sequence
     * when the setter expects a {@link PureSequence}; resolves enum-name
     * strings or Pure {@code Enum} instances to the Java enum constant when
     * needed. Falls through to the original value when no rule applies —
     * the typed cast at the call site then handles class-mismatch failures.
     */
    public static Object coerce(Object value, Class<?> paramType)
    {
        // PE-inlinable fast paths: null/empty/exact-type cases handle the
        // overwhelming majority of writes once warm. Anything past
        // `paramType.isInstance(value)` falls through to a boundary'd helper
        // so the rare unwrap/wrap/enum-coercion chain doesn't blow the PE
        // budget at every call site.
        if (value == null) return null;
        if (value instanceof PureSequence ps && ps.isEmpty()) return null;
        if (paramType.isInstance(value)) return value;
        return coerceSlow(value, paramType);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object coerceSlow(Object value, Class<?> paramType)
    {
        Object unwrapped = unwrapForSetter(value);
        if (paramType.isInstance(unwrapped)) return unwrapped;

        if (PureSequence.class.isAssignableFrom(paramType))
        {
            return toPureSequence(unwrapped);
        }
        if (org.eclipse.collections.api.RichIterable.class.isAssignableFrom(paramType)
                || java.util.Collection.class.isAssignableFrom(paramType))
        {
            return toMutableList(unwrapped);
        }

        // Pure-side {@code Enum} → Java enum constant: dispatched via the
        //   {@code _name()} reflective getter (it's an interface lookup, but
        //   we can't reference the generated class type here — PropertyCoercion
        //   sits in src/main/java which compiles before the codegen phase
        //   produces the metamodel types). Match by simple-name suffix on the
        //   class hierarchy instead.
        String enumName = pureEnumName(value);
        if (enumName != null)
        {
            if (paramType.isEnum())
            {
                Object coerced = coerceToJavaEnumConstant(paramType, enumName);
                if (coerced != null) return coerced;
            }
            Object coerced = coerceStringToEnumInterface(paramType, enumName);
            if (coerced != null) return coerced;
        }
        if (value instanceof String s)
        {
            if (paramType.isEnum())
            {
                Object coerced = coerceToJavaEnumConstant(paramType, s);
                if (coerced != null) return coerced;
            }
            Object coerced = coerceStringToEnumInterface(paramType, s);
            if (coerced != null) return coerced;
        }

        return value;
    }

    /**
     * If {@code value} is a Pure-side {@code Enum} instance, return its
     * {@code _name()}. We can't reference the generated {@code Enum}
     * interface from {@code src/main/java} (it's generated in a later
     * compile phase), so we walk implemented interfaces by name and call
     * {@code _name()} reflectively — this only fires on the rare
     * {@code ^Enum(name=...)} write path.
     */
    private static String pureEnumName(Object value)
    {
        if (value == null) return null;
        for (Class<?> iface : value.getClass().getInterfaces())
        {
            if (iface.getName().equals("org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enum"))
            {
                try
                {
                    return (String) iface.getMethod("_name").invoke(value);
                }
                catch (ReflectiveOperationException ignored)
                {
                    return null;
                }
            }
        }
        return null;
    }

    private static Object unwrapForSetter(Object value)
    {
        if (value == null || (value instanceof PureSequence ps && ps.isEmpty()))
        {
            return null;
        }
        if (value instanceof PureSequence seq && seq.size() == 1)
        {
            return seq.getBoxed(0);
        }
        return value;
    }

    private static PureSequence toPureSequence(Object value)
    {
        if (value instanceof PureSequence seq) return seq;
        if (value == null)
        {
            return new ObjectSequence(new Object[0]);
        }
        return new ObjectSequence(new Object[]{value});
    }

    private static org.eclipse.collections.api.list.MutableList<Object> toMutableList(Object value)
    {
        if (value instanceof PureSequence seq)
        {
            return org.eclipse.collections.api.factory.Lists.mutable.with(seq.toBoxedArray());
        }
        if (value instanceof java.util.List<?> list)
        {
            return org.eclipse.collections.api.factory.Lists.mutable.withAll((java.util.Collection<?>) list);
        }
        if (value == null)
        {
            return org.eclipse.collections.api.factory.Lists.mutable.empty();
        }
        return org.eclipse.collections.api.factory.Lists.mutable.with(value);
    }

    private static Object coerceToJavaEnumConstant(Class<?> enumClass, String name)
    {
        if (name == null) return null;
        for (Object constant : enumClass.getEnumConstants())
        {
            if (constant instanceof java.lang.Enum<?> e && e.name().equals(name))
            {
                return constant;
            }
        }
        return null;
    }

    private static Object coerceStringToEnumInterface(Class<?> targetInterface, String name)
    {
        String valueName = name;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx >= 0) valueName = name.substring(dotIdx + 1);
        int colonIdx = valueName.lastIndexOf(':');
        if (colonIdx >= 0) valueName = valueName.substring(colonIdx + 1);
        try
        {
            Class<?> enumClass = Class.forName(targetInterface.getName() + "Enum");
            if (enumClass.isEnum())
            {
                return coerceToJavaEnumConstant(enumClass, valueName);
            }
        }
        catch (ClassNotFoundException ignored)
        {
        }
        return null;
    }
}
