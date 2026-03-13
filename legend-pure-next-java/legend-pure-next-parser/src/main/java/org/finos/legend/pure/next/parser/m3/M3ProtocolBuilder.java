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

package org.finos.legend.pure.next.parser.m3;


import meta.pure.protocol.grammar.Package_PointerImpl;
import meta.pure.protocol.grammar.PackageableElement;
import meta.pure.protocol.grammar.PointerValueImpl;
import meta.pure.protocol.grammar.SourceInformationImpl;
import meta.pure.protocol.grammar.constraint.ConstraintImpl;
import meta.pure.protocol.grammar.extension.AnnotatedElement;
import meta.pure.protocol.grammar.extension.ProfileImpl;
import meta.pure.protocol.grammar.extension.StereotypeImpl;
import meta.pure.protocol.grammar.extension.Stereotype_PointerImpl;
import meta.pure.protocol.grammar.extension.TagImpl;
import meta.pure.protocol.grammar.extension.Tag_PointerImpl;
import meta.pure.protocol.grammar.extension.TaggedValueImpl;
import meta.pure.protocol.grammar.function.LambdaFunctionImpl;
import meta.pure.protocol.grammar.function.NativeFunctionImpl;
import meta.pure.protocol.grammar.function.UserDefinedFunctionImpl;
import meta.pure.protocol.grammar.function.property.AggregationKind;
import meta.pure.protocol.grammar.function.property.PropertyImpl;
import meta.pure.protocol.grammar.function.property.QualifiedPropertyImpl;
import meta.pure.protocol.grammar.multiplicity.ConcreteMultiplicityImpl;
import meta.pure.protocol.grammar.multiplicity.MultiplicityValueImpl;
import meta.pure.protocol.grammar.relation.ColumnImpl;
import meta.pure.protocol.grammar.relation.GenericTypeOperationImpl;
import meta.pure.protocol.grammar.relation.GenericTypeOperationType;
import meta.pure.protocol.grammar.relation.RelationTypeImpl;
import meta.pure.protocol.grammar.relationship.AssociationImpl;
import meta.pure.protocol.grammar.relationship.GeneralizationImpl;
import meta.pure.protocol.grammar.type.ClassImpl;
import meta.pure.protocol.grammar.type.EnumImpl;
import meta.pure.protocol.grammar.type.EnumerationImpl;
import meta.pure.protocol.grammar.type.FunctionTypeImpl;
import meta.pure.protocol.grammar.type.MeasureImpl;
import meta.pure.protocol.grammar.type.PrimitiveTypeImpl;
import meta.pure.protocol.grammar.type.Type_PointerImpl;
import meta.pure.protocol.grammar.type.UnitImpl;
import meta.pure.protocol.grammar.type.generics.ConcreteGenericTypeImpl;
import meta.pure.protocol.grammar.type.generics.GenericType;
import meta.pure.protocol.grammar.type.generics.MultiplicityParameter;
import meta.pure.protocol.grammar.type.generics.MultiplicityParameterImpl;
import meta.pure.protocol.grammar.type.generics.TypeParameter;
import meta.pure.protocol.grammar.type.generics.TypeParameterImpl;
import meta.pure.protocol.grammar.valuespecification.ArrowInvocationImpl;
import meta.pure.protocol.grammar.valuespecification.AtomicValueImpl;
import meta.pure.protocol.grammar.valuespecification.CollectionImpl;
import meta.pure.protocol.grammar.valuespecification.CompilerGenericTypeAndMultiplicityHolderImpl;
import meta.pure.protocol.grammar.valuespecification.DotApplicationImpl;
import meta.pure.protocol.grammar.valuespecification.FunctionExpression;
import meta.pure.protocol.grammar.valuespecification.FunctionInvocationImpl;
import meta.pure.protocol.grammar.valuespecification.UserDefinedGenericTypeAndMultiplicityHolderImpl;
import meta.pure.protocol.grammar.valuespecification.ValueSpecification;
import meta.pure.protocol.grammar.valuespecification.VariableExpressionImpl;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.list.mutable.ListAdapter;
import org.eclipse.collections.impl.utility.ListIterate;
import org.finos.legend.pure.next.parser.m3.helper._G_PackageableFunction;

import java.util.List;
import java.util.Set;

/**
 * Builder that parses Pure code and builds protocol model objects.
 *
 * <p>
 * Uses ANTLR-generated parser to walk the parse tree and create
 * protocol model instances from the generated classes.
 * </p>
 */
