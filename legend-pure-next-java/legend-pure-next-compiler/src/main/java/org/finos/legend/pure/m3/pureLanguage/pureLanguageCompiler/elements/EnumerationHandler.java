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

import meta.pure.metamodel.function.LambdaFunctionImpl;
import meta.pure.metamodel.function.property.PropertyImpl;
import meta.pure.metamodel.relationship.GeneralizationImpl;
import meta.pure.metamodel.type.EnumImpl;
import meta.pure.metamodel.type.Enumeration;
import meta.pure.metamodel.type.EnumerationImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.valuespecification.AtomicValueImpl;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.SourceInformationCompiler;

/**
 * Handler for Enumeration.
 */
public final class EnumerationHandler
{
    private EnumerationHandler()
    {
    }

    public static Enumeration firstPass(meta.pure.protocol.grammar.type.Enumeration grammar)
    {
        return new EnumerationImpl()
                ._name(grammar._name());
    }

    public static Enumeration secondPass(EnumerationImpl result, meta.pure.protocol.grammar.type.Enumeration grammar, MetadataAccess model)
    {
        // Add generalization: every user-defined enumeration extends Enum
        Type enumType = (Type) model.getElement("meta::pure::metamodel::type::Enum");
        Type propertyType = (Type) model.getElement("meta::pure::metamodel::function::property::Property");
        Type enumerationType = (Type) model.getElement("meta::pure::metamodel::type::Enumeration");
        Multiplicity pureOne = (Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::PureOne");

        // GenericType for this specific enumeration (e.g., CC_GeographicEntityType)
        meta.pure.metamodel.type.generics.GenericType enumGT = new ConcreteGenericTypeImpl()._rawType(result);
        // GenericType for Enumeration<E> parameterized with this enum type
        meta.pure.metamodel.type.generics.GenericType enumerationOfE = new ConcreteGenericTypeImpl()
                ._rawType(enumerationType)
                ._typeArguments(Lists.mutable.with(enumGT));

        // Create one Property per enum value, each with a defaultValue containing the Enum instance
        var properties = grammar._properties().collect(grammarProp ->
        {
            // Create the Enum instance
            EnumImpl enumInstance = new EnumImpl()
                    ._name(grammarProp._name())
                    ._classifierGenericType(enumGT)
                    ._sourceInformation(SourceInformationCompiler.compile(grammarProp._sourceInformation()));

            // Create a parameterless lambda whose body is the AtomicValue
            LambdaFunctionImpl defaultValueLambda = new LambdaFunctionImpl();
            defaultValueLambda._expressionSequence(Lists.mutable.with(
                    new AtomicValueImpl()
                                ._value(enumInstance)
                                ._genericType(enumGT)
                                ._multiplicity(pureOne)
                    )
            );

            // Create the Property: Property<Enumeration<E>, E | 1>
            return new PropertyImpl()
                    ._name(grammarProp._name())
                    ._owner(result)
                    ._genericType(enumGT)
                    ._multiplicity(pureOne)
                    ._classifierGenericType(
                            new ConcreteGenericTypeImpl()
                                    ._rawType(propertyType)
                                    ._typeArguments(Lists.mutable.with(enumerationOfE, enumGT))
                                    ._multiplicityArguments(Lists.mutable.with(pureOne)))
                    ._defaultValue(defaultValueLambda)
                    ._sourceInformation(SourceInformationCompiler.compile(grammarProp._sourceInformation()));
        });

        org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.function.property.Property> props =
                (org.eclipse.collections.api.list.MutableList) properties;

        return result
                ._classifierGenericType(enumerationOfE)
                ._generalizations(Lists.mutable.with(
                        new GeneralizationImpl()
                                ._general(new ConcreteGenericTypeImpl()._rawType(enumType))))
                ._properties(props)
                ._sourceInformation(SourceInformationCompiler.compile(grammar._sourceInformation()));
    }

    public static Enumeration thirdPass(Enumeration cls, meta.pure.protocol.grammar.type.Enumeration grammar, CompilationContext context)
    {
        return cls;
    }

}
