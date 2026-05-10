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

package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.Node;
import org.finos.legend.pure.truffle.runtime.PropertyAccessor;

/**
 * Pure {@code $obj.foo = val} property dispatch. All generated mutable
 * metamodel classes ({@code XImpl}) implement
 * {@link PropertyAccessor#writeProperty} — a single virtual call into a
 * generated {@code switch} on the property name that does typed
 * coercion-and-set per arm. Replaces the previous
 * {@link java.lang.invoke.MethodHandle}-based lookup, which ran the JDK's
 * {@code SignatureParser} machinery on every set and contributed the bulk
 * of remaining "Too deep inlining" Graal failures (782 inlined frames per
 * failed compilation traced to {@code parseZeroOrMoreFormalTypeParameters}).
 *
 * <p>Uses a per-call-site {@link CompilationFinal} class cache so the
 * monomorphic case (the receiver shape stays stable at one
 * {@code ^X(prop=val)} site) lets Graal devirtualize {@code writeProperty}
 * to the cached class's switch and fold {@code propName} into a single
 * typed setter call.</p>
 */
public final class PropertyWriteNode extends Node
{
    @CompilationFinal
    private Class<?> cachedClass;

    public void execute(Object target, String propName, Object value)
    {
        // Pre-filter the enum-CGT backfill: only Java enum constants ever
        // need this work. The boundary path used to fire on every property
        // write, including the overwhelming majority where {@code value} is
        // a String/PureSequence/other Pure object — paying a boundary
        // entry/exit per write across pass-2 object construction. Java
        // {@code Enum} is a single instanceof check that PE evaluates to
        // a constant for known non-enum types, folding the boundary
        // call away entirely.
        if (value instanceof Enum<?>)
        {
            ensureEnumCGT(value);
        }
        Class<?> tc = target.getClass();
        if (tc == cachedClass)
        {
            ((PropertyAccessor) target).writeProperty(propName, value);
            return;
        }
        if (cachedClass == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cachedClass = tc;
        }
        ((PropertyAccessor) target).writeProperty(propName, value);
    }

    /**
     * Backfill {@code classifierGenericType} on Java enum constants when
     * they're assigned to a Pure property — a Java {@code Enum} produced
     * outside the Pure compiler (e.g. via a native that returns one of
     * the codegen'd {@code XEnum} constants) doesn't carry its CGT and
     * downstream type-equality checks rely on it.
     */
    @CompilerDirectives.TruffleBoundary
    private void ensureEnumCGT(Object value)
    {
        if (value != null
                && value.getClass().isEnum()
                && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(value, "classifierGenericType") == null)
        {
            Class<?>[] ifaces = value.getClass().getInterfaces();
            if (ifaces.length > 0)
            {
                String purePath = ifaces[0].getName()
                        .replace("org.finos.legend.pure.truffle.pdb.", "")
                        .replace(".", "::");
                var resolver = org.finos.legend.pure.truffle.PureLanguage.get(this).resolver();
                if (resolver != null)
                {
                    Object enumType = resolver.getElement(purePath);
                    if (enumType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t)
                    {
                        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(value, "classifierGenericType",
                                org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(t, resolver));
                    }
                }
            }
        }
    }
}
