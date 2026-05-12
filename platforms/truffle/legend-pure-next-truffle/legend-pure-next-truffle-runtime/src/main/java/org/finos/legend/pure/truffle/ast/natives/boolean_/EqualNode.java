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

package org.finos.legend.pure.truffle.ast.natives.boolean_;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.collection.MapImpl;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.types.PureDate;

import java.util.Objects;

/**
 * {@code equal(Any[*], Any[*]) : Boolean[1]}.
 *
 * <p>Evaluates both children to raw values, then performs deep equality.</p>
 */
@NodeInfo(shortName = "equal")
public final class EqualNode extends PureNode
{

    private static final int SLOT_CLASSIFIER_GENERIC_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("classifierGenericType");
    private static final int SLOT_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");
    private static final int SLOT_VALUE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("value");
    @Child
    private PureNode left;

    @Child
    private PureNode right;

    public EqualNode(PureNode left, PureNode right)
    {
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame)
    {
        Object a = left.executeGeneric(frame);
        Object b = right.executeGeneric(frame);
        // Identity short-circuit (works on cached Long/Boolean/Type instances).
        if (a == b)
        {
            return true;
        }
        // Fast paths PE can fold completely — avoid the @TruffleBoundary
        // call to normalizeForEquals + callPureEquals for the cases that
        // dominate compiler-pure type comparisons (multiplicity bounds,
        // type-name equality, primitive constant comparisons). Per-instance
        // class-caching was tested as an alternative and regressed by ~5%
        // because compiler-pure equality sites are diverse enough that
        // inline-cache misses cost more than the instanceof chain saves on
        // hits — the JVM-level instanceof on final-shaped boxed primitives
        // is ~2 cycles and the chain short-circuits aggressively.
        if (a instanceof Long la && b instanceof Long lb)
        {
            return la.longValue() == lb.longValue();
        }
        if (a instanceof Boolean ba && b instanceof Boolean bb)
        {
            return ba.booleanValue() == bb.booleanValue();
        }
        if (a instanceof String sa && b instanceof String sb)
        {
            return sa.equals(sb);
        }
        return slowEquals(a, b);
    }

