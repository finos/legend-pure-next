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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural;

import meta.pure.metamodel.function.property.Property;
import meta.pure.metamodel.function.property.PropertyImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;

/**
 * Compiles a grammar-level {@link meta.pure.protocol.grammar.function.property.Property}
 * into a metamodel-level {@link Property}, leveraging {@link GenericTypeCompiler}
 * to resolve the property's generic type.
 */
public final class PropertyCompiler
{
    private PropertyCompiler()
    {
    }

    /**
     * Compile a grammar Property into a metamodel Property.
     * Returns null if the property's type cannot be resolved.
     *
     * @param grammarProperty the grammar-level property to compile
     * @param owner           the GenericType of the owning class/association
     * @param imports         import package paths from the enclosing section
     * @param model           the compiled PureModel used for element lookup
     * @param context         the compilation context for error collection
     * @return a fully resolved metamodel Property, or null if the type is unresolvable
     */
    public static Property compile(meta.pure.protocol.grammar.function.property.Property grammarProperty, GenericType owner, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        int errorsBefore = context.currentErrorCount();
        GenericType genericType = GenericTypeCompiler.compile(grammarProperty._genericType(), imports, model, context);
        if (genericType == null)
        {
            context.enrichCurrentErrorsFrom(errorsBefore, "property '" + grammarProperty._name() + "'");
            return null;
        }
        int annotErrorsBefore = context.currentErrorCount();
        PropertyImpl result = new PropertyImpl()
                ._name(grammarProperty._name())
                ._aggregation(resolveAggregationKind(grammarProperty, model))
                ._genericType(genericType)
                ._multiplicity(MultiplicityCompiler.compile(grammarProperty._multiplicity(), model))
                ._stereotypes(grammarProperty._stereotypes()
                        .collect(s -> AnnotationCompiler.resolveStereotype(s, imports, model, context))
                        .select(java.util.Objects::nonNull))
                ._taggedValues(grammarProperty._taggedValues()
                        .collect(tv -> AnnotationCompiler.resolveTaggedValue(tv, imports, model, context))
                        .select(java.util.Objects::nonNull))
                ._sourceInformation(SourceInformationCompiler.compile(grammarProperty._p_sourceInformation(), model));
        context.enrichCurrentErrorsFrom(annotErrorsBefore, "property '" + grammarProperty._name() + "'");
        if (grammarProperty._defaultValue() != null)
        {
            meta.pure.metamodel.function.LambdaFunction compiledDefault =
                    LambdaCompiler.compile(
                            grammarProperty._defaultValue(), imports, model, context);
            result._defaultValue(compiledDefault);
        }
        if (owner != null)
        {
            result._classifierGenericType(
                    _GenericType.buildUserDefinedGenericType((Type) model.getElement("meta::pure::metamodel::function::property::Property"), model)
                            ._typeArguments(Lists.mutable.with(owner, genericType))
                            ._multiplicityArguments(Lists.mutable.with(result._multiplicity())));
        }
        return result;
    }

    private static meta.pure.metamodel.function.property.AggregationKind resolveAggregationKind(meta.pure.protocol.grammar.function.property.Property grammarProperty, MetadataAccess model)
    {
        meta.pure.protocol.grammar.Enum_Pointer aggPointer = grammarProperty._aggregation();
        String valueName = "None";
        if (aggPointer != null && aggPointer._extraPointerValues() != null && aggPointer._extraPointerValues().notEmpty())
        {
            valueName = aggPointer._extraPointerValues().getFirst()._value();
        }
        return (meta.pure.metamodel.function.property.AggregationKind) org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Enumeration.resolveEnumValue(
                "meta::pure::metamodel::function::property::AggregationKind", valueName, model);
    }
}
