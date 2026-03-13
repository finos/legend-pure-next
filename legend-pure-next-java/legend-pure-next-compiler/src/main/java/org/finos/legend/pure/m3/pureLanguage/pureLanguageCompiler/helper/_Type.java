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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.relation.RelationType;
import meta.pure.metamodel.type.Type;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Helper methods for {@link Type}.
 *
 * <p>
 * Includes C3 linearization (also known as C3 Method Resolution Order)
 * for the Pure type hierarchy, and utilities for finding common supertypes.
 * </p>
 *
 * <p>
 * C3 Linearization Algorithm:
 * <pre>
 *   L(C) = C + merge(L(P1), L(P2), ..., [P1, P2, ...])
 * </pre>
 * where P1, P2, ... are the direct supertypes of C in declaration order.
 * The {@code merge} operation repeatedly selects the first head of any list
 * that does not appear in the tail of any other list, removes it from all lists,
 * and appends it to the result.
 * </p>
 */
public final class _Type
{
    private _Type()
    {
        // static utility
    }

    /**
     * Compute the C3 linearization of the given type.
     *
     * <p>Nil (the bottom type) is <b>not</b> included in the linearization.
     * It is handled via short-circuits in {@link #subtypeOf}, {@link #isBottomType},
     * and {@link #findCommonType} instead.</p>
     *
     * @param type  the type to linearize
     * @param model the model providing type identity
     * @return a list of types ordered from most specific (the type itself)
     *         to least specific (Any), e.g. {@code [Integer, Number, Any]}
     * @throws IllegalStateException if the hierarchy is inconsistent and
     *         cannot be linearized
     */
    public static MutableList<Type> linearize(Type type, MetadataAccess model)
    {
        if (type == null)
        {
            return Lists.mutable.empty();
        }

        // Get direct supertypes
        MutableList<Type> parents = directSupertypes(type);

        if (parents.isEmpty())
        {
            // Top type (Any) — just [Any]
            return Lists.mutable.with(type);
        }

        // Build the list of linearizations of each parent + the parents list itself
        MutableList<MutableList<Type>> listsToMerge = Lists.mutable.empty();
        for (Type parent : parents)
        {
            listsToMerge.add(linearize(parent, model));
        }
        listsToMerge.add(Lists.mutable.withAll(parents));

        // L(C) = C + merge(...)
        MutableList<Type> result = Lists.mutable.with(type);
        result.addAll(merge(listsToMerge));
        return result;
    }

    /**
     * Find the closest common type for a list of Types.
     *
     * <p>When {@code contravariant} is false (covariant / default), finds the
     * <b>Lowest Common Ancestor (LCA)</b> — the most specific type that is a
     * supertype of all the given types (direction: toward Any).</p>
     *
     * <p>When {@code contravariant} is true, finds the <b>Greatest Lower Bound
     * (GLB)</b> — the most general type that is a subtype of all the given
     * types (direction: toward Nil).</p>
     *
     * @param types         the types to unify
     * @param contravariant if true, finds the GLB; if false, finds the LCA
     * @param model         the model providing Any/Nil type identity
     * @return the common type, or {@code null} if none can be determined
     */
    public static Type findCommonType(MutableList<Type> types, boolean contravariant, MetadataAccess model)
    {
        if (types == null || types.isEmpty())
        {
            return null;
        }
        if (types.size() == 1)
        {
            return types.getFirst();
        }

        // Quick check: all types are the same
        Type first = types.getFirst();
        if (types.allSatisfy(t -> t == first))
        {
            return first;
        }

        Type any = model.any();
        Type nil = model.nil();

        // Handle Nil (bottom type) specially:
        // - Covariant (LCA): Nil is a subtype of everything, so ignore it —
        //   the LCA of {X, Nil} is just X.
        // - Contravariant (GLB): Nil is the bottom of all types, so if any
        //   input is Nil, the GLB is Nil.
        if (nil != null)
        {
            if (contravariant)
            {
                if (types.anySatisfy(t -> t == nil))
                {
                    return nil;
                }
            }
            else
            {
                MutableList<Type> nonNil = types.reject(t -> t == nil);
                if (nonNil.isEmpty())
                {
                    return nil;
                }
                if (nonNil.size() < types.size())
                {
                    return findCommonType(nonNil, false, model);
                }
            }
        }

        // RelationTypes use structural (column-based) unification, not class hierarchy
        if (types.allSatisfy(t -> t instanceof RelationType))
        {
            return _RelationType.findCommonRelationType(types.collect(t -> (RelationType) t), model);
        }

        MutableList<MutableList<Type>> linearizations = types.collect(t -> linearize(t, model));
        MutableList<Type> firstLin = linearizations.getFirst();

        // Nil is not in linearizations — it is handled by the short-circuits above.
        if (contravariant)
        {
            // GLB: without Nil in linearizations, there is no explicit common
            // subtype to find. The GLB of unrelated types defaults to Nil.
            return nil;
        }

        // LCA: walk forward to find the most specific common supertype.
        for (Type candidate : firstLin)
        {
            boolean inAll = true;
            for (int i = 1; i < linearizations.size(); i++)
            {
                if (!linearizations.get(i).contains(candidate))
                {
                    inAll = false;
                    break;
                }
            }
            if (inAll)
            {
                return candidate;
            }
        }

        return any;
    }

