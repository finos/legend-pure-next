// REFERENCE PARSER — committed source, hand-maintained until Pure-platform codegen lands.
//
// The canonical source of truth for the M3 visitor mapping is now
// pure/specification/grammar/mapping/mappings-pure/ (Pure code, compiled to
// shared/parser-mappings.pdb, validated continuously by
// MappingsInterpreterValidatorTest against the output of this file).
//
// Until the Java / TS platforms can codegen the visitor from mappings.pure,
// keep both in lockstep when changing the grammar — the validator will surface
// any drift on the next test run. When codegen lands, this file becomes a
// generated artifact again.
//
// Truffle does NOT use this file: the ###Pure section is parsed directly by
// the Pure-side interpreter driven from parser-mappings.pdb (see TrufflePureParser).
package org.finos.legend.pure.next.parser.pureLanguage;

import meta.pure.protocol.grammar.Enum_PointerImpl;
import meta.pure.protocol.grammar.Package_PointerImpl;
import meta.pure.protocol.grammar.PackageableElement;
import meta.pure.protocol.grammar.SourceInformation;
import meta.pure.protocol.grammar.SourceInformationImpl;
import meta.pure.protocol.grammar.constraint.Constraint;
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
import meta.pure.protocol.grammar.function.property.PropertyImpl;
import meta.pure.protocol.grammar.function.property.QualifiedPropertyImpl;
import meta.pure.protocol.grammar.multiplicity.MultiplicityParameter;
import meta.pure.protocol.grammar.multiplicity.MultiplicityValueImpl;
import meta.pure.protocol.grammar.multiplicity.Multiplicity_Protocol;
import meta.pure.protocol.grammar.multiplicity.UndefinedMultiplicityImpl;
import meta.pure.protocol.grammar.multiplicity.UserDefinedAdHocMultiplicityImpl;
import meta.pure.protocol.grammar.multiplicity.UserDefinedMultiplicityParameterImpl;
import meta.pure.protocol.grammar.type.generics.TypeParameterImpl;
import meta.pure.protocol.grammar.PointerValueImpl;
import meta.pure.protocol.grammar.relation.ColumnImpl;
import meta.pure.protocol.grammar.relation.GenericTypeOperationImpl;
import meta.pure.protocol.grammar.relation.RelationTypeImpl;
import meta.pure.protocol.grammar.relationship.AssociationImpl;
import meta.pure.protocol.grammar.relationship.GeneralizationImpl;
import meta.pure.protocol.grammar.type.ClassImpl;
import meta.pure.protocol.grammar.type.EnumerationImpl;
import meta.pure.protocol.grammar.type.FunctionTypeImpl;
import meta.pure.protocol.grammar.type.PrimitiveTypeImpl;
import meta.pure.protocol.grammar.type.Type_PointerImpl;
import meta.pure.protocol.grammar.type.generics.GenericType;
import meta.pure.protocol.grammar.type.generics.TypeParameter;
import meta.pure.protocol.grammar.type.generics.UndefinedGenericTypeImpl;
import meta.pure.protocol.grammar.type.generics.UserDefinedGenericTypeImpl;
import meta.pure.protocol.grammar.valuespecification.ArrowInvocationImpl;
import meta.pure.protocol.grammar.valuespecification.AtomicValueImpl;
import meta.pure.protocol.grammar.valuespecification.CollectionImpl;
import meta.pure.protocol.grammar.valuespecification.CompilerGenericTypeAndMultiplicityHolderImpl;
import meta.pure.protocol.grammar.valuespecification.DotApplicationImpl;
import meta.pure.protocol.grammar.valuespecification.FunctionInvocationImpl;
import meta.pure.protocol.grammar.valuespecification.UserDefinedGenericTypeAndMultiplicityHolderImpl;
import meta.pure.protocol.grammar.valuespecification.ValueSpecification;
import meta.pure.protocol.grammar.valuespecification.VariableExpressionImpl;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.list.mutable.ListAdapter;
import org.finos.legend.pure.next.parser.m3.M3Lexer;
import org.finos.legend.pure.next.parser.m3.M3Parser;
import org.finos.legend.pure.next.parser.m3.M3ParserBaseVisitor;

public class PureLanguageProtocolBuilder extends M3ParserBaseVisitor<Object>
{
    protected final MutableList<PackageableElement> elements = Lists.mutable.empty();

    protected int lineOffset = 0;

