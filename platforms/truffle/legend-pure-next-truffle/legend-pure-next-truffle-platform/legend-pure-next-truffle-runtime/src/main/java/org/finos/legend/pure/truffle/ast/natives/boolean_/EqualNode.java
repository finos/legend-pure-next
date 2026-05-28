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
import org.finos.legend.pure.truffle.runtime.dynobj.PropertyMetadataRegistry;
import org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry;
import org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;
import org.finos.legend.pure.truffle.runtime.helper._GenericType;
import org.finos.legend.pure.truffle.runtime.helper._PackageableElement;
import org.finos.legend.pure.truffle.types.PureDate;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.Objects;

/**
 * {@code equal(Any[*], Any[*]) : Boolean[1]}.
 *
 * <p>Evaluates both children to raw values, then performs deep equality.</p>
 */
@NodeInfo(shortName = "equal")
public final class EqualNode extends PureNode
{

    private static final int SLOT_CLASSIFIER_GENERIC_TYPE = PureClassRegistry.globalSlot("classifierGenericType");
    private static final int SLOT_NAME = PureClassRegistry.globalSlot("name");
    private static final int SLOT_VALUE = PureClassRegistry.globalSlot("value");
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
     * equality, generated-PDBHelper property-key equality (uses reflection), Map
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

