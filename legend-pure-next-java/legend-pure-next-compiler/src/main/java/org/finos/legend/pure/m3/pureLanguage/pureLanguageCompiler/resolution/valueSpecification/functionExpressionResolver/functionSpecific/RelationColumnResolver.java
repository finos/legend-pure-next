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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.functionSpecific;

import static org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext.lazy;


import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.relation.Column;
import meta.pure.metamodel.relation.GenericTypeOperation;
import meta.pure.metamodel.relation.GenericTypeOperationType;
import meta.pure.metamodel.relation.RelationTypeImpl;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.InferredGenericTypeImpl;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Sets;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper.ParametersBinding;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Column;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._RelationType;

/**
 * Handles relation-column-specific enrichment for "magical" column spec
 * functions such as {@code funcColSpec}, {@code aggColSpec},
 * {@code funcColSpecArray}, {@code aggColSpecArray}, and {@code colSpecArray}.
 *
 * <p>These functions carry a compiler-managed TypeHolder parameter whose
 * {@code genericType} encodes the resulting RelationType columns.  After
 * lambda resolution, this resolver enriches the TypeHolder with the
 * concrete column types inferred from the lambda's return type.</p>
 */
public final class RelationColumnResolver
{
    private RelationColumnResolver()
    {
    }

    /**
     * Compiler-tracked enrichment for relation column function TypeHolders.
     * <p>
     * For {@code funcColSpec}: enriches the TypeHolder's RelationType column
     * type from the lambda's actual return type.
     * <p>
     * For {@code funcColSpecArray}: enriches each inner funcColSpec TypeHolder
     * first, then merges their columns into the array's TypeHolder.
     */
    public static void enrichRelationTypeHolderForMagicalFunctions(
            FunctionExpression expr,
            FunctionType functionType,
            ListIterable<? extends ValueSpecification> paramValues,
            ParametersBinding bindings,
            MetadataAccess model,
            CompilationContext context)
    {
        String funcName = expr._functionName();
        if (funcName == null)
        {
            return;
        }
        context.debug("enrichRelationTypeHolder: %s bindings=%s", funcName, bindings);
        if ((funcName.equals("funcColSpec") || funcName.equals("funcColSpec2")) && paramValues.size() == 3)
        {
            context.debug("enrichRelationTypeHolder: funcColSpec branch");
            enrichColumnTypeHolder(paramValues, 0, 1, 2, model);
            updateTypeHolderBinding(functionType, paramValues, 2, bindings);
        }
        else if ((funcName.equals("funcColSpecArray") || funcName.equals("funcColSpecArray2")) && paramValues.size() == 2)
        {
            context.debug("enrichRelationTypeHolder: funcColSpecArray branch");
            mergeColSpecArrayTypeHolder(paramValues);
            updateTypeHolderBinding(functionType, paramValues, 1, bindings);
        }
        else if ((funcName.equals("aggColSpec") || funcName.equals("aggColSpec2")) && paramValues.size() == 4)
        {
            context.debug("enrichRelationTypeHolder: aggColSpec branch");
            enrichColumnTypeHolder(paramValues, 1, 2, 3, model);
            updateTypeHolderBinding(functionType, paramValues, 3, bindings);
        }
        else if ((funcName.equals("aggColSpecArray") || funcName.equals("aggColSpecArray2")) && paramValues.size() == 2)
        {
            context.debug("enrichRelationTypeHolder: aggColSpecArray branch");
            mergeColSpecArrayTypeHolder(paramValues);
            updateTypeHolderBinding(functionType, paramValues, 1, bindings);
        }
        else if ((funcName.equals("colSpecArray") || funcName.equals("colSpec")))
        {
            context.debug("enrichRelationTypeHolder: colSpecArray/colSpec branch");
            tryResolveSubsetForTypeHolder(expr, functionType, paramValues, bindings, model, context);
            updateTypeHolderBinding(functionType, paramValues, 1, bindings);
        }
    }

