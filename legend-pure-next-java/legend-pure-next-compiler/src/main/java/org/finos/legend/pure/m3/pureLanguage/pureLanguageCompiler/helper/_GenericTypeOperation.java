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
import meta.pure.metamodel.relation.RelationType;
import meta.pure.metamodel.relation.RelationTypeImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.GenericTypeValue;
import meta.pure.metamodel.type.generics.InferredGenericTypeImpl;
import meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Sets;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.ParametersBinding;

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
 *
 * <p>Operation types are compared by {@code _name()} since enum values
 * can be either generated Impl instances or graph-loaded Enum instances.</p>
 */
public final class _GenericTypeOperation
{
    private _GenericTypeOperation()
    {
    }

    /** Get the operation name from a GenericTypeOperationType enum instance. */
    private static String opName(GenericTypeOperation gto)
    {
        return gto._operationType()._name();
    }

    private static boolean isUnion(GenericTypeOperation gto) { return "Union".equals(opName(gto)); }
    private static boolean isDifference(GenericTypeOperation gto) { return "Difference".equals(opName(gto)); }
    private static boolean isSubset(GenericTypeOperation gto) { return "Subset".equals(opName(gto)); }
    private static boolean isEqual(GenericTypeOperation gto) { return "Equal".equals(opName(gto)); }

    /**
     * Resolve a {@link GenericTypeOperation} by recursing into left and right,
     * and evaluating the operation if both sides become concrete RelationTypes.
     */
    public static GenericType makeAsConcreteAsPossible(GenericTypeOperation gto, ParametersBinding bindings, MetadataAccess model)
    {
        GenericType resolvedLeft = _GenericType.makeAsConcreteAsPossible(gto._left(), bindings, model);
        GenericType resolvedRight = _GenericType.makeAsConcreteAsPossible(gto._right(), bindings, model);

        // If both sides are concrete RelationTypes, evaluate the operation
        if (resolvedLeft instanceof GenericTypeValue && _GenericType.type(resolvedLeft) instanceof RelationType leftRT
                && resolvedRight instanceof GenericTypeValue && _GenericType.type(resolvedRight) instanceof RelationType rightRT)
        {
            RelationType resultRT = evaluateRelationTypeOperation(leftRT, rightRT, opName(gto), model);
            if (resultRT != null)
            {
                return new InferredGenericTypeImpl(model)._type(resultRT);
            }
        }

        if (resolvedLeft != gto._left() || resolvedRight != gto._right())
        {
            return new GenericTypeOperationImpl(model)
                    ._left(resolvedLeft)
                    ._right(resolvedRight)
                    ._operationType(gto._operationType());
        }
        return gto;
    }

    /**
     * Evaluate a relation type operation on two concrete {@link RelationType}s.
     * Package-visible for testing.
     */
    static RelationType evaluateRelationTypeOperation(
            RelationType leftRT,
            RelationType rightRT,
            String operationName,
            MetadataAccess model)
    {
        if ("Union".equals(operationName))
        {
            RelationTypeImpl result = new RelationTypeImpl();
            GenericType ownerGT = _GenericType.buildUserDefinedGenericType(result, model);
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
            return result
                    ._columns(columns)
                    ._classifierGenericType(
                            new UserDefinedGenericTypeImpl(model)
                                    ._type((Type) model.getElement("meta::pure::metamodel::relation::RelationType"))
                                    ._typeArguments(Lists.mutable.with(ownerGT))
                    );
        }
        else if ("Difference".equals(operationName))
        {
            MutableSet<String> rightNames = Sets.mutable.empty();
            if (rightRT._columns() != null)
            {
                rightRT._columns().forEach(c -> rightNames.add(c._name()));
            }
            RelationTypeImpl result = new RelationTypeImpl();
            GenericType ownerGT = _GenericType.buildUserDefinedGenericType(result, model);
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
            return result
                    ._columns(columns)
                    ._classifierGenericType(
                            new UserDefinedGenericTypeImpl(model)
                                    ._type((Type) model.getElement("meta::pure::metamodel::relation::RelationType"))
                                    ._typeArguments(Lists.mutable.with(ownerGT))
                    );
        }
        // EQUAL, SUBSET → not evaluated
        return null;
    }

    /**
     * Decompose a GenericTypeOperation for type parameter binding.
     */
    public static void collectTypeParameterBindings(GenericTypeOperation gto, GenericType argGT, ParametersBinding bindings)
    {
        if (isSubset(gto))
        {
            _GenericType.collectTypeParameterBindings(gto._left(), argGT, bindings);
        }
        else if (isEqual(gto))
        {
            _GenericType.collectTypeParameterBindings(gto._left(), argGT, bindings);
            _GenericType.collectTypeParameterBindings(gto._right(), argGT, bindings);
        }
    }

    /**
     * Check whether a GenericTypeOperation is concrete enough to use.
     */
    public static boolean isConcrete(GenericTypeOperation gto)
    {
        if (isSubset(gto) || isEqual(gto))
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
        String opStr;
        if (isUnion(gto)) opStr = "+";
        else if (isDifference(gto)) opStr = "-";
        else if (isEqual(gto)) opStr = "=";
        else if (isSubset(gto)) opStr = "⊆";
        else opStr = "?";
        sb.append(opStr);
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
     */
    public static boolean isCompatible(GenericTypeOperation gto, GenericType other, boolean contravariant, MetadataAccess model)
    {
        GenericType right = gto._right();
        if (isEqual(gto))
        {
            return _GenericType.isCompatible(right, other, contravariant, model);
        }
        if (isSubset(gto))
        {
            return _GenericType.isCompatible(other, right, contravariant, model);
        }
        return true;
    }

    /**
     * Reconcile inferred types in matching GenericTypeOperations.
     */
    public static GenericTypeOperation reconcileInferred(GenericTypeOperation expected, GenericTypeOperation actual, MetadataAccess model)
    {
        return new GenericTypeOperationImpl(model)
                ._left(_GenericType.reconcileInferred(expected._left(), actual._left(), model))
                ._right(_GenericType.reconcileInferred(expected._right(), actual._right(), model))
                ._operationType(actual._operationType());
    }
}
