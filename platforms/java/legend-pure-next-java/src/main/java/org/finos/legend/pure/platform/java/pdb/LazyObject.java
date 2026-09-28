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

package org.finos.legend.pure.platform.java.pdb;

import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.FbsNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base of the generated implementations of Pure classes. One object model for
 * both kinds of instance:
 * <ul>
 *   <li>read from a PDB: backed by a FlatBuffer node, each property decoded on
 *   first access and cached. {@code parent} is the object whose property holds
 *   this one, which is what an AncestorRef (a back-reference up the write
 *   stack) resolves against;</li>
 *   <li>built at run time ({@code ^X(...)}): no node, properties are the values
 *   set through the generated {@code _p(v)} / {@code _p_add(v)} setters.</li>
 * </ul>
 * A value set on a node-backed object overrides the stored one, so a copy of a
 * PDB element ({@link #copyInto}) keeps reading its other properties lazily.
 */
public abstract class LazyObject
{
    PdbRuntime runtime;
    FbsNode node;
    /** The element path this object was read from, when it is a top-level element. */
    String elementPath;
    LazyObject parent;
    // Insertion order: the order properties were set (or first read).
    private final Map<String, List<Object>> values = new LinkedHashMap<>();

    /** The Pure class path of this object. */
    public abstract String _purePath();

    /**
     * Every value of a property, decoded lazily (empty when absent).
     *
     * <p>Two underscores, because one would put this in the same namespace as
     * the generated setters (`_&lt;property&gt;(value)`): a Pure class with a
     * property named `values` — {@code List} has one — generates
     * {@code _values(Object)}, and {@code ^List(values='ok')} would then bind
     * to this reader instead, which is applicable and more specific for a
     * String. The property was silently never set.</p>
     */
    public final List<Object> __values(String property)
    {
        List<Object> cached = values.get(property);
        if (cached == null)
        {
            cached = node == null ? Collections.emptyList() : runtime.read(this, property);
            values.put(property, cached);
        }
        return cached;
    }

    /** The properties this object holds values for: set, or already decoded from its PDB node. */
    public final java.util.Set<String> _propertyNames()
    {
        return Collections.unmodifiableSet(values.keySet());
    }

    /** Set a property: a List is its values, null none, anything else the single value. */
    protected final void set(String property, Object value)
    {
        if ("classifierGenericType".equals(property))
        {
            Metadata.checkClassifier(this, value instanceof List && !((List<?>) value).isEmpty() ? ((List<?>) value).get(0) : value);
        }
        // Shared, not copied: Pure values are immutable, and a copy of a list
        // here means a copy of every byte buffer a node is built over.
        values.put(property, value == null ? Collections.emptyList()
                : value instanceof List<?> list ? Collections.unmodifiableList(list)
                : Collections.singletonList(value));
    }

    /** Append to a property ({@code p += v}): the current values, then {@code value}'s. */
    protected final void add(String property, Object value)
    {
        List<Object> all = new ArrayList<>(__values(property));
        if (value instanceof List<?> list)
        {
            all.addAll(list);
        }
        else if (value != null)
        {
            all.add(value);
        }
        values.put(property, Collections.unmodifiableList(all));
    }

    /** A shallow copy into {@code target}: same backing node, same values so far. */
    protected final <T extends LazyObject> T copyInto(T target)
    {
        LazyObject copy = target;
        copy.runtime = runtime;
        copy.node = node;
        copy.parent = parent;
        copy.values.putAll(values);
        return target;
    }

    protected final Object one(String property)
    {
        List<Object> all = __values(property);
        return all.isEmpty() ? null : all.get(0);
    }

    @SuppressWarnings("unchecked")
    protected final <T> List<T> many(String property)
    {
        return (List<T>) (List<?>) __values(property);
    }

    /**
     * The pointer-access contract (feature_pointerAccess.pure): a
     * {@code TempCompilerPointer} carries only the path of the element it stands
     * in for, so reading a slot it merely inherits from that element means a
     * consumer read through a pointer the resolution pass should have replaced.
     * The unset slot would come back empty and read like a real answer, so the
     * read throws instead — as it does on every other host (Truffle's
     * {@code PureDynamicObject.isPointerNativeSlot}, the JavaScript runtime's
     * {@code __guardPointer}, bootstrap's {@code PointerAccessGuard}).
     *
     * <p>Only the generated getters of a pointer class call this — the translator
     * emits the call for that package alone — so property reads on the ~4000
     * other generated classes pay nothing. The runtime's own raw reads
     * ({@link #__values}, and {@code PointerGraph} and {@code Metadata} through
     * it) bypass it entirely, which is what lets a pointer be resolved at all.</p>
     */
    protected final void checkPointerAccess(String property)
    {
        // `path` is a pointer's whole content and `element` the property name a
        // PropertyPointer adds, while classifierGenericType and generalizations
        // are what every dispatch (match, genericType, subtypeOf) reads on every
        // value, a pointer included. The same list Truffle and JavaScript keep;
        // holding it here rather than in the translator keeps one copy of it.
        if ("path".equals(property) || "element".equals(property)
                || "classifierGenericType".equals(property) || "generalizations".equals(property))
        {
            return;
        }
        throw new IllegalStateException("Unresolved pointer access: " + _purePath() + "." + property
                + " — pointer reached a non-pointer-native slot read. compile() should resolve every pointer"
                + " via resolveAndReturnGraph before returning; check whether a producer skipped the boundary");
    }

    @Override
    public String toString()
    {
        Object name = one("name");
        return "<" + (name == null ? "" : name + " ") + "instanceOf " + _purePath() + ">";
    }
}
