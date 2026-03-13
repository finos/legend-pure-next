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
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import meta.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;

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
        PropertyImpl result = new PropertyImpl()
                ._name(grammarProperty._name())
                ._genericType(genericType)
                ._multiplicity(MultiplicityCompiler.compile(grammarProperty._multiplicity(), model))
                ._sourceInformation(SourceInformationCompiler.compile(grammarProperty._sourceInformation()));
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
                    new ConcreteGenericTypeImpl()
                            ._rawType((Type) model.getElement("meta::pure::metamodel::function::property::Property"))
                            ._typeArguments(Lists.mutable.with(owner, genericType))
                            ._multiplicityArguments(Lists.mutable.with(result._multiplicity())));
        }
        return result;
    }
}