    /**
     * Slow path — handles AtomicValue/PureSequence wrappers, deep PureSequence
     * equality, generated-Impl property-key equality (uses reflection), Map
     * deep equality, etc. Boundary-isolated so PE doesn't try to inline the
     * recursive {@code callPureEquals} chain (would bust the inlining budget,
     * see comment on callPureEquals).
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private boolean slowEquals(Object a, Object b)
    {
        Object rawA = normalizeForEquals(a);
        Object rawB = normalizeForEquals(b);
        if (rawA == rawB)
        {
            return true;
        }
        return callPureEquals(rawA, rawB, getResolver());
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return executeBoolean(frame);
    }

    // @TruffleBoundary — equality is inherently recursive over arbitrary
    // structure (PureSequence-of-PureSequence chains were inlining 997 deep
    // and tripping the inlining budget) and equalByProperties uses
    // reflection (Class.getMethod), which is unfriendly to PE. Equality
    // isn't on the tightest inner loop; running it past a boundary trades
    // a Java call for not exhausting Graal's budget on every callsite.
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean callPureEquals(Object a, Object b, TruffleMetadataAccess resolver)
    {
        if (a == b)
        {
            return true;
        }
        if (a == null || b == null)
        {
            return false;
        }
        // Numeric coercion: compare numbers by value
        if (a instanceof Number na && b instanceof Number nb)
        {
            if (a instanceof Long && b instanceof Long)
            {
                return na.longValue() == nb.longValue();
            }
            double da = na.doubleValue();
            double db = nb.doubleValue();
            // Normalize -0.0 to 0.0
            if (da == 0.0) da = 0.0;
            if (db == 0.0) db = 0.0;
            return Double.compare(da, db) == 0;
        }
        // PureSequence comparison
        if (a instanceof org.finos.legend.pure.truffle.types.PureSequence seqA
                && b instanceof org.finos.legend.pure.truffle.types.PureSequence seqB)
        {
            if (seqA.size() != seqB.size()) return false;
            for (int i = 0; i < seqA.size(); i++)
            {
                if (!callPureEquals(normalizeForEquals(seqA.getBoxed(i)), normalizeForEquals(seqB.getBoxed(i)), resolver))
                    return false;
            }
            return true;
        }
        // List comparison
        if (a instanceof java.util.List<?> la && b instanceof java.util.List<?> lb)
        {
            if (la.size() != lb.size())
            {
                return false;
            }
            for (int i = 0; i < la.size(); i++)
            {
                Object ea = normalizeForEquals(la.get(i));
                Object eb = normalizeForEquals(lb.get(i));
                if (!callPureEquals(ea, eb, resolver))
                {
                    return false;
                }
            }
            return true;
        }
        // Map comparison — deep equality by entries
        if (a instanceof MapImpl ma
                && b instanceof MapImpl mb)
        {
            if (ma.size() != mb.size())
            {
                return false;
            }
            for (var entry : ma.getMap().entrySet())
            {
                Object bVal = mb.get(entry.getKey());
                if (!callPureEquals(entry.getValue(), bVal, resolver))
                {
                    return false;
                }
            }
            return true;
        }
        // Enum equality — same name AND same enumeration type.
        // pureTypeIs is class-keyed-cached so the PE inlining-budget hazard
        // that originally tripped IsNode is gone (single CHM.get(Class) per
        // call after warmup).
        boolean aIsEnum = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(a,
                "meta::pure::metamodel::type::Enum");
        boolean bIsEnum = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(b,
                "meta::pure::metamodel::type::Enum");
        if (aIsEnum)
        {
            Object ea = a;
            if (bIsEnum)
            {
                Object eb = b;
                Object eaName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(ea, SLOT_NAME);
                Object ebName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(eb, SLOT_NAME);
                if (!Objects.equals(eaName, ebName))
                {
                    return false;
                }
                // Post enum-to-PDO migration: all enum values are PDO
                // singletons sharing the {@code Enum} classInfo. Two values
                // are the same enum type iff their {@code classifierGenericType}
                // resolves to the same type.
                Object eaCgt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(ea, SLOT_CLASSIFIER_GENERIC_TYPE);
                Object ebCgt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(eb, SLOT_CLASSIFIER_GENERIC_TYPE);
                if (eaCgt != null && ebCgt != null)
                {
                    var typeA = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(eaCgt);
                    var typeB = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(ebCgt);
                    if (typeA == typeB)
                    {
                        return true;
                    }
                    if (typeA != null && typeB != null)
                    {
                        String pathA = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(typeA);
                        String pathB = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(typeB);
                        return pathA != null && pathA.equals(pathB);
                    }
                }
                // Same name but cannot prove same enum type — be strict and
                // treat as unequal. Equating two enums solely on value name
                // (e.g., TestEnum1.FIRST vs TestEnum2.FIRST) is the
                // false-positive testEqualEnum and testIsEnum guard against.
                return false;
            }
            if (b instanceof String s)
            {
                Object eaName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(ea, SLOT_NAME);
                return Objects.equals(eaName, extractEnumValueName(s));
            }
            return false;
        }
        if (bIsEnum && a instanceof String s)
        {
            Object ebName = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(b, SLOT_NAME);
            return Objects.equals(extractEnumValueName(s), ebName);
        }
        // Two enum value strings: compare by extracted value name
        if (a instanceof String sa && b instanceof String sb && sa.contains("::") && sb.contains("::"))
        {
            int dotA = sa.lastIndexOf('.');
            int dotB = sb.lastIndexOf('.');
            if (dotA > 0 && dotB > 0)
            {
                return sa.equals(sb);
            }
        }
        // Generated Impl equality — compare by property values respecting <<equality.Key>>.
        // Both are Pure metamodel objects iff their pureTypeOf is non-null
        // (covers PureDynamicObject + legacy XImpl).
        String ptA = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(a);
        String ptB = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(b);
        boolean aIsPure = ptA != null;
        boolean bIsPure = ptB != null;
        if (aIsPure && bIsPure)
        {
            // For PDO, getClass() is always PureDynamicObject — doesn't
            // distinguish Pure types. Use pureTypeOf for the same-type check.
            if (ptA.equals(ptB) || samePureType(a, b))
            {
                // Guard against circular property references (e.g. Property→owner→Property)
                int depth = EQUALS_DEPTH.get();
                if (depth > 10)
                {
                    return a == b;
                }
                EQUALS_DEPTH.set(depth + 1);
                try
                {
                    return equalByProperties(a, b, resolver);
                }
                finally
                {
                    EQUALS_DEPTH.set(depth);
                }
            }
        }
        return Objects.equals(a, b);
    }

    private static final ThreadLocal<Integer> EQUALS_DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * Check if two Any instances represent the same Pure type.
     * Compares classifierGenericType paths. This correctly distinguishes
     * LeftClass from BottomClass (different types, not equal) while still
     * matching FlatBufferWrapper vs Impl of the same type.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean samePureType(Object a, Object b)
    {
        Object cgtA = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(a, SLOT_CLASSIFIER_GENERIC_TYPE);
        Object cgtB = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(b, SLOT_CLASSIFIER_GENERIC_TYPE);
        if (cgtA == null || cgtB == null)
        {
            return false;
        }
        var typeA = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgtA);
        var typeB = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgtB);
        if (typeA == typeB)
        {
            return true;
        }
        if (typeA != null && typeB != null)
        {
            String pathA = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(typeA);
            String pathB = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(typeB);
            return pathA != null && pathA.equals(pathB);
        }
        return false;
    }

    /** Extract just the value name from an enum value string like "pkg::EnumType.VALUE" → "VALUE". */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String extractEnumValueName(String s)
    {
        int dotIdx = s.lastIndexOf('.');
        if (dotIdx > 0 && s.contains("::"))
        {
            return s.substring(dotIdx + 1);
        }
        return s;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean equalByProperties(Object a, Object b, TruffleMetadataAccess resolver)
    {
        // Check for <<equality.Key>> properties — only compare those if present.
        java.util.Set<String> keyProps = collectEqualityKeyProperties(a, resolver);
        if (keyProps != null && !keyProps.isEmpty())
        {
            for (String propName : keyProps)
            {
                Object va = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(a, propName);
                Object vb = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(b, propName);
                if (!callPureEquals(normalizeForEquals(va), normalizeForEquals(vb), resolver))
                {
                    return false;
                }
            }
            return true;
        }
        // No equality key properties — only identity makes them equal.
        // Top-level PDB elements are cached singletons (via TrufflePdbLoader),
        // so identity comparison is correct and matches Java runtime behavior.
        return false;
    }

    /**
     * Collect property names with the {@code <<equality.Key>>} stereotype
     * from the object's class hierarchy via the PDB metamodel. Memoised per
     * Type on the resolver — see {@link org.finos.legend.pure.truffle.runtime.helper.TypeCache}.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static java.util.Set<String> collectEqualityKeyProperties(Object obj, TruffleMetadataAccess resolver)
    {
        // Fast path: PropertyMetadataRegistry is populated by each XImpl's
        // static{} block with the @equality.Key property names — direct
        // lookup by Pure path, no resolver/Type walk needed.
        String purePath = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(obj);
        if (purePath != null)
        {
            String[] keys = org.finos.legend.pure.truffle.runtime.dynobj.PropertyMetadataRegistry.getEqualityKeys(purePath);
            if (keys != null && keys.length > 0)
            {
                return new java.util.LinkedHashSet<>(java.util.Arrays.asList(keys));
            }
        }
        Object cgt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(obj, SLOT_CLASSIFIER_GENERIC_TYPE);
        if (cgt == null)
        {
            return null;
        }
        Object type = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
        if (type == null)
        {
            if (purePath != null)
            {
                Object elem = resolver.getElement(purePath);
                if (elem != null) type = elem;
            }
        }
        if (type == null)
        {
            return null;
        }
        java.util.Set<String> keys = resolver.typeCache().equalityKeyProperties(type);
        return keys.isEmpty() ? null : keys;
    }

    /**
     * Iterative — recursion here would compound through Graal's PE: each
     * wrapper-unwrap level inlines another copy of {@code normalizeForEquals},
     * which {@code callPureEquals} then calls twice (lhs + rhs) per recursion
     * level. Past 2–3 unwraps that's enough to bust the inlining budget,
     * which surfaced 299× in the "Too deep inlining" bailout list.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object normalizeForEquals(Object v)
    {
        while (true)
        {
            if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(v,
                    "meta::pure::metamodel::valuespecification::AtomicValue"))
            {
                Object inner = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(v, SLOT_VALUE);
                if (inner != null)
                {
                    v = inner;
                    continue;
                }
            }
            if (v instanceof PureDate pd)
            {
                return pd.dateString();
            }
            if (v instanceof org.finos.legend.pure.truffle.types.PureSequence seq)
            {
                if (seq.isEmpty())
                {
                    return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
                }
                if (seq.size() == 1)
                {
                    v = seq.getBoxed(0);
                    continue;
                }
                return v; // keep PureSequence as-is for comparison
            }
            if (v instanceof org.eclipse.collections.api.list.MutableList<?> ml)
            {
                if (ml.isEmpty())
                {
                    return null;
                }
                if (ml.size() == 1)
                {
                    v = ml.get(0);
                    continue;
                }
                return ml;
            }
            return v;
        }
    }
}
