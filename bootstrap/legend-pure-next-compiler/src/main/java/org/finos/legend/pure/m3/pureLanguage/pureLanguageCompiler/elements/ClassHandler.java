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
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.TypeParameter;
import meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Lambda;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._VariableExpression;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.FunctionDefinitionResolver;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.AnnotationCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.ConstraintCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.GeneralizationCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.GenericTypeCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.PropertyCompiler;
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

    public static meta.pure.metamodel.type.Class firstPass(meta.pure.protocol.grammar.type.Class grammar, MetadataAccess model)
    {
        return new ClassImpl() // the classifierGenericType is set in the secondPass
                   ._name(grammar._name());
    }

    public static meta.pure.metamodel.type.Class secondPass(ClassImpl result, meta.pure.protocol.grammar.type.Class grammar, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        // Compile and set type parameters (e.g., T, U for Class<T, U>)
        MutableList<TypeParameter> typeParameters = grammar._typeParameters()
                .collect(tp -> GenericTypeCompiler.compileTypeParameter(tp, result, model))
                .select(Objects::nonNull);
        if (typeParameters.notEmpty())
        {
            result._typeParameters(typeParameters);
        }

        // Compile and set multiplicity parameters (e.g., m for Class<T|m>)
        MutableList<meta.pure.metamodel.multiplicity.MultiplicityParameter> multiplicityParameters = grammar._multiplicityParameters()
                .collect(mp -> org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.MultiplicityCompiler.compileMultiplicityParameter(mp, result, model))
                .select(Objects::nonNull);
        if (multiplicityParameters.notEmpty())
        {
            result._multiplicityParameters(multiplicityParameters);
        }

        // Register declared type/multiplicity params in scope so property GenericType references reuse the same objects
        PureLanguageCompilerContext plcc = context.compilerContextExtensions(PureLanguageCompilerContext.class);
        plcc.setEnclosingOwner(result);

        // Build the owner GenericType: for Pair<U,V> this is Pair<U,V>, not just Pair
        UserDefinedGenericTypeImpl ownerGenericType = _GenericType.buildUserDefinedGenericType(result, model);
        if (typeParameters.notEmpty())
        {
            ownerGenericType._typeArguments(
                    typeParameters.collect(tp ->
                            _GenericType.buildUserDefinedGenericType(tp, model)));
        }
        if (multiplicityParameters.notEmpty())
        {
            ownerGenericType._multiplicityArguments(
                    multiplicityParameters.collect(mp -> mp));
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
                ._generalizations(grammar._generalizations().isEmpty()
                        ? Lists.mutable.with(new meta.pure.metamodel.relationship.GeneralizationImpl(model)
                                ._general(_GenericType.buildUserDefinedGenericType((Type) model.getElement("meta::pure::metamodel::type::Any"), model))
                                ._specific(result))
                        : grammar._generalizations()
                                .collect(g -> GeneralizationCompiler.compile(g, result, imports, model, context))
                                .select(Objects::nonNull))
                ._stereotypes(grammar._stereotypes()
                        .collect(s -> AnnotationCompiler.resolveStereotype(s, imports, model, context))
                        .select(Objects::nonNull))
                ._taggedValues(grammar._taggedValues()
                        .collect(tv -> AnnotationCompiler.resolveTaggedValue(tv, imports, model, context))
                        .select(Objects::nonNull))
                ._classifierGenericType(
                        _GenericType.buildUserDefinedGenericType((Type) model.getElement("meta::pure::metamodel::type::Class"), model)
                                ._typeArguments(Lists.mutable.with(ownerGenericType)))
                ._sourceInformation(SourceInformationCompiler.compile(grammar._sourceInformation(), model));

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
            result._constraints(grammar._constraints().collect(gc -> ConstraintCompiler.compileShell(gc, model)));
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
        context.compilerContextExtensions(PureLanguageCompilerContext.class).setEnclosingOwner(cls);

        // Push type variables into scope so they are visible in qualifiers and constraints
        if (cls._typeVariables() != null && cls._typeVariables().notEmpty())
        {
            context.compilerContextExtensions(PureLanguageCompilerContext.class).pushScope(Lists.mutable.withAll(cls._typeVariables()));
        }

        QualifiedPropertyCompiler.resolveAndValidate(cls._qualifiedProperties(), model, context);

        // Validate Qualified Property overrides
        if (cls._qualifiedProperties() != null)
        {
            cls._qualifiedProperties().forEach(qp ->
            {
                if (cls._generalizations() != null)
                {
                    for (meta.pure.metamodel.relationship.Generalization gen : cls._generalizations())
                    {
                        if (gen._general() != null && _GenericType.type(gen._general()) instanceof meta.pure.metamodel.type.Class superOwner)
                        {
                            org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.function.property.QualifiedProperty> superProps = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Class.findQualifiedProperties(superOwner, qp._name());
                            if (superProps != null && superProps.notEmpty())
                            {
                                GenericType receiverGT = cls._classifierGenericType() != null && _GenericType.typeArguments(cls._classifierGenericType()) != null && _GenericType.typeArguments(cls._classifierGenericType()).notEmpty()
                                        ? _GenericType.typeArguments(cls._classifierGenericType()).getFirst()
                                        : _GenericType.buildUserDefinedGenericType(cls, model);
                                org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.ParametersBinding bindings = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Class.buildBindingsFromGenericType(cls, receiverGT);

                                for (meta.pure.metamodel.function.property.QualifiedProperty superProp : superProps)
                                {
                                    if (qp._parameters().size() == superProp._parameters().size())
                                    {
                                        boolean match = true;
                                        for (int i = 1; i < qp._parameters().size(); i++)
                                        {
                                            GenericType subclassParamGT = qp._parameters().get(i)._genericType();
                                            GenericType superParamGT = _GenericType.makeAsConcreteAsPossible(superProp._parameters().get(i)._genericType(), bindings, model);
                                            if (subclassParamGT == null || superParamGT == null || !_GenericType.print(subclassParamGT).equals(_GenericType.print(superParamGT)))
                                            {
                                                match = false;
                                                break;
                                            }
                                        }
                                        if (match)
                                        {
                                            GenericType msReturnGT = qp._genericType();
                                            GenericType otherReturnGT = _GenericType.makeAsConcreteAsPossible(superProp._genericType(), bindings, model);
                                            meta.pure.metamodel.multiplicity.Multiplicity msReturnMul = qp._multiplicity();
                                            meta.pure.metamodel.multiplicity.Multiplicity otherReturnMul = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity.makeAsConcreteAsPossible(superProp._multiplicity(), bindings);

                                            if (msReturnGT != null && otherReturnGT != null && !_GenericType.isCompatible(otherReturnGT, msReturnGT, model))
                                            {
                                                String clsName = cls._name();
                                                context.addError(new CompilationError("Qualified property override variance mismatch for '" + qp._name() + "' in class '" + clsName + "'. Return type is not covariant. Overridden property has return type '" + _GenericType.print(otherReturnGT) + "', but overriding property has return type '" + _GenericType.print(msReturnGT) + "'.", qp._sourceInformation()));
                                            }
                                            if (msReturnMul != null && otherReturnMul != null && !org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity.subsumes(otherReturnMul, msReturnMul))
                                            {
                                                String clsName = cls._name();
                                                context.addError(new CompilationError("Qualified property override variance mismatch for '" + qp._name() + "' in class '" + clsName + "'. Return multiplicity is not subsumed. Overridden property has return multiplicity '" + org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity.print(otherReturnMul) + "', but overriding property has return multiplicity '" + org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity.print(msReturnMul) + "'.", qp._sourceInformation()));
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            });
        }

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
                // Validate that if this property overrides a superclass property, its return type and multiplicity are INVARIANT
                if (cls._generalizations() != null)
                {
                    for (meta.pure.metamodel.relationship.Generalization gen : cls._generalizations())
                    {
                        if (gen._general() != null && _GenericType.type(gen._general()) instanceof meta.pure.metamodel.SimplePropertyOwner superOwner)
                        {
                            meta.pure.metamodel.function.property.Property superProp = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Class.findProperty(superOwner, prop._name());
                            if (superProp != null)
                            {
                                // Exempt inherited properties from Any (such as sourceInformation) which use protocol-level covariance
                                if (superOwner == model.getElement("meta::pure::metamodel::type::Any"))
                                {
                                    continue;
                                }

                                GenericType receiverGT = cls._classifierGenericType() != null && _GenericType.typeArguments(cls._classifierGenericType()) != null && _GenericType.typeArguments(cls._classifierGenericType()).notEmpty()
                                        ? _GenericType.typeArguments(cls._classifierGenericType()).getFirst()
                                        : _GenericType.buildUserDefinedGenericType(cls, model);
                                meta.pure.metamodel.function.Function resolvedSuperProp = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Property.resolveProperty(superProp, receiverGT, model);
                                
                                String subclassType = _GenericType.print(prop._genericType());
                                String subclassMul = _Multiplicity.print(prop._multiplicity());
                                String superType = _GenericType.print(((meta.pure.metamodel.function.property.Property)resolvedSuperProp)._genericType());
                                String superMul = _Multiplicity.print(((meta.pure.metamodel.function.property.Property)resolvedSuperProp)._multiplicity());
                                
                                if (!subclassType.equals(superType) || !subclassMul.equals(superMul))
                                {
                                    context.addError(new CompilationError("Property override variance mismatch for '" + prop._name() + "'. Overridden property has type '" + superType + superMul + "', but overriding property has type '" + subclassType + subclassMul + "'. Simple property overrides must be strictly invariant in type and multiplicity.", prop._sourceInformation()));
                                }
                                break;
                            }
                        }
                    }
                }

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
                                && _GenericType.typeArguments(cls._classifierGenericType()) != null
                                && _GenericType.typeArguments(cls._classifierGenericType()).notEmpty()
                                ? _GenericType.typeArguments(cls._classifierGenericType()).getFirst()
                                : _GenericType.buildUserDefinedGenericType(cls, model);
                        meta.pure.metamodel.valuespecification.VariableExpressionImpl thisVar =
                                _VariableExpression.newVariableExpression(model)
                                        ._name("this")
                                        ._genericType(ownerGenericType)
                                        ._multiplicity((meta.pure.metamodel.multiplicity.Multiplicity)
                                                model.getElement("meta::pure::metamodel::multiplicity::PureOne"));
                        context.compilerContextExtensions(PureLanguageCompilerContext.class).pushScope(Lists.mutable.with(thisVar));

                        try
                        {
                            // Resolve expression sequence through the third-pass compiler
                            lambda._expressionSequence(FunctionDefinitionResolver.resolveExpressionSequence(lambda._expressionSequence(), model, context));
                            // Set the classifierGenericType on the lambda now that its body is resolved
                            lambda._classifierGenericType(_Lambda.getLambdaClassifierGenericType(model, lambda));
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
                        if (lastExpr._genericType() != null && _GenericType.type(lastExpr._genericType()) != null
                                && prop._genericType() != null && _GenericType.type(prop._genericType()) != null)
                        {
                            meta.pure.metamodel.type.Type defaultType = _GenericType.type(lastExpr._genericType());
                            meta.pure.metamodel.type.Type propertyType = _GenericType.type(prop._genericType());
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

        context.compilerContextExtensions(PureLanguageCompilerContext.class).clearEnclosingOwner();

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
                && _GenericType.typeArguments(cls._classifierGenericType()) != null
                && _GenericType.typeArguments(cls._classifierGenericType()).notEmpty()
                ? _GenericType.typeArguments(cls._classifierGenericType()).getFirst()
                : _GenericType.buildUserDefinedGenericType(cls, model);
        VariableExpressionImpl thisVar = _VariableExpression.newVariableExpression(model)
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
