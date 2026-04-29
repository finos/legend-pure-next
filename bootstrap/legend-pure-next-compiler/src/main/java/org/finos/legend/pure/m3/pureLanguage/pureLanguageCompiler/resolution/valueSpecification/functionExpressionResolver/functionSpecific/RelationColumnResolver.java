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

import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.relation.Column;
import meta.pure.metamodel.relation.GenericTypeOperation;
import meta.pure.metamodel.relation.RelationType;
import meta.pure.metamodel.relation.RelationTypeImpl;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.InferredGenericTypeImpl;
import meta.pure.metamodel.type.generics.TypeParameter;
import meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.FunctionCallParametersBinding;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.ParametersBinding;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Column;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._RelationType;

import java.util.Objects;

import static org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext.lazy;

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
    public static FunctionExpression enrichRelationTypeHolderForMagicalFunctions(
            FunctionExpression expr,
            FunctionType functionType,
            ParametersBinding bindings,
            MetadataAccess model,
            CompilationContext context)
    {
        String funcName = expr._functionName();
        if (funcName == null)
        {
            return expr;
        }
        ListIterable<? extends ValueSpecification> paramValues = expr._parametersValues();
        FunctionCallParametersBinding currentNode = context.compilerContextExtensions(PureLanguageCompilerContext.class).currentFunctionCallNode();
        context.debug("enrichRelationTypeHolder: %s bindings=%s parentBindings=%s", funcName, bindings, lazy(() -> currentNode != null ? currentNode.printParentBindings() : "[]"));
        if ((funcName.equals("funcColSpec") || funcName.equals("funcColSpec2")) && paramValues.size() == 3)
        {
            context.debug("enrichRelationTypeHolder: funcColSpec branch");
            FunctionExpression modifiedExpression = enrichColumnTypeHolder(expr, paramValues, 0, 1, 2, model);
            updateTypeHolderBinding(functionType, modifiedExpression._parametersValues(), 2, bindings);
            return modifiedExpression;
        }
        else if ((funcName.equals("funcColSpecArray") || funcName.equals("funcColSpecArray2")) && paramValues.size() == 2)
        {
            context.debug("enrichRelationTypeHolder: funcColSpecArray branch");
            FunctionExpression modifiedExpression = mergeColSpecArrayTypeHolder(expr, paramValues, model);
            updateTypeHolderBinding(functionType, modifiedExpression._parametersValues(), 1, bindings);
            return modifiedExpression;
        }
        else if ((funcName.equals("aggColSpec") || funcName.equals("aggColSpec2")) && paramValues.size() == 4)
        {
            context.debug("enrichRelationTypeHolder: aggColSpec branch");
            FunctionExpression modifiedExpression = enrichColumnTypeHolder(expr, paramValues, 1, 2, 3, model);
            updateTypeHolderBinding(functionType, modifiedExpression._parametersValues(), 3, bindings);
            return modifiedExpression;
        }
        else if ((funcName.equals("aggColSpecArray") || funcName.equals("aggColSpecArray2")) && paramValues.size() == 2)
        {
            context.debug("enrichRelationTypeHolder: aggColSpecArray branch");
            FunctionExpression modifiedExpression = mergeColSpecArrayTypeHolder(expr, paramValues, model);
            updateTypeHolderBinding(functionType, modifiedExpression._parametersValues(), 1, bindings);
            return modifiedExpression;
        }
        else if ((funcName.equals("colSpecArray") || funcName.equals("colSpec")))
        {
            context.debug("enrichRelationTypeHolder: colSpecArray/colSpec branch");
            FunctionExpression modifiedExpression = tryResolveSubsetForTypeHolder(expr, functionType, paramValues, bindings, model, context);
            updateTypeHolderBinding(functionType, modifiedExpression._parametersValues(), 1, bindings);
            return modifiedExpression;
        }
        return expr;
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
    private static FunctionExpression tryResolveSubsetForTypeHolder(
            FunctionExpression expr,
            FunctionType functionType,
            ListIterable<? extends ValueSpecification> paramValues,
            ParametersBinding bindings,
            MetadataAccess model,
            CompilationContext context)
    {
        // Get the TypeHolder's type parameter name (T)
        String typeParamName = ((TypeParameter) _GenericType.type(_GenericType.typeArguments(functionType._parameters().get(1)._genericType()).getFirst()))._name();

        // Check if bindings have T bound
        MutableList<GenericType> boundTypes = bindings.typeBindings().get(typeParamName);
        if (boundTypes == null || boundTypes.isEmpty())
        {
            return expr;
        }

        GenericType boundGT = boundTypes.getAny();

        // Case 1: T bound to SUBSET operation (Z=(?:K)⊆referenceRelation) — look up columns
        if (boundGT instanceof GenericTypeOperation gto && "Subset".equals(gto._operationType()._name()))
        {
            return resolveFromSubset(expr, gto, paramValues, bindings, model, context);
        }

        // Case 2: T bound to EQUAL with wildcard (V=(?:K)) — resolve K from bindings
        if (boundGT instanceof GenericTypeOperation gto && "Equal".equals(gto._operationType()._name()))
        {
            return resolveFromEqualWithWildcard(expr, gto, paramValues, bindings, model, context);
        }

        return expr;
    }

    /**
     * SUBSET case: look up column names from the colSpec arguments in the
     * reference relation (right side of SUBSET) and build the enriched type.
     */
    private static FunctionExpression resolveFromSubset(
            FunctionExpression expr,
            GenericTypeOperation subsetOp,
            ListIterable<? extends ValueSpecification> paramValues,
            ParametersBinding bindings,
            MetadataAccess model,
            CompilationContext context)
    {
        // Resolve the right side (reference relation) of the SUBSET
        RelationType referenceRT = (RelationType) (_GenericType.type(_GenericType.makeAsConcreteAsPossible(subsetOp._right(), bindings, model)));

        // Extract column names from the string arguments
        MutableList<String> columnNames = extractColumnNames(expr);

        // Look up each column in the reference relation — this IS the SUBSET result
        RelationTypeImpl enrichedRT = new RelationTypeImpl();
        GenericType enrichedGT = new InferredGenericTypeImpl(model)._type(enrichedRT);
        MutableList<Column> enrichedColumns = columnNames.collect(colName ->
                {
                    Column foundColumn = referenceRT._columns().detect(c -> c._name().equals(colName));
                    if (foundColumn == null)
                    {
                        context.addError(new CompilationError(
                                "The column '" + colName + "' can't be found in the relation "
                                        + _RelationType.print(referenceRT, false),
                                expr._sourceInformation()));
                        return null;
                    }
                    return _Column.build(
                            colName, enrichedGT, foundColumn._genericType(),
                            foundColumn._multiplicity(), false, model);
                }
        ).select(Objects::nonNull);
        enrichedRT._columns(enrichedColumns)
                  ._classifierGenericType(
                            new UserDefinedGenericTypeImpl(model)
                                    ._type((Type) model.getElement("meta::pure::metamodel::relation::RelationType"))
                                    ._typeArguments(Lists.mutable.with(enrichedGT))
                  );

        context.debug("tryResolveSubsetForTypeHolder: resolved Z=%s", lazy(() -> _GenericType.print(enrichedGT)));

        // Set the TypeHolder's GT to the resolved Z
        ValueSpecification typeHolder = paramValues.get(1);
        typeHolder._classifierGenericType(_GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::valuespecification::CompilerGenericTypeAndMultiplicityHolder"), model));
        typeHolder._genericType(
                _GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::valuespecification::CompilerGenericTypeAndMultiplicityHolder"), model)
                        ._multiplicityArguments(Lists.mutable.with((Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::PureOne")))
                        ._typeArguments(Lists.mutable.with(enrichedGT))
        );
        return expr._parametersValues(Lists.mutable.with(paramValues.get(0), typeHolder));
    }

    /**
     * EQUAL with wildcard case: resolve the wildcard column type (K) from
     * bindings, and build a column using the string argument's name and K's type.
     * E.g., V=(?:K) with K=String and colName="newLegal" → (newLegal:String[1])
     */
    private static FunctionExpression resolveFromEqualWithWildcard(
            FunctionExpression expr,
            GenericTypeOperation equalOp,
            ListIterable<? extends ValueSpecification> paramValues,
            ParametersBinding bindings,
            MetadataAccess model,
            CompilationContext context)
    {
        // The right side of EQUAL should be a RelationType with a wildcard column: (?:K)
        Column wildcardCol = ((RelationType) _GenericType.type(_GenericType.makeAsConcreteAsPossible(equalOp._right(), bindings, model)))._columns().getFirst();

        GenericType resolvedColType = _GenericType.makeAsConcreteAsPossible(wildcardCol._genericType(), bindings, model);

        // Extract column names from the string arguments
        MutableList<String> columnNames = extractColumnNames(expr);

        // Build the enriched RelationType using the column name + resolved K type
        RelationTypeImpl enrichedRT = new RelationTypeImpl();
        GenericType enrichedGT = new InferredGenericTypeImpl(model)._type(enrichedRT);
        enrichedRT._columns(columnNames.collect(colName ->
                                _Column.build(
                                        colName,
                                        enrichedGT,
                                        resolvedColType,
                                        wildcardCol._multiplicity() != null
                                                ? _Multiplicity.makeAsConcreteAsPossible(wildcardCol._multiplicity(), bindings)
                                                : new meta.pure.metamodel.multiplicity.UserDefinedAdHocMultiplicityImpl(model)
                                                  ._lowerBound(new meta.pure.metamodel.multiplicity.MultiplicityValueImpl(model)._value(1L))
                                                  ._upperBound(new meta.pure.metamodel.multiplicity.MultiplicityValueImpl(model)._value(1L)),
                                        false,
                                        model
                                )
                        )
                    )
                    ._classifierGenericType(
                            new UserDefinedGenericTypeImpl(model)
                                    ._type((Type) model.getElement("meta::pure::metamodel::relation::RelationType"))
                                    ._typeArguments(Lists.mutable.with(enrichedGT))
                    );

        context.debug("resolveFromEqualWithWildcard: resolved V=%s", lazy(() -> _GenericType.print(enrichedGT)));

        return expr._parametersValues(
                Lists.mutable.with(paramValues.get(0), setCgt(paramValues.get(1), model)
                                ._genericType(_GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::valuespecification::CompilerGenericTypeAndMultiplicityHolder"), model)
                                        ._multiplicityArguments(Lists.mutable.with((Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::PureOne")))
                                        ._typeArguments(Lists.mutable.with(enrichedGT))
                                )
                )
        );
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
    private static FunctionExpression mergeColSpecArrayTypeHolder(
            FunctionExpression expr,
            ListIterable<? extends ValueSpecification> paramValues,
            MetadataAccess model)
    {
        MutableList<Column> mergedColumns = ((Collection) paramValues.get(0))._values().flatCollect(v ->
                ((RelationType) _GenericType.type(_GenericType.typeArguments(((FunctionExpression) v)._parametersValues().getLast()._genericType()).getFirst()))._columns()
        );
        RelationType mergedRT = new RelationTypeImpl();

        GenericType enrichedGT = new InferredGenericTypeImpl(model)._type(mergedRT);

        mergedRT._columns(mergedColumns)
                ._classifierGenericType(
                        new UserDefinedGenericTypeImpl(model)
                                ._type((Type) model.getElement("meta::pure::metamodel::relation::RelationType"))
                                ._typeArguments(Lists.mutable.with(enrichedGT))
                );


        return expr._parametersValues(
                Lists.mutable.with(
                        paramValues.get(0),
                        setCgt(paramValues.get(1), model)
                                ._genericType(_GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::valuespecification::CompilerGenericTypeAndMultiplicityHolder"), model)
                                        ._multiplicityArguments(Lists.mutable.with((Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::PureOne")))
                                        ._typeArguments(Lists.mutable.with(new InferredGenericTypeImpl(model)._type(mergedRT))))
                )
        );
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
        if (enrichedGT != null)
        {
            GenericType paramGT = functionType._parameters().get(typeHolderArgIdx)._genericType();
            String typeParamName = ((meta.pure.metamodel.type.generics.TypeParameter) _GenericType.type(_GenericType.typeArguments(paramGT).getFirst()))._name();
            MutableList<GenericType> bound = bindings.typeBindings().getIfAbsentPut(typeParamName, Lists.mutable::empty);
            bound.clear();
            bound.add(_GenericType.typeArguments(enrichedGT).getFirst());
        }
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
    private static FunctionExpression enrichColumnTypeHolder(
            FunctionExpression expr,
            ListIterable<? extends ValueSpecification> paramValues,
            int lambdaIdx, int nameIdx, int typeHolderIdx,
            MetadataAccess model)
    {
        LambdaFunction lambda = (LambdaFunction) ((AtomicValue) paramValues.get(lambdaIdx))._value();
        ValueSpecification lastExpr = lambda._expressionSequence().getLast();
        String colName = (String) ((AtomicValue) paramValues.get(nameIdx))._value();

        RelationTypeImpl relationType = new RelationTypeImpl();
        GenericType ownerGT = new InferredGenericTypeImpl(model)._type(relationType);
        Column col = _Column.build(colName, ownerGT, lastExpr._genericType(), lastExpr._multiplicity(), false, model);
        relationType._columns(Lists.mutable.with(col))
                    ._classifierGenericType(
                            new UserDefinedGenericTypeImpl(model)
                                    ._type((Type) model.getElement("meta::pure::metamodel::relation::RelationType"))
                                    ._typeArguments(Lists.mutable.with(ownerGT))
                    );

        return expr._parametersValues(paramValues.collectWithIndex((x, y) ->
                        y == typeHolderIdx ? setCgt(x, model)._genericType(_GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::valuespecification::CompilerGenericTypeAndMultiplicityHolder"), model)
                                                            ._multiplicityArguments(Lists.mutable.with((Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::PureOne")))
                                                            ._typeArguments(Lists.mutable.with(ownerGT)))
                                : x
                ).toList()
        );
    }

    /** Set CGT on a CompilerGenericTypeAndMultiplicityHolder so it can be serialized to PDB. */
    private static ValueSpecification setCgt(ValueSpecification vs, MetadataAccess model)
    {
        vs._classifierGenericType(_GenericType.buildUserDefinedGenericType(
                (meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::valuespecification::CompilerGenericTypeAndMultiplicityHolder"), model));
        return vs;
    }
}