    /**
     * Try to resolve the TypeHolder's type from type parameter bindings.
     * <p>
     * Handles two cases:
     * <ul>
     * <li>SUBSET: T bound to {@code Z⊆(referenceRelation)} — look up column names
     *     in the reference relation to build the enriched type</li>
     * <li>EQUAL with wildcard: T bound to {@code V=(?:K)} — resolve K from bindings
     *     and build a column using the string argument's name and K's type</li>
     * </ul>
     */
    private static void tryResolveSubsetForTypeHolder(
            FunctionExpression expr,
            FunctionType functionType,
            ListIterable<? extends ValueSpecification> paramValues,
            ParametersBinding bindings,
            MetadataAccess model,
            CompilationContext context)
    {
        // Get the TypeHolder's type parameter name (T)
        GenericType typeHolderParamGT = functionType._parameters().get(1)._genericType();
        if (typeHolderParamGT._typeParameter() == null)
        {
            return;
        }
        String typeParamName = typeHolderParamGT._typeParameter()._name();

        // Check if bindings have T bound
        MutableSet<GenericType> boundTypes = bindings.typeBindings().get(typeParamName);
        if (boundTypes == null || boundTypes.isEmpty())
        {
            return;
        }
        GenericType boundGT = boundTypes.getAny();

        // Case 1: T bound to SUBSET operation (Z=(?:K)⊆referenceRelation) — look up columns
        if (boundGT instanceof GenericTypeOperation gto && gto._type() == GenericTypeOperationType.SUBSET)
        {
            resolveFromSubset(expr, gto, paramValues, bindings, model, context);
            return;
        }

        // Case 2: T bound to EQUAL with wildcard (V=(?:K)) — resolve K from bindings
        if (boundGT instanceof GenericTypeOperation gto && gto._type() == GenericTypeOperationType.EQUAL)
        {
            resolveFromEqualWithWildcard(expr, gto, paramValues, bindings, model, context);
        }
    }

    /**
     * SUBSET case: look up column names from the colSpec arguments in the
     * reference relation (right side of SUBSET) and build the enriched type.
     */
    private static void resolveFromSubset(
            FunctionExpression expr,
            GenericTypeOperation subsetOp,
            ListIterable<? extends ValueSpecification> paramValues,
            ParametersBinding bindings,
            MetadataAccess model,
            CompilationContext context)
    {
        // Resolve the right side (reference relation) of the SUBSET
        GenericType resolvedRight = _GenericType.makeAsConcreteAsPossible(subsetOp._right(), bindings, model);
        if (!(resolvedRight._rawType() instanceof meta.pure.metamodel.relation.RelationType referenceRT))
        {
            return;
        }

        // Extract column names from the string arguments
        MutableList<String> columnNames = extractColumnNames(expr);
        if (columnNames == null || columnNames.isEmpty())
        {
            return;
        }

        // Look up each column in the reference relation — this IS the SUBSET result
        RelationTypeImpl enrichedRT = new RelationTypeImpl();
        GenericType enrichedGT = new InferredGenericTypeImpl()._rawType(enrichedRT);
        MutableList<Column> enrichedColumns = Lists.mutable.empty();

        for (String colName : columnNames)
        {
            Column foundColumn = referenceRT._columns().detect(c -> c._name().equals(colName));
            if (foundColumn == null)
            {
                context.addError(new CompilationError(
                        "The column '" + colName + "' can't be found in the relation "
                                + _RelationType.print(referenceRT, false),
                        expr._sourceInformation()));
                return;
            }
            enrichedColumns.add(_Column.build(
                    colName, enrichedGT, foundColumn._genericType(),
                    foundColumn._multiplicity(), false, model));
        }
        enrichedRT._columns(enrichedColumns);

        context.debug("tryResolveSubsetForTypeHolder: resolved Z=%s", lazy(() -> _GenericType.print(enrichedGT)));

        // Set the TypeHolder's GT to the resolved Z
        ValueSpecification typeHolder = paramValues.get(1);
        if (typeHolder instanceof meta.pure.metamodel.valuespecification.CompilerGenericTypeAndMultiplicityHolderImpl holder)
        {
            holder._genericType(enrichedGT);
        }
    }

