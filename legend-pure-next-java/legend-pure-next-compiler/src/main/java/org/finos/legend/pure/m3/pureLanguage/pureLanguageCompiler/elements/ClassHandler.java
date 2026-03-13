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

import meta.pure.metamodel.type.ClassImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.MultiplicityParameter;
import meta.pure.metamodel.type.generics.TypeParameter;
import meta.pure.metamodel.type.generics.TypeParameterImpl;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Sets;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.AnnotationCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.ConstraintCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.GeneralizationCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.GenericTypeCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.PropertyCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.FunctionDefinitionResolver;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.QualifiedPropertyCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.SourceInformationCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.VariableCompiler;
import org.finos.legend.pure.next.parser.m3.helper._G_PackageableElement;

import java.util.Objects;

/**
 * Handler for Class.
 */
public final class ClassHandler
{
    private ClassHandler()
    {
    }

    public static meta.pure.metamodel.type.Class firstPass(meta.pure.protocol.grammar.type.Class grammar)
    {
        return new ClassImpl()
                ._name(grammar._name());
    }

    public static meta.pure.metamodel.type.Class secondPass(ClassImpl result, meta.pure.protocol.grammar.type.Class grammar, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        // Compile and set type parameters (e.g., T, U for Class<T, U>)
        MutableList<TypeParameter> typeParameters = grammar._typeParameters()
                .collect(GenericTypeCompiler::compileTypeParameter)
                .select(Objects::nonNull);
        if (typeParameters.notEmpty())
        {
            result._typeParameters(typeParameters);
        }

        // Build the owner GenericType: for Pair<U,V> this is Pair<U,V>, not just Pair
        ConcreteGenericTypeImpl ownerGenericType = new ConcreteGenericTypeImpl()._rawType(result);
        if (typeParameters.notEmpty())
        {
            ownerGenericType._typeArguments(
                    typeParameters.collect(tp ->
                            (GenericType) new ConcreteGenericTypeImpl()
                                    ._typeParameter(new TypeParameterImpl()._name(tp._name()))));
        }

        MutableList<meta.pure.metamodel.function.property.Property> properties = grammar._properties()
                .collect(p -> PropertyCompiler.compile(p, ownerGenericType, imports, model, context))
                .select(Objects::nonNull);
        properties.forEach(p -> ((meta.pure.metamodel.function.property.PropertyImpl) p)._owner(result));

        MutableList<meta.pure.metamodel.function.property.QualifiedProperty> qualifiedProperties = grammar._qualifiedProperties()
                .collect(qp -> QualifiedPropertyCompiler.compile(qp, ownerGenericType, imports, model, context))
                .select(Objects::nonNull);
        qualifiedProperties.forEach(qp -> ((meta.pure.metamodel.function.property.QualifiedPropertyImpl) qp)._owner(result));

        result._properties(properties)
                ._qualifiedProperties(qualifiedProperties)
                ._generalizations(grammar._generalizations()
                        .collect(g -> GeneralizationCompiler.compile(g, result, imports, model, context))
                        .select(Objects::nonNull))
                ._stereotypes(grammar._stereotypes()
                        .collect(s -> AnnotationCompiler.resolveStereotype(s, imports, model, context))
                        .select(Objects::nonNull))
                ._taggedValues(grammar._taggedValues()
                        .collect(tv -> AnnotationCompiler.resolveTaggedValue(tv, imports, model, context))
                        .select(Objects::nonNull))
                ._classifierGenericType(
                        new ConcreteGenericTypeImpl()
                                ._rawType((Type) model.getElement("meta::pure::metamodel::type::Class"))
                                ._typeArguments(Lists.mutable.with(ownerGenericType)))
                ._sourceInformation(SourceInformationCompiler.compile(grammar._sourceInformation()));

        // Compile type variables (e.g., Class Foo(x:Integer[1]))
        if (grammar._typeVariables() != null && grammar._typeVariables().notEmpty())
        {
            result._typeVariables(grammar._typeVariables()
                    .collect(v -> VariableCompiler.compileParameter(v, imports, model, context))
                    .select(Objects::nonNull));
        }

        // Create constraint shells (name, owner, source info) without expression sequences
        if (grammar._constraints() != null && grammar._constraints().notEmpty())
        {
            result._constraints(grammar._constraints().collect(ConstraintCompiler::compileShell));
        }

        context.enrichCurrentErrors("class '" + _G_PackageableElement.fullPath(grammar) + "'");
        return result;
    }