    // ---- C3 internal helpers ----

    /**
     * Extract direct supertypes of a type from its generalizations.
     */
    private static MutableList<Type> directSupertypes(Type type)
    {
        if (type._generalizations() == null || type._generalizations().isEmpty())
        {
            return Lists.mutable.empty();
        }
        return type._generalizations()
                .collect(g -> g._general()._rawType())
                .select(t -> t != null)
                .toList();
    }

    /**
     * The merge step of C3 linearization.
     *
     * <p>
     * Repeatedly find the first head of any input list that does not appear
     * in the tail of any other input list. Remove that element from all lists
     * and append it to the result. Repeat until all lists are empty.
     * </p>
     */
    private static MutableList<Type> merge(MutableList<MutableList<Type>> lists)
    {
        MutableList<Type> result = Lists.mutable.empty();

        while (true)
        {
            // Remove empty lists
            lists.removeIf(MutableList::isEmpty);
            if (lists.isEmpty())
            {
                break;
            }

            // Find a good head: one that does not appear in the tail of any other list
            Type candidate = null;
            for (MutableList<Type> list : lists)
            {
                Type head = list.getFirst();
                if (!appearsInTailOfAny(head, lists))
                {
                    candidate = head;
                    break;
                }
            }

            if (candidate == null)
            {
                throw new IllegalStateException(
                        "Inconsistent hierarchy: cannot compute C3 linearization. " +
                        "Remaining lists: " + formatTypeLists(lists));
            }

            result.add(candidate);

            // Remove candidate from the head of all lists where it appears
            Type c = candidate;
            for (MutableList<Type> list : lists)
            {
                if (!list.isEmpty() && list.getFirst() == c)
                {
                    list.remove(0);
                }
            }
        }

        return result;
    }

    /**
     * Check if a candidate type appears in the tail (all elements except the first)
     * of any list.
     */
    private static boolean appearsInTailOfAny(Type candidate, MutableList<MutableList<Type>> lists)
    {
        for (MutableList<Type> list : lists)
        {
            if (list.size() > 1)
            {
                for (int i = 1; i < list.size(); i++)
                {
                    if (list.get(i) == candidate)
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    private static String typeName(Type type)
    {
        if (type instanceof PackageableElement pe)
        {
            return _PackageableElement.path(pe);
        }
        return type.getClass().getSimpleName();
    }

    private static String formatTypeLists(MutableList<MutableList<Type>> lists)
    {
        return "[" + lists.collect(list -> "[" + list.collect(_Type::typeName).makeString(", ") + "]").makeString(", ") + "]";
    }

    /**
     * Check if a type is the top type (Any).
     */
    public static boolean isTopType(Type type)
    {
        if (type instanceof PackageableElement pe)
        {
            return "Any".equals(pe._name());
        }
        return false;
    }

    /**
     * Check if a type is the bottom type (Nil).
     * Nil is a subtype of every type.
     */
    public static boolean isBottomType(Type type)
    {
        return type instanceof PackageableElement pe && "Nil".equals(pe._name());
    }

    /**
     * Check if a type is in the Function hierarchy (i.e. Function or a subtype
     * of Function like FunctionDefinition, LambdaFunction, etc.).
     */
    public static boolean isFunctionType(Type type, MetadataAccess model)
    {
        return linearize(type, model).anySatisfy(t ->
                t instanceof PackageableElement pe && "Function".equals(pe._name()));
    }

    /**
     * Check if {@code subtype} is a subtype of (or equal to) {@code supertype}.
     * <p>
     * Nil (bottom type) is a subtype of every type.
     * Every type is a subtype of Any (top type).
     */
    public static boolean subtypeOf(Type subtype, Type supertype, MetadataAccess model)
    {
        if (subtype == supertype)
        {
            return true;
        }
        if (isBottomType(subtype) || isTopType(supertype))
        {
            return true;
        }
        return linearize(subtype, model).contains(supertype);
    }
}