    /** Parse Pure source and return the list of top-level packageable elements. */
    public java.util.List<PackageableElement> parseElements(final String source, final int lineOffsetIn)
    {
        this.lineOffset = lineOffsetIn;
        org.finos.legend.pure.next.parser.m3.M3Lexer lexer = new org.finos.legend.pure.next.parser.m3.M3Lexer(org.antlr.v4.runtime.CharStreams.fromString(source));
        org.antlr.v4.runtime.CommonTokenStream tokens = new org.antlr.v4.runtime.CommonTokenStream(lexer);
        org.finos.legend.pure.next.parser.m3.M3Parser parser = new org.finos.legend.pure.next.parser.m3.M3Parser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new org.antlr.v4.runtime.BaseErrorListener()
        {
            @Override
            public void syntaxError(org.antlr.v4.runtime.Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg, org.antlr.v4.runtime.RecognitionException e)
            {
                throw new RuntimeException("Parse error in file " + source + " at line " + (line + lineOffsetIn) + ":" + charPositionInLine + " - " + msg);
            }
        });
        visit(parser.definition());
        return elements;
    }

    /** parseElements with no line offset (lineOffset = 0). */
    public java.util.List<PackageableElement> parseElements(final String source)
    {
        return parseElements(source, 0);
    }

    /** Operator token between the i-th and (i-1)-th operand in a left-fold context. */
    protected Token operatorTokenAt(final ParserRuleContext ctx, final int operandIndex)
    {
        return ((org.antlr.v4.runtime.tree.TerminalNode) ctx.getChild(2 * operandIndex - 1)).getSymbol();
    }

    protected VariableExpressionImpl buildVariable(final M3Parser.VariableContext ctx)
    {
        return new VariableExpressionImpl()._name(ctx.identifier().getText())._p_sourceInformation(buildSourceInfo(ctx));
    }

    protected AtomicValueImpl buildInstanceLiteralToken(final M3Parser.InstanceLiteralTokenContext ctx)
    {
        if (ctx.INTEGER() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(Long.parseLong(ctx.INTEGER().getText()))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("Integer")));
        }
        if (ctx.STRING() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(ctx.STRING().getText().substring(1, ctx.STRING().getText().length() - 1))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("String")));
        }
        if (ctx.STRING_TRIPLE() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(org.finos.legend.pure.next.parser.shared.TripleStringStripper.strip(ctx.STRING_TRIPLE().getText()))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("String")));
        }
        if (ctx.FLOAT() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(Double.parseDouble(ctx.FLOAT().getText()))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("Float")));
        }
        if (ctx.DECIMAL() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(new java.math.BigDecimal(ctx.DECIMAL().getText().substring(0, ctx.DECIMAL().getText().length() - 1)))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("Decimal")));
        }
        if (ctx.BOOLEAN() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(Boolean.parseBoolean(ctx.BOOLEAN().getText()))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("Boolean")));
        }
        if (ctx.DATE() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value((ctx.DATE().getText().startsWith("%") ? ctx.DATE().getText().substring(1) : ctx.DATE().getText()))._genericType((ctx.DATE().getText().contains("T") ? new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("DateTime")) : new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("StrictDate"))));
        }
        if (ctx.STRICTTIME() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value((ctx.STRICTTIME().getText().startsWith("%") ? ctx.STRICTTIME().getText().substring(1) : ctx.STRICTTIME().getText()))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("StrictTime")));
        }
        throw new RuntimeException("Unsupported literal token" + ": " + ctx.getText());
    }

    protected ValueSpecification buildOrExpression(final M3Parser.OrExpressionContext ctx)
    {
        java.util.List<M3Parser.AndExpressionContext> operands = ctx.andExpression();
        ValueSpecification result = buildAndExpression(operands.get(0));
        for (int i = 1; i < operands.size(); i++)
        {
            Token opTok = operatorTokenAt(ctx, i);
            M3Parser.AndExpressionContext rhsCtx = operands.get(i);
            ValueSpecification rhs = buildAndExpression(rhsCtx);
            result = new FunctionInvocationImpl()._p_sourceInformation(buildOpSourceInfo(opTok, result, rhsCtx))._functionName("or")._parametersValues(Lists.mutable.with(result, rhs));
        }
        return result;
    }

    protected ValueSpecification buildAndExpression(final M3Parser.AndExpressionContext ctx)
    {
        java.util.List<M3Parser.EqualityExpressionContext> operands = ctx.equalityExpression();
        ValueSpecification result = buildEqualityExpression(operands.get(0));
        for (int i = 1; i < operands.size(); i++)
        {
            Token opTok = operatorTokenAt(ctx, i);
            M3Parser.EqualityExpressionContext rhsCtx = operands.get(i);
            ValueSpecification rhs = buildEqualityExpression(rhsCtx);
            result = new FunctionInvocationImpl()._p_sourceInformation(buildOpSourceInfo(opTok, result, rhsCtx))._functionName("and")._parametersValues(Lists.mutable.with(result, rhs));
        }
        return result;
    }

    protected ValueSpecification buildEqualityExpression(final M3Parser.EqualityExpressionContext ctx)
    {
        java.util.List<M3Parser.RelationalExpressionContext> operands = ctx.relationalExpression();
        ValueSpecification result = buildRelationalExpression(operands.get(0));
        for (int i = 1; i < operands.size(); i++)
        {
            Token opTok = operatorTokenAt(ctx, i);
            M3Parser.RelationalExpressionContext rhsCtx = operands.get(i);
            ValueSpecification rhs = buildRelationalExpression(rhsCtx);
            ValueSpecification eq = new FunctionInvocationImpl()._p_sourceInformation(buildOpSourceInfo(opTok, result, rhsCtx))._functionName("equal")._parametersValues(Lists.mutable.with(result, rhs));
            if (opTok.getType() == org.finos.legend.pure.next.parser.m3.M3Lexer.TEST_NOT_EQUAL)
            {
                result = new FunctionInvocationImpl()._p_sourceInformation(buildOpSourceInfo(opTok, result, rhsCtx))._functionName("not")._parametersValues(Lists.mutable.with(eq));
            }
            else
            {
                result = eq;
            }
        }
        return result;
    }

    protected ValueSpecification buildRelationalExpression(final M3Parser.RelationalExpressionContext ctx)
    {
        java.util.List<M3Parser.AdditiveExpressionContext> operands = ctx.additiveExpression();
        ValueSpecification result = buildAdditiveExpression(operands.get(0));
        for (int i = 1; i < operands.size(); i++)
        {
            Token opTok = operatorTokenAt(ctx, i);
            M3Parser.AdditiveExpressionContext rhsCtx = operands.get(i);
            ValueSpecification rhs = buildAdditiveExpression(rhsCtx);
            result = new FunctionInvocationImpl()._p_sourceInformation(buildOpSourceInfo(opTok, result, rhsCtx))._functionName((opTok.getType() == org.finos.legend.pure.next.parser.m3.M3Lexer.LESSTHAN ? "lessThan" : opTok.getType() == org.finos.legend.pure.next.parser.m3.M3Lexer.LESSTHANEQUAL ? "lessThanEqual" : opTok.getType() == org.finos.legend.pure.next.parser.m3.M3Lexer.GREATERTHAN ? "greaterThan" : "greaterThanEqual"))._parametersValues(Lists.mutable.with(result, rhs));
        }
        return result;
    }

    protected ValueSpecification buildAdditiveExpression(final M3Parser.AdditiveExpressionContext ctx)
    {
        java.util.List<M3Parser.MultiplicativeExpressionContext> operands = ctx.multiplicativeExpression();
        ValueSpecification result = buildMultiplicativeExpression(operands.get(0));
        for (int i = 1; i < operands.size(); i++)
        {
            Token opTok = operatorTokenAt(ctx, i);
            M3Parser.MultiplicativeExpressionContext rhsCtx = operands.get(i);
            ValueSpecification rhs = buildMultiplicativeExpression(rhsCtx);
            result = new FunctionInvocationImpl()._p_sourceInformation(buildOpSourceInfo(opTok, result, rhsCtx))._functionName((opTok.getType() == org.finos.legend.pure.next.parser.m3.M3Lexer.PLUS ? "plus" : "minus"))._parametersValues(Lists.mutable.with(result, rhs));
        }
        return result;
    }

    protected ValueSpecification buildMultiplicativeExpression(final M3Parser.MultiplicativeExpressionContext ctx)
    {
        java.util.List<M3Parser.ExpressionContext> operands = ctx.expression();
        ValueSpecification result = buildExpression(operands.get(0));
        for (int i = 1; i < operands.size(); i++)
        {
            Token opTok = operatorTokenAt(ctx, i);
            M3Parser.ExpressionContext rhsCtx = operands.get(i);
            ValueSpecification rhs = buildExpression(rhsCtx);
            result = new FunctionInvocationImpl()._p_sourceInformation(buildOpSourceInfo(opTok, result, rhsCtx))._functionName((opTok.getType() == org.finos.legend.pure.next.parser.m3.M3Lexer.STAR ? "times" : "divide"))._parametersValues(Lists.mutable.with(result, rhs));
        }
        return result;
    }

    protected ValueSpecification buildCombinedExpression(final M3Parser.CombinedExpressionContext ctx)
    {
        return buildOrExpression(ctx.orExpression());
    }

    protected Multiplicity_Protocol buildMultiplicity(final M3Parser.MultiplicityContext ctx)
    {
        return parseMultiplicityArgument(ctx.multiplicityArgument());
    }

    protected Multiplicity_Protocol parseMultiplicityArgument(final M3Parser.MultiplicityArgumentContext ctx)
    {
        if (ctx.QUESTION() != null)
        {
            return new UndefinedMultiplicityImpl();
        }
        if (ctx.identifier() != null)
        {
            return new UserDefinedMultiplicityParameterImpl()._name(ctx.identifier().getText());
        }
        String __t = ctx.toMultiplicity().getText();
        UserDefinedAdHocMultiplicityImpl __result = new UserDefinedAdHocMultiplicityImpl()._lowerBound(new MultiplicityValueImpl()._value((ctx.fromMultiplicity() != null ? Long.parseLong(ctx.fromMultiplicity().getText()) : (ctx.toMultiplicity() != null && ctx.toMultiplicity().STAR() != null ? Long.parseLong("0") : Long.parseLong(__t)))));
        if (ctx.toMultiplicity() != null && ctx.toMultiplicity().INTEGER() != null) __result._upperBound(new MultiplicityValueImpl()._value(Long.parseLong(__t)));
        return __result;
    }

    protected VariableExpressionImpl buildFunctionVariableExpression(final M3Parser.FunctionVariableExpressionContext ctx)
    {
        return new VariableExpressionImpl()._name(ctx.identifier().getText())._p_sourceInformation(buildSourceInfo(ctx))._genericType(buildGenericType(ctx.type()))._multiplicity(buildMultiplicity(ctx.multiplicity()));
    }

    protected ValueSpecification buildAtomicExpression(final M3Parser.AtomicExpressionContext ctx)
    {
        if (ctx.variable() != null)
        {
            return buildVariable(ctx.variable());
        }
        if (ctx.instanceLiteralToken() != null)
        {
            return buildInstanceLiteralToken(ctx.instanceLiteralToken());
        }
        if (ctx.anyLambda() != null)
        {
            return buildAnyLambda(ctx.anyLambda());
        }
        if (ctx.instanceReference() != null)
        {
            return buildInstanceReference(ctx.instanceReference());
        }
        if (ctx.expressionInstance() != null)
        {
            return buildExpressionInstance(ctx.expressionInstance());
        }
        if (ctx.dsl() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx.dsl()))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("String")))._value(ctx.dsl().DSL_TEXT().getText());
        }
        if (ctx.columnBuilders() != null)
        {
            return buildColumnBuilders(ctx.columnBuilders());
        }
        if (ctx.parentReference() != null)
        {
            return buildParentReference(ctx.parentReference());
        }
        if (ctx.AT() != null)
        {
            return buildAtomicTypeRef(ctx);
        }
        throw new RuntimeException("Unsupported atomicExpression" + ": " + ctx.getText());
    }

    protected ValueSpecification buildParentReference(final M3Parser.ParentReferenceContext ctx)
    {
        return ListAdapter.adapt(ctx.parentReferenceStep())
                .injectInto(new VariableExpressionImpl()._p_sourceInformation(buildSourceInfo(ctx))._name("~"), (ValueSpecification acc, M3Parser.ParentReferenceStepContext it) ->
                {
                    if (it.TILDE() != null)
                    {
                        return new DotApplicationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("~")._parametersValues(Lists.mutable.with(acc));
                    }
                    return new DotApplicationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName(it.propertyName().getText())._parametersValues(Lists.mutable.with(acc));
                });
    }

    protected UserDefinedGenericTypeAndMultiplicityHolderImpl buildAtomicTypeRef(final M3Parser.AtomicExpressionContext ctx)
    {
        return new UserDefinedGenericTypeAndMultiplicityHolderImpl()._p_sourceInformation(buildSourceInfo(ctx))._genericType((ctx.type() != null ? buildGenericType(ctx.type()) : new UndefinedGenericTypeImpl()))._multiplicity((ctx.multiplicityArgument() != null ? parseMultiplicityArgument(ctx.multiplicityArgument()) : (ctx.multiplicity() != null ? buildMultiplicity(ctx.multiplicity()) : new UndefinedMultiplicityImpl())));
    }

    protected ValueSpecification buildColumnBuilders(final M3Parser.ColumnBuildersContext ctx)
    {
        if (ctx.BRACKET_OPEN() != null && ListAdapter.adapt(ctx.oneColSpec()).anySatisfy(__c -> __c.extraFunction() != null))
        {
            return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("aggColSpecArray")._parametersValues(Lists.mutable.with(new CollectionImpl()._values(ListAdapter.adapt(ctx.oneColSpec()).collect(this::buildOneColSpec))._multiplicity(new UserDefinedAdHocMultiplicityImpl()._lowerBound(new MultiplicityValueImpl()._value((long) (ctx.oneColSpec().size())))._upperBound(new MultiplicityValueImpl()._value((long) (ctx.oneColSpec().size())))), new CompilerGenericTypeAndMultiplicityHolderImpl()));
        }
        if (ctx.BRACKET_OPEN() != null && ListAdapter.adapt(ctx.oneColSpec()).anySatisfy(__c -> __c.anyLambda() != null))
        {
            return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("funcColSpecArray")._parametersValues(Lists.mutable.with(new CollectionImpl()._values(ListAdapter.adapt(ctx.oneColSpec()).collect(this::buildOneColSpec))._multiplicity(new UserDefinedAdHocMultiplicityImpl()._lowerBound(new MultiplicityValueImpl()._value((long) (ctx.oneColSpec().size())))._upperBound(new MultiplicityValueImpl()._value((long) (ctx.oneColSpec().size())))), new CompilerGenericTypeAndMultiplicityHolderImpl()));
        }
        if (ctx.BRACKET_OPEN() != null)
        {
            return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("colSpecArray")._parametersValues(Lists.mutable.with(new CollectionImpl()._values(ListAdapter.adapt(ctx.oneColSpec()).collect(this::buildColumnNameAtomic))._multiplicity(new UserDefinedAdHocMultiplicityImpl()._lowerBound(new MultiplicityValueImpl()._value((long) (ctx.oneColSpec().size())))._upperBound(new MultiplicityValueImpl()._value((long) (ctx.oneColSpec().size())))), buildColSpecArrayHolder(ctx)));
        }
        return buildOneColSpec(ctx.oneColSpec().get(0));
    }

    protected ValueSpecification buildOneColSpec(final M3Parser.OneColSpecContext ctx)
    {
        ValueSpecification nameAtomic = new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx.columnName()))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("String")))._value(ctx.columnName().getText());
        ValueSpecification typeHolder = ((ctx.type() != null || ctx.multiplicity() != null) ? new UserDefinedGenericTypeAndMultiplicityHolderImpl()._genericType(new UserDefinedGenericTypeImpl()._type(new RelationTypeImpl()._columns(Lists.mutable.with(buildOneColSpecColumn(ctx))))) : new CompilerGenericTypeAndMultiplicityHolderImpl());
        return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName((ctx.anyLambda() != null ? (ctx.extraFunction() != null ? "aggColSpec" : "funcColSpec") : "colSpec"))._parametersValues((ctx.anyLambda() != null ? (ctx.extraFunction() != null ? Lists.mutable.with(buildAnyLambda(ctx.anyLambda()), buildAnyLambda(ctx.extraFunction().anyLambda()), nameAtomic, typeHolder) : Lists.mutable.with(buildAnyLambda(ctx.anyLambda()), nameAtomic, typeHolder)) : Lists.mutable.with(nameAtomic, typeHolder)));
    }

    protected ColumnImpl buildOneColSpecColumn(final M3Parser.OneColSpecContext ctx)
    {
        // genericType and multiplicity are [0..1] in protocol (tagged
        // optionalInProtocol in m3.ttl): leave them unset when the grammar
        // omits them so compile-pure's `isEmpty()` branches in
        // relationColumnResolver fire and substitute the right defaults.
        // Filling with UndefinedGenericType / UndefinedMultiplicity would
        // short-circuit those branches and break function-signature
        // unification (Relation<(c:String[?])> vs <(c:String[*])>).
        ColumnImpl __result = new ColumnImpl()
                ._name(ctx.columnName().getText())
                ._nameWildCard(false);
        if (ctx.type() != null) __result._genericType(buildGenericType(ctx.type()));
        if (ctx.multiplicity() != null) __result._multiplicity(buildMultiplicity(ctx.multiplicity()));
        return __result;
    }

    protected ValueSpecification buildColumnNameAtomic(final M3Parser.OneColSpecContext ctx)
    {
        return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx.columnName()))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("String")))._value(ctx.columnName().getText());
    }

    protected ValueSpecification buildColSpecArrayHolder(final M3Parser.ColumnBuildersContext ctx)
    {
        if (ListAdapter.adapt(ctx.oneColSpec()).anySatisfy(__c -> __c.type() != null || __c.multiplicity() != null))
        {
            return new UserDefinedGenericTypeAndMultiplicityHolderImpl()._genericType(new UserDefinedGenericTypeImpl()._type(new RelationTypeImpl()._columns(ListAdapter.adapt(ctx.oneColSpec()).select(__c -> __c.type() != null || __c.multiplicity() != null).collect(this::buildOneColSpecColumn))));
        }
        return new CompilerGenericTypeAndMultiplicityHolderImpl();
    }

    protected GenericType buildTypeWithOperation(final M3Parser.TypeWithOperationContext ctx)
    {
        GenericType result = buildGenericType(ctx.type());
        result = (ctx.equalType() != null ? new GenericTypeOperationImpl()._operationType(new Enum_PointerImpl()._value("meta::pure::metamodel::relation::GenericTypeOperationType")._extraPointerValues(Lists.mutable.with(new PointerValueImpl()._value("Equal"))))._left(result)._right(buildGenericType(ctx.equalType().type())) : result);
        result = ListAdapter.adapt(ctx.typeAddSubOperation()).injectInto(result, this::buildWrapAddSubOp);
        result = (ctx.subsetType() != null ? new GenericTypeOperationImpl()._operationType(new Enum_PointerImpl()._value("meta::pure::metamodel::relation::GenericTypeOperationType")._extraPointerValues(Lists.mutable.with(new PointerValueImpl()._value("Subset"))))._left(result)._right(buildGenericType(ctx.subsetType().type())) : result);
        return result;
    }

    protected GenericType buildWrapAddSubOp(final GenericType base, final M3Parser.TypeAddSubOperationContext ctx)
    {
        if (ctx.addType() != null)
        {
            return new GenericTypeOperationImpl()._operationType(new Enum_PointerImpl()._value("meta::pure::metamodel::relation::GenericTypeOperationType")._extraPointerValues(Lists.mutable.with(new PointerValueImpl()._value("Union"))))._left(base)._right(buildGenericType(ctx.addType().type()));
        }
        return new GenericTypeOperationImpl()._operationType(new Enum_PointerImpl()._value("meta::pure::metamodel::relation::GenericTypeOperationType")._extraPointerValues(Lists.mutable.with(new PointerValueImpl()._value("Difference"))))._left(base)._right(buildGenericType(ctx.subType().type()));
    }

    protected GenericType buildTypeOrUndefined(final M3Parser.TypeOrUndefinedContext ctx)
    {
        if (ctx.QUESTION() != null)
        {
            return new UndefinedGenericTypeImpl();
        }
        return buildTypeWithOperation(ctx.typeWithOperation());
    }

    protected ValueSpecification buildMilestoningVariableExpression(final M3Parser.BuildMilestoningVariableExpressionContext ctx)
    {
        if (ctx.variable() != null)
        {
            return buildVariable(ctx.variable());
        }
        throw new RuntimeException("Milestoning date expressions not yet supported" + ": " + ctx.getText());
    }

    protected ValueSpecification buildInstanceReference(final M3Parser.InstanceReferenceContext ctx)
    {
        if (ctx.allOrFunction() != null && ctx.allOrFunction().functionExpressionParameters() != null)
        {
            return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName((ctx.qualifiedName() != null ? ctx.qualifiedName().getText() : ctx.getText()))._parametersValues(ListAdapter.adapt(ctx.allOrFunction().functionExpressionParameters().combinedExpression()).collect(this::buildCombinedExpression));
        }
        if (ctx.allOrFunction() != null && ctx.allOrFunction().allFunction() != null)
        {
            return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("getAll")._parametersValues(Lists.mutable.with(new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(new Package_PointerImpl()._value((ctx.qualifiedName() != null ? ctx.qualifiedName().getText() : ctx.getText().split("\\.")[0])))));
        }
        if (ctx.allOrFunction() != null && ctx.allOrFunction().allVersionsFunction() != null)
        {
            return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("getAllVersions")._parametersValues(Lists.mutable.with(new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(new Package_PointerImpl()._value((ctx.qualifiedName() != null ? ctx.qualifiedName().getText() : ctx.getText().split("\\.")[0])))));
        }
        if (ctx.allOrFunction() != null && ctx.allOrFunction().allVersionsInRangeFunction() != null)
        {
            return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("getAllVersionsInRange")._parametersValues(Lists.mutable.<ValueSpecification>with(new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(new Package_PointerImpl()._value((ctx.qualifiedName() != null ? ctx.qualifiedName().getText() : ctx.getText().split("\\.")[0])))).withAll(ListAdapter.adapt(ctx.allOrFunction().allVersionsInRangeFunction().buildMilestoningVariableExpression()).collect(this::buildMilestoningVariableExpression)));
        }
        if (ctx.allOrFunction() != null && ctx.allOrFunction().allFunctionWithMilestoning() != null)
        {
            return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("getAll")._parametersValues(Lists.mutable.<ValueSpecification>with(new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(new Package_PointerImpl()._value((ctx.qualifiedName() != null ? ctx.qualifiedName().getText() : ctx.getText().split("\\.")[0])))).withAll(ListAdapter.adapt(ctx.allOrFunction().allFunctionWithMilestoning().buildMilestoningVariableExpression()).collect(this::buildMilestoningVariableExpression)));
        }
        return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(new Package_PointerImpl()._value(ctx.getText()));
    }

    protected ValueSpecification buildExpressionInstance(final M3Parser.ExpressionInstanceContext ctx)
    {
        if (ctx.variable() != null)
        {
            return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("copy")._parametersValues((!ctx.expressionInstanceParserPropertyAssignment().isEmpty() ? Lists.mutable.with(new VariableExpressionImpl()._name(ctx.variable().identifier().getText())._p_sourceInformation(buildSourceInfo(ctx.variable())), new CollectionImpl()._p_sourceInformation(buildSourceInfo(ctx))._values(ListAdapter.adapt(ctx.expressionInstanceParserPropertyAssignment()).collect(this::buildExpressionInstanceParserPropertyAssignment))._multiplicity(new UserDefinedAdHocMultiplicityImpl()._lowerBound(new MultiplicityValueImpl()._value((long) (ctx.expressionInstanceParserPropertyAssignment().size())))._upperBound(new MultiplicityValueImpl()._value((long) (ctx.expressionInstanceParserPropertyAssignment().size()))))) : Lists.mutable.with(new VariableExpressionImpl()._name(ctx.variable().identifier().getText())._p_sourceInformation(buildSourceInfo(ctx.variable())))));
        }
        if (ctx.qualifiedName() != null)
        {
            return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("new")._parametersValues((!ctx.expressionInstanceParserPropertyAssignment().isEmpty() ? Lists.mutable.with(buildExpressionInstanceNewHead(ctx), new CollectionImpl()._p_sourceInformation(buildSourceInfo(ctx))._values(ListAdapter.adapt(ctx.expressionInstanceParserPropertyAssignment()).collect(this::buildExpressionInstanceParserPropertyAssignment))._multiplicity(new UserDefinedAdHocMultiplicityImpl()._lowerBound(new MultiplicityValueImpl()._value((long) (ctx.expressionInstanceParserPropertyAssignment().size())))._upperBound(new MultiplicityValueImpl()._value((long) (ctx.expressionInstanceParserPropertyAssignment().size()))))) : Lists.mutable.with(buildExpressionInstanceNewHead(ctx))));
        }
        return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("copy")._parametersValues((!ctx.expressionInstanceParserPropertyAssignment().isEmpty() ? Lists.mutable.with(buildCombinedExpression(ctx.combinedExpression()), new CollectionImpl()._p_sourceInformation(buildSourceInfo(ctx))._values(ListAdapter.adapt(ctx.expressionInstanceParserPropertyAssignment()).collect(this::buildExpressionInstanceParserPropertyAssignment))._multiplicity(new UserDefinedAdHocMultiplicityImpl()._lowerBound(new MultiplicityValueImpl()._value((long) (ctx.expressionInstanceParserPropertyAssignment().size())))._upperBound(new MultiplicityValueImpl()._value((long) (ctx.expressionInstanceParserPropertyAssignment().size()))))) : Lists.mutable.with(buildCombinedExpression(ctx.combinedExpression()))));
    }

    protected UserDefinedGenericTypeAndMultiplicityHolderImpl buildExpressionInstanceNewHead(final M3Parser.ExpressionInstanceContext ctx)
    {
        return new UserDefinedGenericTypeAndMultiplicityHolderImpl()._p_sourceInformation(buildSourceInfo(ctx))._genericType(buildExpressionInstanceGenericType(ctx))._multiplicity(new UserDefinedAdHocMultiplicityImpl()._lowerBound(new MultiplicityValueImpl()._value((long) (1)))._upperBound(new MultiplicityValueImpl()._value((long) (1))));
    }

    protected UserDefinedGenericTypeImpl buildExpressionInstanceGenericType(final M3Parser.ExpressionInstanceContext ctx)
    {
        UserDefinedGenericTypeImpl __result = new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value((ctx.qualifiedName() != null ? ctx.qualifiedName().getText() : "Unknown")));
        if (ctx.typeArguments() != null) __result._typeArguments(ListAdapter.adapt(ctx.typeArguments().typeOrUndefined()).collect(this::buildTypeOrUndefined));
        if (ctx.multiplicityArguments() != null) __result._multiplicityArguments(ListAdapter.adapt(ctx.multiplicityArguments().multiplicityArgument()).collect(this::parseMultiplicityArgument));
        if (ctx.typeVariableValues() != null) __result._typeVariableValues(ListAdapter.adapt(ctx.typeVariableValues().instanceLiteral()).collect(this::buildInstanceLiteral));
        return __result;
    }

    protected FunctionInvocationImpl buildExpressionInstanceParserPropertyAssignment(final M3Parser.ExpressionInstanceParserPropertyAssignmentContext ctx)
    {
        return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("keyExpression")._parametersValues((ctx.PLUS() != null ? Lists.mutable.with(new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("String")))._value(ctx.propertyName().getText()), buildExpressionInstanceRightSide(ctx.expressionInstanceRightSide()), new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("Boolean")))._value(true)) : Lists.mutable.with(new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("String")))._value(ctx.propertyName().getText()), buildExpressionInstanceRightSide(ctx.expressionInstanceRightSide()))));
    }

    protected ValueSpecification buildExpressionInstanceRightSide(final M3Parser.ExpressionInstanceRightSideContext ctx)
    {
        if (ctx.expressionInstanceAtomicRightSide() != null && ctx.expressionInstanceAtomicRightSide().combinedExpression() != null)
        {
            return buildCombinedExpression(ctx.expressionInstanceAtomicRightSide().combinedExpression());
        }
        if (ctx.expressionInstanceAtomicRightSide() != null && ctx.expressionInstanceAtomicRightSide().expressionInstance() != null)
        {
            return buildExpressionInstance(ctx.expressionInstanceAtomicRightSide().expressionInstance());
        }
        if (ctx.expressionInstanceAtomicRightSide() != null && ctx.expressionInstanceAtomicRightSide().qualifiedName() != null)
        {
            return new VariableExpressionImpl()._p_sourceInformation(buildSourceInfo(ctx.expressionInstanceAtomicRightSide()))._name(ctx.expressionInstanceAtomicRightSide().qualifiedName().getText());
        }
        throw new RuntimeException("Unsupported expressionInstanceRightSide" + ": " + ctx.getText());
    }

    protected ValueSpecification buildFunctionExpression(final ValueSpecification receiver, final M3Parser.FunctionExpressionContext ctx)
    {
        return ListAdapter.adapt(ctx.arrowStep())
                .injectInto(receiver, (ValueSpecification acc, M3Parser.ArrowStepContext it) ->
                {
                    return new ArrowInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName(it.qualifiedName().getText())._parametersValues(Lists.mutable.<ValueSpecification>with(acc).withAll(ListAdapter.adapt(it.functionExpressionParameters().combinedExpression()).collect(this::buildCombinedExpression)));
                });
    }

    protected ValueSpecification buildPropertyExpression(final ValueSpecification receiver, final M3Parser.PropertyExpressionContext ctx)
    {
        return new DotApplicationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName(ctx.propertyName().getText())._parametersValues((ctx.functionExpressionParameters() != null ? Lists.mutable.<ValueSpecification>with(receiver).withAll(ListAdapter.adapt(ctx.functionExpressionParameters().combinedExpression()).collect(this::buildCombinedExpression)) : Lists.mutable.with(receiver)));
    }

    protected VariableExpressionImpl buildLambdaParam(final M3Parser.LambdaParamContext ctx)
    {
        VariableExpressionImpl __result = new VariableExpressionImpl()._name(ctx.identifier().getText());
        if (ctx.lambdaParamType() != null) __result._p_sourceInformation(buildSourceInfo(ctx));
        if (ctx.lambdaParamType() != null) __result._genericType(buildGenericType(ctx.lambdaParamType().type()));
        if (ctx.lambdaParamType() != null) __result._multiplicity(buildMultiplicity(ctx.lambdaParamType().multiplicity()));
        return __result;
    }

    protected LambdaFunctionImpl buildLambdaFunction(final M3Parser.LambdaFunctionContext ctx)
    {
        return new LambdaFunctionImpl()._p_sourceInformation(buildSourceInfo(ctx))._parameters(ListAdapter.adapt(ctx.lambdaParam()).collect(this::buildLambdaParam))._expressionSequence(buildCodeBlock(ctx.lambdaPipe().codeBlock()));
    }

    protected ValueSpecification buildAnyLambda(final M3Parser.AnyLambdaContext ctx)
    {
        if (ctx.lambdaFunction() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx.lambdaFunction()))._value(buildLambdaFunction(ctx.lambdaFunction()));
        }
        if (ctx.lambdaPipe() != null && ctx.lambdaParam() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(new LambdaFunctionImpl()._p_sourceInformation(buildSourceInfo(ctx))._parameters(Lists.mutable.with(buildLambdaParam(ctx.lambdaParam())))._expressionSequence(buildCodeBlock(ctx.lambdaPipe().codeBlock())));
        }
        if (ctx.lambdaPipe() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(new LambdaFunctionImpl()._p_sourceInformation(buildSourceInfo(ctx))._parameters(Lists.mutable.empty())._expressionSequence(buildCodeBlock(ctx.lambdaPipe().codeBlock())));
        }
        throw new RuntimeException("Unsupported anyLambda" + ": " + ctx.getText());
    }

    protected ValueSpecification buildNotExpression(final M3Parser.NotExpressionContext ctx)
    {
        return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("not")._parametersValues(Lists.mutable.with(buildSimpleExpression(ctx.simpleExpression())));
    }

    protected ValueSpecification buildNonArrowOrEqualExpression(final M3Parser.NonArrowOrEqualExpressionContext ctx)
    {
        if (ctx.atomicExpression() != null)
        {
            return buildAtomicExpression(ctx.atomicExpression());
        }
        if (ctx.expressionsArray() != null)
        {
            return buildExpressionsArray(ctx.expressionsArray());
        }
        if (ctx.notExpression() != null)
        {
            return buildNotExpression(ctx.notExpression());
        }
        if (ctx.signedExpression() != null)
        {
            return buildSignedExpression(ctx.signedExpression());
        }
        if (ctx.sliceExpression() != null)
        {
            return buildSliceExpression(ctx.sliceExpression());
        }
        if (ctx.combinedExpression() != null)
        {
            return buildCombinedExpression(ctx.combinedExpression());
        }
        throw new RuntimeException("Unexpected nonArrowOrEqualExpression" + ": " + ctx.getText());
    }

    protected ValueSpecification buildSignedExpression(final M3Parser.SignedExpressionContext ctx)
    {
        if (ctx.MINUS() != null)
        {
            return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("minus")._parametersValues(Lists.mutable.with(buildSimpleExpression(ctx.simpleExpression())));
        }
        return buildSimpleExpression(ctx.simpleExpression());
    }

    protected ValueSpecification buildSliceExpression(final M3Parser.SliceExpressionContext ctx)
    {
        return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("slice")._parametersValues(ListAdapter.adapt(ctx.expression()).collect(this::buildExpression));
    }

    protected ValueSpecification buildLetExpression(final M3Parser.LetExpressionContext ctx)
    {
        return new FunctionInvocationImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionName("letFunction")._parametersValues(Lists.mutable.with(new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx.identifier()))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("String")))._value(ctx.identifier().getText()), buildCombinedExpression(ctx.combinedExpression())));
    }

    protected PrimitiveTypeImpl buildPrimitiveDefinition(final M3Parser.PrimitiveDefinitionContext ctx)
    {
        PrimitiveTypeImpl __result = new PrimitiveTypeImpl()._p_sourceInformation(buildSourceInfo(ctx))._name((ctx.qualifiedName().getText().contains("::") ? ctx.qualifiedName().getText().substring(ctx.qualifiedName().getText().lastIndexOf("::") + 2) : ctx.qualifiedName().getText()));
        if (ctx.qualifiedName().getText().contains("::")) __result._package(new Package_PointerImpl()._value(ctx.qualifiedName().getText().substring(0, ctx.qualifiedName().getText().lastIndexOf("::"))));
        if (ctx.typeVariableParameters() != null) __result._typeVariables(ListAdapter.adapt(ctx.typeVariableParameters().functionVariableExpression()).collect(this::buildFunctionVariableExpression));
        if (ctx.type() != null) __result._generalizations(Lists.mutable.with(buildClassGeneralization(ctx.type())));
        if (ctx.constraints() != null) __result._constraints(ListAdapter.adapt(ctx.constraints().constraint()).collect(this::buildConstraint));
        if (ctx.stereotypes() != null) __result._stereotypes(ListAdapter.adapt(ctx.stereotypes().stereotype()).collect(this::buildStereotype));
        if (ctx.taggedValues() != null) __result._taggedValues(ListAdapter.adapt(ctx.taggedValues().taggedValue()).collect(this::buildTaggedValue));
        return __result;
    }

    @Override
    public Object visitPrimitiveDefinition(final M3Parser.PrimitiveDefinitionContext ctx)
    {
        PrimitiveTypeImpl __built = buildPrimitiveDefinition(ctx);
        elements.add(__built);
        return __built;
    }

    protected EnumerationImpl buildEnumDefinition(final M3Parser.EnumDefinitionContext ctx)
    {
        String enumPath = ctx.qualifiedName().getText();
        EnumerationImpl __result = new EnumerationImpl()._p_sourceInformation(buildSourceInfo(ctx))._name((enumPath.contains("::") ? enumPath.substring(enumPath.lastIndexOf("::") + 2) : enumPath))._properties(ListAdapter.adapt(ctx.enumValue()).collect(v -> buildEnumValue(v, enumPath)));
        if (enumPath.contains("::")) __result._package(new Package_PointerImpl()._value(enumPath.substring(0, enumPath.lastIndexOf("::"))));
        if (ctx.stereotypes() != null) __result._stereotypes(ListAdapter.adapt(ctx.stereotypes().stereotype()).collect(this::buildStereotype));
        if (ctx.taggedValues() != null) __result._taggedValues(ListAdapter.adapt(ctx.taggedValues().taggedValue()).collect(this::buildTaggedValue));
        return __result;
    }

    @Override
    public Object visitEnumDefinition(final M3Parser.EnumDefinitionContext ctx)
    {
        EnumerationImpl __built = buildEnumDefinition(ctx);
        elements.add(__built);
        return __built;
    }

    // Each enum value becomes a Property on the Enumeration. The protocol declares
    // genericType / multiplicity / aggregation as [1]; fill all three with their
    // meaningful enum-value defaults (the enclosing Enumeration type, cardinality 1,
    // aggregation None). Kept in sync with mapping_enumeration.pure::buildEnumValue
    // so the MappingsInterpreterValidator can compare every slot strictly.
    protected PropertyImpl buildEnumValue(final M3Parser.EnumValueContext ctx, final String enumPath)
    {
        PropertyImpl __result = new PropertyImpl()
                ._name(ctx.identifier().getText())
                ._p_sourceInformation(buildSourceInfo(ctx))
                ._aggregation(new Enum_PointerImpl()
                        ._value("meta::pure::metamodel::function::property::AggregationKind")
                        ._extraPointerValues(Lists.mutable.with(new PointerValueImpl()._value("None"))))
                ._genericType(new UserDefinedGenericTypeImpl()
                        ._type(new Type_PointerImpl()._value(enumPath)))
                ._multiplicity(new UserDefinedAdHocMultiplicityImpl()
                        ._lowerBound(new MultiplicityValueImpl()._value(1L))
                        ._upperBound(new MultiplicityValueImpl()._value(1L)));
        if (ctx.stereotypes() != null) __result._stereotypes(ListAdapter.adapt(ctx.stereotypes().stereotype()).collect(this::buildStereotype));
        if (ctx.taggedValues() != null) __result._taggedValues(ListAdapter.adapt(ctx.taggedValues().taggedValue()).collect(this::buildTaggedValue));
        return __result;
    }

    protected TypeParameterImpl buildTypeParameter(final M3Parser.TypeParameterContext ctx)
    {
        return new TypeParameterImpl()._name(ctx.identifier().getText());
    }

    protected TypeParameterImpl buildTypeParameterWithVariance(final M3Parser.TypeParameterWithVarianceContext ctx)
    {
        TypeParameterImpl __result = new TypeParameterImpl()._name(ctx.identifier().getText());
        if (ctx.MINUS() != null) __result._contravariant(true);
        return __result;
    }

    protected UserDefinedMultiplicityParameterImpl buildMultParamDef(final M3Parser.IdentifierContext ctx)
    {
        return new UserDefinedMultiplicityParameterImpl()._name(ctx.getText());
    }

    protected ClassImpl buildClassDefinition(final M3Parser.ClassDefinitionContext ctx)
    {
        ClassImpl __result = new ClassImpl()._p_sourceInformation(buildSourceInfo(ctx))._name((ctx.qualifiedName().getText().contains("::") ? ctx.qualifiedName().getText().substring(ctx.qualifiedName().getText().lastIndexOf("::") + 2) : ctx.qualifiedName().getText()));
        if (ctx.qualifiedName().getText().contains("::")) __result._package(new Package_PointerImpl()._value(ctx.qualifiedName().getText().substring(0, ctx.qualifiedName().getText().lastIndexOf("::"))));
        if (ctx.typeParametersWithVarianceAndMultiplicityParameters() != null && ctx.typeParametersWithVarianceAndMultiplicityParameters().typeParametersWithVariance() != null) __result._typeParameters(ListAdapter.adapt(ctx.typeParametersWithVarianceAndMultiplicityParameters().typeParametersWithVariance().typeParameterWithVariance()).collect(this::buildTypeParameterWithVariance));
        if (ctx.typeParametersWithVarianceAndMultiplicityParameters() != null && ctx.typeParametersWithVarianceAndMultiplicityParameters().multiplictyParameters() != null) __result._multiplicityParameters(ListAdapter.adapt(ctx.typeParametersWithVarianceAndMultiplicityParameters().multiplictyParameters().identifier()).collect(this::buildMultParamDef));
        if (ctx.typeVariableParameters() != null) __result._typeVariables(ListAdapter.adapt(ctx.typeVariableParameters().functionVariableExpression()).collect(this::buildFunctionVariableExpression));
        if (!ctx.type().isEmpty()) __result._generalizations(ListAdapter.adapt(ctx.type()).collect(this::buildClassGeneralization));
        if (ctx.constraints() != null) __result._constraints(ListAdapter.adapt(ctx.constraints().constraint()).collect(this::buildConstraint));
        if (ctx.classBody() != null && ctx.classBody().properties() != null) __result._properties(ListAdapter.adapt(ctx.classBody().properties().property()).collect(this::buildProperty));
        if (ctx.classBody() != null && ctx.classBody().properties() != null) __result._qualifiedProperties(ListAdapter.adapt(ctx.classBody().properties().qualifiedProperty()).collect(this::buildQualifiedProperty));
        if (ctx.stereotypes() != null) __result._stereotypes(ListAdapter.adapt(ctx.stereotypes().stereotype()).collect(this::buildStereotype));
        if (ctx.taggedValues() != null) __result._taggedValues(ListAdapter.adapt(ctx.taggedValues().taggedValue()).collect(this::buildTaggedValue));
        return __result;
    }

    @Override
    public Object visitClassDefinition(final M3Parser.ClassDefinitionContext ctx)
    {
        ClassImpl __built = buildClassDefinition(ctx);
        elements.add(__built);
        return __built;
    }

    protected GeneralizationImpl buildClassGeneralization(final M3Parser.TypeContext ctx)
    {
        return new GeneralizationImpl()._general(buildGenericType(ctx))._p_sourceInformation(buildSourceInfo(ctx));
    }

    protected UserDefinedFunctionImpl buildFunctionDefinition(final M3Parser.FunctionDefinitionContext ctx)
    {
        UserDefinedFunctionImpl __result = new UserDefinedFunctionImpl()._p_sourceInformation(buildSourceInfo(ctx))._name(functionId(ctx))._functionName((ctx.qualifiedName().getText().contains("::") ? ctx.qualifiedName().getText().substring(ctx.qualifiedName().getText().lastIndexOf("::") + 2) : ctx.qualifiedName().getText()))._parameters(ListAdapter.adapt(ctx.functionTypeSignature().functionVariableExpression()).collect(this::buildFunctionVariableExpression))._returnGenericType(buildGenericType(ctx.functionTypeSignature().type()))._returnMultiplicity(buildMultiplicity(ctx.functionTypeSignature().multiplicity()))._expressionSequence(buildCodeBlock(ctx.codeBlock()));
        if (ctx.qualifiedName().getText().contains("::")) __result._package(new Package_PointerImpl()._value(ctx.qualifiedName().getText().substring(0, ctx.qualifiedName().getText().lastIndexOf("::"))));
        if (ctx.typeAndMultiplicityParameters() != null && ctx.typeAndMultiplicityParameters().typeParameters() != null) __result._typeParameters(ListAdapter.adapt(ctx.typeAndMultiplicityParameters().typeParameters().typeParameter()).collect(this::buildTypeParameter));
        if (ctx.typeAndMultiplicityParameters() != null && ctx.typeAndMultiplicityParameters().multiplictyParameters() != null) __result._multiplicityParameters(ListAdapter.adapt(ctx.typeAndMultiplicityParameters().multiplictyParameters().identifier()).collect(this::buildMultParamDef));
        if (ctx.constraints() != null) __result._preConstraints(ListAdapter.adapt(ctx.constraints().constraint()).reject(__c -> __c.getText().contains("$return")).collect(this::buildConstraint));
        if (ctx.constraints() != null) __result._postConstraints(ListAdapter.adapt(ctx.constraints().constraint()).select(__c -> __c.getText().contains("$return")).collect(this::buildConstraint));
        if (ctx.stereotypes() != null) __result._stereotypes(ListAdapter.adapt(ctx.stereotypes().stereotype()).collect(this::buildStereotype));
        if (ctx.taggedValues() != null) __result._taggedValues(ListAdapter.adapt(ctx.taggedValues().taggedValue()).collect(this::buildTaggedValue));
        return __result;
    }

    @Override
    public Object visitFunctionDefinition(final M3Parser.FunctionDefinitionContext ctx)
    {
        UserDefinedFunctionImpl __built = buildFunctionDefinition(ctx);
        elements.add(__built);
        return __built;
    }

    protected NativeFunctionImpl buildNativeFunction(final M3Parser.NativeFunctionContext ctx)
    {
        NativeFunctionImpl __result = new NativeFunctionImpl()._p_sourceInformation(buildSourceInfo(ctx))._name(nativeFunctionId(ctx))._functionName((ctx.qualifiedName().getText().contains("::") ? ctx.qualifiedName().getText().substring(ctx.qualifiedName().getText().lastIndexOf("::") + 2) : ctx.qualifiedName().getText()))._parameters(ListAdapter.adapt(ctx.functionTypeSignature().functionVariableExpression()).collect(this::buildFunctionVariableExpression))._returnGenericType(buildGenericType(ctx.functionTypeSignature().type()))._returnMultiplicity(buildMultiplicity(ctx.functionTypeSignature().multiplicity()));
        if (ctx.qualifiedName().getText().contains("::")) __result._package(new Package_PointerImpl()._value(ctx.qualifiedName().getText().substring(0, ctx.qualifiedName().getText().lastIndexOf("::"))));
        if (ctx.typeAndMultiplicityParameters() != null && ctx.typeAndMultiplicityParameters().typeParameters() != null) __result._typeParameters(ListAdapter.adapt(ctx.typeAndMultiplicityParameters().typeParameters().typeParameter()).collect(this::buildTypeParameter));
        if (ctx.typeAndMultiplicityParameters() != null && ctx.typeAndMultiplicityParameters().multiplictyParameters() != null) __result._multiplicityParameters(ListAdapter.adapt(ctx.typeAndMultiplicityParameters().multiplictyParameters().identifier()).collect(this::buildMultParamDef));
        if (ctx.stereotypes() != null) __result._stereotypes(ListAdapter.adapt(ctx.stereotypes().stereotype()).collect(this::buildStereotype));
        if (ctx.taggedValues() != null) __result._taggedValues(ListAdapter.adapt(ctx.taggedValues().taggedValue()).collect(this::buildTaggedValue));
        return __result;
    }

    @Override
    public Object visitNativeFunction(final M3Parser.NativeFunctionContext ctx)
    {
        NativeFunctionImpl __built = buildNativeFunction(ctx);
        elements.add(__built);
        return __built;
    }

    protected String functionId(final M3Parser.FunctionDefinitionContext ctx)
    {
        return (ctx.qualifiedName().getText().contains("::") ? ctx.qualifiedName().getText().substring(ctx.qualifiedName().getText().lastIndexOf("::") + 2) : ctx.qualifiedName().getText()) + buildParamListSig(ctx.functionTypeSignature()) + "__" + buildTypeSig(ctx.functionTypeSignature().type()) + "_" + buildMultSig(ctx.functionTypeSignature().multiplicity()) + "_";
    }

    protected String nativeFunctionId(final M3Parser.NativeFunctionContext ctx)
    {
        return (ctx.qualifiedName().getText().contains("::") ? ctx.qualifiedName().getText().substring(ctx.qualifiedName().getText().lastIndexOf("::") + 2) : ctx.qualifiedName().getText()) + buildParamListSig(ctx.functionTypeSignature()) + "__" + buildTypeSig(ctx.functionTypeSignature().type()) + "_" + buildMultSig(ctx.functionTypeSignature().multiplicity()) + "_";
    }

    protected String buildParamListSig(final M3Parser.FunctionTypeSignatureContext ctx)
    {
        if (!ctx.functionVariableExpression().isEmpty())
        {
            return "_" + ListAdapter.adapt(ctx.functionVariableExpression()).collect(this::buildParamSig).makeString("__");
        }
        return "";
    }

    protected String buildParamSig(final M3Parser.FunctionVariableExpressionContext ctx)
    {
        return buildTypeSig(ctx.type()) + "_" + buildMultSig(ctx.multiplicity());
    }

    protected String buildTypeSig(final M3Parser.TypeContext ctx)
    {
        if (ctx.qualifiedName() != null)
        {
            return (ctx.qualifiedName().getText().contains("::") ? ctx.qualifiedName().getText().substring(ctx.qualifiedName().getText().lastIndexOf("::") + 2) : ctx.qualifiedName().getText());
        }
        return "UNKNOWN";
    }

    protected String buildMultSig(final M3Parser.MultiplicityContext ctx)
    {
        if (ctx.multiplicityArgument() != null && ctx.multiplicityArgument().QUESTION() != null)
        {
            return "UNDEFINED";
        }
        if (ctx.multiplicityArgument() != null && ctx.multiplicityArgument().identifier() != null)
        {
            return ctx.multiplicityArgument().identifier().getText();
        }
        if (ctx.multiplicityArgument() != null && ctx.multiplicityArgument().fromMultiplicity() != null && ctx.multiplicityArgument() != null && ctx.multiplicityArgument().toMultiplicity() != null && ctx.multiplicityArgument().toMultiplicity().STAR() != null)
        {
            return "$" + ctx.multiplicityArgument().fromMultiplicity().getText() + "_MANY$";
        }
        if (ctx.multiplicityArgument() != null && ctx.multiplicityArgument().fromMultiplicity() != null)
        {
            return "$" + ctx.multiplicityArgument().fromMultiplicity().getText() + "_" + ctx.multiplicityArgument().toMultiplicity().getText() + "$";
        }
        if (ctx.multiplicityArgument() != null && ctx.multiplicityArgument().toMultiplicity() != null && ctx.multiplicityArgument().toMultiplicity().STAR() != null)
        {
            return "MANY";
        }
        return ctx.multiplicityArgument().toMultiplicity().getText();
    }

    protected AssociationImpl buildAssociation(final M3Parser.AssociationContext ctx)
    {
        AssociationImpl __result = new AssociationImpl()._p_sourceInformation(buildSourceInfo(ctx))._name((ctx.qualifiedName().getText().contains("::") ? ctx.qualifiedName().getText().substring(ctx.qualifiedName().getText().lastIndexOf("::") + 2) : ctx.qualifiedName().getText()));
        if (ctx.qualifiedName().getText().contains("::")) __result._package(new Package_PointerImpl()._value(ctx.qualifiedName().getText().substring(0, ctx.qualifiedName().getText().lastIndexOf("::"))));
        if (ctx.associationBody() != null && ctx.associationBody().properties() != null) __result._properties(ListAdapter.adapt(ctx.associationBody().properties().property()).collect(this::buildProperty));
        if (ctx.associationBody() != null && ctx.associationBody().properties() != null) __result._qualifiedProperties(ListAdapter.adapt(ctx.associationBody().properties().qualifiedProperty()).collect(this::buildQualifiedProperty));
        if (ctx.stereotypes() != null) __result._stereotypes(ListAdapter.adapt(ctx.stereotypes().stereotype()).collect(this::buildStereotype));
        if (ctx.taggedValues() != null) __result._taggedValues(ListAdapter.adapt(ctx.taggedValues().taggedValue()).collect(this::buildTaggedValue));
        return __result;
    }

    @Override
    public Object visitAssociation(final M3Parser.AssociationContext ctx)
    {
        AssociationImpl __built = buildAssociation(ctx);
        elements.add(__built);
        return __built;
    }

    protected ProfileImpl buildProfile(final M3Parser.ProfileContext ctx)
    {
        ProfileImpl __result = new ProfileImpl()._p_sourceInformation(buildSourceInfo(ctx))._name((ctx.qualifiedName().getText().contains("::") ? ctx.qualifiedName().getText().substring(ctx.qualifiedName().getText().lastIndexOf("::") + 2) : ctx.qualifiedName().getText()));
        if (ctx.qualifiedName().getText().contains("::")) __result._package(new Package_PointerImpl()._value(ctx.qualifiedName().getText().substring(0, ctx.qualifiedName().getText().lastIndexOf("::"))));
        if (ctx.stereotypeDefinitions() != null) __result._p_stereotypes(ListAdapter.adapt(ctx.stereotypeDefinitions().identifier()).collect(this::buildProfileStereotypeDef));
        if (ctx.tagDefinitions() != null) __result._p_tags(ListAdapter.adapt(ctx.tagDefinitions().identifier()).collect(this::buildProfileTagDef));
        return __result;
    }

    @Override
    public Object visitProfile(final M3Parser.ProfileContext ctx)
    {
        ProfileImpl __built = buildProfile(ctx);
        elements.add(__built);
        return __built;
    }

    protected StereotypeImpl buildProfileStereotypeDef(final M3Parser.IdentifierContext ctx)
    {
        return new StereotypeImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(ctx.getText());
    }

    protected TagImpl buildProfileTagDef(final M3Parser.IdentifierContext ctx)
    {
        return new TagImpl()._p_sourceInformation(buildSourceInfo(ctx))._value(ctx.getText());
    }

    protected Stereotype_PointerImpl buildStereotype(final M3Parser.StereotypeContext ctx)
    {
        return new Stereotype_PointerImpl()._p_sourceInformation(buildSourceInfo(ctx.qualifiedName()))._value(ctx.qualifiedName().getText())._extraPointerValues(Lists.mutable.with(new PointerValueImpl()._p_sourceInformation(buildSourceInfo(ctx.identifier()))._value(ctx.identifier().getText())));
    }

    protected TaggedValueImpl buildTaggedValue(final M3Parser.TaggedValueContext ctx)
    {
        return new TaggedValueImpl()._tag(new Tag_PointerImpl()._p_sourceInformation(buildSourceInfo(ctx.qualifiedName()))._value(ctx.qualifiedName().getText())._extraPointerValues(Lists.mutable.with(new PointerValueImpl()._p_sourceInformation(buildSourceInfo(ctx.identifier()))._value(ctx.identifier().getText()))))._value(ListAdapter.adapt(ctx.STRING()).collect(__n -> { String __raw = __n.getText(); return __raw.substring(1, __raw.length() - 1); }).makeString(""));
    }

    protected ValueSpecification buildSimpleExpression(final M3Parser.SimpleExpressionContext ctx)
    {
        return ListAdapter.adapt(ctx.propertyOrFunctionExpression())
                .injectInto(buildNonArrowOrEqualExpression(ctx.nonArrowOrEqualExpression()), (ValueSpecification acc, M3Parser.PropertyOrFunctionExpressionContext it) ->
                {
                    if (it.propertyExpression() != null)
                    {
                        return buildPropertyExpression(acc, it.propertyExpression());
                    }
                    if (it.functionExpression() != null)
                    {
                        return buildFunctionExpression(acc, it.functionExpression());
                    }
                    return acc;
                });
    }

    protected UserDefinedGenericTypeImpl buildGenericType(final M3Parser.TypeContext ctx)
    {
        if (ctx.qualifiedName() != null)
        {
            UserDefinedGenericTypeImpl __result = new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._p_sourceInformation(buildSourceInfo(ctx.qualifiedName()))._value(ctx.qualifiedName().getText()));
            if (ctx.typeArguments() != null) __result._typeArguments(ListAdapter.adapt(ctx.typeArguments().typeOrUndefined()).collect(this::buildTypeOrUndefined));
            if (ctx.multiplicityArguments() != null) __result._multiplicityArguments(ListAdapter.adapt(ctx.multiplicityArguments().multiplicityArgument()).collect(this::parseMultiplicityArgument));
            if (ctx.typeVariableValues() != null) __result._typeVariableValues(ListAdapter.adapt(ctx.typeVariableValues().instanceLiteral()).collect(this::buildInstanceLiteral));
            return __result;
        }
        if (ctx.CURLY_BRACKET_OPEN() != null)
        {
            return new UserDefinedGenericTypeImpl()._type(new FunctionTypeImpl()._parameters(ListAdapter.adapt(ctx.functionTypePureType()).collect(this::buildFunctionTypePureType))._returnType(buildGenericType(ctx.type()))._returnMultiplicity(buildMultiplicity(ctx.multiplicity())));
        }
        if (ctx.GROUP_OPEN() != null)
        {
            return new UserDefinedGenericTypeImpl()._type(new RelationTypeImpl()._columns(ListAdapter.adapt(ctx.columnType()).collect(this::buildColumnType)));
        }
        throw new RuntimeException("No matching alternative for type: " + ctx.getText());
    }

    protected ColumnImpl buildColumnType(final M3Parser.ColumnTypeContext ctx)
    {
        // Same optionalInProtocol semantics as buildOneColSpecColumn: leave
        // genericType / multiplicity unset when grammar omits them so
        // compile-pure's isEmpty() branches in relationColumnResolver fire.
        String name = "";
        if (ctx.mayColumnName() != null && ctx.mayColumnName().columnName() != null)
        {
            String raw = ctx.mayColumnName().columnName().getText();
            name = raw.startsWith("'") ? raw.substring(1, raw.length() - 1) : raw;
        }
        ColumnImpl __result = new ColumnImpl()
                ._p_sourceInformation(buildSourceInfo(ctx))
                ._name(name)
                ._nameWildCard(ctx.mayColumnName() != null && ctx.mayColumnName().QUESTION() != null);
        if (ctx.mayColumnType() != null && ctx.mayColumnType().type() != null) __result._genericType(buildGenericType(ctx.mayColumnType().type()));
        if (ctx.multiplicity() != null) __result._multiplicity(buildMultiplicity(ctx.multiplicity()));
        return __result;
    }

    protected VariableExpressionImpl buildFunctionTypePureType(final M3Parser.FunctionTypePureTypeContext ctx)
    {
        // VariableExpression.name is [1] in protocol; function-type parameters are
        // unnamed in grammar (`{T[m]}` not `{n: T[m]}`), so fill with "". Mirrors
        // mapping_type.pure::buildFunctionTypePureType.
        return new VariableExpressionImpl()._name("")._genericType(buildGenericType(ctx.type()))._multiplicity(buildMultiplicity(ctx.multiplicity()));
    }

    protected ConstraintImpl buildConstraint(final M3Parser.ConstraintContext ctx)
    {
        if (ctx.simpleConstraint() != null)
        {
            ConstraintImpl __result = new ConstraintImpl()._p_sourceInformation(buildSourceInfo(ctx))._functionDefinition(new LambdaFunctionImpl()._p_sourceInformation(buildSourceInfo(ctx.simpleConstraint()))._expressionSequence(Lists.mutable.with(buildCombinedExpression(ctx.simpleConstraint().combinedExpression()))));
            if (ctx.simpleConstraint() != null && ctx.simpleConstraint().constraintId() != null) __result._name(ctx.simpleConstraint().constraintId().VALID_STRING().getText());
            return __result;
        }
        if (ctx.complexConstraint() != null)
        {
            ConstraintImpl __result = new ConstraintImpl()._p_sourceInformation(buildSourceInfo(ctx))._name(ctx.complexConstraint().VALID_STRING().getText())._functionDefinition(new LambdaFunctionImpl()._p_sourceInformation(buildSourceInfo(ctx.complexConstraint().constraintFunction()))._expressionSequence(Lists.mutable.with(buildCombinedExpression(ctx.complexConstraint().constraintFunction().combinedExpression()))));
            if (ctx.complexConstraint() != null && ctx.complexConstraint().constraintOwner() != null) __result._owner(ctx.complexConstraint().constraintOwner().VALID_STRING().getText());
            if (ctx.complexConstraint() != null && ctx.complexConstraint().constraintExternalId() != null) __result._externalId(ctx.complexConstraint().constraintExternalId().STRING().getText().substring(1, ctx.complexConstraint().constraintExternalId().STRING().getText().length() - 1));
            if (ctx.complexConstraint() != null && ctx.complexConstraint().constraintEnforcementLevel() != null) __result._enforcementLevel(ctx.complexConstraint().constraintEnforcementLevel().ENFORCEMENT_LEVEL().getText());
            if (ctx.complexConstraint() != null && ctx.complexConstraint().constraintMessage() != null) __result._messageFunction(new LambdaFunctionImpl()._p_sourceInformation(buildSourceInfo(ctx.complexConstraint().constraintMessage()))._expressionSequence(Lists.mutable.with(buildCombinedExpression(ctx.complexConstraint().constraintMessage().combinedExpression()))));
            return __result;
        }
        throw new RuntimeException("No matching alternative for constraint: " + ctx.getText());
    }

    protected PropertyImpl buildProperty(final M3Parser.PropertyContext ctx)
    {
        PropertyImpl __result = new PropertyImpl()
                ._name(ctx.propertyName().getText())
                ._p_sourceInformation(buildSourceInfo(ctx))
                ._genericType(buildGenericType(ctx.propertyReturnType().type()))
                ._multiplicity(buildMultiplicity(ctx.propertyReturnType().multiplicity()))
                ._aggregation(buildAggregation(ctx.aggregation()));
        if (ctx.defaultValue() != null) __result._defaultValue(new LambdaFunctionImpl()._p_sourceInformation(buildSourceInfo(ctx.defaultValue()))._expressionSequence(Lists.mutable.with(buildCombinedExpression(ctx.defaultValue().combinedExpression()))));
        if (ctx.stereotypes() != null) __result._stereotypes(ListAdapter.adapt(ctx.stereotypes().stereotype()).collect(this::buildStereotype));
        if (ctx.taggedValues() != null) __result._taggedValues(ListAdapter.adapt(ctx.taggedValues().taggedValue()).collect(this::buildTaggedValue));
        return __result;
    }

    // Always emit aggregation as a fully-shaped Enum_Pointer (with extraPointerValues
    // populated); defaults to 'None' when the grammar's `aggregation?` is absent.
    // Mirrors mapping_property.pure::buildAggregation so the validator can compare
    // the slot strictly. Compile-pure already handles every shape via the
    // None/Composite/Shared branch in compileAggregationKind.
    protected static Enum_PointerImpl buildAggregation(final M3Parser.AggregationContext ctx)
    {
        String kind;
        if (ctx == null)
        {
            kind = "None";
        }
        else
        {
            String raw = ctx.AGGREGATION_TYPE().getText();
            if ("composite".equals(raw)) kind = "Composite";
            else if ("shared".equals(raw)) kind = "Shared";
            else kind = "None";
        }
        return new Enum_PointerImpl()
                ._value("meta::pure::metamodel::function::property::AggregationKind")
                ._extraPointerValues(Lists.mutable.with(new PointerValueImpl()._value(kind)));
    }

    protected QualifiedPropertyImpl buildQualifiedProperty(final M3Parser.QualifiedPropertyContext ctx)
    {
        QualifiedPropertyImpl __result = new QualifiedPropertyImpl()._name(ctx.identifier().getText())._p_sourceInformation(buildSourceInfo(ctx))._parameters(ListAdapter.adapt(ctx.qualifiedPropertyBody().functionVariableExpression()).collect(this::buildFunctionVariableExpression))._expressionSequence(buildCodeBlock(ctx.qualifiedPropertyBody().codeBlock()))._genericType(buildGenericType(ctx.propertyReturnType().type()))._multiplicity(buildMultiplicity(ctx.propertyReturnType().multiplicity()));
        if (ctx.stereotypes() != null) __result._stereotypes(ListAdapter.adapt(ctx.stereotypes().stereotype()).collect(this::buildStereotype));
        if (ctx.taggedValues() != null) __result._taggedValues(ListAdapter.adapt(ctx.taggedValues().taggedValue()).collect(this::buildTaggedValue));
        return __result;
    }

    protected CollectionImpl buildExpressionsArray(final M3Parser.ExpressionsArrayContext ctx)
    {
        return new CollectionImpl()._p_sourceInformation(buildSourceInfo(ctx))._values(ListAdapter.adapt(ctx.combinedExpression()).collect(this::buildCombinedExpression))._multiplicity(new UserDefinedAdHocMultiplicityImpl()._lowerBound(new MultiplicityValueImpl()._value((long) (ctx.combinedExpression().size())))._upperBound(new MultiplicityValueImpl()._value((long) (ctx.combinedExpression().size()))));
    }

    protected MutableList<ValueSpecification> buildCodeBlock(final M3Parser.CodeBlockContext ctx)
    {
        return ListAdapter.adapt(ctx.programLine())
                .collectIf(
                        (M3Parser.ProgramLineContext it) -> it.combinedExpression() != null || it.letExpression() != null,
                        (M3Parser.ProgramLineContext it) ->
                        {
                            if (it.combinedExpression() != null)
                            {
                                return (ValueSpecification) buildCombinedExpression(it.combinedExpression());
                            }
                            if (it.letExpression() != null)
                            {
                                return (ValueSpecification) buildLetExpression(it.letExpression());
                            }
                            throw new RuntimeException("unreachable: predicate guarantees an alt matches");
                        });
    }

    protected ValueSpecification buildExpression(final M3Parser.ExpressionContext ctx)
    {
        return ListAdapter.adapt(ctx.propertyOrFunctionExpression())
                .injectInto(buildNonArrowOrEqualExpression(ctx.nonArrowOrEqualExpression()), (ValueSpecification acc, M3Parser.PropertyOrFunctionExpressionContext it) ->
                {
                    if (it.propertyExpression() != null)
                    {
                        return buildPropertyExpression(acc, it.propertyExpression());
                    }
                    if (it.functionExpression() != null)
                    {
                        return buildFunctionExpression(acc, it.functionExpression());
                    }
                    return acc;
                });
    }

    protected AtomicValueImpl buildInstanceLiteral(final M3Parser.InstanceLiteralContext ctx)
    {
        if (ctx.instanceLiteralToken() != null)
        {
            return buildInstanceLiteralToken(ctx.instanceLiteralToken());
        }
        if (ctx.INTEGER() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value((ctx.MINUS() != null ? -Long.parseLong(ctx.INTEGER().getText()) : Long.parseLong(ctx.INTEGER().getText())))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("Integer")));
        }
        if (ctx.FLOAT() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value((ctx.MINUS() != null ? -Double.parseDouble(ctx.FLOAT().getText()) : Double.parseDouble(ctx.FLOAT().getText())))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("Float")));
        }
        if (ctx.DECIMAL() != null)
        {
            return new AtomicValueImpl()._p_sourceInformation(buildSourceInfo(ctx))._value((ctx.MINUS() != null ? new java.math.BigDecimal("-" + ctx.DECIMAL().getText().substring(0, ctx.DECIMAL().getText().length() - 1)) : new java.math.BigDecimal(ctx.DECIMAL().getText().substring(0, ctx.DECIMAL().getText().length() - 1))))._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("Decimal")));
        }
        throw new RuntimeException("Unsupported literal" + ": " + ctx.getText());
    }

    protected SourceInformationImpl buildSourceInfo(final ParserRuleContext ctx)
    {
        return new SourceInformationImpl()._startLine((long) (ctx.getStart().getLine()) + lineOffset)._startColumn((long) (ctx.getStart().getCharPositionInLine()) + 1)._endLine((long) (ctx.getStop().getLine()) + lineOffset)._endColumn((long) (ctx.getStop().getCharPositionInLine() + ctx.getStop().getText().length()));
    }

    protected SourceInformationImpl buildOpSourceInfo(final Token opTok, final ValueSpecification left, final ParserRuleContext ctx)
    {
        SourceInformation leftSrc = left._p_sourceInformation();
        return new SourceInformationImpl()._startLine((leftSrc != null && leftSrc._startLine() != null ? leftSrc._startLine() : (long) (opTok.getLine()) + lineOffset))._startColumn((leftSrc != null && leftSrc._startColumn() != null ? leftSrc._startColumn() : (long) (opTok.getCharPositionInLine()) + 1))._endLine((long) (ctx.getStop().getLine()) + lineOffset)._endColumn((long) (ctx.getStop().getCharPositionInLine() + ctx.getStop().getText().length()));
    }

}
