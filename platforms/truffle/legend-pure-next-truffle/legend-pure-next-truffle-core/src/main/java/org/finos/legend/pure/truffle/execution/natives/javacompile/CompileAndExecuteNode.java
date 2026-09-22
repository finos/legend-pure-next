// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.execution.natives.javacompile;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.execution.ast.PureNode;
import org.finos.legend.pure.truffle.execution.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.execution.natives.string.StringHelper;
import org.finos.legend.pure.truffle.execution.types.PureSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code executeJavaSource(String[1], String[1], String[1], Any[*]) : Any[*]}
 *
 * <p>Compiles a Java source string in-process via {@code javax.tools.JavaCompiler},
 * loads the resulting class through a throw-away {@code ClassLoader}, and
 * invokes a named static method on it. Used by the Pure→Java translator's
 * round-trip / PCT tests to actually run the generated Java and compare
 * against the Pure baseline.</p>
 *
 * <p>The actual javac + classload + reflective-invoke logic lives in
 * {@link JavaCompileNatives#compileAndInvoke}.</p>
 */
@NodeInfo(shortName = "compileAndExecute")
public final class CompileAndExecuteNode extends PureNode
{
    private static final String SIG = "compileAndExecute_String_1__String_1__String_1__Any_MANY__Any_MANY_";
    private static final int SLOT_VALUES = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("values");
    private static final int SLOT_NAME = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("name");
    private static final int SLOT_PROPERTIES = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("properties");
    private static final int SLOT_DEFAULT_VALUE = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("defaultValue");
    private static final int SLOT_EXPRESSION_SEQUENCE = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("expressionSequence");
    private static final int SLOT_VALUE = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("value");

    @Child
    private PureNode sourceArg;
    @Child
    private PureNode classNameArg;
    @Child
    private PureNode methodNameArg;
    @Child
    private PureNode argsArg;

    public CompileAndExecuteNode(PureNode sourceArg, PureNode classNameArg, PureNode methodNameArg, PureNode argsArg)
    {
        this.sourceArg = sourceArg;
        this.classNameArg = classNameArg;
        this.methodNameArg = methodNameArg;
        this.argsArg = argsArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String source     = StringHelper.asString(sourceArg.executeGeneric(frame), SIG);
        String className  = StringHelper.asString(classNameArg.executeGeneric(frame), SIG);
        String methodName = StringHelper.asString(methodNameArg.executeGeneric(frame), SIG);
        Object argsRaw    = argsArg.executeGeneric(frame);
        return doInvoke(source, className, methodName, argsRaw, getContext(), getResolver());
    }

    @CompilerDirectives.TruffleBoundary
    private static Object doInvoke(String source, String className, String methodName, Object argsRaw,
                                   org.finos.legend.pure.truffle.execution.PureContext context,
                                   org.finos.legend.pure.truffle.compiler.module.MetadataAccess resolver)
    {
        int sz = CollectionHelper.size(argsRaw);
        List<Object> javaArgs = new ArrayList<>(sz);
        for (int i = 0; i < sz; i++)
        {
            javaArgs.add(CollectionHelper.at(argsRaw, i));
        }
        Object result = JavaCompileNatives.compileAndInvoke(source, className, methodName, javaArgs);
        return toPureValue(result, context, resolver);
    }

    /**
     * Map the invoked method's return value onto Pure's {@code Any[*]} result.
     *
     * <p>Null means the empty sequence, and bare scalars (Long, Double, Boolean,
     * String) flow as-is — Truffle accepts a single value for a [*] slot the same
     * way the engine relational adapter returns a single value from a
     * {@code [*]}-typed function.</p>
     *
     * <p>A returned {@link List} must become a {@link PureSequence}. Left as a raw
     * java.util.List it prints exactly like the expected collection yet never
     * compares equal to it: EqualNode handles PureSequence-vs-PureSequence and
     * List-vs-List, but not a Pure literal against a Java list. That failed every
     * translated take/drop/init/reverse on a non-empty input, and made an empty
     * ArrayList unequal to {@code []} because only null mapped to EMPTY.</p>
     *
     * <p>Only the top level is unwrapped from List. An element may be a Pure
     * {@code List<T>} class instance rather than a nested multiplicity, so it
     * keeps its value; scalar elements still go through {@link #toPureScalar}.</p>
     */
    private static Object toPureValue(Object result, org.finos.legend.pure.truffle.execution.PureContext context,
                                      org.finos.legend.pure.truffle.compiler.module.MetadataAccess resolver)
    {
        if (result == null)
        {
            return PureSequence.EMPTY;
        }
        if (result instanceof List<?> list)
        {
            if (list.isEmpty())
            {
                return PureSequence.EMPTY;
            }
            Object[] values = new Object[list.size()];
            for (int i = 0; i < values.length; i++)
            {
                values[i] = toPureScalar(list.get(i), context, resolver);
            }
            return new org.finos.legend.pure.truffle.execution.types.ObjectSequence(values);
        }
        return toPureScalar(result, context, resolver);
    }

    /**
     * The translator maps Pure dates onto java.time; map them back to
     * {@link org.finos.legend.pure.truffle.execution.types.PureDate}. Like the
     * List case above, a raw LocalDate prints like the expected date but never
     * compares equal to one, because PureDate equality is on {@code dateString}.
     *
     * <p>The string is built by hand to match Pure's literal form rather than
     * java.time's: no {@code +} before a year past 9999, and a DateTime always
     * carries seconds ({@code LocalDateTime.toString()} drops {@code :00}).
     * Fractional seconds keep only their significant digits, so a precision
     * written with trailing zeros ({@code .4990000}) can't be recovered here.</p>
     */
    private static Object toPureScalar(Object value, org.finos.legend.pure.truffle.execution.PureContext context,
                                       org.finos.legend.pure.truffle.compiler.module.MetadataAccess resolver)
    {
        if (value instanceof java.time.LocalDateTime dateTime)
        {
            return org.finos.legend.pure.truffle.execution.types.PureDate.of(
                    pureDateString(dateTime.toLocalDate()) + "T" + pureTimeString(dateTime), "DateTime");
        }
        if (value instanceof java.time.LocalDate date)
        {
            return org.finos.legend.pure.truffle.execution.types.PureDate.of(pureDateString(date), "StrictDate");
        }
        // A Pure enum value is a constant of a generated Java enum whose
        // _purePath() names the Enumeration. Checked before the helper-object
        // case below, which would otherwise try to instantiate the enumeration.
        if (value instanceof Enum<?> javaEnum)
        {
            Object pureEnum = pureEnumValue(generatedPurePath(value), javaEnum.name(), resolver);
            if (pureEnum != null)
            {
                return pureEnum;
            }
        }
        // The translator represents a Pure Pair as Map.Entry and a user-class
        // instance as a generated helper object. Like Lists and dates, neither
        // compares equal to the Pure object it stands for, so rebuild one — the
        // same way ZipNode builds its Pairs. Anything that can't be rebuilt is
        // returned untouched.
        if (value instanceof java.util.Map.Entry<?, ?> entry)
        {
            Object pair = newPureInstance("meta::pure::functions::collection::Pair", context, resolver);
            if (pair != null)
            {
                org.finos.legend.pure.truffle.compiler.helper._Any.write(pair, "first", toPureValue(entry.getKey(), context, resolver));
                org.finos.legend.pure.truffle.compiler.helper._Any.write(pair, "second", toPureValue(entry.getValue(), context, resolver));
                return pair;
            }
        }
        String purePath = generatedPurePath(value);
        // The generated Variant class prints its compact JSON; parse it into
        // the runtime's Variant, as fromJson would.
        if (org.finos.legend.pure.execution.PureVariant.TYPE_PATH.equals(purePath))
        {
            return new org.finos.legend.pure.execution.PureVariant(org.finos.legend.pure.execution.JsonValue.parse(value.toString()));
        }
        // The generated PureDate prints its Pure text and carries its kind
        // (StrictDate, DateTime, or Date for a partial date).
        if ("meta::pure::metamodel::type::primitives::Date".equals(purePath))
        {
            try
            {
                java.lang.reflect.Field kind = value.getClass().getField("kind");
                kind.setAccessible(true);
                return org.finos.legend.pure.truffle.execution.types.PureDate.of(value.toString(), String.valueOf(kind.get(value)));
            }
            catch (ReflectiveOperationException e)
            {
                return value;
            }
        }
        if (purePath != null)
        {
            Object instance = newPureInstance(purePath, context, resolver);
            if (instance != null)
            {
                copyFields(value, instance, context, resolver);
                return instance;
            }
        }
        return value;
    }

    /**
     * The Pure class path a translator-generated helper object reports from
     * {@code _purePath()}, or null for anything else. The helper classes are
     * package-private, hence setAccessible.
     */
    private static String generatedPurePath(Object value)
    {
        if (value == null)
        {
            return null;
        }
        try
        {
            java.lang.reflect.Method method = value.getClass().getMethod("_purePath");
            method.setAccessible(true);
            return method.invoke(value) instanceof String path && !path.isEmpty() ? path : null;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    /**
     * The Pure enum value named {@code name} in the Enumeration at
     * {@code enumerationPath}, or null. Same lookup as an enum literal
     * (RawPropertyAccessNode): the enumeration's {@code values}, else — for
     * enumerations loaded from a PDB, where {@code values} is empty — its
     * {@code properties}, whose default-value lambda holds the enum value as an
     * AtomicValue. A constant whose Pure name is a Java reserved word was
     * emitted with a trailing underscore, so that is tried too.
     */
    private static Object pureEnumValue(String enumerationPath, String name,
                                        org.finos.legend.pure.truffle.compiler.module.MetadataAccess resolver)
    {
        if (enumerationPath == null)
        {
            return null;
        }
        Object enumeration = resolver.getElement(enumerationPath);
        if (enumeration == null)
        {
            return null;
        }
        String unmangled = name.endsWith("_") ? name.substring(0, name.length() - 1) : name;
        if (org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(enumeration, SLOT_VALUES) instanceof PureSequence values)
        {
            for (int i = 0; i < values.size(); i++)
            {
                Object candidate = values.getBoxed(i);
                Object candidateName = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(candidate, SLOT_NAME);
                if (name.equals(candidateName) || unmangled.equals(candidateName))
                {
                    return candidate;
                }
            }
        }
        if (org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(enumeration, SLOT_PROPERTIES) instanceof PureSequence properties)
        {
            for (int i = 0; i < properties.size(); i++)
            {
                Object property = properties.getBoxed(i);
                Object propertyName = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(property, SLOT_NAME);
                if (!name.equals(propertyName) && !unmangled.equals(propertyName))
                {
                    continue;
                }
                Object defaultValue = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(property, SLOT_DEFAULT_VALUE);
                Object expressions = defaultValue == null ? null : org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(defaultValue, SLOT_EXPRESSION_SEQUENCE);
                if (expressions instanceof PureSequence sequence && !sequence.isEmpty())
                {
                    return org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(sequence.getBoxed(0), SLOT_VALUE);
                }
            }
        }
        return null;
    }

    /**
     * Write a platform object's properties onto the Pure instance, converting
     * each value. Platform objects (the Java platform's LazyObject) list the
     * properties they hold with {@code _propertyNames()} and return a property's
     * values with {@code __values(name)} (two underscores, so a Pure property named `values` cannot shadow it); a single value is written as itself.
     */
    private static void copyFields(Object helper, Object instance, org.finos.legend.pure.truffle.execution.PureContext context,
                                   org.finos.legend.pure.truffle.compiler.module.MetadataAccess resolver)
    {
        try
        {
            java.lang.reflect.Method names = helper.getClass().getMethod("_propertyNames");
            java.lang.reflect.Method values = helper.getClass().getMethod("__values", String.class);
            for (Object name : (java.util.Set<?>) names.invoke(helper))
            {
                List<?> propertyValues = (List<?>) values.invoke(helper, name);
                if (propertyValues.isEmpty())
                {
                    continue;
                }
                try
                {
                    Object value = propertyValues.size() == 1 ? propertyValues.get(0) : propertyValues;
                    org.finos.legend.pure.truffle.compiler.helper._Any.write(instance, (String) name, toPureValue(value, context, resolver));
                }
                catch (RuntimeException e)
                {
                    // A property the Pure class doesn't declare: leave it out.
                }
            }
        }
        catch (ReflectiveOperationException e)
        {
            // Not a platform object: nothing to copy.
        }
    }

    /** A fresh Pure instance of {@code classPath} with its classifierGenericType set, or null if it can't be made. */
    private static Object newPureInstance(String classPath, org.finos.legend.pure.truffle.execution.PureContext context,
                                          org.finos.legend.pure.truffle.compiler.module.MetadataAccess resolver)
    {
        try
        {
            Object instance = org.finos.legend.pure.truffle.execution.TruffleInstanceFactory.createInstance(classPath, resolver);
            Object cgt = context.cgtForType(classPath);
            if (cgt != null)
            {
                org.finos.legend.pure.truffle.compiler.helper._Any.write(instance, "classifierGenericType", cgt);
            }
            return instance;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static String pureDateString(java.time.LocalDate date)
    {
        return String.format("%04d-%02d-%02d", date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    private static String pureTimeString(java.time.LocalDateTime dateTime)
    {
        String hms = String.format("%02d:%02d:%02d", dateTime.getHour(), dateTime.getMinute(), dateTime.getSecond());
        int nanos = dateTime.getNano();
        return nanos == 0 ? hms : hms + "." + String.format("%09d", nanos).replaceAll("0+$", "");
    }
}
