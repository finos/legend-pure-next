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

import meta.pure.metamodel.Inferred;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.relation.Column;
import meta.pure.metamodel.relation.RelationType;
import meta.pure.metamodel.relation.RelationTypeImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import meta.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Sets;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Helper methods for {@link RelationType}.
 */
public class _RelationType
{
    private _RelationType()
    {
        // static utility
    }

    /**
     * Return a human-readable string for a {@link RelationType},
     * e.g. {@code (name:String[1], age:Integer[0..1])}.
     *
     * @param rt       the relation type to print
     * @param fullPath if true, use fully-qualified type names in columns
     * @return the formatted string
     */
    public static String print(RelationType rt, boolean fullPath)
    {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        boolean first = true;
        if (rt._columns() != null)
        {
            for (Column col : rt._columns())
            {
                if (!first)
                {
                    sb.append(", ");
                }
                first = false;
                if (col._nameWildCard() != null && col._nameWildCard())
                {
                    sb.append('?');
                }
                else
                {
                    sb.append(col._name());
                }
                sb.append(':');
                if (col._genericType() != null)
                {
                    sb.append(_GenericType.print(col._genericType(), fullPath));
                }
                if (col._multiplicity() != null)
                {
                    sb.append(_Multiplicity.print(col._multiplicity()));
                }
            }
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * Check whether {@code actual} is structurally compatible with {@code declared}.
     * Every column in declared must have a matching column (by name) in actual
     * with compatible type and multiplicity.
     *
     * @param declared      the expected relation type
     * @param actual        the actual relation type
     * @param contravariant if true, the check is in a contravariant position
     * @return true if actual is compatible with declared
     */
    public static boolean isCompatible(RelationType declared, RelationType actual, boolean contravariant, MetadataAccess model)
    {
        if (declared._columns() == null || actual._columns() == null)
        {
            return true;
        }
        for (Column declaredCol : declared._columns())
        {
            // Skip wildcard columns — they're structural templates, not named columns
            if (declaredCol._nameWildCard() != null && declaredCol._nameWildCard())
            {
                continue;
            }
            Column actualCol = actual._columns()
                    .detect(c -> declaredCol._name().equals(c._name()));
            if (actualCol == null)
            {
                return false;
            }
            // Check type compatibility (covariant: actual column type must be subtype of declared)
            if (declaredCol._genericType() != null && actualCol._genericType() != null)
            {
                if (!_GenericType.isCompatible(declaredCol._genericType(), actualCol._genericType(), contravariant, model))
                {
                    return false;
                }
            }
            // Check multiplicity compatibility
            if (declaredCol._multiplicity() != null && actualCol._multiplicity() != null)
            {
                if (!_Multiplicity.subsumes(declaredCol._multiplicity(), actualCol._multiplicity()))
                {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Find the common type for a list of RelationTypes.
     * Returns a RelationType (common columns) or Any/Nil (no common columns).
     */
    public static meta.pure.metamodel.type.Type findCommonRelationType(MutableList<RelationType> relationTypes, MetadataAccess model)
    {
        return findCommonRelationType(relationTypes, false, model);
    }

    /**
     * Find the common type for a list of RelationTypes.
     * The result contains the intersection of column names, with each
     * column's type and multiplicity unified across all inputs.
     * When no common columns exist, returns Any (covariant) or Nil (contravariant).
     *
     * @param relationTypes the relation types to unify
     * @param contravariant if true, uses GLB (toward Nil) instead of LCA (toward Any)
     * @return RelationType if common columns exist, Any/Nil otherwise, null if input is empty
     */
    public static Type findCommonRelationType(MutableList<RelationType> relationTypes, boolean contravariant, MetadataAccess model)
    {
        if (relationTypes == null || relationTypes.isEmpty())
        {
            return null;
        }
        if (relationTypes.size() == 1)
        {
            return relationTypes.getFirst();
        }

        // Find common column names (intersection)
        MutableSet<String> commonNames = Sets.mutable.withAll(
                relationTypes.getFirst()._columns().collect(Column::_name));
        for (int i = 1; i < relationTypes.size(); i++)
        {
            MutableSet<String> names = Sets.mutable.withAll(
                    relationTypes.get(i)._columns().collect(Column::_name));
            commonNames.retainAll(names);
        }

        if (commonNames.isEmpty())
        {
            return contravariant ? model.nil() : model.any();
        }

        // For each common column, find the common type and multiplicity
        RelationTypeImpl result = new RelationTypeImpl();
        GenericType ownerGT = new ConcreteGenericTypeImpl()._rawType(result);

        MutableList<Column> commonColumns = Lists.mutable.empty();
        for (String colName : commonNames)
        {
            MutableList<GenericType> colTypes = Lists.mutable.empty();
            MutableList<Multiplicity> colMuls = Lists.mutable.empty();
            for (RelationType rt : relationTypes)
            {
                Column col = rt._columns().detect(c -> colName.equals(c._name()));
                if (col != null)
                {
                    if (col._genericType() != null)
                    {
                        colTypes.add(col._genericType());
                    }
                    if (col._multiplicity() != null)
                    {
                        colMuls.add(col._multiplicity());
                    }
                }
            }
            GenericType commonType = colTypes.notEmpty() ? _GenericType.findCommonGenericType(colTypes, model) : null;
            Multiplicity commonMul = colMuls.notEmpty() ? _Multiplicity.findCommonMultiplicity(colMuls) : null;
            commonColumns.add(_Column.build(colName, ownerGT, commonType, commonMul, false, model));
        }

        return result._columns(commonColumns);
    }

    /**
     * Check whether all column types in a RelationType are concrete.
     */
    public static boolean isConcrete(RelationType rt)
    {
        if (rt._columns() != null)
        {
            for (Column col : rt._columns())
            {
                if (!_GenericType.isConcrete(col._genericType()))
                {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Collect type and multiplicity parameter bindings from matching two RelationTypes.
     * Matches wildcard columns {@code (?:K[m])} in the parameter side against
     * concrete columns in the argument side, binding both K (type) and m (multiplicity).
     */
    public static void collectTypeParameterBindings(RelationType paramRT, RelationType argRT, ParametersBinding bindings)
    {
        if (paramRT._columns() == null || argRT._columns() == null)
        {
            return;
        }
        for (Column paramCol : paramRT._columns())
        {
            if (paramCol._nameWildCard() != null && paramCol._nameWildCard())
            {
                // Wildcard column (?:K[m]): bind K and m from the first arg column
                Column argCol = argRT._columns().getFirst();
                if (argCol != null)
                {
                    if (paramCol._genericType() != null)
                    {
                        _GenericType.collectTypeParameterBindings(paramCol._genericType(), argCol._genericType(), bindings);
                    }
                    // Bind multiplicity parameter (m) from the concrete column's multiplicity
                    _Multiplicity.collectMultiplicityParameterBindings(paramCol._multiplicity(), argCol._multiplicity(), bindings);
                }
            }
        }
    }

    /**
     * Reconcile inferred types in RelationType columns by name.
     * Widens inferred generic types and multiplicities where the expected type subsumes the actual.
     */
    public static void reconcileInferred(meta.pure.metamodel.relation.RelationType expectedRT,
                                         meta.pure.metamodel.relation.RelationType actualRT,
                                         MetadataAccess model)
    {
        if (expectedRT._columns() != null && actualRT._columns() != null)
        {
            for (var actualCol : actualRT._columns())
            {
                // Skip wildcard columns
                if (actualCol._nameWildCard() != null && actualCol._nameWildCard())
                {
                    continue;
                }
                var expectedCol = expectedRT._columns().detect(c -> actualCol._name().equals(c._name()));
                if (expectedCol != null)
                {
                    if (actualCol._genericType() instanceof Inferred
                            && expectedCol._genericType() != null
                            && _GenericType.isCompatible(expectedCol._genericType(), actualCol._genericType(), model))
                    {
                        ((meta.pure.metamodel.relation.ColumnImpl) actualCol)
                                ._genericType(_GenericType.asInferred(expectedCol._genericType()));
                    }
                    if (actualCol._multiplicity() instanceof Inferred
                            && expectedCol._multiplicity() != null
                            && _Multiplicity.subsumes(expectedCol._multiplicity(), actualCol._multiplicity()))
                    {
                        ((meta.pure.metamodel.relation.ColumnImpl) actualCol)
                                ._multiplicity(_Multiplicity.asInferred(expectedCol._multiplicity(), model));
                    }
                    _GenericType.reconcileInferred(expectedCol._genericType(), actualCol._genericType(), model);
                }
            }
        }
    }

    /**
     * Resolve type parameter references in RelationType column types.
     * Returns a new GenericType wrapping the resolved RelationType if anything changed,
     * or {@code null} if nothing changed.
     * <p>
     * This handles the case where a RelationType has wildcard columns like {@code (?:K)},
     * and K is bound in the bindings. The resolved output will have the concrete type
     * substituted for K.
     *
     * @param rt       the RelationType to resolve
     * @param bindings parameter bindings
     * @param model    the model
     * @return resolved GenericType, or null if unchanged
     */
    public static GenericType makeAsConcreteAsPossible(RelationType rt, ParametersBinding bindings, MetadataAccess model)
    {
        if (rt._columns() == null || rt._columns().isEmpty())
        {
            return null;
        }

        boolean changed = false;
        MutableList<Column> resolvedColumns = Lists.mutable.empty();
        for (Column col : rt._columns())
        {
            GenericType colGT = col._genericType();
            GenericType resolvedColGT = colGT != null ? _GenericType.makeAsConcreteAsPossible(colGT, bindings, model) : colGT;
            Multiplicity colMul = col._multiplicity();
            Multiplicity resolvedColMul = colMul != null ? _Multiplicity.makeAsConcreteAsPossible(colMul, bindings) : colMul;
            if (resolvedColGT != colGT || resolvedColMul != colMul)
            {
                changed = true;
                Multiplicity finalMul = resolvedColMul != null ? resolvedColMul
                        : new meta.pure.metamodel.multiplicity.ConcreteMultiplicityImpl()
                                ._lowerBound(new meta.pure.metamodel.multiplicity.MultiplicityValueImpl()._value(1L))
                                ._upperBound(new meta.pure.metamodel.multiplicity.MultiplicityValueImpl()._value(1L));
                RelationTypeImpl tempRT = new RelationTypeImpl();
                GenericType tempOwnerGT = new ConcreteGenericTypeImpl()._rawType(tempRT);
                resolvedColumns.add(_Column.build(col._name(), tempOwnerGT, resolvedColGT != null ? resolvedColGT : colGT, finalMul,
                        col._nameWildCard() != null && col._nameWildCard(), model));
            }
            else
            {
                resolvedColumns.add(col);
            }
        }
        if (changed)
        {
            RelationTypeImpl newRT = new RelationTypeImpl();
            GenericType newOwnerGT = new meta.pure.metamodel.type.generics.InferredGenericTypeImpl()._rawType(newRT);
            MutableList<Column> rebuiltColumns = Lists.mutable.empty();
            for (Column col : resolvedColumns)
            {
                rebuiltColumns.add(_Column.build(col._name(), newOwnerGT, col._genericType(),
                        col._multiplicity(), col._nameWildCard() != null && col._nameWildCard(), model));
            }
            newRT._columns(rebuiltColumns);
            return newOwnerGT;
        }
        return null;
    }
}
