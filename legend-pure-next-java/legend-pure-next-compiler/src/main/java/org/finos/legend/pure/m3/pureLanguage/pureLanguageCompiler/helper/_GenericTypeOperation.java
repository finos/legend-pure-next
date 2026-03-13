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

import meta.pure.metamodel.relation.Column;
import meta.pure.metamodel.relation.GenericTypeOperation;
import meta.pure.metamodel.relation.GenericTypeOperationImpl;
import meta.pure.metamodel.relation.GenericTypeOperationType;
import meta.pure.metamodel.relation.RelationType;
import meta.pure.metamodel.relation.RelationTypeImpl;
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.InferredGenericTypeImpl;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Sets;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Utility methods for {@link GenericTypeOperation}.
 * <p>
 * A {@code GenericTypeOperation} represents type algebra on relation types:
 * <ul>
 *   <li>{@code T+R} — UNION: concatenate columns</li>
 *   <li>{@code T-R} — DIFFERENCE: remove columns by name</li>
 *   <li>{@code T=R} — EQUAL: type equality constraint</li>
 *   <li>{@code T⊆R} — SUBSET: subset constraint</li>
 * </ul>
 */
public final class _GenericTypeOperation
{
    private _GenericTypeOperation()
    {
    }

    /**
     * Resolve a {@link GenericTypeOperation} by recursing into left and right,
     * and evaluating the operation if both sides become concrete RelationTypes.
     *
     * @return the resolved GenericType, which may be a concrete RelationType,
     *         a partially-resolved GenericTypeOperation, or the original if unchanged
     */
    public static GenericType makeAsConcreteAsPossible(GenericTypeOperation gto, ParametersBinding bindings, MetadataAccess model)
    {
        GenericType resolvedLeft = _GenericType.makeAsConcreteAsPossible(gto._left(), bindings, model);
        GenericType resolvedRight = _GenericType.makeAsConcreteAsPossible(gto._right(), bindings, model);

        // If both sides are concrete RelationTypes, evaluate the operation
        if (resolvedLeft._rawType() instanceof RelationType leftRT
                && resolvedRight._rawType() instanceof RelationType rightRT)
        {
            RelationType resultRT = evaluateRelationTypeOperation(leftRT, rightRT, gto._type(), model);
            if (resultRT != null)
            {
                return new InferredGenericTypeImpl()._rawType(resultRT);
            }
        }

        if (resolvedLeft != gto._left() || resolvedRight != gto._right())
        {
            return new GenericTypeOperationImpl()
                    ._left(resolvedLeft)
                    ._right(resolvedRight)
                    ._type(gto._type());
        }
        return gto;
    }

    /**
     * Evaluate a {@link GenericTypeOperationType} on two concrete {@link RelationType}s.
     * <ul>
     *   <li><b>UNION</b>: concatenate columns from left and right</li>
     *   <li><b>DIFFERENCE</b>: remove columns from left whose names appear in right</li>
     *   <li><b>EQUAL / SUBSET</b>: not evaluated (returns null)</li>
     * </ul>
     */
    public static RelationType evaluateRelationTypeOperation(
            RelationType leftRT,
            RelationType rightRT,
            GenericTypeOperationType opType,
            MetadataAccess model)
    {
        return switch (opType)
        {
            case UNION ->
            {
                RelationTypeImpl result = new RelationTypeImpl();
                GenericType ownerGT = new ConcreteGenericTypeImpl()._rawType(result);
                MutableList<Column> columns = Lists.mutable.empty();
                if (leftRT._columns() != null)
                {
                    for (Column col : leftRT._columns())
                    {
                        columns.add(_Column.build(col._name(), ownerGT, col._genericType(), col._multiplicity(), col._nameWildCard() != null && col._nameWildCard(), model));
                    }
                }
                if (rightRT._columns() != null)
                {
                    for (Column col : rightRT._columns())
                    {
                        columns.add(_Column.build(col._name(), ownerGT, col._genericType(), col._multiplicity(), col._nameWildCard() != null && col._nameWildCard(), model));
                    }
                }
                yield result._columns(columns);
            }
            case DIFFERENCE ->
            {
                MutableSet<String> rightNames = Sets.mutable.empty();
                if (rightRT._columns() != null)
                {
                    rightRT._columns().forEach(c -> rightNames.add(c._name()));
                }
                RelationTypeImpl result = new RelationTypeImpl();
                GenericType ownerGT = new ConcreteGenericTypeImpl()._rawType(result);
                MutableList<Column> columns = Lists.mutable.empty();
                if (leftRT._columns() != null)
                {
                    for (Column col : leftRT._columns())
                    {
                        if (!rightNames.contains(col._name()))
                        {
                            columns.add(_Column.build(col._name(), ownerGT, col._genericType(), col._multiplicity(), col._nameWildCard() != null && col._nameWildCard(), model));
                        }
                    }
                }
                yield result._columns(columns);
            }
            case EQUAL, SUBSET -> null;
        };
    }

