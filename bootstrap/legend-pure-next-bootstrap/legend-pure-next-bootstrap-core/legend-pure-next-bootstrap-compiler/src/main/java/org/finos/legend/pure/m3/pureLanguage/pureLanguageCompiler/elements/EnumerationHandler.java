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
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.relationship.GeneralizationImpl;
import meta.pure.metamodel.type.EnumImpl;
import meta.pure.metamodel.type.Enumeration;
import meta.pure.metamodel.type.EnumerationImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.InferredGenericTypeImpl;
import meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl;
import meta.pure.metamodel.valuespecification.AtomicValueImpl;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._FunctionType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.AnnotationCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.SourceInformationCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._G_PackageableElement;

import java.util.Objects;

/**
 * Handler for Enumeration.
 */
public final class EnumerationHandler
{
    private EnumerationHandler()
    {
    }

    public static Enumeration firstPass(meta.pure.protocol.grammar.type.Enumeration grammar, MetadataAccess model, org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext context)
    {
        return new EnumerationImpl() // the classifierGenericType is set in the secondPass
                ._name(grammar._name());
    }

    public static Enumeration secondPass(EnumerationImpl result, meta.pure.protocol.grammar.type.Enumeration grammar, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        // Add generalization: every user-defined enumeration extends Enum
        Type enumType = (Type) model.getElement("meta::pure::metamodel::type::Enum");
        Type propertyType = (Type) model.getElement("meta::pure::metamodel::function::property::Property");
        Type enumerationType = (Type) model.getElement("meta::pure::metamodel::type::Enumeration");
        Multiplicity pureOne = (Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::PureOne");

        // GenericType for this specific enumeration (e.g., CC_GeographicEntityType)
        UserDefinedGenericTypeImpl enumGT = _GenericType.buildUserDefinedGenericType(result, model);
        // GenericType for Enumeration<E> parameterized with this enum type
        UserDefinedGenericTypeImpl enumerationOfE = _GenericType.buildUserDefinedGenericType(enumerationType, model)
                ._typeArguments(Lists.mutable.with(enumGT));

        // Check for duplicate enum value names
        grammar._properties().collect(meta.pure.protocol.grammar.function.property.Property::_name)
                .toBag().selectDuplicates().toSet()
                .each(name -> context.addError(new CompilationError(
                        "Duplicate enum value '" + name + "'",
                        SourceInformationCompiler.compile(grammar._p_sourceInformation(), context.getSourceId(), model))));

        // Create one Property per enum value, each with a defaultValue containing the Enum instance
        var properties = grammar._properties().collect(grammarProp ->
        {
            // Create the Enum instance
            EnumImpl enumInstance = new EnumImpl()  // the classifierGenericType is set below
                    ._name(grammarProp._name())
                    ._classifierGenericType(enumGT)
                    ._sourceInformation(SourceInformationCompiler.compile(grammarProp._p_sourceInformation(), context.getSourceId(), model));

            // Create a parameterless lambda whose body is the AtomicValue

            // ClassifierGenericType -----
            meta.pure.metamodel.type.FunctionTypeImpl ft = _FunctionType.newFunctionType(model);
            ft._returnType(enumGT);
            ft._returnMultiplicity(pureOne);
            InferredGenericTypeImpl classifierGenericType = _GenericType.buildInferredGenericType((Type) model.getElement("meta::pure::metamodel::function::LambdaFunction"), model)
                    ._typeArguments(org.eclipse.collections.impl.factory.Lists.mutable.with(
                            new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(model)._type(ft)));
            // ClassifierGenericType -----

            LambdaFunctionImpl defaultValueLambda = new LambdaFunctionImpl();
            defaultValueLambda._expressionSequence(Lists.mutable.with(
                    new AtomicValueImpl(model)
                                ._sourceInformation(SourceInformationCompiler.compile(grammarProp._p_sourceInformation(), context.getSourceId(), model))
                                ._value(enumInstance)
                                ._genericType(enumGT)
                                ._multiplicity(pureOne)
                    )
            )._classifierGenericType(classifierGenericType);




            // Create the Property: Property<Enumeration<E>, E | 1>
            return new PropertyImpl(
                            _GenericType.buildUserDefinedGenericType(propertyType, model)
                            ._typeArguments(Lists.mutable.with(enumerationOfE, enumGT))
                            ._multiplicityArguments(Lists.mutable.with(pureOne))
                        )._aggregation((meta.pure.metamodel.function.property.AggregationKind) org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Enumeration.resolveEnumValue("meta::pure::metamodel::function::property::AggregationKind", "None", model))
                         ._name(grammarProp._name())
                         ._owner(result)
                         ._genericType(enumGT)
                         ._multiplicity(pureOne)
                         ._defaultValue(defaultValueLambda)
                         ._stereotypes(grammarProp._stereotypes()
                                 .collect(s -> AnnotationCompiler.resolveStereotype(s, imports, model, context))
                                 .select(Objects::nonNull))
                         ._taggedValues(grammarProp._taggedValues()
                                 .collect(tv -> AnnotationCompiler.resolveTaggedValue(tv, imports, model, context))
                                 .select(Objects::nonNull))
                         ._sourceInformation(SourceInformationCompiler.compile(grammarProp._p_sourceInformation(), context.getSourceId(), model));
        });

        org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.function.property.Property> props =
                (org.eclipse.collections.api.list.MutableList) properties;

        result._classifierGenericType(enumerationOfE)
                ._generalizations(Lists.mutable.with(
                        new GeneralizationImpl(model)
                                ._general(_GenericType.buildUserDefinedGenericType(enumType, model))
                                ._specific(result)))
                ._properties(props)
                ._stereotypes(grammar._stereotypes()
                        .collect(s -> AnnotationCompiler.resolveStereotype(s, imports, model, context))
                        .select(Objects::nonNull))
                ._taggedValues(grammar._taggedValues()
                        .collect(tv -> AnnotationCompiler.resolveTaggedValue(tv, imports, model, context))
                        .select(Objects::nonNull))
                ._sourceInformation(SourceInformationCompiler.compile(grammar._p_sourceInformation(), context.getSourceId(), model));

        context.enrichCurrentErrors("enumeration '" + _G_PackageableElement.fullPath(grammar) + "'");
        return result;
    }

    public static Enumeration thirdPass(Enumeration cls, meta.pure.protocol.grammar.type.Enumeration grammar, CompilationContext context)
    {
        return cls;
    }

}
