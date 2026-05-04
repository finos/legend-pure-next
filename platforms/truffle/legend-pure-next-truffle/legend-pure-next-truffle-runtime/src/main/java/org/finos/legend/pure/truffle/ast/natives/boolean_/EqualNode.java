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
        Object rawA = normalizeForEquals(left.executeGeneric(frame));
        Object rawB = normalizeForEquals(right.executeGeneric(frame));
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
        // Enum equality — same name AND same enumeration type
        if (a instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enum ea)
        {
            if (b instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enum eb)
            {
                if (!Objects.equals(ea._name(), eb._name()))
                {
                    return false;
                }
                // Same name — check same enum type
                // For generated Java enums, same class = same enum type
                // For EnumImpl (FlatBuffer-wrapped), same class doesn't mean same
                // enum type — must check CGT path
                if (a.getClass() == b.getClass() && a.getClass().isEnum())
                {
                    return true;
                }
                // FlatBuffer-wrapped enums: compare classifierGenericType by path
                if (ea._classifierGenericType() != null && eb._classifierGenericType() != null)
                {
                    var typeA = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(ea._classifierGenericType());
                    var typeB = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(eb._classifierGenericType());
                    if (typeA == typeB)
                    {
                        return true;
                    }
                    if (typeA instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement peA
                            && typeB instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement peB)
                    {
                        return org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(peA)
                                .equals(org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(peB));
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
                return Objects.equals(ea._name(), extractEnumValueName(s));
            }
            return false;
        }
        if (b instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enum eb && a instanceof String s)
        {
            return Objects.equals(extractEnumValueName(s), eb._name());
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
        // Generated Impl equality — compare by property values respecting <<equality.Key>>
        // Also handles cross-class comparisons (FlatBufferWrapper vs Impl) when both
        // represent the same Pure type (checked via classifierGenericType path).
        if (a instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any anyA
                && b instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any anyB)
        {
            if (a.getClass() == b.getClass() || samePureType(anyA, anyB))
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
                    return equalByProperties(anyA, anyB, resolver);
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
    private static boolean samePureType(
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any a,
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any b)
    {
        var cgtA = a._classifierGenericType();
        var cgtB = b._classifierGenericType();
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
        if (typeA instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement peA
                && typeB instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement peB)
        {
            return org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(peA)
                    .equals(org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(peB));
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
    private static boolean equalByProperties(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any a, org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any b, TruffleMetadataAccess resolver)
    {
        // Check for <<equality.Key>> properties — only compare those if present.
        // Try runtime metamodel first, then Java interface name fallback.
        java.util.Set<String> keyProps = collectEqualityKeyProperties(a, resolver);
        if (keyProps != null && !keyProps.isEmpty())
        {
            for (String propName : keyProps)
            {
                try
                {
                    java.lang.reflect.Method m = a.getClass().getMethod("_" + propName);
                    Object va = m.invoke(a);
                    Object vb = m.invoke(b);
                    if (!callPureEquals(normalizeForEquals(va), normalizeForEquals(vb), resolver))
                    {
                        return false;
                    }
                }
                catch (Exception e)
                {
                    throw new RuntimeException("Equality comparison failed for property '" + propName + "' on " + a.getClass().getName(), e);
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
    private static java.util.Set<String> collectEqualityKeyProperties(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any obj, TruffleMetadataAccess resolver)
    {
        var cgt = obj._classifierGenericType();
        if (cgt == null)
        {
            return null;
        }
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type type =
                org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
        if (type == null)
        {
            // Fall back to interface-name resolution for FlatBuffer wrappers
            // whose generic type doesn't surface a resolved Type.
            String ifaceName = obj.getClass().getInterfaces().length > 0
                    ? obj.getClass().getInterfaces()[0].getName().replace(".", "::") : null;
            if (ifaceName != null)
            {
                Object elem = resolver.getElement(ifaceName);
                if (elem instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t)
                {
                    type = t;
                }
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
            if (v instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue av
                    && av._value() != null)
            {
                v = av._value();
                continue;
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
