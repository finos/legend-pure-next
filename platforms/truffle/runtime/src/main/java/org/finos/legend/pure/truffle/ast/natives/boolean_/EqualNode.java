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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;

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
    public Object executeGeneric(VirtualFrame frame)
    {
        Object a = left.executeGeneric(frame);
        Object b = right.executeGeneric(frame);
        Object rawA = normalizeForEquals(a);
        Object rawB = normalizeForEquals(b);
        return callPureEquals(rawA, rawB);
    }

    @TruffleBoundary
    private static boolean callPureEquals(Object a, Object b)
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
                if (!callPureEquals(ea, eb))
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
                if (!callPureEquals(entry.getValue(), bVal))
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
                return Objects.equals(ea._name(), s);
            }
            return false;
        }
        if (b instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enum eb && a instanceof String s)
        {
            return Objects.equals(s, eb._name());
        }
        // Generated Impl equality — compare by property values respecting <<equality.Key>>
        if (a instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any anyA
                && b instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any anyB)
        {
            if (a.getClass() == b.getClass())
            {
                return equalByProperties(anyA, anyB);
            }
        }
        return Objects.equals(a, b);
    }

    private static boolean equalByProperties(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any a, org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any b)
    {
        // Check for <<equality.Key>> properties — only compare those if present.
        // Try runtime metamodel first, then Java interface name fallback.
        java.util.Set<String> keyProps = collectEqualityKeyProperties(a);
        if (keyProps != null && !keyProps.isEmpty())
        {
            for (String propName : keyProps)
            {
                try
                {
                    java.lang.reflect.Method m = a.getClass().getMethod("_" + propName);
                    Object va = m.invoke(a);
                    Object vb = m.invoke(b);
                    if (!callPureEquals(normalizeForEquals(va), normalizeForEquals(vb)))
                    {
                        return false;
                    }
                }
                catch (Exception ignored)
                {
                }
            }
            return true;
        }

        // No equality keys found — compare all property getter methods
        for (java.lang.reflect.Method m : a.getClass().getMethods())
        {
            String name = m.getName();
            if (name.startsWith("_") && m.getParameterCount() == 0
                    && !name.equals("_classifierGenericType") && !name.equals("_sourceInformation")
                    && !name.equals("_elementOverride") && !name.equals("_copy")
                    && !name.equals("_class") && !name.equals("_hashCode"))
            {
                try
                {
                    Object va = m.invoke(a);
                    Object vb = m.invoke(b);
                    if (!callPureEquals(normalizeForEquals(va), normalizeForEquals(vb)))
                    {
                        return false;
                    }
                }
                catch (Exception ignored)
                {
                }
            }
        }
        return true;
    }

    /**
     * Collect property names with the {@code <<equality.Key>>} stereotype
     * from the object's class hierarchy via the PDB metamodel.
     */
    private static java.util.Set<String> collectEqualityKeyProperties(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any obj)
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
                var resolver = org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder.current().resolver();
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
        collectEqualityKeysRecursive(spo, keys, seen);
        if (!keys.isEmpty() || obj.getClass().getSimpleName().contains("Side") || obj.getClass().getSimpleName().contains("Right"))
        {
            System.err.println("[EQ-KEY-RESULT] " + obj.getClass().getSimpleName() + " spo=" + spo.getClass().getSimpleName() + " keys=" + keys);
        }
        return keys;
    }

    private static void collectEqualityKeysRecursive(
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SimplePropertyOwner owner,
            java.util.Set<String> keys, java.util.Set<String> seen)
    {
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
                                && ster._profile() instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe
                                && "meta::pure::profiles::equality".equals(
                                org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe)))
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
                        collectEqualityKeysRecursive(superOwner, keys, seen);
                    }
                }
            }
        }
    }

    private static Object normalizeForEquals(Object v)
    {
        if (v instanceof org.finos.legend.pure.truffle.types.PureSequence seq)
        {
            if (seq.size() == 0)
            {
                return null;
            }
            if (seq.size() == 1)
            {
                return normalizeForEquals(seq.getBoxed(0));
            }
            return java.util.Arrays.asList(seq.toBoxedArray());
        }
        if (v instanceof org.finos.legend.pure.truffle.types.PureNull)
        {
            return null;
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
        // Unwrap AtomicValue (dates kept as AV)
        if (v instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue av)
        {
            Object inner = av._value();
            return inner != null ? inner : null;
        }
        // Unwrap Collection (legacy VS — shouldn't appear but handle defensively)
        if (v instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.Collection col && col._values() != null)
        {
            if (col._values().size() == 1)
            {
                return normalizeForEquals(col._values().getBoxed(0));
            }
        }
        return v;
    }
}
