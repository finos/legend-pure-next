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

import meta.pure.metamodel.function.property.Property;
import meta.pure.metamodel.function.property.PropertyImpl;
import meta.pure.metamodel.function.property.QualifiedProperty;
import meta.pure.metamodel.relationship.Association;
import meta.pure.metamodel.relationship.AssociationImpl;
import meta.pure.metamodel.type.Class;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import meta.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.PropertyCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.QualifiedPropertyCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.SourceInformationCompiler;
import org.finos.legend.pure.next.parser.m3.helper._G_PackageableElement;

import java.util.Objects;

/**
 * Handler for Association.
 */
public final class AssociationHandler
{
    private AssociationHandler()
    {
    }

    public static Association firstPass(meta.pure.protocol.grammar.relationship.Association grammar)
    {
        return new AssociationImpl()
                ._name(grammar._name());
    }

    public static Association secondPass(AssociationImpl result, meta.pure.protocol.grammar.relationship.Association grammar, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        if (grammar._properties().size() != 2)
        {
            context.addError(new CompilationError("Association '" + _G_PackageableElement.fullPath(grammar) + "' must have exactly 2 properties, found " + grammar._properties().size(), SourceInformationCompiler.compile(grammar._sourceInformation())));
            return result;
        }

        result._sourceInformation(SourceInformationCompiler.compile(grammar._sourceInformation()));

        // Compile both properties without classifierGenericType first
        MutableList<Property> compiled = grammar._properties()
                .collect(p -> PropertyCompiler.compile(p, null, imports, model, context))
                .select(Objects::nonNull);
        compiled.forEach(p -> ((PropertyImpl) p)._owner(result));
        result._properties(compiled);

        // Each property's owner is the other property's type
        if (compiled.size() == 2)
        {
            Type propertyType = (Type) model.getElement("meta::pure::metamodel::function::property::Property");
            setPropertyClassifierGenericType((PropertyImpl) compiled.get(0), compiled.get(1)._genericType(), propertyType);
            setPropertyClassifierGenericType((PropertyImpl) compiled.get(1), compiled.get(0)._genericType(), propertyType);
        }

        // Compile qualified properties — the QP owner is the owner of the first property,
        // which is the second property's type (the class that "owns" the first property)
        GenericType qpOwner = compiled.size() == 2 ? compiled.get(1)._genericType() : null;
        MutableList<QualifiedProperty> compiledQPs = grammar._qualifiedProperties()
                .collect(qp -> QualifiedPropertyCompiler.compile(qp, qpOwner, imports, model, context))
                .select(Objects::nonNull);
        compiledQPs.forEach(qp -> ((meta.pure.metamodel.function.property.QualifiedPropertyImpl) qp)._owner(result));
        result._qualifiedProperties(compiledQPs);

        // Register association properties on their target classes
        if (compiled.size() == 2)
        {
            registerOnTargetClass(compiled.get(0), compiled.get(1)._genericType(), model);
            registerOnTargetClass(compiled.get(1), compiled.get(0)._genericType(), model);

            // Register qualified properties on the owner class
            if (qpOwner != null && qpOwner._rawType() instanceof Class ownerCls)
            {
                ownerCls._qualifiedPropertiesFromAssociations().addAll(compiledQPs.toList());
            }
        }

        context.enrichCurrentErrors("association '" + _G_PackageableElement.fullPath(grammar) + "'");
        return result;
    }

    private static void setPropertyClassifierGenericType(PropertyImpl property, GenericType owner, Type propertyType)
    {
        property._classifierGenericType(
                new ConcreteGenericTypeImpl()
                        ._rawType(propertyType)
                        ._typeArguments(Lists.mutable.with(owner, property._genericType()))
                        ._multiplicityArguments(Lists.mutable.with(property._multiplicity())));
    }

    /**
     * Third pass: resolve expression sequences in qualified properties
     * and validate return type/multiplicity compatibility.
     */
    public static Association thirdPass(Association assoc, MetadataAccess model, CompilationContext context)
    {
        QualifiedPropertyCompiler.resolveAndValidate(assoc._qualifiedProperties(), model, context);
        return assoc;
    }

    /**
     * Register a property on its target class's {@code propertiesFromAssociations} list.
     * The target class is determined by the owner generic type (the other side of the association).
     */
    private static void registerOnTargetClass(Property property, GenericType ownerGT, MetadataAccess model)
    {
        if (ownerGT != null && ownerGT._rawType() instanceof Class targetCls)
        {
            targetCls._propertiesFromAssociations().add(property);
        }
    }

}
