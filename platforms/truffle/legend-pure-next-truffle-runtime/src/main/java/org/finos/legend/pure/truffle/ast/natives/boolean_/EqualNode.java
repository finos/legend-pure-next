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
import org.apache.jena.base.Sys;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.StandaloneEvaluator;
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

    private final TruffleMetadataAccess resolver;

    public EqualNode(PureNode left, PureNode right)
    {
        this.left = left;
        this.right = right;
        this.resolver = StandaloneEvaluator.INSTANCE.resolver();
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object a = left.executeGeneric(frame);
        Object b = right.executeGeneric(frame);
        Object rawA = normalizeForEquals(a);
        Object rawB = normalizeForEquals(b);
        return callPureEquals(rawA, rawB, resolver);
    }

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
        if (a instanceof org.finos.legend.pure.truffle.pdb.meta.pure.functions.collection.MapImpl ma
                && b instanceof org.finos.legend.pure.truffle.pdb.meta.pure.functions.collection.MapImpl mb)
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
                return true; // same name, can't distinguish type → assume equal
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
    private static String extractEnumValueName(String s)
    {
        int dotIdx = s.lastIndexOf('.');
        if (dotIdx > 0 && s.contains("::"))
        {
            return s.substring(dotIdx + 1);
        }
        return s;
    }

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
                    throw new RuntimeException("Equality comparison failed for property '" + propName + "' on " + a.getClass().getSimpleName(), e);
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
     * from the object's class hierarchy via the PDB metamodel.
     */
    private static java.util.Set<String> collectEqualityKeyProperties(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any obj, TruffleMetadataAccess resolver)
    {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        var cgt = obj._classifierGenericType();
        if (cgt == null)
        {
            return null;
        }
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SimplePropertyOwner spo = null;
        var type = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
        if (type instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SimplePropertyOwner s)
        {
            spo = s;
        }
        else
        {
            // Try resolving via the MetadataAccess from the Java interface name
            String ifaceName = obj.getClass().getInterfaces().length > 0
                    ? obj.getClass().getInterfaces()[0].getName().replace(".", "::") : null;
            if (ifaceName != null)
            {
                var elem = resolver.getElement(ifaceName);
                if (elem instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SimplePropertyOwner s2)
                {
                    spo = s2;
                }
            }
        }
        if (spo == null)
        {
            return null;
        }
        try
        {
            collectEqualityKeysRecursive(spo, keys, seen);
        }
        catch (StackOverflowError ignored)
        {
            // FlatBuffer wrapper cycles — fall back to no equality keys
            return null;
        }
        return keys;
    }

    private static void collectEqualityKeysRecursive(
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SimplePropertyOwner owner,
            java.util.Set<String> keys, java.util.Set<String> seen)
    {
        collectEqualityKeysRecursive(owner, keys, seen, 0);
    }

    private static void collectEqualityKeysRecursive(
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SimplePropertyOwner owner,
            java.util.Set<String> keys, java.util.Set<String> seen,
            int depth)
    {
        // Guard against cycles in the generalization hierarchy.
        // FlatBuffer wrappers create new Java objects per access, so identity
        // and name checks can fail. A depth limit is the robust safety net.
        if (depth > 10)
        {
            return;
        }
        if (owner._properties() != null)
        {
            for (Object p : owner._properties().toBoxedArray())
            {
                if (!(p instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property prop))
                {
                    continue;
                }
                String propName = prop._name();
                if (propName == null || seen.contains(propName))
                {
                    continue;
                }
                seen.add(propName);
                org.finos.legend.pure.truffle.types.PureSequence stereotypes = prop._stereotypes();
                if (stereotypes != null)
                {
                    for (Object st : stereotypes.toBoxedArray())
                    {
                        if (st instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.extension.Stereotype ster
                                && "Key".equals(ster._value())
                                && ster._profile() instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement ppe
                                && "meta::pure::profiles::equality".equals(
                                org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(ppe)))
                        {
                            keys.add(propName);
                            break;
                        }
                    }
                }
            }
        }
        if (owner instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type type && type._generalizations() != null)
        {
            for (Object gen : type._generalizations().toBoxedArray())
            {
                if (gen instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.relationship.Generalization g && g._general() != null)
                {
                    var superType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(g._general());
                    if (superType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SimplePropertyOwner superOwner)
                    {
                        collectEqualityKeysRecursive(superOwner, keys, seen, depth + 1);
                    }
                }
            }
        }
    }

    private static Object normalizeForEquals(Object v)
    {
        // AtomicValue from PDB metadata — unwrap to raw value for comparison
        if (v instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue av && av._value() != null)
        {
            return normalizeForEquals(av._value());
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
                return normalizeForEquals(seq.getBoxed(0));
            }
            return v; // keep PureSequence as-is for comparison
        }
        // MutableList normalization
        if (v instanceof org.eclipse.collections.api.list.MutableList<?> ml)
        {
            if (ml.isEmpty())
            {
                return null;
            }
            if (ml.size() == 1)
            {
                return normalizeForEquals(ml.get(0));
            }
            return ml;
        }
        return v;
    }
}