    /**
     * Decompose a GenericTypeOperation for type parameter binding.
     * <ul>
     *   <li><b>SUBSET</b> {@code (Z⊆T)}: bind only the left side (Z) to the arg</li>
     *   <li><b>EQUAL</b> {@code (Z=(?:K))}: bind both sides to the arg</li>
     * </ul>
     *
     * @param gto      the operation on the parameter side
     * @param argGT    the concrete argument type
     * @param bindings the bindings to populate
     */
    public static void collectTypeParameterBindings(GenericTypeOperation gto, GenericType argGT, ParametersBinding bindings)
    {
        if (gto._type() == GenericTypeOperationType.SUBSET)
        {
            _GenericType.collectTypeParameterBindings(gto._left(), argGT, bindings);
        }
        else if (gto._type() == GenericTypeOperationType.EQUAL)
        {
            _GenericType.collectTypeParameterBindings(gto._left(), argGT, bindings);
            _GenericType.collectTypeParameterBindings(gto._right(), argGT, bindings);
        }
    }

    /**
     * Check whether a GenericTypeOperation is concrete enough to use.
     * <p>
     * For SUBSET {@code (Z⊆T)} and EQUAL {@code (V=(?:K))}, only the right side
     * needs to be concrete — the left side is a derived type parameter.
     * For other operations (UNION, DIFFERENCE), both sides must be concrete.
     */
    public static boolean isConcrete(GenericTypeOperation gto)
    {
        if (gto._type() == GenericTypeOperationType.SUBSET || gto._type() == GenericTypeOperationType.EQUAL)
        {
            return _GenericType.isConcrete(gto._right());
        }
        return _GenericType.isConcrete(gto._left()) && _GenericType.isConcrete(gto._right());
    }

    /**
     * Print a GenericTypeOperation in Pure syntax, e.g. {@code T-Z+V}.
     */
    public static void printTo(GenericTypeOperation gto, boolean fullPath, StringBuilder sb)
    {
        _GenericType.printTo(gto._left(), fullPath, sb);
        sb.append(switch (gto._type())
        {
            case UNION -> "+";
            case DIFFERENCE -> "-";
            case EQUAL -> "=";
            case SUBSET -> "⊆";
        });
        _GenericType.printTo(gto._right(), fullPath, sb);
    }

    /**
     * Collect all referenced type parameter names in a GenericTypeOperation.
     */
    public static void collectReferencedTypeParameterNames(GenericTypeOperation gto, MutableSet<String> names)
    {
        _GenericType.collectReferencedTypeParameterNames(gto._left(), names);
        _GenericType.collectReferencedTypeParameterNames(gto._right(), names);
    }

    /**
     * Check compatibility of a GenericTypeOperation against a concrete GenericType.
     * <p>
     * EQUAL ({@code V=(?:K)}): the right side is the structural constraint —
     * check {@code isCompatible(right, other)}.
     * <p>
     * SUBSET ({@code Z⊆T}): the right side T is the superset —
     * check {@code isCompatible(other, right)}, i.e. every column in actual
     * must exist in the superset T.
     */
    public static boolean isCompatible(GenericTypeOperation gto, GenericType other, boolean contravariant, MetadataAccess model)
    {
        GenericType right = gto._right();
        if (right == null || right._rawType() == null)
        {
            return true;
        }
        if (gto._type() == GenericTypeOperationType.EQUAL)
        {
            return _GenericType.isCompatible(right, other, contravariant, model);
        }
        if (gto._type() == GenericTypeOperationType.SUBSET)
        {
            // Actual must be a subset of the superset T: every column in actual must be in T
            return _GenericType.isCompatible(other, right, contravariant, model);
        }
        return true;
    }

    /**
     * Reconcile inferred types in matching GenericTypeOperations.
     * Recurses into left and right sides.
     */
    public static void reconcileInferred(GenericTypeOperation expected, GenericTypeOperation actual, MetadataAccess model)
    {
        _GenericType.reconcileInferred(expected._left(), actual._left(), model);
        _GenericType.reconcileInferred(expected._right(), actual._right(), model);
    }
}
