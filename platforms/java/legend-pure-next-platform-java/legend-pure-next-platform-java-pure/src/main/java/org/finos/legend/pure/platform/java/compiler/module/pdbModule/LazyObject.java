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

package org.finos.legend.pure.platform.java.compiler.module.pdbModule;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.platform.pure.compiler.pdb.reader.FbsNode;

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
    /** The default values computed for this object's unset properties, kept once read (not carried by a copy). */
    private Map<String, List<Object>> defaults;

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
        List<Object> cached = frozen(property);
        if (cached == null)
        {
            cached = node == null ? Lists.fixedSize.empty() : runtime.read(this, property);
            if (cached.isEmpty())
            {
                // Never set: its default value, if the class gives it one, computed for this object on its first
                // read and kept, so every read is the same collection (is.pure). Kept apart from what was set: a
                // copy holds only that, and computes its own (testCopyKeepsDefaultValues).
                List<Object> computed = defaults == null ? null : defaults.get(property);
                if (computed == null)
                {
                    computed = Metadata.defaultValue(this, property);
                    if (computed != null)
                    {
                        if (defaults == null)
                        {
                            defaults = new java.util.HashMap<>();
                        }
                        defaults.put(property, computed);
                    }
                }
                if (computed != null)
                {
                    return computed;
                }
            }
            values.put(property, cached);
        }
        return cached;
    }

    /**
     * What this object holds for a property — set, or decoded from its PDB node — without the default a read
     * computes for an unset one (rawEvalProperty).
     */
    public final List<Object> __storedValues(String property)
    {
        List<Object> cached = frozen(property);
        if (cached != null)
        {
            return cached;
        }
        List<Object> decoded = node == null ? Lists.fixedSize.empty() : runtime.read(this, property);
        // Decoded once, as a read keeps it: every read is the same collection (is.pure). An empty one is not kept,
        // so a later read still computes the default.
        if (!decoded.isEmpty())
        {
            values.put(property, decoded);
        }
        return decoded;
    }

    /** The properties this object holds values for: set, or already decoded from its PDB node. */
    public final java.util.Set<String> _propertyNames()
    {
        return Collections.unmodifiableSet(values.keySet());
    }

    // -- a field's own collection (pure/specification/IN_PLACE_UPDATE_ANALYSIS.md) --------------------------------------------
    // A growable list held as a property's value is referenced by nothing but this object: it was stored updatable in place
    // (UpdatingInPlace) and has not been read since, other than by a read the compiler proved holds the only reference to it
    // (updatableInPlaceValues). Any other read, and a copy, hands out a read-only view of it from then on.

    /** A value the compiler proved updatable in place, being stored: a growable list is kept as this object's own. */
    public record UpdatingInPlace(Object value)
    {
    }

    public static Object updatableInPlace(Object value)
    {
        return new UpdatingInPlace(value);
    }

    public static Object updatableInPlaceIf(Object value, boolean updatableInPlace)
    {
        return updatableInPlace ? new UpdatingInPlace(value) : value;
    }

    /**
     * A property's values, read where the compiler proved the read holds the only reference to them: the object's
     * own list itself, which the reader may change in place. Anything else reads as usual.
     */
    public static List<Object> updatableInPlaceValues(Object object, String property)
    {
        if (object instanceof LazyObject lazy)
        {
            List<Object> own = lazy.values.get(property);
            if (own != null && org.finos.legend.pure.platform.java.runtime.PureCollections.isGrowable(own))
            {
                return own;
            }
            // Not one it holds as its own (a default computed for it, say): read-only.
            List<Object> read = lazy.__values(property);
            return org.finos.legend.pure.platform.java.runtime.PureCollections.isGrowable(read)
                    ? org.finos.legend.pure.platform.java.runtime.PureCollections.stored(read) : read;
        }
        return null;
    }

    /** What this object holds for a property, its own list from now on read-only (null when nothing is held). */
    private List<Object> frozen(String property)
    {
        List<Object> held = values.get(property);
        if (held != null && org.finos.legend.pure.platform.java.runtime.PureCollections.isGrowable(held))
        {
            held = org.finos.legend.pure.platform.java.runtime.PureCollections.stored(held);
            values.put(property, held);
        }
        return held;
    }

    /** Set a property: a List is its values, null none, anything else the single value. */
    @SuppressWarnings("unchecked")
    protected final void set(String property, Object value)
    {
        if (value instanceof UpdatingInPlace updatableInPlace)
        {
            if (updatableInPlace.value() instanceof List<?> list && org.finos.legend.pure.platform.java.runtime.PureCollections.isGrowable(list))
            {
                values.put(property, (List<Object>) list);
                return;
            }
            value = updatableInPlace.value();
        }
        if ("classifierGenericType".equals(property))
        {
            Metadata.checkClassifier(this, value instanceof List && !((List<?>) value).isEmpty() ? ((List<?>) value).get(0) : value);
        }
        // Shared, not copied: Pure values are immutable, and a copy of a list
        // here means a copy of every byte buffer a node is built over. Held
        // behind a read-only view, so a read of the property is never a list a
        // caller may grow in place (PureCollections.isGrowable) — the caller
        // that set it may still hold the list itself — yet `is` the very list
        // that was stored (PureCollections.StoredList).
        values.put(property, value == null ? Lists.fixedSize.empty()
                : value instanceof List<?> list ? org.finos.legend.pure.platform.java.runtime.PureCollections.stored(list)
                : Lists.fixedSize.of(value));
    }

    /** Append to a property ({@code p += v}): the current values, then {@code value}'s. */
    protected final void add(String property, Object value)
    {
        MutableList<Object> all = Lists.mutable.withAll(__values(property));
        if (value instanceof List<?> list)
        {
            all.addAll(list);
        }
        else if (value != null)
        {
            all.add(value);
        }
        values.put(property, all.asUnmodifiable());
    }

    /**
     * This object, about to be changed by a copy expression that holds its only reference
     * (pure/specification/IN_PLACE_UPDATE_ANALYSIS.md): made what a copy would be — the defaults it kept dropped, since a copy
     * computes its own from its own values.
     */
    public final LazyObject __reusedForCopy()
    {
        defaults = null;
        return this;
    }

    /** {@link #__reusedForCopy} on a value typed by its class's interface, as the translated code holds it. */
    /**
     * A copy with several keys changing its source itself: {@code keys}, every key value, computed before any is set
     * (as a copy would), then set on the object by {@code set}.
     */
    public static Object reuse(Object object, java.util.function.BiFunction<Object, Object[], Object> set, Object... keys)
    {
        return set.apply(reusedForCopy(object), keys);
    }

    public static Object reusedForCopy(Object object)
    {
        return object instanceof LazyObject lazy ? lazy.__reusedForCopy() : object;
    }

    /** A shallow copy into {@code target}: same backing node, same values so far. */
    protected final <T extends LazyObject> T copyInto(T target)
    {
        LazyObject copy = target;
        copy.runtime = runtime;
        copy.node = node;
        copy.parent = parent;
        // A list this object updatable in place is now held by two: both read it as a view from now on.
        for (String property : new java.util.ArrayList<>(values.keySet()))
        {
            frozen(property);
        }
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