    /**
     * EQUAL with wildcard case: resolve the wildcard column type (K) from
     * bindings, and build a column using the string argument's name and K's type.
     * E.g., V=(?:K) with K=String and colName="newLegal" → (newLegal:String[1])
     */
    private static void resolveFromEqualWithWildcard(
            FunctionExpression expr,
            GenericTypeOperation equalOp,
            ListIterable<? extends ValueSpecification> paramValues,
            ParametersBinding bindings,
            MetadataAccess model,
            CompilationContext context)
    {
        // The right side of EQUAL should be a RelationType with a wildcard column: (?:K)
        GenericType right = _GenericType.makeAsConcreteAsPossible(equalOp._right(), bindings, model);
        if (!(right._rawType() instanceof meta.pure.metamodel.relation.RelationType wildcardRT)
                || wildcardRT._columns() == null || wildcardRT._columns().isEmpty())
        {
            return;
        }

        Column wildcardCol = wildcardRT._columns().getFirst();
        if (wildcardCol._nameWildCard() == null || !wildcardCol._nameWildCard())
        {
            return;
        }

        // Resolve the wildcard column's type (K) from bindings
        GenericType resolvedColType = _GenericType.makeAsConcreteAsPossible(wildcardCol._genericType(), bindings, model);
        if (resolvedColType == null || resolvedColType._rawType() == null)
        {
            return;
        }

        // Extract column names from the string arguments
        MutableList<String> columnNames = extractColumnNames(expr);
        if (columnNames == null || columnNames.isEmpty())
        {
            return;
        }

        // Build the enriched RelationType using the column name + resolved K type
        RelationTypeImpl enrichedRT = new RelationTypeImpl();
        GenericType enrichedGT = new InferredGenericTypeImpl()._rawType(enrichedRT);
        MutableList<Column> enrichedColumns = Lists.mutable.empty();

        for (String colName : columnNames)
        {
            meta.pure.metamodel.multiplicity.Multiplicity colMul = wildcardCol._multiplicity() != null
                    ? _Multiplicity.makeAsConcreteAsPossible(wildcardCol._multiplicity(), bindings)
                    : new meta.pure.metamodel.multiplicity.ConcreteMultiplicityImpl()
                            ._lowerBound(new meta.pure.metamodel.multiplicity.MultiplicityValueImpl()._value(1L))
                            ._upperBound(new meta.pure.metamodel.multiplicity.MultiplicityValueImpl()._value(1L));
            enrichedColumns.add(_Column.build(
                    colName, enrichedGT, resolvedColType, colMul, false, model));
        }
        enrichedRT._columns(enrichedColumns);

        context.debug("resolveFromEqualWithWildcard: resolved V=%s", lazy(() -> _GenericType.print(enrichedGT)));

        // Set the TypeHolder's GT to the resolved V
        ValueSpecification typeHolder = paramValues.get(1);
        if (typeHolder instanceof meta.pure.metamodel.valuespecification.CompilerGenericTypeAndMultiplicityHolderImpl holder)
        {
            holder._genericType(enrichedGT);
        }
    }


    /**
     * Extract column names from a colSpecArray or colSpec FunctionExpression.
     */
    private static MutableList<String> extractColumnNames(ValueSpecification arg)
    {
        if (arg instanceof FunctionExpression fe)
        {
            String funcName = fe._functionName();
            if ("colSpecArray".equals(funcName))
            {
                ValueSpecification first = fe._parametersValues().getFirst();
                if (first instanceof Collection coll)
                {
                    MutableList<String> names = Lists.mutable.empty();
                    for (ValueSpecification v : coll._values())
                    {
                        if (v instanceof AtomicValue av && av._value() instanceof String s)
                        {
                            names.add(s);
                        }
                    }
                    return names;
                }
            }
            else if ("colSpec".equals(funcName))
            {
                ValueSpecification first = fe._parametersValues().getFirst();
                if (first instanceof AtomicValue av && av._value() instanceof String s)
                {
                    return Lists.mutable.with(s);
                }
            }
        }
        return null;
    }

