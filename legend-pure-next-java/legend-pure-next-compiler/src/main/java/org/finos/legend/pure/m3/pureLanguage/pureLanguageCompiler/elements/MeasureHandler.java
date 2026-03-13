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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.elements;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.type.Measure;
import meta.pure.metamodel.type.MeasureImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.UnitImpl;
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import meta.pure.metamodel.relationship.GeneralizationImpl;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.LambdaCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.SourceInformationCompiler;

/**
 * Handler for Measure and its Units.
 */
public final class MeasureHandler
{
    private MeasureHandler()
    {
    }

    public static Measure firstPass(meta.pure.protocol.grammar.type.Measure grammar)
    {
        MeasureImpl measure = new MeasureImpl()
                ._name(grammar._name());

        // Create canonical unit
        if (grammar._canonicalUnit() != null)
        {
            meta.pure.protocol.grammar.type.Unit gu = grammar._canonicalUnit();
            measure._canonicalUnit(new UnitImpl()
                    ._name(gu._name())
                    ._sourceInformation(SourceInformationCompiler.compile(gu._sourceInformation())));
        }

        // Create non-canonical units
        if (grammar._nonCanonicalUnits() != null && grammar._nonCanonicalUnits().notEmpty())
        {
            measure._nonCanonicalUnits(grammar._nonCanonicalUnits().collect(gu ->
                    new UnitImpl()
                            ._name(gu._name())
                            ._sourceInformation(SourceInformationCompiler.compile(gu._sourceInformation()))));
        }

        return measure;
    }

    public static Measure secondPass(MeasureImpl result, meta.pure.protocol.grammar.type.Measure grammar, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        result._classifierGenericType(
                new ConcreteGenericTypeImpl()
                        ._rawType((Type) model.getElement("meta::pure::metamodel::type::Measure")))
                ._sourceInformation(SourceInformationCompiler.compile(grammar._sourceInformation()));

        // Wire each unit: measure backlink, classifierGenericType, generalizations, conversionFunction
        Type unitType = (Type) model.getElement("meta::pure::metamodel::type::Unit");

        if (result._canonicalUnit() != null)
        {
            result._canonicalUnit(wireUnit((UnitImpl) result._canonicalUnit(), grammar._canonicalUnit(), result, unitType, imports, model, context));
        }

        if (result._nonCanonicalUnits() != null && grammar._nonCanonicalUnits() != null)
        {
            MutableList<meta.pure.protocol.grammar.type.Unit> grammarUnits = (MutableList<meta.pure.protocol.grammar.type.Unit>) grammar._nonCanonicalUnits();
            MutableList<meta.pure.metamodel.type.Unit> compiledUnits = (MutableList<meta.pure.metamodel.type.Unit>) result._nonCanonicalUnits();
            result._nonCanonicalUnits(compiledUnits.collectWithIndex((u, i) ->
                    wireUnit((UnitImpl) u, grammarUnits.get(i), result, unitType, imports, model, context)));
        }

        return result;
    }

    private static UnitImpl wireUnit(UnitImpl unit, meta.pure.protocol.grammar.type.Unit grammarUnit, MeasureImpl measure, Type unitType, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        unit._measure(measure)
                ._classifierGenericType(new ConcreteGenericTypeImpl()._rawType(unitType))
                ._generalizations(Lists.mutable.with(
                        new GeneralizationImpl()
                                ._general(new ConcreteGenericTypeImpl()._rawType(measure))));

        if (grammarUnit != null && grammarUnit._conversionFunction() != null)
        {
            meta.pure.metamodel.function.LambdaFunctionImpl compiledLambda = LambdaCompiler.compile(grammarUnit._conversionFunction(), imports, model, context);
            // Set the parameter type to Number[1] as per the metamodel specification
            if (compiledLambda._parameters() != null)
            {
                compiledLambda._parameters().forEach(p ->
                        ((meta.pure.metamodel.valuespecification.VariableExpressionImpl) p)
                                ._genericType(new ConcreteGenericTypeImpl()._rawType((Type) model.getElement("meta::pure::metamodel::type::primitives::Number")))
                                ._multiplicity((meta.pure.metamodel.multiplicity.Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::PureOne")));
            }
            unit._conversionFunction(compiledLambda);
        }

        return unit;
    }

    public static PackageableElement thirdPass(Measure me, MetadataAccess pureModel, CompilationContext context)
    {
        return me;
    }
}
