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

import meta.pure.metamodel.function.property.QualifiedProperty;
import meta.pure.metamodel.function.property.QualifiedPropertyImpl;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.FunctionTypeImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.FunctionDefinitionResolver;

import java.util.Objects;

/**
 * Compiles a grammar-level {@link meta.pure.protocol.grammar.function.property.QualifiedProperty}
 * into a metamodel-level {@link QualifiedProperty}.
 *
 * <p>The first parameter is an implicit {@code this} parameter typed to the owner.</p>
 */
public final class QualifiedPropertyCompiler
{
    private QualifiedPropertyCompiler()
    {
    }

    /**
     * Compile a grammar QualifiedProperty into a metamodel QualifiedProperty.
     * Returns null if the property's return type cannot be resolved.
     *
     * @param grammarQP the grammar-level qualified property to compile
     * @param owner     the GenericType of the owning class/association (may be null)
     * @param imports   import package paths from the enclosing section
     * @param model     the compiled PureModel used for element lookup
     * @param context   the compilation context for error collection
     * @return a fully resolved metamodel QualifiedProperty, or null if unresolvable
     */
    public static QualifiedProperty compile(
            meta.pure.protocol.grammar.function.property.QualifiedProperty grammarQP,
            GenericType owner,
            MutableList<String> imports,
            MetadataAccess model,
            CompilationContext context)
    {
        int errorsBefore = context.currentErrorCount();
        GenericType genericType = GenericTypeCompiler.compile(grammarQP._genericType(), imports, model, context);
        if (genericType == null)
        {
            context.enrichCurrentErrorsFrom(errorsBefore, "qualified property '" + grammarQP._name() + "'");
            return null;
        }

        Multiplicity multiplicity = MultiplicityCompiler.compile(grammarQP._multiplicity(), model);

        // Compile explicit parameters
        MutableList<VariableExpression> parameters = grammarQP._parameters()
                .collect(p -> VariableCompiler.compileParameter(p, imports, model, context))
                .select(Objects::nonNull);

        // Prepend implicit 'this' parameter typed to the owner
        if (owner != null)
        {
            VariableExpression thisParam = new VariableExpressionImpl()
                    ._name("this")
                    ._genericType(owner)
                    ._multiplicity((Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::PureOne"));
            parameters.add(0, thisParam);
        }

        // Compile expression sequence
        MutableList<ValueSpecification> expressionSequence = grammarQP._expressionSequence()
                        .collect(vs -> ValueSpecificationCompiler.compile(vs, imports, model, context));

        QualifiedPropertyImpl result = new QualifiedPropertyImpl()
                ._name(grammarQP._name())
                ._genericType(genericType)
                ._multiplicity(multiplicity)
                ._parameters(parameters)
                ._expressionSequence(expressionSequence)
                ._stereotypes(grammarQP._stereotypes()
                        .collect(s -> AnnotationCompiler.resolveStereotype(s, imports, model, context))
                        .select(Objects::nonNull))
                ._taggedValues(grammarQP._taggedValues()
                        .collect(tv -> AnnotationCompiler.resolveTaggedValue(tv, imports, model, context))
                        .select(Objects::nonNull));

        if (owner != null)
        {
            result._classifierGenericType(
                    new ConcreteGenericTypeImpl()
                            ._rawType((Type) model.getElement("meta::pure::metamodel::function::property::QualifiedProperty"))
                            ._typeArguments(Lists.mutable.with(
                                    new ConcreteGenericTypeImpl()._rawType(
                                            new FunctionTypeImpl()
                                                    ._parameters(parameters)
                                                    ._returnType(genericType)
                                                    ._returnMultiplicity(multiplicity)))));
        }

        return result;
    }

    /**
     * Third pass: resolve expression sequences in qualified properties and validate
     * that the last expression's type and multiplicity are compatible
     * with the declared return signature.
     */
    public static void resolveAndValidate(
            MutableList<QualifiedProperty> qualifiedProperties, MetadataAccess model, CompilationContext context)
    {
        if (qualifiedProperties == null)
        {
            return;
        }
        for (QualifiedProperty qp : qualifiedProperties)
        {
            FunctionDefinitionResolver.resolveAndValidate(qp, "qualified property", qp._name(), qp._genericType(), qp._multiplicity(), model, context);
        }
    }
}