    /**
     * Third pass: resolve expression sequences in qualified properties
     * and validate return type/multiplicity compatibility.
     */
    public static meta.pure.metamodel.type.Class thirdPass(meta.pure.metamodel.type.Class cls, meta.pure.protocol.grammar.type.Class grammar,
                                  MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        // Set in-scope type/multiplicity params so inner FunctionExpressions in qualified
        // properties can validate that unresolved params come from this class.
        context.compilerContextExtensions(PureLanguageCompilerContext.class).setScopeTypeParamNames(cls._typeParameters() != null
                ? cls._typeParameters().collect(TypeParameter::_name).toSet()
                : Sets.mutable.empty());
        context.compilerContextExtensions(PureLanguageCompilerContext.class).setScopeMultiplicityParamNames(cls._multiplicityParameters() != null
                ? cls._multiplicityParameters().collect(MultiplicityParameter::_name).toSet()
                : Sets.mutable.empty());

        // Push type variables into scope so they are visible in qualifiers and constraints
        if (cls._typeVariables() != null && cls._typeVariables().notEmpty())
        {
            context.compilerContextExtensions(PureLanguageCompilerContext.class).pushScope(Lists.mutable.withAll(cls._typeVariables()));
        }

        QualifiedPropertyCompiler.resolveAndValidate(cls._qualifiedProperties(), model, context);

        // Resolve constraint expression sequences
        if (grammar != null)
        {
            resolveClassConstraints(cls, grammar, imports, model, context);
        }

        // Resolve and validate default value lambdas on properties
        if (cls._properties() != null)
        {
            cls._properties().forEach(prop ->
            {
                if (prop._defaultValue() != null)
                {
                    // A property with a default value must have lower bound >= 1
                    if (prop._multiplicity() != null
                            && _Multiplicity.lowerBound(prop._multiplicity()) < 1)
                    {
                        context.addError(new CompilationError(
                                "Property with a default value cannot have a multiplicity with a lower bound of 0",
                                prop._sourceInformation()));
                        return;
                    }

                    meta.pure.metamodel.function.LambdaFunction lambda = prop._defaultValue();
                    if (lambda._expressionSequence() != null && lambda._expressionSequence().notEmpty())
                    {
                        // Build a 'this' variable of the owning class type and push it into scope
                        GenericType ownerGenericType = cls._classifierGenericType() != null
                                && cls._classifierGenericType()._typeArguments() != null
                                && cls._classifierGenericType()._typeArguments().notEmpty()
                                ? cls._classifierGenericType()._typeArguments().getFirst()
                                : new meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl()._rawType(cls);
                        meta.pure.metamodel.valuespecification.VariableExpressionImpl thisVar =
                                new meta.pure.metamodel.valuespecification.VariableExpressionImpl()
                                        ._name("this")
                                        ._genericType(ownerGenericType)
                                        ._multiplicity((meta.pure.metamodel.multiplicity.Multiplicity)
                                                model.getElement("meta::pure::metamodel::multiplicity::PureOne"));
                        context.compilerContextExtensions(PureLanguageCompilerContext.class).pushScope(Lists.mutable.with(thisVar));

                        try
                        {
                            // Resolve expression sequence through the third-pass compiler
                            lambda._expressionSequence(FunctionDefinitionResolver.resolveExpressionSequence(lambda._expressionSequence(), model, context));
                        }
                        finally
                        {
                            context.compilerContextExtensions(PureLanguageCompilerContext.class).popScope();
                        }
                        // Validate type and multiplicity of the resolved default value
                        meta.pure.metamodel.valuespecification.ValueSpecification lastExpr =
                                lambda._expressionSequence().getLast();
                        meta.pure.metamodel.SourceInformation srcInfo =
                                prop._sourceInformation();

                        // Type check
                        if (lastExpr._genericType() != null && lastExpr._genericType()._rawType() != null
                                && prop._genericType() != null && prop._genericType()._rawType() != null)
                        {
                            meta.pure.metamodel.type.Type defaultType = lastExpr._genericType()._rawType();
                            meta.pure.metamodel.type.Type propertyType = prop._genericType()._rawType();
                            if (defaultType != propertyType)
                            {
                                context.addError(new CompilationError(
                                        "Default value type mismatch: expected " + _GenericType.print(prop._genericType())
                                                + " but got " + _GenericType.print(lastExpr._genericType()),
                                        srcInfo));
                            }
                        }
                        // Multiplicity check
                        if (lastExpr._multiplicity() != null
                                && !_Multiplicity.subsumes(
                                        prop._multiplicity(), lastExpr._multiplicity()))
                        {
                            context.addError(new CompilationError(
                                    "Default value multiplicity mismatch: expected " + _Multiplicity.print(prop._multiplicity())
                                            + " but got " + _Multiplicity.print(lastExpr._multiplicity()),
                                    srcInfo));
                        }
                    }
                }
            });
        }

        if (cls._typeVariables() != null && cls._typeVariables().notEmpty())
        {
            context.compilerContextExtensions(PureLanguageCompilerContext.class).popScope();
        }

        context.compilerContextExtensions(PureLanguageCompilerContext.class).setScopeTypeParamNames(Sets.mutable.empty());
        context.compilerContextExtensions(PureLanguageCompilerContext.class).setScopeMultiplicityParamNames(Sets.mutable.empty());

        return cls;
    }

    private static void resolveClassConstraints(meta.pure.metamodel.type.Class cls, meta.pure.protocol.grammar.type.Class grammar,
                                                MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        if (grammar._constraints() == null || grammar._constraints().isEmpty())
        {
            return;
        }

        GenericType ownerGenericType = cls._classifierGenericType() != null
                && cls._classifierGenericType()._typeArguments() != null
                && cls._classifierGenericType()._typeArguments().notEmpty()
                ? cls._classifierGenericType()._typeArguments().getFirst()
                : new ConcreteGenericTypeImpl()._rawType(cls);
        VariableExpressionImpl thisVar = new VariableExpressionImpl()
                ._name("this")
                ._genericType(ownerGenericType)
                ._multiplicity((meta.pure.metamodel.multiplicity.Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::PureOne"));
        context.compilerContextExtensions(PureLanguageCompilerContext.class).pushScope(Lists.mutable.with(thisVar));

        try
        {
            // Type variables become extra lambda parameters so they are bound, not open
            MutableList<VariableExpression> extraParams = cls._typeVariables() != null
                    ? Lists.mutable.withAll(cls._typeVariables())
                    : null;
            ConstraintCompiler.resolveConstraints(cls._constraints(), grammar._constraints(), thisVar, extraParams, imports, model, context);
        }
        finally
        {
            context.compilerContextExtensions(PureLanguageCompilerContext.class).popScope();
        }
    }

}