    /**
     * Merge inner colSpec TypeHolder columns into the array TypeHolder.
     * Shared by funcColSpecArray and aggColSpecArray branches.
     **/
    private static void mergeColSpecArrayTypeHolder(
            ListIterable<? extends ValueSpecification> paramValues)
    {
        MutableList<Column> mergedColumns = Lists.mutable.empty();
        ((Collection) paramValues.get(0))._values().forEach(v ->
        {
            // Anything buy colspec
            if (v instanceof FunctionExpression k)
            {
                GenericType typeHolderGT = k._parametersValues().getLast()._genericType();
                if (typeHolderGT != null && typeHolderGT._rawType() instanceof meta.pure.metamodel.relation.RelationType rt)
                {
                    mergedColumns.addAllIterable(rt._columns());
                }
            }
        });
        if (mergedColumns.notEmpty())
        {
            meta.pure.metamodel.relation.RelationType mergedRT = new RelationTypeImpl()._columns(mergedColumns);
            ((meta.pure.metamodel.valuespecification.CompilerGenericTypeAndMultiplicityHolderImpl) paramValues.get(1))
                    ._genericType(new InferredGenericTypeImpl()._rawType(mergedRT));
        }
    }

    /**
     * After enriching a TypeHolder, update the corresponding type parameter binding
     * so resolved parameters reflect the enriched type (e.g. T → (name:String[1])
     * instead of the unenriched T → (name:)).
     */
    private static void updateTypeHolderBinding(
            FunctionType functionType,
            ListIterable<? extends ValueSpecification> paramValues,
            int typeHolderArgIdx,
            ParametersBinding bindings)
    {
        GenericType enrichedGT = paramValues.get(typeHolderArgIdx)._genericType();
        GenericType paramGT = functionType._parameters().get(typeHolderArgIdx)._genericType();
        String typeParamName = paramGT._typeParameter()._name();
        MutableSet<GenericType> bound = bindings.typeBindings().getIfAbsentPut(typeParamName, Sets.mutable::empty);
        bound.clear();
        bound.add(enrichedGT);
    }

    /**
     * Fully compute the TypeHolder's genericType from the lambda's return type
     * and the column name String parameter.  The TypeHolder is set as Inferred
     * so that {@code resetResolution} can strip it between candidate attempts.
     *
     * @param lambdaIdx     index of the lambda arg (0 for funcColSpec, 1 for aggColSpec)
     * @param nameIdx       index of the column name String arg (1 for funcColSpec, 0 for aggColSpec)
     * @param typeHolderIdx index of the TypeHolder arg (last parameter)
     */
    private static void enrichColumnTypeHolder(
            ListIterable<? extends ValueSpecification> paramValues,
            int lambdaIdx, int nameIdx, int typeHolderIdx,
            MetadataAccess model)
    {
        ValueSpecification typeHolderArg = paramValues.get(typeHolderIdx);
        if (typeHolderArg instanceof meta.pure.metamodel.valuespecification.CompilerGenericTypeAndMultiplicityHolder
                && paramValues.get(lambdaIdx) instanceof AtomicValue av
                && av._value() instanceof LambdaFunction lambda
                && lambda._expressionSequence() != null
                && lambda._expressionSequence().notEmpty())
        {
            ValueSpecification lastExpr = lambda._expressionSequence().getLast();
            // Extract column name from the String parameter
            String colName = null;
            if (paramValues.get(nameIdx) instanceof AtomicValue nameAv && nameAv._value() instanceof String name)
            {
                colName = name;
            }
            if (colName != null && lastExpr._genericType() != null)
            {
                RelationTypeImpl relationType = new RelationTypeImpl();
                GenericType ownerGT = new InferredGenericTypeImpl()._rawType(relationType);
                Column col = _Column.build(colName, ownerGT, lastExpr._genericType(), lastExpr._multiplicity(), false, model);
                relationType._columns(Lists.mutable.with(col));
                ((meta.pure.metamodel.valuespecification.CompilerGenericTypeAndMultiplicityHolderImpl) typeHolderArg)
                        ._genericType(ownerGT);
            }
        }
    }
}