    /**
     * Resolver-passed deep equality. Same logic as {@link #executeBoolean(VirtualFrame)}
     * but takes the operands directly — used by AST nodes that need to compare
     * values inside an inner loop ({@link
     * org.finos.legend.pure.truffle.ast.natives.collection.ContainsNode}) without
     * paying for an extra {@link RawLambdaCallNode} dispatch per element.
     */
    public static boolean equalsStatic(Object a, Object b, TruffleMetadataAccess resolver)
    {
        if (a == b) return true;
        if (a instanceof Long la && b instanceof Long lb) return la.longValue() == lb.longValue();
        if (a instanceof Boolean ba && b instanceof Boolean bb) return ba.booleanValue() == bb.booleanValue();
        if (a instanceof String sa && b instanceof String sb) return sa.equals(sb);
        return slowEqualsStatic(a, b, resolver);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean slowEqualsStatic(Object a, Object b, TruffleMetadataAccess resolver)
    {
        Object rawA = normalizeForEquals(a);
        Object rawB = normalizeForEquals(b);
        if (rawA == rawB) return true;
        return callPureEquals(rawA, rawB, resolver);
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
        if (a instanceof PureSequence seqA
                && b instanceof PureSequence seqB)
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
        // Map comparison — deep equality by entries. A Pure Map is a MapImpl;
        // a translated TS map object round-trips through toPureValue as a plain
        // java.util.Map (LinkedHashMap). Compare either-shape against a MapImpl
        // by entries so a round-tripped map equals a Pure-built one. Guard on
        // "at least one MapImpl" so we don't change plain-Map-vs-Map semantics.
        if ((a instanceof MapImpl || b instanceof MapImpl)
                && (a instanceof MapImpl || a instanceof java.util.Map)
                && (b instanceof MapImpl || b instanceof java.util.Map))
        {
            java.util.Map<Object, Object> ma = asEntryMap(a);
            java.util.Map<Object, Object> mb = asEntryMap(b);
            if (ma.size() != mb.size())
            {
                return false;
            }
            for (var entry : ma.entrySet())
            {
                if (!mb.containsKey(entry.getKey()))
                {
                    return false;
                }
                if (!callPureEquals(normalizeForEquals(entry.getValue()),
                        normalizeForEquals(mb.get(entry.getKey())), resolver))
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
        boolean aIsEnum = PureObj.pureTypeIs(a,
                "meta::pure::metamodel::type::Enum");
        boolean bIsEnum = PureObj.pureTypeIs(b,
                "meta::pure::metamodel::type::Enum");
        if (aIsEnum)
        {
            Object ea = a;
            if (bIsEnum)
            {
                Object eb = b;
                Object eaName = PureObj.readBySlot(ea, SLOT_NAME);
                Object ebName = PureObj.readBySlot(eb, SLOT_NAME);
                if (!Objects.equals(eaName, ebName))
                {
                    return false;
                }
                // Post enum-to-PDO migration: all enum values are PDO
                // singletons sharing the {@code Enum} classInfo. Two values
                // are the same enum type iff their {@code classifierGenericType}
                // resolves to the same type.
                Object eaCgt = PureObj.readBySlot(ea, SLOT_CLASSIFIER_GENERIC_TYPE);
                Object ebCgt = PureObj.readBySlot(eb, SLOT_CLASSIFIER_GENERIC_TYPE);
                if (eaCgt != null && ebCgt != null)
                {
                    var typeA = _GenericType.type(eaCgt);
                    var typeB = _GenericType.type(ebCgt);
                    if (typeA == typeB)
                    {
                        return true;
                    }
                    if (typeA != null && typeB != null)
                    {
                        String pathA = _PackageableElement.path(typeA, resolver);
                        String pathB = _PackageableElement.path(typeB, resolver);
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
                Object eaName = PureObj.readBySlot(ea, SLOT_NAME);
                return Objects.equals(eaName, extractEnumValueName(s));
            }
            return false;
        }
        if (bIsEnum && a instanceof String s)
        {
            Object ebName = PureObj.readBySlot(b, SLOT_NAME);
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
        // Generated PDBHelper equality — compare by property values respecting <<equality.Key>>.
        // Both are Pure metamodel objects iff their pureTypeOf is non-null
        // (covers PureDynamicObject + legacy XPDBHelper).
        String ptA = PureObj.pureTypeOf(a);
        String ptB = PureObj.pureTypeOf(b);
        boolean aIsPure = ptA != null;
        boolean bIsPure = ptB != null;
        if (aIsPure && bIsPure)
        {
            // For PDO, getClass() is always PureDynamicObject — doesn't
            // distinguish Pure types. Use pureTypeOf for the same-type check.
            if (ptA.equals(ptB) || samePureType(a, b, resolver))
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
     * matching FlatBufferWrapper vs PDBHelper of the same type.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean samePureType(Object a, Object b, TruffleMetadataAccess resolver)
    {
        Object cgtA = PureObj.readBySlot(a, SLOT_CLASSIFIER_GENERIC_TYPE);
        Object cgtB = PureObj.readBySlot(b, SLOT_CLASSIFIER_GENERIC_TYPE);
        if (cgtA == null || cgtB == null)
        {
            return false;
        }
        var typeA = _GenericType.type(cgtA);
        var typeB = _GenericType.type(cgtB);
        if (typeA == typeB)
        {
            return true;
        }
        if (typeA != null && typeB != null)
        {
            String pathA = _PackageableElement.path(typeA, resolver);
            String pathB = _PackageableElement.path(typeB, resolver);
            return pathA != null && pathA.equals(pathB);
        }
        return false;
    }

    /** View a Pure Map (MapImpl) or a plain java.util.Map as an entry map for comparison. */
    @SuppressWarnings("unchecked")
    private static java.util.Map<Object, Object> asEntryMap(Object o)
    {
        if (o instanceof MapImpl m)
        {
            return m.getMap();
        }
        return (java.util.Map<Object, Object>) o;
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
                Object va = PureObj.read(a, propName);
                Object vb = PureObj.read(b, propName);
                if (!callPureEquals(normalizeForEquals(va), normalizeForEquals(vb), resolver))
                {
                    return false;
                }
            }
            return true;
        }
        // No equality key properties — for top-level PackageableElements,
        // fall back to path equality. A PE's full path uniquely identifies it
        // within the model; two PE instances with the same path are
        // logically the same element. This handles the in-memory module
        // case where compile-pure creates fresh Package instances for paths
        // that also exist in core.pdb (e.g. {@code meta::pure::test}) —
        // identity differs but they're the same logical element.
        // Restricted to PackageableElement subtypes — user-class instances
        // (e.g. {@code ^NoKeyClass(name='x')}) have a {@code name} property
        // but aren't elements, and path equality on them would conflate
        // distinct instances that happen to share a name.
        if (a instanceof PureDynamicObject
                && b instanceof PureDynamicObject
                && PureObj.isType(a,
                        "meta::pure::metamodel::PackageableElement", resolver))
        {
            String pathA = _PackageableElement.path(a, resolver);
            String pathB = _PackageableElement.path(b, resolver);
            if (pathA != null && !pathA.isEmpty() && pathA.equals(pathB))
            {
                return true;
            }
        }
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
        // Fast path: PropertyMetadataRegistry is populated by each XPDBHelper's
        // static{} block with the @equality.Key property names — direct
        // lookup by Pure path, no resolver/Type walk needed.
        String purePath = PureObj.pureTypeOf(obj);
        if (purePath != null)
        {
            String[] keys = PropertyMetadataRegistry.getEqualityKeys(purePath);
            if (keys != null && keys.length > 0)
            {
                return new java.util.LinkedHashSet<>(java.util.Arrays.asList(keys));
            }
        }
        Object cgt = PureObj.readBySlot(obj, SLOT_CLASSIFIER_GENERIC_TYPE);
        if (cgt == null)
        {
            return null;
        }
        Object type = _GenericType.type(cgt);
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
            if (PureObj.pureTypeIs(v,
                    "meta::pure::metamodel::valuespecification::AtomicValue"))
            {
                Object inner = PureObj.readBySlot(v, SLOT_VALUE);
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
            if (v instanceof PureSequence seq)
            {
                if (seq.isEmpty())
                {
                    return PureSequence.EMPTY;
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