public class M3ProtocolBuilder
        extends M3ParserBaseVisitor<Object>
{
    private final MutableList<meta.pure.protocol.grammar.PackageableElement> elements = Lists.mutable.empty();
    private int lineOffset = 0;

    /**
     * Parse Pure code and build protocol model elements.
     *
     * @param source     Pure source code
     * @param lineOffset line offset to add to all reported line numbers
     *                   (0-based: 0 means no offset)
     * @return list of parsed PackageableElement instances
     */
    public List<PackageableElement> parseElements(final String source, final int lineOffset)
    {
        this.lineOffset = lineOffset;
        M3Lexer lexer = new M3Lexer(CharStreams.fromString(source));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        M3Parser parser = new M3Parser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new org.antlr.v4.runtime.BaseErrorListener()
        {
            @Override
            public void syntaxError(org.antlr.v4.runtime.Recognizer<?, ?> recognizer,
                                    Object offendingSymbol,
                                    int line, int charPositionInLine,
                                    String msg,
                                    org.antlr.v4.runtime.RecognitionException e)
            {
                throw new RuntimeException(
                        "Parse error in file " + source + " at line " + (line + lineOffset) + ":" + charPositionInLine + " - " + msg);
            }
        });

        M3Parser.DefinitionContext tree = parser.definition();
        visit(tree);

        return elements;
    }

    /**
     * Parse Pure code and build protocol model elements (no line offset).
     *
     * @param source Pure source code
     * @return list of parsed PackageableElement instances
     */
    public List<PackageableElement> parseElements(final String source)
    {
        return parseElements(source, 0);
    }

    @Override
    public Object visitClassDefinition(final M3Parser.ClassDefinitionContext ctx)
    {
        ClassImpl classDef = setNameAndPackage(new ClassImpl(), ctx.qualifiedName().getText())
                ._sourceInformation(buildSourceInfo(ctx));

        // Collect type and multiplicity parameters (e.g., Class<T, -V|m>)
        M3Parser.TypeParametersWithVarianceAndMultiplicityParametersContext tpCtx = ctx.typeParametersWithVarianceAndMultiplicityParameters();
        MutableList<TypeParameter> typeParams = collectTypeParams(tpCtx == null ? null : tpCtx.typeParametersWithVariance());
        if (!typeParams.isEmpty())
        {
            classDef._typeParameters(typeParams);
        }
        Set<String> typeParamNames = typeParams.collect(tp -> tp._name()).toSet();
        Set<String> multParamNames = collectMultiplicityParams(tpCtx == null ? null : tpCtx.multiplictyParameters());
        if (!multParamNames.isEmpty())
        {
            classDef._multiplicityParameters(Lists.mutable.withAll(multParamNames).collect(n -> (MultiplicityParameter) new MultiplicityParameterImpl()._name(n)));
        }

        // Handle type variable parameters (e.g., Class Foo(x:Integer[1]))
        if (ctx.typeVariableParameters() != null)
        {
            classDef._typeVariables(
                    ListAdapter.adapt(ctx.typeVariableParameters()
                                    .functionVariableExpression())
                            .collect(ctx2 -> buildFunctionVariableExpression(ctx2, typeParamNames, multParamNames)));
        }

        // Handle extends (generalizations)
        if (ctx.type() != null && !ctx.type().isEmpty())
        {
            classDef._generalizations(
                    ListAdapter.adapt(ctx.type()).collect(typeCtx ->
                            new GeneralizationImpl()
                                    ._general(buildGenericType(typeCtx, typeParamNames))
                                    ._sourceInformation(buildSourceInfo(typeCtx))));
        }

        // Parse stereotypes and tagged values for class
        parseStereotypesAndTaggedValues(classDef, ctx.stereotypes(), ctx.taggedValues());

        // Handle constraints
        if (ctx.constraints() != null)
        {
            classDef._constraints(
                    ListAdapter.adapt(ctx.constraints().constraint())
                            .collect(ctx2 -> buildConstraint(ctx2, typeParamNames, multParamNames)));
        }

        if (ctx.classBody() != null && ctx.classBody().properties() != null)
        {
            classDef._properties(
                    ListAdapter.adapt(ctx.classBody().properties().property())
                            .collect(ctx2 -> buildProperty(ctx2, typeParamNames, multParamNames)));
            classDef._qualifiedProperties(
                    ListAdapter.adapt(ctx.classBody().properties().qualifiedProperty())
                            .collect(ctx2 -> buildQualifiedProperty(ctx2, typeParamNames, multParamNames)));
        }

        elements.add(classDef);
        return classDef;
    }

    @Override
    public Object visitPrimitiveDefinition(final M3Parser.PrimitiveDefinitionContext ctx)
    {
        PrimitiveTypeImpl primDef = setNameAndPackage(new PrimitiveTypeImpl(), ctx.qualifiedName().getText())
                ._sourceInformation(buildSourceInfo(ctx));

        // Handle stereotypes and tagged values
        parseStereotypesAndTaggedValues(primDef, ctx.stereotypes(), ctx.taggedValues());

        // Handle type variable parameters
        if (ctx.typeVariableParameters() != null)
        {
            primDef._typeVariables(
                    ListAdapter.adapt(ctx.typeVariableParameters()
                                    .functionVariableExpression())
                            .collect(ctx2 -> buildFunctionVariableExpression(ctx2, java.util.Collections.emptySet(), java.util.Collections.emptySet())));
        }

        // Handle extends type
        if (ctx.type() != null)
        {
            primDef._generalizations(Lists.mutable.with(
                    new GeneralizationImpl()
                            ._general(buildGenericType(ctx.type(), java.util.Collections.emptySet()))
                            ._sourceInformation(buildSourceInfo(ctx.type()))));
        }

        // Handle constraints
        if (ctx.constraints() != null)
        {
            primDef._constraints(
                    ListAdapter.adapt(ctx.constraints().constraint())
                            .collect(ctx2 -> buildConstraint(ctx2, java.util.Collections.emptySet(), java.util.Collections.emptySet())));
        }

        elements.add(primDef);
        return primDef;
    }

    // ========================================================================
    // Constraints
    // ========================================================================

    private ConstraintImpl buildConstraint(final M3Parser.ConstraintContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        ConstraintImpl c = new ConstraintImpl()
                ._sourceInformation(buildSourceInfo(ctx));

        if (ctx.simpleConstraint() != null)
        {
            M3Parser.SimpleConstraintContext sc = ctx.simpleConstraint();
            // Optional name
            if (sc.constraintId() != null)
            {
                c._name(sc.constraintId().VALID_STRING().getText());
            }
            // The function body is the combinedExpression
            c._functionDefinition(new LambdaFunctionImpl()
                    ._sourceInformation(buildSourceInfo(sc))
                    ._expressionSequence(Lists.mutable.with(visitCombinedExpr(sc.combinedExpression(), typeParamNames, multParamNames))));
        }
        else if (ctx.complexConstraint() != null)
        {
            M3Parser.ComplexConstraintContext cc = ctx.complexConstraint();
            // Name is always present for complex constraints
            c._name(cc.VALID_STRING().getText());

            // Owner
            if (cc.constraintOwner() != null)
            {
                c._owner(cc.constraintOwner().VALID_STRING().getText());
            }
            // ExternalId
            if (cc.constraintExternalId() != null)
            {
                String raw = cc.constraintExternalId().STRING().getText();
                // Strip quotes
                c._externalId(raw.substring(1, raw.length() - 1));
            }
            // Function
            c._functionDefinition(new LambdaFunctionImpl()
                    ._sourceInformation(buildSourceInfo(cc.constraintFunction()))
                    ._expressionSequence(Lists.mutable.with(visitCombinedExpr(cc.constraintFunction().combinedExpression(), typeParamNames, multParamNames))));

            // Enforcement level
            if (cc.constraintEnforcementLevel() != null)
            {
                c._enforcementLevel(cc.constraintEnforcementLevel().ENFORCEMENT_LEVEL().getText());
            }
            // Message function
            if (cc.constraintMessage() != null)
            {
                c._messageFunction(new LambdaFunctionImpl()
                        ._sourceInformation(buildSourceInfo(cc.constraintMessage()))
                        ._expressionSequence(Lists.mutable.with(visitCombinedExpr(cc.constraintMessage().combinedExpression(), typeParamNames, multParamNames))));
            }
        }

        return c;
    }

    private PropertyImpl buildProperty(final M3Parser.PropertyContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        PropertyImpl prop = new PropertyImpl()
                ._name(ctx.propertyName().getText())
                ._sourceInformation(buildSourceInfo(ctx))
                ._genericType(buildGenericType(ctx.propertyReturnType().type(), typeParamNames))
                ._multiplicity(parseMultiplicity(ctx.propertyReturnType().multiplicity().getText(), multParamNames));

        // Parse stereotypes and tagged values for property
        parseStereotypesAndTaggedValues(prop, ctx.stereotypes(), ctx.taggedValues());

        // Parse aggregation kind
        if (ctx.aggregation() != null)
        {
            String aggText = ctx.aggregation().getText();
            // Remove parentheses: (none) -> none
            aggText = aggText.substring(1, aggText.length() - 1);
            prop._aggregation(AggregationKind.valueOf(aggText.toUpperCase()));
        }

        // Parse default value
        if (ctx.defaultValue() != null)
        {
            prop._defaultValue(new LambdaFunctionImpl()
                    ._sourceInformation(buildSourceInfo(ctx.defaultValue()))
                    ._expressionSequence(Lists.mutable.with(
                            visitCombinedExpr(ctx.defaultValue().combinedExpression(), typeParamNames, multParamNames))));
        }

        return prop;
    }

    private QualifiedPropertyImpl buildQualifiedProperty(final M3Parser.QualifiedPropertyContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        QualifiedPropertyImpl qp = new QualifiedPropertyImpl()
                ._name(ctx.identifier().getText())
                ._sourceInformation(buildSourceInfo(ctx));

        // Parse stereotypes and tagged values
        parseStereotypesAndTaggedValues(qp, ctx.stereotypes(), ctx.taggedValues());

        // Parse parameters from qualifiedPropertyBody
        M3Parser.QualifiedPropertyBodyContext bodyCtx = ctx.qualifiedPropertyBody();
        qp._parameters(
                ListAdapter.adapt(bodyCtx.functionVariableExpression())
                        .collect(ctx2 -> buildFunctionVariableExpression(ctx2, typeParamNames, multParamNames)));

        // Parse expression body (codeBlock)
        qp._expressionSequence(visitCodeBlockExpressions(bodyCtx.codeBlock(), typeParamNames, multParamNames));

        // Parse return type (resolves type parameter refs like T)
        qp._genericType(buildGenericType(ctx.propertyReturnType().type(), typeParamNames));
        qp._multiplicity(parseMultiplicity(ctx.propertyReturnType().multiplicity().getText(), multParamNames));

        return qp;
    }

    private ConcreteMultiplicityImpl parseMultiplicity(final String mult, final Set<String> multParamNames)
    {
        // Handle malformed or null input
        if (mult == null || !mult.startsWith("[") || !mult.endsWith("]"))
        {
            return new ConcreteMultiplicityImpl()
                    ._lowerBound(multVal(1))
                    ._upperBound(multVal(1));
        }

        // Format: [1], [0..1], [*], [1..*], [m] (multiplicity param)
        String inner = mult.substring(1, mult.length() - 1);

        // Check if this is a multiplicity parameter reference
        if (multParamNames.contains(inner))
        {
            return new ConcreteMultiplicityImpl()
                    ._multiplicityParameter(inner);
        }

        ConcreteMultiplicityImpl result = new ConcreteMultiplicityImpl();
        try
        {
            if (inner.equals("*"))
            {
                result._lowerBound(multVal(0));
            }
            else if (inner.contains(".."))
            {
                String[] parts = inner.split("\\.\\.");
                result._lowerBound(multVal(Integer.parseInt(parts[0])));
                if (!parts[1].equals("*"))
                {
                    result._upperBound(multVal(Integer.parseInt(parts[1])));
                }
            }
            else
            {
                int val = Integer.parseInt(inner);
                result._lowerBound(multVal(val));
                result._upperBound(multVal(val));
            }
        }
        catch (NumberFormatException e)
        {
            result._lowerBound(multVal(1));
            result._upperBound(multVal(1));
        }

        return result;
    }

    @Override
    public Object visitEnumDefinition(final M3Parser.EnumDefinitionContext ctx)
    {
        EnumerationImpl enumDef = setNameAndPackage(new EnumerationImpl(), ctx.qualifiedName().getText())
                ._sourceInformation(buildSourceInfo(ctx));

        // Parse stereotypes and tagged values for enumeration
        parseStereotypesAndTaggedValues(enumDef, ctx.stereotypes(), ctx.taggedValues());

        enumDef._properties(
                ListAdapter.adapt(ctx.enumValue()).collect(valueCtx ->
                {
                    PropertyImpl prop = new PropertyImpl()
                            ._name(valueCtx.identifier().getText())
                            ._sourceInformation(buildSourceInfo(valueCtx));
                    parseStereotypesAndTaggedValues(prop, valueCtx.stereotypes(), valueCtx.taggedValues());
                    return prop;
                }));

        elements.add(enumDef);
        return enumDef;
    }

    @Override
    public Object visitAssociation(final M3Parser.AssociationContext ctx)
    {
        AssociationImpl assoc = setNameAndPackage(new AssociationImpl(), ctx.qualifiedName().getText())
                ._sourceInformation(buildSourceInfo(ctx));

        // Parse stereotypes and tagged values for association
        parseStereotypesAndTaggedValues(assoc, ctx.stereotypes(), ctx.taggedValues());

        // Parse association properties
        if (ctx.associationBody() != null && ctx.associationBody().properties() != null)
        {
            assoc._properties(
                    ListAdapter.adapt(ctx.associationBody().properties().property())
                            .collect(ctx2 -> buildProperty(ctx2, java.util.Collections.emptySet(), java.util.Collections.emptySet())));
            assoc._qualifiedProperties(
                    ListAdapter.adapt(ctx.associationBody().properties().qualifiedProperty())
                            .collect(ctx2 -> buildQualifiedProperty(ctx2, java.util.Collections.emptySet(), java.util.Collections.emptySet())));
        }

        elements.add(assoc);
        return assoc;
    }

    // ========================================================================
    // Measure
    // ========================================================================

    @Override
    public Object visitMeasureDefinition(final M3Parser.MeasureDefinitionContext ctx)
    {
        String fullName = ctx.qualifiedName().getText();
        MeasureImpl measure = setNameAndPackage(new MeasureImpl(), fullName)
                ._sourceInformation(buildSourceInfo(ctx));

        // Stereotypes and tagged values
        parseStereotypesAndTaggedValues(measure, ctx.stereotypes(), ctx.taggedValues());

        M3Parser.MeasureBodyContext body = ctx.measureBody();

        // Canonical unit
        if (body.canonicalUnitExpr() != null)
        {
            measure._canonicalUnit(buildUnit(body.canonicalUnitExpr().unitExpr(), fullName));
        }

        if (body.unitExpr() != null)
        {
            MutableList nonCanonical = ListAdapter.adapt(body.unitExpr())
                    .collect(uCtx -> buildUnit(uCtx, fullName));
            if (body.nonConvertibleUnitExpr() != null)
            {
                nonCanonical.addAllIterable(
                        ListAdapter.adapt(body.nonConvertibleUnitExpr()).collect(ncCtx ->
                                new UnitImpl()
                                        ._name(ncCtx.identifier().getText())
                                        ._sourceInformation(buildSourceInfo(ncCtx))));
            }
            measure._nonCanonicalUnits(nonCanonical);
        }
        else if (body.nonConvertibleUnitExpr() != null)
        {
            measure._nonCanonicalUnits(
                    ListAdapter.adapt(body.nonConvertibleUnitExpr()).collect(ncCtx ->
                            new UnitImpl()
                                    ._name(ncCtx.identifier().getText())
                                    ._sourceInformation(buildSourceInfo(ncCtx))));
        }

        elements.add(measure);
        return measure;
    }

    private UnitImpl buildUnit(final M3Parser.UnitExprContext ctx, final String measureFqn)
    {
        M3Parser.UnitConversionExprContext conv = ctx.unitConversionExpr();
        return new UnitImpl()
                ._name(ctx.identifier().getText())
                ._sourceInformation(buildSourceInfo(ctx))
                ._conversionFunction(new LambdaFunctionImpl()
                        ._sourceInformation(buildSourceInfo(conv))
                        ._parameters(Lists.mutable.with(new VariableExpressionImpl()
                                ._name(conv.identifier().getText())))
                        ._expressionSequence(visitCodeBlockExpressions(conv.codeBlock(), java.util.Collections.emptySet(), java.util.Collections.emptySet())));
    }

    @Override
    public Object visitProfile(final M3Parser.ProfileContext ctx)
    {
        ProfileImpl profile = setNameAndPackage(new ProfileImpl(), ctx.qualifiedName().getText())
                ._sourceInformation(buildSourceInfo(ctx));

        // Parse stereotype definitions
        // Note: Do NOT set profile back-reference to avoid circular reference
        if (ctx.stereotypeDefinitions() != null)
        {
            profile._p_stereotypes(
                    ListAdapter.adapt(ctx.stereotypeDefinitions().identifier()).collect(idCtx ->
                            new StereotypeImpl()
                                    ._value(idCtx.getText())
                                    ._sourceInformation(buildSourceInfo(idCtx))));
        }

        // Parse tag definitions
        // Note: Do NOT set profile back-reference to avoid circular reference
        if (ctx.tagDefinitions() != null)
        {
            profile._p_tags(
                    ListAdapter.adapt(ctx.tagDefinitions().identifier()).collect(idCtx ->
                            new TagImpl()
                                    ._value(idCtx.getText())
                                    ._sourceInformation(buildSourceInfo(idCtx))));
        }

        elements.add(profile);
        return profile;
    }

    @SuppressWarnings("unchecked")
    private <T extends PackageableElement> T setNameAndPackage(final T element, final String fullName)
    {
        int lastSep = fullName.lastIndexOf("::");
        if (lastSep >= 0)
        {
            element._name(fullName.substring(lastSep + 2));
            element._package(new Package_PointerImpl()
                    ._pointerValue(fullName.substring(0, lastSep)));
        }
        else
        {
            element._name(fullName);
        }
        return element;
    }

    /**
     * Parse stereotypes and tagged values from context and add them to the given element.
     * This method is shared across all AnnotatedElement types (Class, Enumeration, Enum, etc.)
     */
    private void parseStereotypesAndTaggedValues(final AnnotatedElement element, final M3Parser.StereotypesContext stereotypesCtx, final M3Parser.TaggedValuesContext taggedValuesCtx)
    {
        if (stereotypesCtx != null)
        {
            element._stereotypes(
                    ListAdapter.adapt(stereotypesCtx.stereotype())
                            .collect(this::buildStereotypePointer));
        }

        if (taggedValuesCtx != null)
        {
            element._taggedValues(
                    ListAdapter.adapt(taggedValuesCtx.taggedValue())
                            .collect(this::buildTaggedValue));
        }
    }

    private SourceInformationImpl buildSourceInfo(final ParserRuleContext ctx)
    {
        return new SourceInformationImpl()
                ._startLine((long) ctx.getStart().getLine() + lineOffset)
                ._startColumn((long) ctx.getStart().getCharPositionInLine() + 1)
                ._endLine((long) ctx.getStop().getLine() + lineOffset)
                ._endColumn((long) (ctx.getStop().getCharPositionInLine()
                        + ctx.getStop().getText().length()));
    }

    /**
     * Build a Stereotype_Pointer from a stereotype context.
     * Grammar: stereotype = qualifiedName DOT identifier
     * pointerValue = profile path, extraPointerValues[0] = stereotype name
     */
    private Stereotype_PointerImpl buildStereotypePointer(final M3Parser.StereotypeContext ctx)
    {
        return new Stereotype_PointerImpl()
                ._pointerValue(ctx.qualifiedName().getText())
                ._extraPointerValues(Lists.mutable.with(
                        new PointerValueImpl()
                                ._value(ctx.identifier().getText())
                                ._sourceInformation(buildSourceInfo(ctx.identifier()))))
                ._sourceInformation(buildSourceInfo(ctx.qualifiedName()));
    }

    /**
     * Build a TaggedValue from a taggedValue context.
     * Grammar: taggedValue = qualifiedName DOT identifier EQUALS STRING
     * pointerValue = profile path, extraPointerValues[0] = tag name
     */
    private TaggedValueImpl buildTaggedValue(final M3Parser.TaggedValueContext ctx)
    {
        // Concatenate all STRING tokens (handles 'a' + 'b' syntax)
        StringBuilder sb = new StringBuilder();
        ListAdapter.adapt(ctx.STRING()).each(node ->
        {
            String raw = node.getText();
            sb.append(raw.substring(1, raw.length() - 1));
        });
        return new TaggedValueImpl()
                ._tag(new Tag_PointerImpl()
                        ._pointerValue(ctx.qualifiedName().getText())
                        ._extraPointerValues(Lists.mutable.with(
                                new PointerValueImpl()
                                        ._value(ctx.identifier().getText())
                                        ._sourceInformation(buildSourceInfo(ctx.identifier()))))
                        ._sourceInformation(buildSourceInfo(ctx.qualifiedName())))
                ._value(sb.toString());
    }

    // ========================================================================
    // Function Definition
    // ========================================================================

    @Override
    public Object visitFunctionDefinition(final M3Parser.FunctionDefinitionContext ctx)
    {
        String fullName = ctx.qualifiedName().getText();
        UserDefinedFunctionImpl func = setNameAndPackage(new UserDefinedFunctionImpl(), fullName)
                ._sourceInformation(buildSourceInfo(ctx));

        // Parse stereotypes and tagged values
        parseStereotypesAndTaggedValues(func, ctx.stereotypes(), ctx.taggedValues());

        // Collect type and multiplicity parameters (e.g., func<T,Z|m>)
        M3Parser.TypeAndMultiplicityParametersContext tpCtx = ctx.typeAndMultiplicityParameters();
        MutableList<TypeParameter> typeParams = collectTypeParams(tpCtx == null ? null : tpCtx.typeParameters());
        if (!typeParams.isEmpty())
        {
            func._typeParameters(typeParams);
        }
        Set<String> typeParamNames = typeParams.collect(tp -> tp._name()).toSet();
        Set<String> multParamNames = collectMultiplicityParams(tpCtx == null ? null : tpCtx.multiplictyParameters());
        if (!multParamNames.isEmpty())
        {
            func._multiplicityParameters(Lists.mutable.withAll(multParamNames).collect(n -> (MultiplicityParameter) new MultiplicityParameterImpl()._name(n)));
        }

        M3Parser.FunctionTypeSignatureContext sigCtx = ctx.functionTypeSignature();
        func._parameters(
                ListAdapter.adapt(sigCtx.functionVariableExpression())
                        .collect(ctx2 -> buildFunctionVariableExpression(ctx2, typeParamNames, multParamNames)));

        // Parse return type and multiplicity
        func._returnGenericType(buildGenericType(sigCtx.type(), typeParamNames, multParamNames));
        func._returnMultiplicity(parseMultiplicity(sigCtx.multiplicity().getText(), multParamNames));

        // Build function name (ID)
        String simpleName = fullName.contains("::") ? fullName.substring(fullName.lastIndexOf("::") + 2) : fullName;
        func._functionName(simpleName);
        func._name(_G_PackageableFunction.buildId(func));

        // Parse constraints
        if (ctx.constraints() != null)
        {
            func._preConstraints(
                    ListAdapter.adapt(ctx.constraints().constraint())
                            .reject(cCtx -> cCtx.getText().contains("$return"))
                            .collect(ctx2 -> buildConstraint(ctx2, typeParamNames, multParamNames)));
            func._postConstraints(
                    ListAdapter.adapt(ctx.constraints().constraint())
                            .select(cCtx -> cCtx.getText().contains("$return"))
                            .collect(ctx2 -> buildConstraint(ctx2, typeParamNames, multParamNames)));
        }

        // Parse body (codeBlock)
        func._expressionSequence(visitCodeBlockExpressions(ctx.codeBlock(), typeParamNames, multParamNames));

        elements.add(func);
        return func;
    }

    @Override
    public Object visitNativeFunction(final M3Parser.NativeFunctionContext ctx)
    {
        String fullName = ctx.qualifiedName().getText();
        NativeFunctionImpl func = setNameAndPackage(new NativeFunctionImpl(), fullName)
                ._sourceInformation(buildSourceInfo(ctx));

        // Parse stereotypes and tagged values
        parseStereotypesAndTaggedValues(func, ctx.stereotypes(), ctx.taggedValues());

        // Collect type and multiplicity parameters (e.g., rename<T,Z,K,V>)
        M3Parser.TypeAndMultiplicityParametersContext tpCtx = ctx.typeAndMultiplicityParameters();
        MutableList<TypeParameter> typeParams = collectTypeParams(tpCtx == null ? null : tpCtx.typeParameters());
        if (!typeParams.isEmpty())
        {
            func._typeParameters(typeParams);
        }
        Set<String> typeParamNames = typeParams.collect(tp -> tp._name()).toSet();
        Set<String> multParamNames = collectMultiplicityParams(tpCtx == null ? null : tpCtx.multiplictyParameters());
        if (!multParamNames.isEmpty())
        {
            func._multiplicityParameters(Lists.mutable.withAll(multParamNames).collect(n -> (MultiplicityParameter) new MultiplicityParameterImpl()._name(n)));
        }

        M3Parser.FunctionTypeSignatureContext sigCtx = ctx.functionTypeSignature();
        func._parameters(
                ListAdapter.adapt(sigCtx.functionVariableExpression())
                        .collect(ctx2 -> buildFunctionVariableExpression(ctx2, typeParamNames, multParamNames)));

        // Parse return type and multiplicity
        func._returnGenericType(buildGenericType(sigCtx.type(), typeParamNames, multParamNames));
        func._returnMultiplicity(parseMultiplicity(sigCtx.multiplicity().getText(), multParamNames));

        // Build function name (ID)
        String simpleName = fullName.contains("::") ? fullName.substring(fullName.lastIndexOf("::") + 2) : fullName;
        func._functionName(simpleName);
        func._name(_G_PackageableFunction.buildId(func));

        elements.add(func);
        return func;
    }


    // ========================================================================
    // Code Block and Expression Visitors
    // ========================================================================

    private MutableList<ValueSpecification> visitCodeBlockExpressions(final M3Parser.CodeBlockContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        return ListAdapter.adapt(ctx.programLine())
                .collectIf(
                        lineCtx -> lineCtx.combinedExpression() != null || lineCtx.letExpression() != null,
                        lineCtx -> lineCtx.combinedExpression() != null
                                ? visitCombinedExpr(lineCtx.combinedExpression(), typeParamNames, multParamNames)
                                : visitLetExpr(lineCtx.letExpression(), typeParamNames, multParamNames));
    }

    private ValueSpecification visitLetExpr(final M3Parser.LetExpressionContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        // let x = expr  →  FunctionExpression(functionName="letFunction", params=[name, value])
        return new FunctionInvocationImpl()
                ._sourceInformation(buildSourceInfo(ctx))
                ._functionName("letFunction")
                ._parametersValues(Lists.mutable.with(
                        new AtomicValueImpl()
                                ._genericType(buildPrimitiveGenericType("String"))
                                ._value(ctx.identifier().getText()),
                        visitCombinedExpr(ctx.combinedExpression(), typeParamNames, multParamNames)));
    }

    private ValueSpecification visitCombinedExpr(final M3Parser.CombinedExpressionContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        // combinedExpression: expressionOrExpressionGroup expressionPart*
        // Precedence handling: batch arithmetic parts and boolean
        // parts separately to apply operator precedence via
        // snatching (see applyArithmeticParts).
        ValueSpecification result = visitExprOrGroup(ctx.expressionOrExpressionGroup(), typeParamNames, multParamNames);

        MutableList<M3Parser.ArithmeticPartContext> arth = Lists.mutable.empty();
        MutableList<M3Parser.BooleanPartContext> bool = Lists.mutable.empty();

        // Invariant: arth.isEmpty() || bool.isEmpty()
        final ValueSpecification[] accum = {result, result}; // [0]=boolResult, [1]=arithResult
        ListAdapter.adapt(ctx.expressionPart()).each(partCtx ->
        {
            if (partCtx.arithmeticPart() != null)
            {
                if (!bool.isEmpty())
                {
                    accum[0] = applyBooleanParts(bool, accum[1], typeParamNames, multParamNames);
                    bool.clear();
                }
                arth.add(partCtx.arithmeticPart());
            }
            else if (partCtx.booleanPart() != null)
            {
                if (!arth.isEmpty())
                {
                    accum[1] = applyArithmeticParts(arth, accum[0], typeParamNames, multParamNames);
                    arth.clear();
                }
                bool.add(partCtx.booleanPart());
            }
        });

        if (!arth.isEmpty())
        {
            result = applyArithmeticParts(arth, accum[0], typeParamNames, multParamNames);
        }
        else if (!bool.isEmpty())
        {
            result = applyBooleanParts(bool, accum[1], typeParamNames, multParamNames);
        }
        return result;
    }

    private ValueSpecification visitExprOrGroup(final M3Parser.ExpressionOrExpressionGroupContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        return visitExpr(ctx.expression(), typeParamNames, multParamNames);
    }

    private ValueSpecification visitExpr(final M3Parser.ExpressionContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        // expression: nonArrowOrEqualExpression (propertyOrFunctionExpression)* (equalNotEqual)?
        ValueSpecification result = ListAdapter.adapt(
                ctx.propertyOrFunctionExpression()).injectInto(
                visitNonArrowOrEqual(ctx.nonArrowOrEqualExpression(), typeParamNames, multParamNames),
                (acc, pofCtx) ->
                {
                    if (pofCtx.propertyExpression() != null)
                    {
                        return visitPropertyExpr(acc, pofCtx.propertyExpression(), typeParamNames, multParamNames);
                    }
                    else if (pofCtx.functionExpression() != null)
                    {
                        return visitArrowFunctionExpr(acc, pofCtx.functionExpression(), typeParamNames, multParamNames);
                    }
                    return acc;
                });

        if (ctx.equalNotEqual() != null)
        {
            result = applyEqualNotEqual(result, ctx.equalNotEqual(), typeParamNames, multParamNames);
        }

        return result;
    }

    private ValueSpecification visitNonArrowOrEqual(final M3Parser.NonArrowOrEqualExpressionContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        if (ctx.atomicExpression() != null)
        {
            return visitAtomicExpr(ctx.atomicExpression(), typeParamNames, multParamNames);
        }
        else if (ctx.expressionsArray() != null)
        {
            return visitExpressionsArr(ctx.expressionsArray(), typeParamNames, multParamNames);
        }
        else if (ctx.notExpression() != null)
        {
            return new FunctionInvocationImpl()
                    ._sourceInformation(buildSourceInfo(ctx.notExpression()))
                    ._functionName("not")
                    ._parametersValues(Lists.mutable.with(
                            visitSimpleExpr(ctx.notExpression().simpleExpression(), typeParamNames, multParamNames)));
        }
        else if (ctx.signedExpression() != null)
        {
            M3Parser.SignedExpressionContext signCtx = ctx.signedExpression();
            ValueSpecification inner = visitSimpleExpr(signCtx.simpleExpression(), typeParamNames, multParamNames);
            if (signCtx.MINUS() != null)
            {
                // Fold negation into numeric literals: -1 -> AtomicValue(-1)
                if (inner instanceof AtomicValueImpl av && av._value() instanceof Number num)
                {
                    Object negated = negateNumber(num);
                    return av._value(negated);
                }
                return new FunctionInvocationImpl()
                        ._sourceInformation(buildSourceInfo(signCtx))
                        ._functionName("minus")
                        ._parametersValues(Lists.mutable.with(inner));
            }
            return inner;
        }
        else if (ctx.sliceExpression() != null)
        {
            M3Parser.SliceExpressionContext sliceCtx = ctx.sliceExpression();
            return new FunctionInvocationImpl()
                    ._sourceInformation(buildSourceInfo(sliceCtx))
                    ._functionName("slice")
                    ._parametersValues(
                            ListAdapter.adapt(sliceCtx.expression())
                                    .collect(e -> visitExpr(e, typeParamNames, multParamNames)));
        }
        else if (ctx.combinedExpression() != null)
        {
            // Parenthesized: ( combinedExpression )
            return visitCombinedExpr(ctx.combinedExpression(), typeParamNames, multParamNames);
        }
        throw new RuntimeException("Unexpected nonArrowOrEqualExpression: " + ctx.getText());
    }

    // ========================================================================
    // Atomic Expressions
    // ========================================================================

    private ValueSpecification visitAtomicExpr(final M3Parser.AtomicExpressionContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        if (ctx.variable() != null)
        {
            return visitVariableRef(ctx.variable());
        }
        else if (ctx.instanceLiteralToken() != null)
        {
            return visitLiteralToken(ctx.instanceLiteralToken());
        }
        else if (ctx.anyLambda() != null)
        {
            return visitAnyLambdaExpr(ctx.anyLambda(), typeParamNames, multParamNames);
        }
        else if (ctx.instanceReference() != null)
        {
            return visitInstanceRef(ctx.instanceReference(), typeParamNames, multParamNames);
        }
        else if (ctx.expressionInstance() != null)
        {
            return buildExpressionInstance(ctx.expressionInstance(), typeParamNames, multParamNames);
        }
        else if (ctx.dsl() != null)
        {
            // DSL block: #{...}# -> Collection wrapping the text
            return new AtomicValueImpl()
                    ._sourceInformation(buildSourceInfo(ctx.dsl()))
                    ._genericType(buildPrimitiveGenericType("String"))
                    ._value(ctx.dsl().DSL_TEXT().getText());
        }
        else if (ctx.unitInstance() != null)
        {
            // Unit instance: 5 Measure~Unit
            M3Parser.UnitInstanceContext uiCtx = ctx.unitInstance();
            return new FunctionInvocationImpl()
                    ._sourceInformation(buildSourceInfo(uiCtx))
                    ._functionName("unitInstance")
                    ._parametersValues(Lists.mutable.with(
                            new AtomicValueImpl()
                                    ._genericType(buildPrimitiveGenericType("String"))
                                    ._value(uiCtx.unitInstanceLiteral().getText()),
                            new AtomicValueImpl()
                                    ._genericType(buildPrimitiveGenericType("String"))
                                    ._value(uiCtx.unitName().getText())));
        }
        else if (ctx.columnBuilders() != null)
        {
            // Column builders: ~colName or ~[col1, col2]
            M3Parser.ColumnBuildersContext cbCtx = ctx.columnBuilders();
            MutableList<M3Parser.OneColSpecContext> colSpecs = ListAdapter.adapt(cbCtx.oneColSpec());
            MutableList<ValueSpecification> builtCols = ListAdapter.adapt(colSpecs).collect(colSpec ->
            {
                boolean hasLambda = colSpec.anyLambda() != null;
                boolean hasReduceLambda = colSpec.extraFunction() != null;
                String funcName;
                if (hasLambda)
                {
                    int lambdaParamCount = countLambdaParams(colSpec.anyLambda());
                    if (hasReduceLambda)
                    {
                        funcName = lambdaParamCount == 3 ? "aggColSpec2" : "aggColSpec";
                    }
                    else
                    {
                        funcName = lambdaParamCount == 3 ? "funcColSpec2" : "funcColSpec";
                    }
                }
                else
                {
                    funcName = "colSpec";
                }
                MutableList<ValueSpecification> paramVals = Lists.mutable.empty();

                // map lambda as first param
                if (hasLambda)
                {
                    paramVals.add(visitAnyLambdaExpr(colSpec.anyLambda(), typeParamNames, multParamNames));
                }

                // reduce lambda as second param (for aggColSpec)
                if (hasReduceLambda)
                {
                    paramVals.add(visitAnyLambdaExpr(colSpec.extraFunction().anyLambda(), typeParamNames, multParamNames));
                }

                // Column name (String)
                paramVals.add(new AtomicValueImpl()
                        ._genericType(buildPrimitiveGenericType("String"))
                        ._value(colSpec.columnName().getText()));

                if (colSpec.type() != null || colSpec.multiplicity() != null)
                {
                    // Build a RelationType column wrapping the column name + type
                    ColumnImpl col = new ColumnImpl()
                            ._name(colSpec.columnName().getText());
                    if (colSpec.type() != null)
                    {
                        col._genericType(buildGenericType(colSpec.type(), typeParamNames));
                    }
                    if (colSpec.multiplicity() != null)
                    {
                        col._multiplicity(parseMultiplicity(colSpec.multiplicity().getText(), multParamNames));
                    }
                    RelationTypeImpl relationType = new RelationTypeImpl()
                            ._columns(Lists.mutable.with(col));
                    UserDefinedGenericTypeAndMultiplicityHolderImpl holder = new UserDefinedGenericTypeAndMultiplicityHolderImpl()
                            ._genericType(new ConcreteGenericTypeImpl()._rawType(relationType));
                    paramVals.add(holder);
                }
                else if (hasLambda)
                {
                    // funcColSpec/aggColSpec TypeHolder — compiler fills the genericType
                    CompilerGenericTypeAndMultiplicityHolderImpl holder = new CompilerGenericTypeAndMultiplicityHolderImpl();
                    paramVals.add(holder);
                }
                else
                {
                    // plain colSpec (e.g. ~colName) — compiler infers T from context
                    CompilerGenericTypeAndMultiplicityHolderImpl holder = new CompilerGenericTypeAndMultiplicityHolderImpl();
                    paramVals.add(holder);
                }
                return (ValueSpecification) new FunctionInvocationImpl()
                        ._sourceInformation(buildSourceInfo(colSpec))
                        ._functionName(funcName)
                        ._parametersValues(paramVals);
            });
            if (colSpecs.size() == 1 && cbCtx.BRACKET_OPEN() == null)
            {
                // Single column: ~colName or ~colName:Type — return inner colSpec() directly
                return builtCols.getFirst();
            }
            else
            {
                // Multi column: ~[col1, col2] or ~[col1:Type1, col2:Type2] or ~[col1:x|f(x), col2:y|g(y)]
                boolean anyLambda = colSpecs.anySatisfy(cs -> cs.anyLambda() != null);

                if (anyLambda)
                {
                    // Determine array function based on lambda param count and whether reduce lambdas are present:
                    // With reduce lambda → aggColSpecArray / aggColSpecArray2
                    // Without reduce lambda → funcColSpecArray / funcColSpecArray2
                    M3Parser.OneColSpecContext firstLambdaCol = colSpecs.detect(cs -> cs.anyLambda() != null);
                    int lambdaParamCount = countLambdaParams(firstLambdaCol.anyLambda());
                    boolean hasReduceLambda = colSpecs.anySatisfy(cs -> cs.extraFunction() != null);
                    String arrayFuncName;
                    if (hasReduceLambda)
                    {
                        arrayFuncName = lambdaParamCount == 3 ? "aggColSpecArray2" : "aggColSpecArray";
                    }
                    else
                    {
                        arrayFuncName = lambdaParamCount == 3 ? "funcColSpecArray2" : "funcColSpecArray";
                    }

                    // Pass the already-built funcColSpec/funcColSpec2 expressions as a Collection
                    MutableList<ValueSpecification> arrayParams = Lists.mutable.empty();
                    arrayParams.add(new CollectionImpl()
                            ._values(builtCols)
                            ._multiplicity(new ConcreteMultiplicityImpl()
                                    ._lowerBound(multVal(builtCols.size()))
                                    ._upperBound(multVal(builtCols.size()))));

                    CompilerGenericTypeAndMultiplicityHolderImpl holder = new CompilerGenericTypeAndMultiplicityHolderImpl();
                    arrayParams.add(holder);

                    return new FunctionInvocationImpl()
                            ._sourceInformation(buildSourceInfo(cbCtx))
                            ._functionName(arrayFuncName)
                            ._parametersValues(arrayParams);
                }
                else
                {
                    // colSpecArray<T>(s:String[*], cl:T[1]) takes names as String[*] and a single TypeHolder
                    MutableList<ValueSpecification> arrayParams = Lists.mutable.empty();

                    // Collect all column names as String values
                    MutableList<ValueSpecification> nameValues = ListAdapter.adapt(colSpecs).collect(colSpec ->
                            (ValueSpecification) new AtomicValueImpl()
                                    ._genericType(buildPrimitiveGenericType("String"))
                                    ._value(colSpec.columnName().getText()));
                    arrayParams.add(new CollectionImpl()
                            ._values(nameValues)
                            ._multiplicity(new ConcreteMultiplicityImpl()
                                    ._lowerBound(multVal(nameValues.size()))
                                    ._upperBound(multVal(nameValues.size()))));

                    // Build combined RelationType from all typed columns
                    MutableList<meta.pure.protocol.grammar.relation.Column> columns = Lists.mutable.empty();
                    for (M3Parser.OneColSpecContext colSpec : colSpecs)
                    {
                        if (colSpec.type() != null || colSpec.multiplicity() != null)
                        {
                            ColumnImpl col = new ColumnImpl()
                                    ._name(colSpec.columnName().getText());
                            if (colSpec.type() != null)
                            {
                                col._genericType(buildGenericType(colSpec.type(), typeParamNames));
                            }
                            if (colSpec.multiplicity() != null)
                            {
                                col._multiplicity(parseMultiplicity(colSpec.multiplicity().getText(), multParamNames));
                            }
                            columns.add(col);
                        }
                    }
                    if (columns.notEmpty())
                    {
                        RelationTypeImpl relationType = new RelationTypeImpl()._columns(columns);
                        UserDefinedGenericTypeAndMultiplicityHolderImpl holder = new UserDefinedGenericTypeAndMultiplicityHolderImpl()
                                ._genericType(new ConcreteGenericTypeImpl()._rawType(relationType));
                        arrayParams.add(holder);
                    }
                    else
                    {
                        // No types — compiler infers T from context
                        CompilerGenericTypeAndMultiplicityHolderImpl holder = new CompilerGenericTypeAndMultiplicityHolderImpl();
                        arrayParams.add(holder);
                    }

                    return new FunctionInvocationImpl()
                            ._sourceInformation(buildSourceInfo(cbCtx))
                            ._functionName("colSpecArray")
                            ._parametersValues(arrayParams);
                }
            }
        }
        else if (ctx.AT() != null)
        {
            // @Type reference — produces a GenericTypeHolder
            if (ctx.type() != null)
            {
                return new UserDefinedGenericTypeAndMultiplicityHolderImpl()
                        ._sourceInformation(buildSourceInfo(ctx))
                        ._genericType(buildGenericType(ctx.type(), typeParamNames));
            }
            else if (ctx.multiplicity() != null)
            {
                return new UserDefinedGenericTypeAndMultiplicityHolderImpl()
                        ._sourceInformation(buildSourceInfo(ctx))
                        ._multiplicity(parseMultiplicity(ctx.multiplicity().getText(), multParamNames));
            }
            else
            {
                throw new RuntimeException("@Type reference without type or multiplicity: " + ctx.getText());
            }
        }
        throw new RuntimeException("Unsupported atomicExpression: " + ctx.getText());
    }

    // ========================================================================
    // Expression Instance (^Type(prop=value, ...))
    // ========================================================================

    private ValueSpecification buildExpressionInstance(final M3Parser.ExpressionInstanceContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        boolean isCopy = ctx.variable() != null;

        MutableList<ValueSpecification> params;

        if (isCopy)
        {
            // Copy: ^$variable(prop=value, ...)
            // First param: variable expression referencing the source
            params = Lists.mutable.with(
                    new VariableExpressionImpl()
                            ._name(ctx.variable().identifier().getText())
                            ._sourceInformation(buildSourceInfo(ctx.variable())));
        }
        else
        {
            // New: ^Type(prop=value, ...) or ^Type<Args>(prop=value, ...)
            String typeName = ctx.qualifiedName() != null
                    ? ctx.qualifiedName().getText()
                    : "Unknown";

            ConcreteGenericTypeImpl genericType = new ConcreteGenericTypeImpl()
                    ._rawType(new Type_PointerImpl()._pointerValue(typeName));

            // Type arguments and multiplicity arguments: ^Type<Args|MultArgs>(...)
            if (ctx.typeArguments() != null)
            {
                genericType._typeArguments(
                        ListAdapter.adapt(ctx.typeArguments().typeWithOperation())
                                .collect(twCtx -> buildTypeWithOperation(twCtx, typeParamNames, multParamNames)));
            }
            if (ctx.multiplicityArguments() != null)
            {
                genericType._multiplicityArguments(
                        ListAdapter.adapt(ctx.multiplicityArguments()
                                        .multiplicityArgument())
                                .collect(this::parseMultiplicityArgument));
            }

            params = Lists.mutable.with(
                    new UserDefinedGenericTypeAndMultiplicityHolderImpl()
                            ._sourceInformation(buildSourceInfo(ctx))
                            ._genericType(genericType)
                            ._multiplicity(buildPureOne()));
        }

        // Remaining params: property assignments wrapped as a single Collection
        // of keyExpression calls, to match new(T[1], KeyExpression[*])
        if (!ctx.expressionInstanceParserPropertyAssignment().isEmpty())
        {
            MutableList<ValueSpecification> keyExprs = ListAdapter.adapt(ctx.expressionInstanceParserPropertyAssignment()).collect(assignCtx ->
            {
                    MutableList<ValueSpecification> keParams = Lists.mutable.with(
                            new AtomicValueImpl()
                                    ._sourceInformation(buildSourceInfo(assignCtx))
                                    ._genericType(buildPrimitiveGenericType("String"))
                                    ._value(
                                            ListAdapter.adapt(assignCtx.propertyName())
                                                    .collect(pnCtx -> pnCtx.getText())
                                                    .makeString(".")),
                            visitExprInstanceRightSide(assignCtx.expressionInstanceRightSide(), typeParamNames, multParamNames));
                    if (assignCtx.PLUS() != null)
                    {
                        keParams.add(new AtomicValueImpl()
                                ._genericType(buildPrimitiveGenericType("Boolean"))
                                ._value(true));
                    }
                    return (ValueSpecification) new FunctionInvocationImpl()
                            ._sourceInformation(buildSourceInfo(assignCtx))
                            ._functionName("keyExpression")
                            ._parametersValues(keParams);
            });
            params.add(new CollectionImpl()
                    ._sourceInformation(buildSourceInfo(ctx))
                    ._values(keyExprs)
                    ._multiplicity(new ConcreteMultiplicityImpl()
                            ._lowerBound(multVal(keyExprs.size()))
                            ._upperBound(multVal(keyExprs.size()))));
        }

        return new FunctionInvocationImpl()
                ._sourceInformation(buildSourceInfo(ctx))
                ._functionName(isCopy ? "copy" : "new")
                ._parametersValues(params);
    }

    /**
     * Parse the right-hand side of an expression instance property assignment.
     * Reuses existing expression visitors for each grammar alternative.
     */
    private ValueSpecification visitExprInstanceRightSide(final M3Parser.ExpressionInstanceRightSideContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        M3Parser.ExpressionInstanceAtomicRightSideContext atomic = ctx.expressionInstanceAtomicRightSide();
        if (atomic.combinedExpression() != null)
        {
            return visitCombinedExpr(atomic.combinedExpression(), typeParamNames, multParamNames);
        }
        else if (atomic.expressionInstance() != null)
        {
            return buildExpressionInstance(atomic.expressionInstance(), typeParamNames, multParamNames);
        }
        else if (atomic.qualifiedName() != null)
        {
            // qualifiedName as a type/enum reference
            return new VariableExpressionImpl()
                    ._name(atomic.qualifiedName().getText())
                    ._sourceInformation(buildSourceInfo(atomic));
        }
        throw new RuntimeException("Unsupported expressionInstanceRightSide: " + ctx.getText());
    }

    // ========================================================================
    // Variable ($x)
    // ========================================================================

    private VariableExpressionImpl visitVariableRef(final M3Parser.VariableContext ctx)
    {
        return new VariableExpressionImpl()
                ._name(ctx.identifier().getText())
                ._sourceInformation(buildSourceInfo(ctx));
    }

    // ========================================================================
    // Instance Literals (integer, string, float, boolean, date)
    // ========================================================================

    private AtomicValueImpl visitLiteralToken(final M3Parser.InstanceLiteralTokenContext ctx)
    {
        Object value;
        String genericTypeName;

        if (ctx.INTEGER() != null)
        {
            value = Long.parseLong(ctx.INTEGER().getText());
            genericTypeName = "Integer";
        }
        else if (ctx.STRING() != null)
        {
            String raw = ctx.STRING().getText();
            value = raw.substring(1, raw.length() - 1);
            genericTypeName = "String";
        }
        else if (ctx.FLOAT() != null)
        {
            value = Double.parseDouble(ctx.FLOAT().getText());
            genericTypeName = "Float";
        }
        else if (ctx.DECIMAL() != null)
        {
            value = Double.parseDouble(ctx.DECIMAL().getText());
            genericTypeName = "Decimal";
        }
        else if (ctx.BOOLEAN() != null)
        {
            value = Boolean.parseBoolean(ctx.BOOLEAN().getText());
            genericTypeName = "Boolean";
        }
        else if (ctx.DATE() != null)
        {
            value = ctx.DATE().getText();
            genericTypeName = ctx.DATE().getText().contains("T") ? "DateTime" : "StrictDate";
        }
        else if (ctx.STRICTTIME() != null)
        {
            value = ctx.STRICTTIME().getText();
            genericTypeName = "StrictTime";
        }
        else
        {
            throw new RuntimeException("Unsupported literal token: " + ctx.getText());
        }

        return new AtomicValueImpl()
                ._sourceInformation(buildSourceInfo(ctx))
                ._value(value)
                ._genericType(buildPrimitiveGenericType(genericTypeName));
    }

    private ConcreteGenericTypeImpl buildPrimitiveGenericType(final String typeName)
    {
        return new ConcreteGenericTypeImpl()
                ._rawType(new Type_PointerImpl()._pointerValue(typeName));
    }

    private ConcreteMultiplicityImpl buildPureOne()
    {
        return new ConcreteMultiplicityImpl()
                ._lowerBound(multVal(1))
                ._upperBound(multVal(1));
    }

    private MultiplicityValueImpl multVal(final long v)
    {
        return new MultiplicityValueImpl()._value(v);
    }

    // ========================================================================
    // Lambda / anonymous function
    // ========================================================================

    private ValueSpecification visitAnyLambdaExpr(final M3Parser.AnyLambdaContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        LambdaFunctionImpl lambda;
        if (ctx.lambdaFunction() != null)
        {
            lambda = visitLambdaFunc(ctx.lambdaFunction(), typeParamNames, multParamNames);
        }
        else if (ctx.lambdaPipe() != null && ctx.lambdaParam() != null)
        {
            // lambdaParam lambdaPipe  (inline form: x|...)
            lambda = buildLambda(
                    Lists.mutable.with(ctx.lambdaParam()),
                    ctx.lambdaPipe(), ctx, typeParamNames, multParamNames);
        }
        else if (ctx.lambdaPipe() != null)
        {
            // bare pipe: |...
            lambda = buildLambda(Lists.mutable.empty(),
                    ctx.lambdaPipe(), ctx, typeParamNames, multParamNames);
        }
        else
        {
            throw new RuntimeException("Unsupported anyLambda: " + ctx.getText());
        }

        // Wrap lambda in an AtomicValue since LambdaFunction is not a ValueSpecification
        return new AtomicValueImpl()
                ._sourceInformation(lambda._sourceInformation())
                ._value(lambda);
    }

    private LambdaFunctionImpl visitLambdaFunc(final M3Parser.LambdaFunctionContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        // lambdaFunction: { (lambdaParam,...)? lambdaPipe }
        return buildLambda(ctx.lambdaParam(), ctx.lambdaPipe(), ctx, typeParamNames, multParamNames);
    }

    private LambdaFunctionImpl buildLambda(final List<M3Parser.LambdaParamContext> paramCtxs, final M3Parser.LambdaPipeContext pipeCtx, final ParserRuleContext outerCtx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        return new LambdaFunctionImpl()
                ._sourceInformation(buildSourceInfo(outerCtx))
                ._parameters(
                        ListAdapter.adapt(paramCtxs).collect(paramCtx ->
                        {
                            VariableExpressionImpl param = new VariableExpressionImpl()
                                    ._name(paramCtx.identifier().getText());
                            if (paramCtx.lambdaParamType() != null)
                            {
                                param._sourceInformation(buildSourceInfo(paramCtx))
                                        ._genericType(buildGenericType(paramCtx.lambdaParamType().type(), typeParamNames))
                                        ._multiplicity(parseMultiplicity(paramCtx.lambdaParamType().multiplicity().getText(), multParamNames));
                            }
                            return param;
                        }))
                ._expressionSequence(visitCodeBlockExpressions(pipeCtx.codeBlock(), typeParamNames, multParamNames));
    }

    /**
     * Count the number of lambda parameters from an anyLambda grammar context.
     * Used to dispatch funcColSpec (1 param) vs funcColSpec2 (3 params).
     */
    private int countLambdaParams(M3Parser.AnyLambdaContext ctx)
    {
        if (ctx.lambdaFunction() != null)
        {
            return ctx.lambdaFunction().lambdaParam().size();
        }
        else if (ctx.lambdaParam() != null)
        {
            return 1;
        }
        return 0;
    }

    // ========================================================================
    // Instance Reference (function call / variable reference)
    // ========================================================================

    private ValueSpecification visitInstanceRef(final M3Parser.InstanceReferenceContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        // instanceReference: (PATH_SEPARATOR | qualifiedName | unitName) allOrFunction?
        String name = ctx.getText();
        if (ctx.allOrFunction() != null && ctx.allOrFunction().functionExpressionParameters() != null)
        {
            // It's a function call: name(args)
            name = ctx.qualifiedName() != null
                    ? ctx.qualifiedName().getText()
                    : ctx.getText();

            FunctionInvocationImpl sfe = new FunctionInvocationImpl()
                    ._sourceInformation(buildSourceInfo(ctx))
                    ._functionName(name);

            M3Parser.FunctionExpressionParametersContext paramsCtx = ctx.allOrFunction().functionExpressionParameters();
            sfe._parametersValues(
                    ListAdapter.adapt(paramsCtx.combinedExpression())
                            .collect(ctx2 -> visitCombinedExpr(ctx2, typeParamNames, multParamNames)));
            return sfe;
        }

        if (ctx.allOrFunction() != null && ctx.allOrFunction().allFunction() != null)
        {
            // Class.all()
            return buildAllFunction(ctx, "getAll");
        }

        if (ctx.allOrFunction() != null && ctx.allOrFunction().allVersionsFunction() != null)
        {
            // Class.allVersions()
            return buildAllFunction(ctx, "getAllVersions");
        }

        if (ctx.allOrFunction() != null && ctx.allOrFunction().allVersionsInRangeFunction() != null)
        {
            // Class.allVersionsInRange(start, end)
            M3Parser.AllVersionsInRangeFunctionContext avirCtx = ctx.allOrFunction().allVersionsInRangeFunction();
            return buildAllFunction(ctx, "getAllVersionsInRange",
                    ListAdapter.adapt(avirCtx.buildMilestoningVariableExpression())
                            .collect(this::visitMilestoningExpr));
        }

        if (ctx.allOrFunction() != null && ctx.allOrFunction().allFunctionWithMilestoning() != null)
        {
            // Class.all(date) or Class.all(date1, date2)
            M3Parser.AllFunctionWithMilestoningContext afmCtx = ctx.allOrFunction().allFunctionWithMilestoning();
            return buildAllFunction(ctx, "getAll",
                    ListAdapter.adapt(afmCtx.buildMilestoningVariableExpression())
                            .collect(this::visitMilestoningExpr));
        }

        // Simple qualified name reference (class, enum, etc.)
        return new AtomicValueImpl()
                ._sourceInformation(buildSourceInfo(ctx))
                ._value(new Package_PointerImpl()
                        ._pointerValue(name));
    }

    private FunctionInvocationImpl buildAllFunction(final M3Parser.InstanceReferenceContext ctx, final String functionName)
    {
        return buildAllFunction(ctx, functionName, Lists.mutable.empty());
    }

    private FunctionInvocationImpl buildAllFunction(final M3Parser.InstanceReferenceContext ctx, final String functionName, final MutableList<ValueSpecification> additionalParams)
    {
        // First parameter is the class reference
        String className = ctx.qualifiedName() != null
                ? ctx.qualifiedName().getText()
                : ctx.getText().split("\\.")[0];
        return new FunctionInvocationImpl()
                ._sourceInformation(buildSourceInfo(ctx))
                ._functionName(functionName)
                ._parametersValues(Lists.mutable.<ValueSpecification>with(
                                new AtomicValueImpl()
                                        ._sourceInformation(buildSourceInfo(ctx))
                                        ._value(new Package_PointerImpl()
                                                ._pointerValue(className)))
                        .withAll(additionalParams));
    }

    private ValueSpecification visitMilestoningExpr(final M3Parser.BuildMilestoningVariableExpressionContext ctx)
    {
        if (ctx.variable() != null)
        {
            return visitVariableRef(ctx.variable());
        }
        // TODO: properly handle LATEST_DATE and DATE tokens once
        //       CDateTime/CStrictDate types are available in protocol model
        throw new UnsupportedOperationException("Milestoning date expressions not yet supported: " + ctx.getText());
    }

    // ========================================================================
    // Property & Arrow Function Expressions
    // ========================================================================

    private ValueSpecification visitPropertyExpr(final ValueSpecification receiver, final M3Parser.PropertyExpressionContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        // property access: .propName or .propName(args)
        MutableList<ValueSpecification> params = Lists.mutable.with(receiver);
        if (ctx.functionExpressionParameters() != null)
        {
            params.addAllIterable(
                    ListAdapter.adapt(ctx.functionExpressionParameters().combinedExpression())
                            .collect(ctx2 -> visitCombinedExpr(ctx2, typeParamNames, multParamNames)));
        }
        return new DotApplicationImpl()
                ._sourceInformation(buildSourceInfo(ctx))
                ._functionName(ctx.propertyName().getText())
                ._parametersValues(params);
    }

    private ValueSpecification visitArrowFunctionExpr(final ValueSpecification receiver, final M3Parser.FunctionExpressionContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        // arrow: ->funcName(args) (->funcName(args))*
        return ListIterate.injectInto(receiver,
                ctx.qualifiedName(),
                (current, qn) ->
                {
                    int idx = ctx.qualifiedName().indexOf(qn);
                    ArrowInvocationImpl sfe = new ArrowInvocationImpl()
                            ._sourceInformation(buildSourceInfo(ctx))
                            ._functionName(qn.getText());
                    MutableList<ValueSpecification> params = Lists.mutable.with(current);
                    params.addAllIterable(
                            ListAdapter.adapt(ctx.functionExpressionParameters(idx)
                                            .combinedExpression())
                                    .collect(ctx2 -> visitCombinedExpr(ctx2, typeParamNames, multParamNames)));
                    sfe._parametersValues(params);
                    return sfe;
                });
    }

    // ========================================================================
    // Expression Parts (arithmetic, boolean, equal/notEqual)
    // ========================================================================

    // ----- Arithmetic precedence helpers -----

    private static boolean isAdditiveOp(final String op)
    {
        return "plus".equals(op) || "minus".equals(op);
    }

    private static boolean isProductOp(final String op)
    {
        return "times".equals(op) || "divide".equals(op);
    }

    private static boolean isRelationalOp(final String op)
    {
        return "lessThan".equals(op)
                || "lessThanEqual".equals(op)
                || "greaterThan".equals(op)
                || "greaterThanEqual".equals(op);
    }

    /**
     * Returns true if operator1 has strictly lower precedence
     * than operator2.
     * Precedence: relational < additive < multiplicative
     */
    private static boolean isStrictlyLowerPrecedence(final String operator1, final String operator2)
    {
        return (isRelationalOp(operator1)
                && (isAdditiveOp(operator2)
                || isProductOp(operator2)))
                || (isAdditiveOp(operator1)
                && isProductOp(operator2));
    }

    // ----- Arithmetic part building -----

    private String getArithFuncName(final M3Parser.ArithmeticPartContext ctx)
    {
        if (ctx.PLUS() != null && !ctx.PLUS().isEmpty())
        {
            return "plus";
        }
        if (ctx.STAR() != null && !ctx.STAR().isEmpty())
        {
            return "times";
        }
        if (ctx.MINUS() != null && !ctx.MINUS().isEmpty())
        {
            return "minus";
        }
        if (ctx.DIVIDE() != null && !ctx.DIVIDE().isEmpty())
        {
            return "divide";
        }
        if (ctx.LESSTHAN() != null)
        {
            return "lessThan";
        }
        if (ctx.LESSTHANEQUAL() != null)
        {
            return "lessThanEqual";
        }
        if (ctx.GREATERTHAN() != null)
        {
            return "greaterThan";
        }
        if (ctx.GREATERTHANEQUAL() != null)
        {
            return "greaterThanEqual";
        }
        throw new RuntimeException("Unknown arithmetic: " + ctx.getText());
    }

    /**
     * Build ONE FunctionExpression from an arithmetic
     * part context with the given initial value.
     * All operators use direct binary (two-param) form.
     * For >2 operands, left-fold into nested binary calls:
     * e.g. a + b + c -> plus(plus(a, b), c)
     */
    private FunctionInvocationImpl buildArithSfe(final M3Parser.ArithmeticPartContext ctx, final String funcName, final ValueSpecification initialValue, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        MutableList<ValueSpecification> allOperands = Lists.mutable.<ValueSpecification>with(initialValue)
                .withAll(ListAdapter.adapt(ctx.simpleExpression()).collect(e -> visitSimpleExpr(e, typeParamNames, multParamNames)));

        // Left-fold into nested binary calls
        ValueSpecification result = allOperands.get(0);
        for (int i = 1; i < allOperands.size(); i++)
        {
            result = new FunctionInvocationImpl()
                    ._sourceInformation(buildSourceInfo(ctx))
                    ._functionName(funcName)
                    ._parametersValues(Lists.mutable.with(result, allOperands.get(i)));
        }

        return (FunctionInvocationImpl) result;
    }

    /**
     * Visit a simpleExpression (expression without trailing
     * equalNotEqual).
     */
    private ValueSpecification visitSimpleExpr(final M3Parser.SimpleExpressionContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        ValueSpecification result = visitNonArrowOrEqual(ctx.nonArrowOrEqualExpression(), typeParamNames, multParamNames);

        return ListAdapter.adapt(ctx.propertyOrFunctionExpression())
                .injectInto(result, (acc, pofCtx) ->
                {
                    if (pofCtx.propertyExpression() != null)
                    {
                        return visitPropertyExpr(acc, pofCtx.propertyExpression(), typeParamNames, multParamNames);
                    }
                    else if (pofCtx.functionExpression() != null)
                    {
                        return visitArrowFunctionExpr(acc, pofCtx.functionExpression(), typeParamNames, multParamNames);
                    }
                    return acc;
                });
    }

    /**
     * Process a batch of arithmetic parts with precedence
     * snatching. When a higher-precedence op follows a lower
     * one, the last operand of the previous expression is
     * snatched and becomes the initial operand of the new one.
     * E.g. 2 + 2 * 4 -> plus([2, times([2, 4])])
     */
    private ValueSpecification applyArithmeticParts(final MutableList<M3Parser.ArithmeticPartContext> parts, final ValueSpecification initialValue, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        final FunctionInvocationImpl[] accum = {null};
        parts.each(ctx ->
        {
            String funcName = getArithFuncName(ctx);
            if (accum[0] == null)
            {
                accum[0] = buildArithSfe(ctx, funcName, initialValue, typeParamNames, multParamNames);
            }
            else if (isStrictlyLowerPrecedence((String) accum[0]._functionName(), funcName))
            {
                ValueSpecification lastParam = getLastParam(accum[0]);
                FunctionInvocationImpl newSfe = buildArithSfe(ctx, funcName, lastParam, typeParamNames, multParamNames);
                replaceLastParam(accum[0], newSfe);
            }
            else
            {
                accum[0] = buildArithSfe(ctx, funcName, accum[0], typeParamNames, multParamNames);
            }
        });
        return accum[0];
    }

    /**
     * Get the last parameter value from an expression.
     * All operators now use direct binary params.
     */
    private ValueSpecification getLastParam(final FunctionExpression sfe)
    {
        MutableList<ValueSpecification> params = ListAdapter.adapt(sfe._parametersValues());
        return params.getLast();
    }

    /**
     * Replace the last parameter value in an expression.
     * All operators now use direct binary params.
     */
    private void replaceLastParam(final FunctionExpression sfe, final ValueSpecification newVal)
    {
        MutableList<ValueSpecification> newParams = ListAdapter.adapt(sfe._parametersValues());
        newParams.set(newParams.size() - 1, newVal);
        sfe._parametersValues(newParams);
    }

    // ----- Boolean parts -----

    /**
     * Returns true if boolOp1 has lower precedence than
     * boolOp2. OR has lower precedence than AND.
     */
    private static boolean isLowerPrecedenceBoolean(final String boolOp1, final String boolOp2)
    {
        return "or".equals(boolOp1) && "and".equals(boolOp2);
    }

    /**
     * Returns true if the function is AND or OR (used to
     * determine if equalNotEqual should snatch).
     */
    private static boolean isAndOrOr(final String funcName)
    {
        return "and".equals(funcName) || "or".equals(funcName);
    }

    /**
     * Build a boolean SFE from a BooleanPartContext.
     */
    private FunctionInvocationImpl buildBoolSfe(final M3Parser.BooleanPartContext ctx, final String funcName, final ValueSpecification initialValue, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        return new FunctionInvocationImpl()
                ._sourceInformation(buildSourceInfo(ctx))
                ._functionName(funcName)
                ._parametersValues(Lists.mutable.with(initialValue, processCombinedArithmeticOnly(ctx.combinedArithmeticOnly(), typeParamNames, multParamNames)));
    }

    /**
     * Process a batch of boolean parts with precedence
     * snatching. AND has higher precedence than OR, so
     * when AND follows OR, the last operand of OR is
     * snatched and becomes the first operand of AND.
     * E.g. a || b && c -> or(a, and(b, c))
     */
    private ValueSpecification applyBooleanParts(final MutableList<M3Parser.BooleanPartContext> parts, final ValueSpecification initialValue, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        FunctionInvocationImpl sfe = null;
        for (M3Parser.BooleanPartContext ctx : parts)
        {
            if (ctx.AND() != null)
            {
                if (sfe == null)
                {
                    sfe = buildBoolSfe(ctx, "and", initialValue, typeParamNames, multParamNames);
                }
                else if (isLowerPrecedenceBoolean((String) sfe._functionName(), "and"))
                {
                    MutableList<ValueSpecification> params = ListAdapter.adapt(sfe._parametersValues());
                    ValueSpecification lastParam = params.get(params.size() - 1);
                    FunctionInvocationImpl newSfe = buildBoolSfe(ctx, "and", lastParam, typeParamNames, multParamNames);
                    params.set(params.size() - 1, newSfe);
                    sfe._parametersValues(params);
                }
                else
                {
                    sfe = buildBoolSfe(ctx, "and", sfe, typeParamNames, multParamNames);
                }
            }
            else if (ctx.OR() != null)
            {
                if (sfe == null)
                {
                    sfe = buildBoolSfe(ctx, "or", initialValue, typeParamNames, multParamNames);
                }
                else
                {
                    sfe = buildBoolSfe(ctx, "or", sfe, typeParamNames, multParamNames);
                }
            }
            else if (ctx.equalNotEqual() != null)
            {
                if (sfe != null && isAndOrOr((String) sfe._functionName()))
                {
                    MutableList<ValueSpecification> params = ListAdapter.adapt(sfe._parametersValues());
                    ValueSpecification lastParam = params.get(params.size() - 1);
                    ValueSpecification eqResult = applyEqualNotEqual(lastParam, ctx.equalNotEqual(), typeParamNames, multParamNames);
                    params.set(params.size() - 1, eqResult);
                    sfe._parametersValues(params);
                }
                else
                {
                    ValueSpecification eqResult = applyEqualNotEqual(
                            sfe == null ? initialValue : sfe,
                            ctx.equalNotEqual(), typeParamNames, multParamNames);
                    if (eqResult instanceof FunctionInvocationImpl sfeResult)
                    {
                        sfe = sfeResult;
                    }
                    else
                    {
                        return eqResult;
                    }
                }
            }
        }
        return sfe;
    }

    private ValueSpecification applyEqualNotEqual(final ValueSpecification left, final M3Parser.EqualNotEqualContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        // equalNotEqual: (TEST_EQUAL | TEST_NOT_EQUAL) combinedArithmeticOnly
        FunctionInvocationImpl equalSfe = new FunctionInvocationImpl()
                ._sourceInformation(buildSourceInfo(ctx))
                ._functionName("equal")
                ._parametersValues(Lists.mutable.with(
                        left,
                        processCombinedArithmeticOnly(ctx.combinedArithmeticOnly(), typeParamNames, multParamNames)));

        if (ctx.TEST_EQUAL() != null)
        {
            return equalSfe;
        }

        // != is not(equal(a, b))
        return new FunctionInvocationImpl()
                ._sourceInformation(buildSourceInfo(ctx))
                ._functionName("not")
                ._parametersValues(Lists.mutable.with(equalSfe));
    }

    private ValueSpecification processCombinedArithmeticOnly(final M3Parser.CombinedArithmeticOnlyContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        ValueSpecification result = visitExprOrGroup(ctx.expressionOrExpressionGroup(), typeParamNames, multParamNames);
        MutableList<M3Parser.ArithmeticPartContext> parts = ListAdapter.adapt(ctx.arithmeticPart());
        if (!parts.isEmpty())
        {
            result = applyArithmeticParts(parts, result, typeParamNames, multParamNames);
        }
        return result;
    }

    // ========================================================================
    // Expressions Array  (collection: [ expr, expr, ... ])
    // ========================================================================

    private CollectionImpl visitExpressionsArr(final M3Parser.ExpressionsArrayContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        MutableList<ValueSpecification> vals = ListAdapter.adapt(ctx.combinedExpression())
                .collect(exprCtx -> visitCombinedExpr(exprCtx, typeParamNames, multParamNames));

        return new CollectionImpl()
                ._sourceInformation(buildSourceInfo(ctx))
                ._values(vals)
                ._multiplicity(new ConcreteMultiplicityImpl()
                        ._lowerBound(multVal(vals.size()))
                        ._upperBound(multVal(vals.size())));
    }

    // ========================================================================
    // Function Variable Expression (parameter declaration: name : Type[mult])
    // ========================================================================

    private VariableExpressionImpl buildFunctionVariableExpression(final M3Parser.FunctionVariableExpressionContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        return new VariableExpressionImpl()
                ._name(ctx.identifier().getText())
                ._sourceInformation(buildSourceInfo(ctx))
                ._genericType(buildGenericType(ctx.type(), typeParamNames, multParamNames))
                ._multiplicity(
                        parseMultiplicity(ctx.multiplicity().getText(), multParamNames));
    }

    // ========================================================================
    // Type Parameter Helpers
    // ========================================================================

    /**
     * Collect type parameters from grammar context (with variance support).
     * Returns a list of TypeParameter objects. Used by class visitor.
     */
    private MutableList<TypeParameter> collectTypeParams(
            final M3Parser.TypeParametersWithVarianceContext ctx)
    {
        if (ctx != null)
        {
            return ListAdapter.adapt(ctx.typeParameterWithVariance())
                    .collect(tpCtx ->
                    {
                        TypeParameterImpl tp = new TypeParameterImpl()
                                ._name(tpCtx.identifier().getText());
                        if (tpCtx.MINUS() != null)
                        {
                            tp._contravariant(true);
                        }
                        return tp;
                    });
        }
        return Lists.mutable.empty();
    }

    /**
     * Collect type parameters from grammar context (no variance).
     * Returns a list of TypeParameter objects. Used by function visitors.
     */
    private MutableList<TypeParameter> collectTypeParams(final M3Parser.TypeParametersContext ctx)
    {
        if (ctx != null)
        {
            return ListAdapter.adapt(ctx.typeParameter())
                    .collect(tpCtx -> new TypeParameterImpl()
                            ._name(tpCtx.identifier().getText()));
        }
        return Lists.mutable.empty();
    }

    /**
     * Collect multiplicity parameter names from grammar context.
     * Returns an immutable set of the names.
     * Shared by class and function visitors.
     */
    private Set<String> collectMultiplicityParams(final M3Parser.MultiplictyParametersContext ctx)
    {
        if (ctx != null)
        {
            return ListAdapter.adapt(ctx.identifier())
                    .collect(RuleContext::getText)
                    .toSet().asUnmodifiable();
        }
        return java.util.Collections.emptySet();
    }

    // ========================================================================
    // GenericType builder
    // ========================================================================

    /**
     * Build a GenericType from a typeWithOperation context.
     * Handles type algebra: equal (=), add (+), sub (-), subset (⊆).
     * Grammar: typeWithOperation : type equalType? (typeAddSubOperation)* subsetType?
     */
    private GenericType buildTypeWithOperation(final M3Parser.TypeWithOperationContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        GenericType result = buildGenericType(ctx.type(), typeParamNames, multParamNames);

        // Handle equal: Z=(?:K)
        if (ctx.equalType() != null)
        {
            result = new GenericTypeOperationImpl()
                    ._type(GenericTypeOperationType.EQUAL)
                    ._left(result)
                    ._right(buildGenericType(ctx.equalType().type(), typeParamNames, multParamNames));
        }

        // Handle add/sub operations: T-Z+V
        for (M3Parser.TypeAddSubOperationContext opCtx : ctx.typeAddSubOperation())
        {
            if (opCtx.addType() != null)
            {
                result = new GenericTypeOperationImpl()
                        ._type(GenericTypeOperationType.UNION)
                        ._left(result)
                        ._right(buildGenericType(opCtx.addType().type(), typeParamNames, multParamNames));
            }
            else
            {
                result = new GenericTypeOperationImpl()
                        ._type(GenericTypeOperationType.DIFFERENCE)
                        ._left(result)
                        ._right(buildGenericType(opCtx.subType().type(), typeParamNames, multParamNames));
            }
        }

        // Handle subset: ⊆T
        if (ctx.subsetType() != null)
        {
            result = new GenericTypeOperationImpl()
                    ._type(GenericTypeOperationType.SUBSET)
                    ._left(result)
                    ._right(buildGenericType(ctx.subsetType().type(), typeParamNames, multParamNames));
        }

        return result;
    }


    private ConcreteGenericTypeImpl buildGenericType(final M3Parser.TypeContext ctx, final Set<String> typeParamNames)
    {
        return buildGenericType(ctx, typeParamNames, java.util.Collections.emptySet());
    }

    private ConcreteGenericTypeImpl buildGenericType(final M3Parser.TypeContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        ConcreteGenericTypeImpl gt = new ConcreteGenericTypeImpl();

        if (ctx.qualifiedName() != null)
        {
            String typeName = ctx.qualifiedName().getText();

            // Check if this is a reference to a declared type parameter
            if (typeParamNames.contains(typeName))
            {
                gt._typeParameter(new TypeParameterImpl()
                        ._name(typeName));
            }
            else
            {
                gt._rawType(new Type_PointerImpl()
                        ._pointerValue(typeName)
                        ._sourceInformation(buildSourceInfo(ctx.qualifiedName())));
            }

            if (ctx.typeArguments() != null)
            {
                gt._typeArguments(
                        ListAdapter.adapt(ctx.typeArguments().typeWithOperation())
                                .collect(twCtx -> buildTypeWithOperation(twCtx, typeParamNames, multParamNames)));
            }

            // Handle multiplicity arguments: Type<Arg|*>
            if (ctx.multiplicityArguments() != null)
            {
                gt._multiplicityArguments(
                        ListAdapter.adapt(ctx.multiplicityArguments().multiplicityArgument())
                                .collect(this::parseMultiplicityArgument));
            }

            // Handle type variable values: Varchar(200)
            if (ctx.typeVariableValues() != null)
            {
                gt._typeVariableValues(
                        ListAdapter.adapt(ctx.typeVariableValues().instanceLiteral())
                                .collect(this::buildInstanceLiteralValue));
            }
        }
        else if (ctx.unitName() != null)
        {
            gt._rawType(new Type_PointerImpl()
                    ._pointerValue(ctx.unitName().getText())
                    ._sourceInformation(buildSourceInfo(ctx.unitName())));
        }
        else if (ctx.CURLY_BRACKET_OPEN() != null)
        {
            // FunctionType: {ParamType1[m1], ParamType2[m2] -> ReturnType[m]}
            gt._rawType(buildFunctionType(ctx, typeParamNames, multParamNames));
        }
        else if (ctx.GROUP_OPEN() != null && !ctx.columnType().isEmpty())
        {
            // RelationType: (col:String, col2:Integer)
            gt._rawType(buildRelationType(ctx, typeParamNames, multParamNames));
        }

        return gt;
    }

    /**
     * Build a FunctionType from a type context with curly braces.
     * Grammar: { functionTypePureType? (COMMA functionTypePureType)*
     * ARROW type multiplicity }
     */
    private FunctionTypeImpl buildFunctionType(final M3Parser.TypeContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        return new FunctionTypeImpl()
                ._parameters(
                        ListAdapter.adapt(ctx.functionTypePureType())
                                .collect(paramCtx -> new VariableExpressionImpl()
                                        ._genericType(buildGenericType(paramCtx.type(), typeParamNames, multParamNames))
                                        ._multiplicity(parseMultiplicity(paramCtx.multiplicity().getText(), multParamNames))))
                ._returnType(buildGenericType(ctx.type(), typeParamNames, multParamNames))
                ._returnMultiplicity(parseMultiplicity(ctx.multiplicity().getText(), multParamNames));
    }

    /**
     * Build a RelationType from a type context with parentheses.
     * Grammar: ( columnType (COMMA columnType)* )
     * columnType: mayColumnName COLON mayColumnType multiplicity?
     */
    private RelationTypeImpl buildRelationType(final M3Parser.TypeContext ctx, final Set<String> typeParamNames, final Set<String> multParamNames)
    {
        return new RelationTypeImpl()
                ._columns(ListAdapter.adapt(ctx.columnType())
                        .collect(colCtx ->
                        {
                            ColumnImpl col = new ColumnImpl();

                            // Column name
                            if (colCtx.mayColumnName() != null && colCtx.mayColumnName().columnName() != null)
                            {
                                String colName = colCtx.mayColumnName().columnName().getText();
                                // Strip quotes if it's a STRING literal
                                if (colName.startsWith("'") && colName.endsWith("'"))
                                {
                                    colName = colName.substring(1, colName.length() - 1);
                                }
                                col._name(colName);
                            }
                            else if (colCtx.mayColumnName() != null && colCtx.mayColumnName().QUESTION() != null)
                            {
                                col._nameWildCard(true);
                            }

                            // Column type
                            if (colCtx.mayColumnType() != null && colCtx.mayColumnType().type() != null)
                            {
                                col._genericType(buildGenericType(colCtx.mayColumnType().type(), typeParamNames));
                            }

                            // Column multiplicity (optional)
                            if (colCtx.multiplicity() != null)
                            {
                                col._multiplicity(parseMultiplicity(colCtx.multiplicity().getText(), multParamNames));
                            }

                            return col._sourceInformation(buildSourceInfo(colCtx));
                        }));
    }

    /**
     * Parse a multiplicityArgument context into a Multiplicity.
     * The grammar rule is:
     * multiplicityArgument: identifier
     * | ((fromMultiplicity DOTDOT)? toMultiplicity)
     */
    private ConcreteMultiplicityImpl parseMultiplicityArgument(final M3Parser.MultiplicityArgumentContext ctx)
    {
        if (ctx.identifier() != null)
        {
            return new ConcreteMultiplicityImpl()
                    ._multiplicityParameter(ctx.identifier().getText());
        }

        ConcreteMultiplicityImpl mult = new ConcreteMultiplicityImpl();

        // Parse toMultiplicity (required): INTEGER or STAR
        if (ctx.toMultiplicity() != null)
        {
            String toText = ctx.toMultiplicity().getText();
            if (ctx.fromMultiplicity() != null)
            {
                // Range: fromMultiplicity..toMultiplicity
                mult._lowerBound(multVal(Integer.parseInt(ctx.fromMultiplicity().getText())));
                if (!"*".equals(toText))
                {
                    mult._upperBound(multVal(Integer.parseInt(toText)));
                }
            }
            else
            {
                // Just toMultiplicity
                if ("*".equals(toText))
                {
                    mult._lowerBound(multVal(0));
                }
                else
                {
                    int val = Integer.parseInt(toText);
                    mult._lowerBound(multVal(val));
                    mult._upperBound(multVal(val));
                }
            }
        }

        return mult;
    }

    /**
     * Visit an instanceLiteral context to create an AtomicValue.
     * Handles both simple tokens and signed numbers.
     */
    private AtomicValueImpl buildInstanceLiteralValue(final M3Parser.InstanceLiteralContext ctx)
    {
        // Simple token case
        if (ctx.instanceLiteralToken() != null)
        {
            return visitLiteralToken(ctx.instanceLiteralToken());
        }

        // Signed number: MINUS/PLUS INTEGER/FLOAT/DECIMAL
        boolean negative = ctx.MINUS() != null;
        Object value;
        String genericTypeName;

        if (ctx.INTEGER() != null)
        {
            long val = Long.parseLong(ctx.INTEGER().getText());
            value = negative ? -val : val;
            genericTypeName = "Integer";
        }
        else if (ctx.FLOAT() != null)
        {
            double val = Double.parseDouble(ctx.FLOAT().getText());
            value = negative ? -val : val;
            genericTypeName = "Float";
        }
        else if (ctx.DECIMAL() != null)
        {
            double val = Double.parseDouble(ctx.DECIMAL().getText());
            value = negative ? -val : val;
            genericTypeName = "Decimal";
        }
        else
        {
            throw new RuntimeException("Unsupported literal: " + ctx.getText());
        }

        return new AtomicValueImpl()
                ._sourceInformation(buildSourceInfo(ctx))
                ._value(value)
                ._genericType(buildPrimitiveGenericType(genericTypeName));
    }

    /**
     * Negate a numeric value, preserving its type.
     */
    private static Object negateNumber(final Number num)
    {
        if (num instanceof Long l)
        {
            return -l;
        }
        else if (num instanceof Double d)
        {
            return -d;
        }
        else if (num instanceof Integer i)
        {
            return -i;
        }
        throw new RuntimeException("Unsupported number type: " + num.getClass());
    }

}
