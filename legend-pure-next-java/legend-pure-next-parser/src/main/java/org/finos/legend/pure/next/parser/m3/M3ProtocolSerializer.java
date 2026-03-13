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

import meta.pure.protocol.grammar.Package_Pointer;
import meta.pure.protocol.grammar.constraint.Constraint;
import meta.pure.protocol.grammar.extension.Profile;
import meta.pure.protocol.grammar.extension.Stereotype_Pointer;
import meta.pure.protocol.grammar.extension.TaggedValue;
import meta.pure.protocol.grammar.function.FunctionDefinition_Protocol;
import meta.pure.protocol.grammar.function.LambdaFunction;
import meta.pure.protocol.grammar.function.NativeFunction;
import meta.pure.protocol.grammar.function.PackageableFunction;
import meta.pure.protocol.grammar.function.UserDefinedFunction;
import meta.pure.protocol.grammar.function.property.AggregationKind;
import meta.pure.protocol.grammar.function.property.Property;
import meta.pure.protocol.grammar.function.property.QualifiedProperty;
import meta.pure.protocol.grammar.multiplicity.Multiplicity;
import meta.pure.protocol.grammar.relation.Column;
import meta.pure.protocol.grammar.relation.GenericTypeOperation;
import meta.pure.protocol.grammar.relation.RelationType;
import meta.pure.protocol.grammar.relationship.Association;
import meta.pure.protocol.grammar.relationship.Generalization;
import meta.pure.protocol.grammar.type.Enumeration;
import meta.pure.protocol.grammar.type.FunctionType;
import meta.pure.protocol.grammar.type.Measure;
import meta.pure.protocol.grammar.type.PrimitiveType;
import meta.pure.protocol.grammar.type.Type_Pointer;
import meta.pure.protocol.grammar.type.Type_Protocol;
import meta.pure.protocol.grammar.type.Unit;
import meta.pure.protocol.grammar.type.generics.GenericType;
import meta.pure.protocol.grammar.type.generics.TypeParameter;
import meta.pure.protocol.grammar.valuespecification.ArrowInvocation;
import meta.pure.protocol.grammar.valuespecification.AtomicValue;
import meta.pure.protocol.grammar.valuespecification.Collection;
import meta.pure.protocol.grammar.valuespecification.DotApplication;
import meta.pure.protocol.grammar.valuespecification.FunctionExpression;
import meta.pure.protocol.grammar.valuespecification.GenericTypeAndMultiplicityHolder;
import meta.pure.protocol.grammar.valuespecification.ValueSpecification;
import meta.pure.protocol.grammar.valuespecification.VariableExpression;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.utility.ListIterate;

import java.util.function.BiConsumer;

/**
 * Serializes protocol model objects back to Pure code.
 */
public class M3ProtocolSerializer
{
    /**
     * Controls how parentheses are emitted around operator expressions.
     */
    public enum ParenthesisMode
    {
        /** Only add parens where needed to preserve semantics. */
        MINIMAL,
        /** Always wrap to make all precedence explicit. */
        EXPLICIT
    }

    // Precedence levels (higher = tighter binding)
    private static final int PREC_OR = 1;
    private static final int PREC_AND = 2;
    private static final int PREC_EQUAL = 3;       // ==, !=
    private static final int PREC_COMPARE = 4;      // >, <, >=, <=
    private static final int PREC_ADDITIVE = 5;     // +, -
    private static final int PREC_MULTIPLICATIVE = 6; // *, /
    private static final int PREC_UNARY = 7;        // !, -(expr)
    private static final int PREC_ARROW = 8;        // ->, .

    private final ParenthesisMode parenMode;

    public M3ProtocolSerializer()
    {
        this(ParenthesisMode.MINIMAL);
    }

    public M3ProtocolSerializer(final ParenthesisMode mode)
    {
        this.parenMode = mode;
    }

    /**
     * Return the precedence level for an operator function name.
     * Returns -1 for non-operator functions.
     */
    private static int precedenceOf(final String funcName)
    {
        return switch (funcName)
        {
            case "or" -> PREC_OR;
            case "and" -> PREC_AND;
            case "equal" -> PREC_EQUAL;
            case "lessThan", "greaterThan", "lessThanEqual", "greaterThanEqual" -> PREC_COMPARE;
            case "plus", "minus" -> PREC_ADDITIVE;
            case "times", "divide" -> PREC_MULTIPLICATIVE;
            case "not" -> PREC_UNARY;
            default -> -1;
        };
    }

    /**
     * Serialize a class definition to Pure code.
     *
     * @param classDef class definition
     * @return Pure code string
     */
    public String serializeClass(final meta.pure.protocol.grammar.type.Class classDef)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("Class ");

        // Stereotypes
        serializeStereotypes(sb, classDef._stereotypes());

        // Tagged values
        serializeTaggedValues(sb, classDef._taggedValues());

        // Full qualified name
        sb.append(getFullQualifiedName(classDef));

        // Type parameters (e.g., <T, -U> or <T|m>)
        serializeTypeParametersWithContravariance(sb, classDef._typeParameters(), classDef);

        // Type variable parameters (e.g., (x:Integer[1]))
        if (classDef._typeVariables() != null && !classDef._typeVariables().isEmpty())
        {
            sb.append("(");
            appendJoining(sb, classDef._typeVariables(), ", ", (b, param) ->
            {
                b.append(param._name()).append(": ");
                serializeGenericType(b, param._genericType());
                b.append(serializeMultiplicity(param._multiplicity()));
            });
            sb.append(")");
        }

        // Generalizations (extends)
        if (classDef._generalizations() != null && !classDef._generalizations().isEmpty())
        {
            sb.append(" extends ");
            appendJoining(sb, classDef._generalizations(), ", ", (b, gen) ->
            {
                if (gen._general() != null && gen._general()._rawType() != null)
                {
                    b.append(getPointerValueFromProtocol(gen._general()._rawType()));
                }
            });
        }

        // Constraints
        serializeConstraints(sb, classDef._constraints());

        sb.append("\n{\n");

        // Properties
        for (Property prop : classDef._properties())
        {
            serializeProperty(sb, prop);
        }

        // Qualified properties
        for (QualifiedProperty qp : classDef._qualifiedProperties())
        {
            serializeQualifiedProperty(sb, qp);
        }

        sb.append("}");

        return sb.toString();
    }

    /**
     * Serialize constraints block: [ c1, c2, ... ]
     */
    private void serializeConstraints(final StringBuilder sb, final MutableList<Constraint> constraints)
    {
        if (constraints == null || constraints.isEmpty())
        {
            return;
        }
        sb.append("\n[\n");
        appendJoining(sb, constraints, ",\n", (b, c) -> serializeConstraint(b, c));
        sb.append("\n]");
    }

    private void serializeConstraint(final StringBuilder sb, final Constraint c)
    {
        boolean isComplex = c._owner() != null
                || c._externalId() != null
                || c._enforcementLevel() != null
                || c._messageFunction() != null;

        if (isComplex)
        {
            // Complex: name ( ~owner: x ~externalId: 'y'
            //   ~function: expr ~enforcementLevel: z
            //   ~message: expr )
            sb.append(c._name());
            sb.append("\n(\n");

            if (c._owner() != null)
            {
                sb.append("~owner: ");
                sb.append(c._owner());
                sb.append("\n");
            }
            if (c._externalId() != null)
            {
                sb.append("~externalId: '");
                sb.append(c._externalId());
                sb.append("'\n");
            }
            sb.append("~function: ");
            serializeFunctionBody(sb, c._functionDefinition());
            sb.append("\n");
            if (c._enforcementLevel() != null)
            {
                sb.append("~enforcementLevel: ");
                sb.append(c._enforcementLevel());
                sb.append("\n");
            }
            if (c._messageFunction() != null)
            {
                sb.append("~message: ");
                serializeFunctionBody(sb, c._messageFunction());
                sb.append("\n");
            }
            sb.append(")");
        }
        else
        {
            // Simple: optionalName: expression
            if (c._name() != null)
            {
                sb.append(c._name()).append(": ");
            }
            serializeFunctionBody(sb, c._functionDefinition());
        }
    }

    /**
     * Serialize the body of a FunctionDefinition (the expression
     * sequence without braces).
     */
    private void serializeFunctionBody(final StringBuilder sb,
            final FunctionDefinition_Protocol fdp)
    {
        if (fdp instanceof meta.pure.protocol.grammar.function.FunctionDefinition fd
                && fd._expressionSequence() != null)
        {
            for (ValueSpecification vs : fd._expressionSequence())
            {
                serializeExpression(sb, vs);
            }
        }
    }

    /**
     * Serialize a function definition to Pure code.
     *
     * @param func function definition
     * @return Pure code string
     */
    public String serializeFunction(final UserDefinedFunction func)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("function ");

        // Stereotypes
        serializeStereotypes(sb, func._stereotypes());

        // Tagged values
        serializeTaggedValues(sb, func._taggedValues());

        // Package and base function name
        if (func._package() != null && func._package()._pointerValue() != null)
        {
            String pkg = String.valueOf(func._package()._pointerValue());
            if (!pkg.isEmpty())
            {
                sb.append(pkg);
                sb.append("::");
            }
        }

        // Decode function name: e.g., "f_Any_MANY_" -> base name "f"
        String encodedName = String.valueOf(func._name());
        String baseName = decodeFunctionBaseName(encodedName);
        sb.append(baseName);

        // Type parameters (e.g., <T,Z,K,V> or <T|m>)
        serializeTypeParameters(sb, func._typeParameters(), func);

        // Parameters
        sb.append("(");
        if (func._parameters() != null)
        {
            appendJoining(sb, func._parameters(), ", ", (b, param) ->
            {
                b.append(param._name()).append(": ");
                serializeGenericType(b, param._genericType());
                b.append(serializeMultiplicity(param._multiplicity()));
            });
        }
        sb.append("): ");

        // Return type
        serializeGenericType(sb, func._returnGenericType());
        sb.append(serializeMultiplicity(func._returnMultiplicity()));

        // Pre-constraints (before body)
        MutableList<Constraint> allConstraints = Lists.mutable.empty();
        if (func._preConstraints() != null)
        {
            allConstraints.addAll(func._preConstraints());
        }
        if (func._postConstraints() != null)
        {
            allConstraints.addAll(func._postConstraints());
        }
        serializeConstraints(sb, allConstraints);

        sb.append("\n{\n");

        // Expression sequence (body)
        if (func._expressionSequence() != null)
        {
            MutableList<ValueSpecification> exprs = func._expressionSequence();
            boolean singleExpr = exprs.size() == 1;
            for (int i = 0; i < exprs.size(); i++)
            {
                sb.append("  ");
                serializeExpression(sb, exprs.get(i));
                if (!singleExpr)
                {
                    sb.append(";");
                }
                sb.append("\n");
            }
        }

        sb.append("}");

        return sb.toString();
    }

    /**
     * Serialize a native function declaration to Pure code.
     *
     * @param func native function
     * @return Pure code string
     */
    public String serializeNativeFunction(final NativeFunction func)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("native function ");

        // Stereotypes
        serializeStereotypes(sb, func._stereotypes());

        // Tagged values
        serializeTaggedValues(sb, func._taggedValues());

        // Package and base function name
        if (func._package() != null && func._package()._pointerValue() != null)
        {
            String pkg = String.valueOf(func._package()._pointerValue());
            if (!pkg.isEmpty())
            {
                sb.append(pkg);
                sb.append("::");
            }
        }

        // Decode function name
        String encodedName = String.valueOf(func._name());
        String baseName = decodeFunctionBaseName(encodedName);
        sb.append(baseName);

        // Type parameters (e.g., <T,Z,K,V> or <T|m>)
        serializeTypeParameters(sb, func._typeParameters(), func);

        // Parameters
        sb.append("(");
        if (func._parameters() != null)
        {
            appendJoining(sb, func._parameters(), ", ", (b, param) ->
            {
                b.append(param._name()).append(": ");
                serializeGenericType(b, param._genericType());
                b.append(serializeMultiplicity(param._multiplicity()));
            });
        }
        sb.append("): ");

        // Return type
        serializeGenericType(sb, func._returnGenericType());
        sb.append(serializeMultiplicity(func._returnMultiplicity()));

        sb.append(";");

        return sb.toString();
    }

    /**
     * Decode a function name from its encoded Pure form.
     * E.g., "f_Any_MANY_" -> "f", "myFunc_String_1__Integer_1_" -> "myFunc"
     */
    private String decodeFunctionBaseName(final String encodedName)
    {
        // The encoded name has a trailing pattern of _Type_Mult_ segments.
        // The base name is everything before the first _Type_Mult_ pattern.
        // Simple approach: find function name before _<TypeName>_<Mult>_ suffix
        int idx = encodedName.indexOf('_');
        if (idx > 0)
        {
            return encodedName.substring(0, idx);
        }
        return encodedName;
    }

    /**
     * Serialize a value specification expression to Pure code.
     */
    private void serializeExpression(final StringBuilder sb,
                                      final ValueSpecification expr)
    {
        switch (expr)
        {
            case GenericTypeAndMultiplicityHolder gth -> serializeGenericTypeAndMultiplicityHolder(sb, gth);
            case Collection col -> serializeCollection(sb, col);
            case AtomicValue av -> serializeAtomicValue(sb, av);
            case DotApplication dot -> serializeDotApplication(sb, dot);
            case FunctionExpression sfe -> serializeFunctionExpression(sb, sfe);
            case VariableExpression ve -> sb.append("$").append(ve._name());
            default -> sb.append("/* unsupported expression */");
        }
    }

    /**
     * Serialize a LambdaFunction to Pure code.
     * Forms:
     * - Single param without type: x|body
     * - Single param with type:    x: Type[mult]|body
     * - Multi param:               {a: T1[m1], b: T2[m2]|body}
     */
    private void serializeLambda(final StringBuilder sb,
                                  final LambdaFunction lambda)
    {
        MutableList<VariableExpression> params = lambda._parameters();
        MutableList<ValueSpecification> body = lambda._expressionSequence();

        boolean multiParam = params != null && params.size() > 1;
        boolean hasTypeAnnotation = hasLambdaTypeAnnotation(params);

        // Multi-param or typed single-param uses curly braces
        if (multiParam)
        {
            sb.append("{");
        }

        if (params != null)
        {
            appendJoining(sb, params, ", ", (b, param) ->
            {
                b.append(param._name());
                if (hasTypeAnnotation)
                {
                    b.append(": ");
                    serializeGenericType(b, param._genericType());
                    b.append(serializeMultiplicity(param._multiplicity()));
                }
            });
        }

        sb.append("|");

        // Serialize body expressions
        if (body != null && !body.isEmpty())
        {
            appendJoining(sb, body, "; ", (b, expr) -> serializeExpression(b, expr));
        }

        if (multiParam)
        {
            sb.append("}");
        }
    }

    /**
     * Check if lambda parameters have explicit type annotations.
     */
    private boolean hasLambdaTypeAnnotation(final MutableList<VariableExpression> params)
    {
        return params != null && ListIterate.anySatisfy(params,
                param -> param._genericType() != null && param._genericType()._rawType() != null);
    }

    /**
     * Serialize a GenericType to Pure code, including type arguments.
     * E.g., "Function", {@code "Function<Any>"}, {@code "Property<String, Integer>"}
     */
    private void serializeGenericType(final StringBuilder sb,
                                       final GenericType gt)
    {
        if (gt == null)
        {
            sb.append("Any");
            return;
        }

        // Handle GenericTypeOperation (type algebra: T-Z+V, Z=(?:K)⊆T)
        if (gt instanceof GenericTypeOperation gto)
        {
            serializeGenericTypeOperation(sb, gto);
            return;
        }

        // Handle type parameter references (e.g., T, Z, K, V)
        if (gt._typeParameter() != null && gt._typeParameter()._name() != null)
        {
            sb.append(gt._typeParameter()._name().toString());
            return;
        }

        if (gt._rawType() == null)
        {
            sb.append("Any");
            return;
        }

        Type_Protocol rawType = gt._rawType();

        if (rawType instanceof FunctionType ft)
        {
            serializeFunctionType(sb, ft);
        }
        else if (rawType instanceof RelationType rt)
        {
            serializeRelationType(sb, rt);
        }
        else
        {
            sb.append(getPointerValueFromProtocol(rawType));
        }

        // Add type arguments if present
        MutableList<GenericType> typeArgs = gt._typeArguments();
        boolean hasTypeArgs = typeArgs != null && !typeArgs.isEmpty();
        MutableList<Multiplicity> multArgs = gt._multiplicityArguments();
        boolean hasMultArgs = multArgs != null && !multArgs.isEmpty();

        if (hasTypeArgs || hasMultArgs)
        {
            sb.append("<");
            if (hasTypeArgs)
            {
                appendJoining(sb, typeArgs, ", ", (b, arg) -> serializeGenericType(b, arg));
            }
            // Multiplicity arguments follow last type arg with | separator
            // e.g. Property<Nil, String|*>
            if (hasMultArgs)
            {
                for (Multiplicity mult : multArgs)
                {
                    sb.append("|");
                    sb.append(serializeMultiplicityParts(mult));
                }
            }
            sb.append(">");
        }

        // Type variable values (e.g., Varchar(200))
        MutableList<ValueSpecification> tvv = gt._typeVariableValues();
        if (tvv != null && !tvv.isEmpty())
        {
            sb.append("(");
            appendJoining(sb, tvv, ", ", (b, vs) -> serializeExpression(b, vs));
            sb.append(")");
        }
    }

    /**
     * Serialize a column spec from a colSpec SFE.
     * Format: colName (or colName : Type or colName : Type[mult])
     */
    private void serializeColSpec(final StringBuilder sb,
            final ValueSpecification colParam)
    {
        if (colParam instanceof FunctionExpression colSfe)
        {
            MutableList<ValueSpecification> colParams = colSfe._parametersValues();
            if (colParams != null && !colParams.isEmpty())
            {
                // Column name
                sb.append(extractStringValue(colParams.get(0)));
                // Optional type (GenericTypeAndMultiplicityHolder with RelationType wrapping)
                for (int i = 1; i < colParams.size(); i++)
                {
                    ValueSpecification cp = colParams.get(i);
                    if (cp instanceof GenericTypeAndMultiplicityHolder holder)
                    {
                        GenericType gt = holder._genericType();
                        if (gt != null && gt._rawType() instanceof meta.pure.protocol.grammar.relation.RelationType rt
                                && rt._columns() != null && rt._columns().notEmpty())
                        {
                            // Extract type and multiplicity from the RelationType's column
                            meta.pure.protocol.grammar.relation.Column col = rt._columns().getFirst();
                            if (col._genericType() != null)
                            {
                                sb.append(" : ");
                                serializeGenericType(sb, col._genericType());
                            }
                            if (col._multiplicity() != null)
                            {
                                sb.append(serializeMultiplicity(col._multiplicity()));
                            }
                        }
                        else if (gt != null)
                        {
                            sb.append(" : ");
                            serializeGenericType(sb, gt);
                            if (holder._multiplicity() != null)
                            {
                                sb.append(serializeMultiplicity(holder._multiplicity()));
                            }
                        }
                    }
                }
            }
        }
        else
        {
            // Fallback: simple string value
            sb.append(extractStringValue(colParam));
        }
    }

    /**
     * Serialize just the type arguments part of a GenericType
     * (the &lt;args|multArgs&gt; portion, without the base type name).
     */
    private void serializeGenericTypeArgs(final StringBuilder sb,
            final GenericType gt)
    {
        MutableList<GenericType> typeArgs = gt._typeArguments();
        boolean hasTypeArgs = typeArgs != null && !typeArgs.isEmpty();
        MutableList<Multiplicity> multArgs = gt._multiplicityArguments();
        boolean hasMultArgs = multArgs != null && !multArgs.isEmpty();
        if (hasTypeArgs || hasMultArgs)
        {
            sb.append("<");
            if (hasTypeArgs)
            {
                appendJoining(sb, typeArgs, ", ", (b, arg) -> serializeGenericType(b, arg));
            }
            if (hasMultArgs)
            {
                for (Multiplicity mult : multArgs)
                {
                    sb.append("|");
                    sb.append(serializeMultiplicityParts(mult));
                }
            }
            sb.append(">");
        }
    }


    /**
     * Render multiplicity parts for use inside type arguments.
     * E.g., "*", "1", "0..1"
     */
    private String serializeMultiplicityParts(final Multiplicity mult)
    {
        if (mult == null)
        {
            return "*";
        }
        int lower = 0;
        int upper = -1;
        if (mult._lowerBound() != null && mult._lowerBound()._value() != null)
        {
            lower = ((Number) mult._lowerBound()._value()).intValue();
        }
        if (mult._upperBound() != null && mult._upperBound()._value() != null)
        {
            upper = ((Number) mult._upperBound()._value()).intValue();
        }
        if (upper == -1)
        {
            return lower == 0 ? "*" : lower + "..*";
        }
        if (lower == upper)
        {
            return String.valueOf(lower);
        }
        return lower + ".." + upper;
    }


    private void serializeGenericTypeAndMultiplicityHolder(final StringBuilder sb,
                                             final GenericTypeAndMultiplicityHolder gth)
    {
        sb.append("@");
        if (gth._genericType() != null)
        {
            serializeGenericType(sb, gth._genericType());
        }
        else if (gth._multiplicity() != null)
        {
            sb.append(serializeMultiplicity(gth._multiplicity()));
        }
    }

    private void serializeCollection(final StringBuilder sb,
                                       final Collection col)
    {
        MutableList<ValueSpecification> values = col._values();
        if (values == null || values.isEmpty())
        {
            sb.append("[]");
            return;
        }
        sb.append("[");
        appendJoining(sb, values, ", ", (b, v) -> serializeCollectionElement(b, v));
        sb.append("]");
    }

    private void serializeAtomicValue(final StringBuilder sb,
                                       final AtomicValue av)
    {
        Object value = av._value();
        if (value == null)
        {
            sb.append("[]");
            return;
        }
        String typeName = getTypeName(av);
        serializeTypedValue(sb, value, typeName);
    }

    private String getTypeName(final ValueSpecification vs)
    {
        if (vs._genericType() != null && vs._genericType()._rawType() != null)
        {
            return getPointerValueFromProtocol(vs._genericType()._rawType());
        }
        return null;
    }

    /**
     * Extract pointer value from a Type_Protocol.
     * Returns the pointerValue if it's a Type_Pointer,
     * or the class name for structural types.
     */
    private String getPointerValueFromProtocol(
            final Type_Protocol rawType)
    {
        return switch (rawType)
        {
            case Type_Pointer tp -> String.valueOf(tp._pointerValue());
            case FunctionType ft -> "FunctionType";
            case RelationType rt -> "RelationType";
            default -> "Unknown";
        };
    }

    /**
     * Serialize a FunctionType to Pure code.
     * E.g., {String[1],Integer[1]->Boolean[1]}
     */
    private void serializeFunctionType(final StringBuilder sb,
                                        final FunctionType ft)
    {
        sb.append("{");
        MutableList<VariableExpression> params = ft._parameters();
        if (params != null)
        {
            for (int i = 0; i < params.size(); i++)
            {
                if (i > 0)
                {
                    sb.append(",");
                }
                VariableExpression param = params.get(i);
                serializeGenericType(sb, param._genericType());
                sb.append(serializeMultiplicity(param._multiplicity()));
            }
        }
        sb.append("->");
        serializeGenericType(sb, ft._returnType());
        sb.append(serializeMultiplicity(ft._returnMultiplicity()));
        sb.append("}");
    }

    /**
     * Serialize a RelationType to Pure code.
     * E.g., (name:String[1],age:Integer[0..1],ok:Boolean)
     */
    private void serializeRelationType(final StringBuilder sb, final RelationType rt)
    {
        sb.append("(");
        MutableList<Column> columns = rt._columns();
        for (int i = 0; i < columns.size(); i++)
        {
            if (i > 0)
            {
                sb.append(",");
            }
            Column col = columns.get(i);
            if (col._nameWildCard() != null && Boolean.TRUE.equals(col._nameWildCard()))
            {
                sb.append("?");
            }
            else
            {
                String colName = String.valueOf(col._name());
                if (colName.contains(" "))
                {
                    sb.append("'").append(colName).append("'");
                }
                else
                {
                    sb.append(colName);
                }
            }
            sb.append(":");
            if (col._genericType() != null)
            {
                serializeGenericType(sb, col._genericType());
            }
            if (col._multiplicity() != null)
            {
                sb.append(serializeMultiplicity(col._multiplicity()));
            }
        }
        sb.append(")");
    }

    /**
     * Serialize a GenericTypeOperation to Pure code.
     * Handles type algebra: Z=(?:K)⊆T, T-Z+V
     */
    private void serializeGenericTypeOperation(final StringBuilder sb, final GenericTypeOperation op)
    {
        serializeGenericType(sb, op._left());
        switch (op._type())
        {
            case EQUAL:
                sb.append("=");
                break;
            case UNION:
                sb.append("+");
                break;
            case DIFFERENCE:
                sb.append("-");
                break;
            case SUBSET:
                sb.append("⊆");
                break;
            default:
                throw new UnsupportedOperationException("Unknown GenericTypeOperation type: " + op._type());
        }
        serializeGenericType(sb, op._right());
    }

    /**
     * Serialize type parameters for functions: {@code <T,Z,K,V>} or
     * {@code <T|m>}
     */
    private void serializeTypeParameters(final StringBuilder sb,
            final MutableList<TypeParameter> params, final PackageableFunction func)
    {
        // Collect multiplicity param names from function multiplicities
        MutableSet<String> mulParamNames = collectMultiplicityParamNames(func);
        boolean hasTypeParams = params != null && !params.isEmpty();
        boolean hasMulParams = !mulParamNames.isEmpty();

        if (hasTypeParams || hasMulParams)
        {
            sb.append("<");
            if (hasTypeParams)
            {
                appendJoining(sb, params, ",", (b, tp) -> b.append(tp._name()));
            }
            if (hasMulParams)
            {
                sb.append("|");
                appendJoining(sb, mulParamNames.toList(), ",", (b, name) -> b.append(name));
            }
            sb.append(">");
        }
    }

    /**
     * Serialize type parameters for classes with contravariance:
     * {@code <T, -U>} or {@code <T|m>}
     */
    private void serializeTypeParametersWithContravariance(final StringBuilder sb,
            final MutableList<TypeParameter> params, final meta.pure.protocol.grammar.type.Class classDef)
    {
        // Collect multiplicity param names from class properties
        MutableSet<String> mulParamNames = collectMultiplicityParamNamesFromClass(classDef);
        boolean hasTypeParams = params != null && !params.isEmpty();
        boolean hasMulParams = !mulParamNames.isEmpty();

        if (hasTypeParams || hasMulParams)
        {
            sb.append("<");
            if (hasTypeParams)
            {
                appendJoining(sb, params, ", ", (b, tp) ->
                {
                    if (tp._contravariant() != null && Boolean.TRUE.equals(tp._contravariant()))
                    {
                        b.append("-");
                    }
                    b.append(tp._name());
                });
            }
            if (hasMulParams)
            {
                sb.append("|");
                appendJoining(sb, mulParamNames.toList(), ",", (b, name) -> b.append(name));
            }
            sb.append(">");
        }
    }

    /**
     * Collect multiplicity parameter names from a function's
     * parameters and return type.
     */
    private MutableSet<String> collectMultiplicityParamNames(final PackageableFunction func)
    {
        MutableSet<String> names = Sets.mutable.empty();
        if (func._parameters() != null)
        {
            for (VariableExpression param : func._parameters())
            {
                if (param._multiplicity() != null
                        && param._multiplicity()._multiplicityParameter() != null)
                {
                    names.add(String.valueOf(param._multiplicity()._multiplicityParameter()));
                }
            }
        }
        if (func._returnMultiplicity() != null
                && func._returnMultiplicity()._multiplicityParameter() != null)
        {
            names.add(String.valueOf(func._returnMultiplicity()._multiplicityParameter()));
        }
        return names;
    }

    /**
     * Collect multiplicity parameter names from a class's properties.
     */
    private MutableSet<String> collectMultiplicityParamNamesFromClass(final meta.pure.protocol.grammar.type.Class classDef)
    {
        MutableSet<String> names = Sets.mutable.empty();
        if (classDef._properties() != null)
        {
            for (Property prop : classDef._properties())
            {
                if (prop._multiplicity() != null
                        && prop._multiplicity()._multiplicityParameter() != null)
                {
                    names.add(String.valueOf(prop._multiplicity()._multiplicityParameter()));
                }
            }
        }
        return names;
    }



    /**
     * Serialize a collection element, wrapping negative expressions in parens.
     */
    private void serializeCollectionElement(final StringBuilder sb,
                                             final Object v)
    {
        if (v instanceof FunctionExpression sfe)
        {
            String funcName = String.valueOf(sfe._functionName());
            if ("minus".equals(funcName)
                    && sfe._parametersValues() != null
                    && sfe._parametersValues().size() == 1)
            {
                // Wrap negation expression -(expr) in parens in collection context
                sb.append("(");
                serializeFunctionExpression(sb, sfe);
                sb.append(")");
                return;
            }
        }
        if (v instanceof LambdaFunction lf)
        {
            serializeLambda(sb, lf);
        }
        else if (v instanceof ValueSpecification vs)
        {
            serializeExpression(sb, vs);
        }
        else
        {
            serializeRawValue(sb, v);
        }
    }

    private void serializeTypedValue(final StringBuilder sb,
                                      final Object v,
                                      final String typeName)
    {
        if (v instanceof LambdaFunction lf)
        {
            serializeLambda(sb, lf);
        }
        else if (v instanceof ValueSpecification vs)
        {
            serializeExpression(sb, vs);
        }
        else if ("Decimal".equals(typeName))
        {
            sb.append(v).append("d");
        }
        else if ("StrictDate".equals(typeName)
                || "DateTime".equals(typeName)
                || "StrictTime".equals(typeName))
        {
            // Dates are stored as strings with % prefix
            String dateStr = String.valueOf(v);
            if (!dateStr.startsWith("%"))
            {
                sb.append("%");
            }
            sb.append(dateStr);
        }
        else if (v instanceof Package_Pointer pp)
        {
            sb.append(pp._pointerValue());
        }
        else if (v instanceof String str)
        {
            if (str.startsWith("#") && str.endsWith("#"))
            {
                // DSL text: emit as-is without quotes
                sb.append(str);
            }
            else
            {
                sb.append("'").append(str).append("'");
            }
        }
        else
        {
            sb.append(v);
        }
    }

    private void serializeRawValue(final StringBuilder sb, final Object v)
    {
        switch (v)
        {
            case AtomicValue av -> serializeAtomicValue(sb, av);
            case ValueSpecification vs -> serializeExpression(sb, vs);
            case String str -> sb.append("'").append(str).append("'");
            default -> sb.append(v);
        }
    }

    private void serializeDotApplication(
            final StringBuilder sb,
            final DotApplication dot)
    {
        MutableList<ValueSpecification> params = dot._parametersValues();
        // First param is the receiver
        if (params != null && !params.isEmpty())
        {
            serializeExpression(sb, params.get(0));
        }
        sb.append(".").append(dot._functionName());
        // Additional args (e.g., milestoning params)
        if (params != null && params.size() > 1)
        {
            sb.append("(");
            for (int i = 1; i < params.size(); i++)
            {
                if (i > 1)
                {
                    sb.append(", ");
                }
                serializeExpression(sb, params.get(i));
            }
            sb.append(")");
        }
    }

    private void serializeFunctionExpression(
            final StringBuilder sb,
            final FunctionExpression sfe)
    {
        String funcName = String.valueOf(sfe._functionName());
        MutableList<ValueSpecification> params = sfe._parametersValues();

        // Handle slice: [expr:expr] or [:expr] or [expr:expr:expr]
        if ("slice".equals(funcName) && params != null
                && !params.isEmpty())
        {
            sb.append("[");
            if (params.size() == 1)
            {
                // [:expr] form
                sb.append(":");
                serializeExpression(sb, params.get(0));
            }
            else
            {
                for (int i = 0; i < params.size(); i++)
                {
                    if (i > 0)
                    {
                        sb.append(":");
                    }
                    serializeExpression(sb, params.get(i));
                }
            }
            sb.append("]");
            return;
        }

        // Handle unitInstance: 5 Measure~Unit
        if ("unitInstance".equals(funcName) && params != null
                && params.size() == 2)
        {
            String numericValue = extractStringValue(params.get(0));
            String unitName = extractStringValue(params.get(1));
            sb.append(numericValue).append(" ").append(unitName);
            return;
        }

        // Handle colSpec: ~colName or ~colName : Type[mult]
        if ("colSpec".equals(funcName) && params != null
                && !params.isEmpty())
        {
            sb.append("~");
            // The FE itself is the colSpec — pass it directly to serializeColSpec
            serializeColSpec(sb, sfe);
            return;
        }

        // Handle funcColSpec / funcColSpec2: ~colName : lambda
        if (("funcColSpec".equals(funcName) || "funcColSpec2".equals(funcName)) && params != null
                && params.size() >= 2)
        {
            sb.append("~");
            // funcColSpec(lambda, name, TypeHolder?) — name is second param
            sb.append(extractStringValue(params.get(1)));
            sb.append(" : ");
            // Lambda is first param (AtomicValue wrapping a LambdaFunction)
            serializeExpression(sb, params.get(0));
            return;
        }

        // Handle aggColSpec / aggColSpec2: ~colName : mapLambda : reduceLambda
        if (("aggColSpec".equals(funcName) || "aggColSpec2".equals(funcName)) && params != null
                && params.size() >= 3)
        {
            sb.append("~");
            // aggColSpec(mapLambda, reduceLambda, name, TypeHolder?) — name is third param
            sb.append(extractStringValue(params.get(2)));
            sb.append(" : ");
            serializeExpression(sb, params.get(0));
            sb.append(" : ");
            serializeExpression(sb, params.get(1));
            return;
        }

        // Handle colSpecArray: ~[col1, col2] or ~[col1:Type1, col2:Type2]
        if ("colSpecArray".equals(funcName) && params != null)
        {
            sb.append("~[");
            // New structure: colSpecArray(Collection["name","id"], TypeHolder((name:String, id:Integer)))
            if (params.size() >= 1 && params.get(0) instanceof meta.pure.protocol.grammar.valuespecification.Collection nameCol)
            {
                // Extract names from Collection and types from RelationType TypeHolder
                MutableList<ValueSpecification> names = nameCol._values();
                // Build column-to-type map from RelationType if present
                java.util.Map<String, GenericType> typeMap = new java.util.LinkedHashMap<>();
                java.util.Map<String, Multiplicity> mulMap = new java.util.LinkedHashMap<>();
                if (params.size() >= 2 && params.get(1) instanceof GenericTypeAndMultiplicityHolder holder
                        && holder._genericType() != null
                        && holder._genericType()._rawType() instanceof meta.pure.protocol.grammar.relation.RelationType rt
                        && rt._columns() != null)
                {
                    for (meta.pure.protocol.grammar.relation.Column col : rt._columns())
                    {
                        if (col._genericType() != null)
                        {
                            typeMap.put(col._name(), col._genericType());
                        }
                        if (col._multiplicity() != null)
                        {
                            mulMap.put(col._name(), col._multiplicity());
                        }
                    }
                }
                for (int i = 0; i < names.size(); i++)
                {
                    if (i > 0)
                    {
                        sb.append(", ");
                    }
                    String colName = extractStringValue(names.get(i));
                    sb.append(colName);
                    if (typeMap.containsKey(colName))
                    {
                        sb.append(" : ");
                        serializeGenericType(sb, typeMap.get(colName));
                    }
                    if (mulMap.containsKey(colName))
                    {
                        sb.append(serializeMultiplicity(mulMap.get(colName)));
                    }
                }
            }
            else
            {
                // Fallback: old structure with individual colSpec calls
                appendJoining(sb, params, ", ", (b, p) -> serializeColSpec(b, p));
            }
            sb.append("]");
            return;
        }

        // Handle funcColSpecArray / funcColSpecArray2: ~[col1 : lambda, col2 : lambda]
        if (("funcColSpecArray".equals(funcName) || "funcColSpecArray2".equals(funcName))
                && params != null && params.size() >= 1
                && params.get(0) instanceof meta.pure.protocol.grammar.valuespecification.Collection col)
        {
            sb.append("~[");
            MutableList<ValueSpecification> elements = col._values();
            for (int i = 0; i < elements.size(); i++)
            {
                if (i > 0)
                {
                    sb.append(", ");
                }
                // Each element is a funcColSpec/funcColSpec2(lambda, name, TypeHolder?)
                if (elements.get(i) instanceof FunctionExpression innerFe
                        && innerFe._parametersValues() != null
                        && innerFe._parametersValues().size() >= 2)
                {
                    sb.append(extractStringValue(innerFe._parametersValues().get(1)));
                    sb.append(" : ");
                    serializeExpression(sb, innerFe._parametersValues().get(0));
                }
            }
            sb.append("]");
            return;
        }

        // Handle aggColSpecArray / aggColSpecArray2: ~[col1 : mapLambda : reduceLambda, col2 : mapLambda : reduceLambda]
        if (("aggColSpecArray".equals(funcName) || "aggColSpecArray2".equals(funcName))
                && params != null && params.size() >= 1
                && params.get(0) instanceof meta.pure.protocol.grammar.valuespecification.Collection col)
        {
            sb.append("~[");
            MutableList<ValueSpecification> elements = col._values();
            for (int i = 0; i < elements.size(); i++)
            {
                if (i > 0)
                {
                    sb.append(", ");
                }
                // Each element is an aggColSpec/aggColSpec2(mapLambda, reduceLambda, name, TypeHolder?)
                if (elements.get(i) instanceof FunctionExpression innerFe
                        && innerFe._parametersValues() != null
                        && innerFe._parametersValues().size() >= 3)
                {
                    sb.append(extractStringValue(innerFe._parametersValues().get(2)));
                    sb.append(" : ");
                    serializeExpression(sb, innerFe._parametersValues().get(0));
                    sb.append(" : ");
                    serializeExpression(sb, innerFe._parametersValues().get(1));
                }
            }
            sb.append("]");
            return;
        }

        // Handle getAll: Class.all() or Class.all(date) or Class.all(date1, date2)
        if ("getAll".equals(funcName) && params != null
                && !params.isEmpty())
        {
            serializeExpression(sb, params.get(0));
            sb.append(".all(");
            for (int i = 1; i < params.size(); i++)
            {
                if (i > 1)
                {
                    sb.append(", ");
                }
                serializeExpression(sb, params.get(i));
            }
            sb.append(")");
            return;
        }

        // Handle getAllVersions: Class.allVersions()
        if ("getAllVersions".equals(funcName) && params != null
                && params.size() == 1)
        {
            serializeExpression(sb, params.get(0));
            sb.append(".allVersions()");
            return;
        }

        // Handle getAllVersionsInRange: Class.allVersionsInRange(start, end)
        if ("getAllVersionsInRange".equals(funcName) && params != null
                && params.size() == 3)
        {
            serializeExpression(sb, params.get(0));
            sb.append(".allVersionsInRange(");
            serializeExpression(sb, params.get(1));
            sb.append(", ");
            serializeExpression(sb, params.get(2));
            sb.append(")");
            return;
        }



        // Handle letFunction: 'x'->letFunction(value) -> let x = value
        if ("letFunction".equals(funcName) && params != null
                && params.size() == 2)
        {
            // First param is the variable name (AtomicValue wrapping a string)
            ValueSpecification nameParam = params.get(0);
            String varName = extractStringValue(nameParam);
            sb.append("let ").append(varName).append(" = ");
            serializeExpression(sb, params.get(1));
            return;
        }

        // Handle new: ^Type(prop=value, ...) expression instances
        if ("new".equals(funcName) && params != null
                && params.size() >= 1
                && params.get(0) instanceof GenericTypeAndMultiplicityHolder typeHolder
                && typeHolder._genericType() != null)
        {
            // First param: GenericTypeAndMultiplicityHolder with type name and optional type/mult args
            GenericType gt = typeHolder._genericType();
            String typeName = ((Type_Pointer) gt._rawType())._pointerValue();
            sb.append("^").append(typeName);

            // Serialize type arguments / multiplicity arguments if present
            if ((gt._typeArguments() != null && !gt._typeArguments().isEmpty())
                    || (gt._multiplicityArguments() != null && !gt._multiplicityArguments().isEmpty()))
            {
                serializeGenericTypeArgs(sb, gt);
            }
            sb.append("(");

            // Second param (index 1) is a Collection of keyExpression calls
            if (params.size() >= 2 && params.get(1) instanceof Collection keyCollection)
            {
                appendJoining(sb, keyCollection._values(), ", ", (b, keyParam) ->
                {
                    if (keyParam instanceof FunctionExpression keySfe)
                    {
                        MutableList<ValueSpecification> keyParams = keySfe._parametersValues();
                        if (keyParams != null && keyParams.size() >= 2)
                        {
                            String propName = extractStringValue(keyParams.get(0));
                            b.append(propName).append("=");
                            serializeExpression(b, keyParams.get(1));
                        }
                    }
                });
            }
            sb.append(")");
            return;
        }
        // Handle copy: ^$variable(prop=value, ...) expression instances
        if ("copy".equals(funcName) && params != null
                && params.size() >= 1
                && params.get(0) instanceof VariableExpression varExpr)
        {
            sb.append("^$").append(varExpr._name());
            sb.append("(");

            // Second param (index 1) is a Collection of keyExpression calls
            if (params.size() >= 2 && params.get(1) instanceof Collection keyCollection)
            {
                appendJoining(sb, keyCollection._values(), ", ", (b, keyParam) ->
                {
                    if (keyParam instanceof FunctionExpression keySfe)
                    {
                        MutableList<ValueSpecification> keyParams = keySfe._parametersValues();
                        if (keyParams != null && keyParams.size() >= 2)
                        {
                            String propName = extractStringValue(keyParams.get(0));
                            b.append(propName).append("=");
                            serializeExpression(b, keyParams.get(1));
                        }
                    }
                });
            }
            sb.append(")");
            return;
        }

        // Handle not: not(equal(a,b)) -> a != b; not(x) -> !x
        if ("not".equals(funcName) && params != null
                && params.size() == 1)
        {
            ValueSpecification inner = params.get(0);
            if (inner instanceof FunctionExpression eqSfe
                    && "equal".equals(eqSfe._functionName()))
            {
                // not(equal(a, b)) -> a != b
                MutableList<ValueSpecification> eqParams = eqSfe._parametersValues();
                possiblyAddParenthesis(sb, "not", eqParams.get(0));
                sb.append(" != ");
                possiblyAddParenthesis(sb, "not", eqParams.get(1));
            }
            else
            {
                sb.append("!");
                possiblyAddParenthesis(sb, "not", inner);
            }
            return;
        }

        // Handle binary operators: plus, minus, times, etc.
        if (isInfixOperator(funcName) && params != null
                && params.size() == 2)
        {
            possiblyAddParenthesisLeft(sb, funcName, params.get(0));
            sb.append(" ").append(getOperatorSymbol(funcName))
                    .append(" ");
            possiblyAddParenthesisRight(sb, funcName, params.get(1));
            return;
        }


        // Handle negation expression: minus(expr) -> -(expr)
        // Note: negative literals like -1 are encoded directly in AtomicValue
        if ("minus".equals(funcName) && params != null
                && params.size() == 1)
        {
            ValueSpecification operand = params.get(0);
            boolean wrap = operand instanceof FunctionExpression opSfe
                    && (isInfix(opSfe) || isUnary(opSfe));
            sb.append("-");
            if (wrap)
            {
                sb.append("(");
                serializeExpression(sb, operand);
                sb.append(")");
            }
            else
            {
                serializeExpression(sb, operand);
            }
            return;
        }

        // Default: function application
        if (params != null && !params.isEmpty())
        {
            // Use arrow notation only for ArrowApplication instances
            boolean usePrefix = !(sfe instanceof ArrowInvocation);

            if (usePrefix)
            {
                sb.append(funcName).append("(");
                appendJoining(sb, params, ", ", (b, p) -> serializeExpression(b, p));
                sb.append(")");
            }
            else
            {
                // Arrow notation: first param -> funcName(rest)
                mayWrapArrowReceiver(sb, params.get(0));
                sb.append("->").append(funcName).append("(");
                for (int i = 1; i < params.size(); i++)
                {
                    if (i > 1)
                    {
                        sb.append(", ");
                    }
                    serializeExpression(sb, params.get(i));
                }
                sb.append(")");
            }
        }
        else
        {
            sb.append(funcName).append("()");
        }
    }

    /**
     * Determine if a function call should use prefix notation f(args)
     * vs arrow notation firstArg->f(rest).
     *
     * Rules from legend-engine renderFunction:
     * - First arg is lambda -> prefix
     * - First arg is infix func (not minus) -> prefix
     * - Only 1 arg and it's infix (not minus) or primitive -> prefix
     * - Otherwise -> arrow
     */
    private boolean shouldUsePrefixNotation(final String funcName,
            final MutableList<ValueSpecification> params)
    {
        if (params == null || params.isEmpty())
        {
            return true;
        }
        ValueSpecification first = params.get(0);

        // First arg is a lambda -> prefix
        if (first instanceof LambdaFunction)
        {
            return true;
        }
        if (first instanceof AtomicValue av)
        {
            Object value = av._value();
            if (value instanceof LambdaFunction)
            {
                return true;
            }
        }

        // First arg is an infix func (not minus) -> prefix
        if (first instanceof FunctionExpression firstSfe)
        {
            String firstFunc = String.valueOf(firstSfe._functionName());
            if (!"minus".equals(firstFunc)
                    && isInfix(firstSfe))
            {
                return true;
            }
        }

        // Only 1 arg: if it's infix (not minus) or primitive -> prefix
        if (params.size() == 1)
        {
            if (first instanceof FunctionExpression firstSfe2)
            {
                String firstFunc = String.valueOf(firstSfe2._functionName());
                if (!"minus".equals(firstFunc)
                        && isInfix(firstSfe2))
                {
                    return true;
                }
            }
            if (isPrimitiveValue(first))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a FunctionExpression is an infix operator.
     * This includes binary operators and not(equal(...)).
     */
    private boolean isInfix(final FunctionExpression sfe)
    {
        String func = String.valueOf(sfe._functionName());
        if (isInfixOperator(func))
        {
            return true;
        }
        // not(equal(...)) renders as != which is infix
        if ("not".equals(func)
                && sfe._parametersValues() != null
                && !sfe._parametersValues().isEmpty())
        {
            ValueSpecification inner = sfe._parametersValues().get(0);
            return inner instanceof FunctionExpression innerSfe
                    && "equal".equals(innerSfe._functionName());
        }
        return false;
    }

    /**
     * Check if a FunctionExpression is a unary operator (!, -(expr)).
     */
    private static boolean isUnary(final FunctionExpression sfe)
    {
        String func = String.valueOf(sfe._functionName());
        return ("minus".equals(func) || "not".equals(func))
                && sfe._parametersValues() != null
                && sfe._parametersValues().size() == 1;
    }

    /**
     * Check if a ValueSpecification is a primitive literal.
     */
    private boolean isPrimitiveValue(final ValueSpecification vs)
    {
        if (!(vs instanceof AtomicValue av))
        {
            return false;
        }
        Object value = av._value();
        if (value == null)
        {
            return false;
        }
        // Primitive if the value is not a nested ValueSpecification
        // or LambdaFunction (i.e., it's a raw literal)
        return !(value instanceof ValueSpecification)
                && !(value instanceof LambdaFunction);
    }

    /**
     * Wrap the first argument of an arrow expression in parens
     * if it contains an operator with lower precedence than arrow (->).
     * Arrow binds tighter than all operators in the grammar, so any
     * infix, negation, not, or not(equal) expression needs wrapping.
     */
    private void mayWrapArrowReceiver(final StringBuilder sb,
                                      final ValueSpecification first)
    {
        boolean wrap = false;
        if (first instanceof FunctionExpression sfe)
        {
            wrap = isInfix(sfe) || isUnary(sfe);
        }
        if (wrap)
        {
            sb.append("(");
            serializeExpression(sb, first);
            sb.append(")");
        }
        else
        {
            serializeExpression(sb, first);
        }
    }

    /**
     * Possibly wrap a value in parentheses when it appears as
     * an operand of an infix or not operator.
     */
    private void possiblyAddParenthesis(final StringBuilder sb,
            final String outerFunc, final ValueSpecification param)
    {
        if (needsParenthesis(outerFunc, param))
        {
            sb.append("(");
            serializeExpression(sb, param);
            sb.append(")");
        }
        else
        {
            serializeExpression(sb, param);
        }
    }

    /**
     * Check if a param needs parentheses when used as operand
     * of the given outer function.
     */
    private boolean needsParenthesis(final String outerFunc, final ValueSpecification param)
    {
        if (param instanceof FunctionExpression sfe)
        {
            String innerFunc = String.valueOf(sfe._functionName());

            // Negation -(expr) inside an operator always needs parens: e.g. 2 * -(3 + 4)
            if ("minus".equals(innerFunc) && sfe._parametersValues() != null && sfe._parametersValues().size() == 1 && isOperatorContext(outerFunc))
            {
                return true;
            }

            if (parenMode == ParenthesisMode.MINIMAL)
            {
                return needsParenthesisMinimal(outerFunc, sfe, innerFunc);
            }
            else
            {
                return needsParenthesisExplicit(outerFunc, sfe, innerFunc);
            }
        }
        return false;
    }

    /**
     * MINIMAL mode: only add parens when inner has strictly lower
     * precedence than outer, which would change parse tree if removed.
     */
    private boolean needsParenthesisMinimal(final String outerFunc,
            final FunctionExpression sfe, final String innerFunc)
    {
        int outerPrec = precedenceOf(outerFunc);
        int innerPrec = innerPrecedenceOf(sfe, innerFunc);

        if (outerPrec < 0 || innerPrec < 0)
        {
            return false;
        }

        // Inner has strictly lower precedence -> needs parens
        return innerPrec < outerPrec;
    }

    /**
     * EXPLICIT mode: add parens whenever inner is a different operator
     * (current behavior that makes all precedence visible).
     */
    private boolean needsParenthesisExplicit(final String outerFunc,
            final FunctionExpression sfe, final String innerFunc)
    {
        // Infix inside an operator context: always wrap different operators
        if (isInfix(sfe) && isOperatorContext(outerFunc))
        {
            MutableList<ValueSpecification> innerParams = sfe._parametersValues() != null ? sfe._parametersValues() : Lists.mutable.empty();
            if (innerParams.notEmpty())
            {
                return innerParams.size() > 1 || innerParams.getFirst() instanceof FunctionExpression;
            }
        }
        return false;
    }

    /**
     * Get the effective precedence of an inner expression.
     * Handles not(equal(...)) which renders as != (PREC_EQUAL level).
     */
    private static int innerPrecedenceOf(final FunctionExpression sfe, final String innerFunc)
    {
        // not(equal(...)) renders as !=, which is at PREC_EQUAL level
        if ("not".equals(innerFunc)
                && sfe._parametersValues() != null
                && !sfe._parametersValues().isEmpty()
                && sfe._parametersValues().get(0) instanceof FunctionExpression innerSfe
                && "equal".equals(innerSfe._functionName()))
        {
            return PREC_EQUAL;
        }
        return precedenceOf(innerFunc);
    }

    /**
     * Add parentheses for the LEFT operand of a binary operator.
     * In MINIMAL mode: same or higher precedence on left = no parens (left-associative).
     * In EXPLICIT mode: same operator on left = no parens, different = parens.
     */
    private void possiblyAddParenthesisLeft(final StringBuilder sb,
            final String outerFunc, final ValueSpecification param)
    {
        if (param instanceof FunctionExpression sfe
                && isInfix(sfe))
        {
            String innerFunc = String.valueOf(sfe._functionName());
            if (parenMode == ParenthesisMode.MINIMAL)
            {
                int outerPrec = precedenceOf(outerFunc);
                int innerPrec = innerPrecedenceOf(sfe, innerFunc);
                // Left-associative: same or higher prec on left = no parens
                if (outerPrec >= 0 && innerPrec >= outerPrec)
                {
                    serializeExpression(sb, param);
                    return;
                }
            }
            else
            {
                // EXPLICIT: same operator on left = no parens
                if (isArithmeticOp(outerFunc) && innerFunc.equals(outerFunc))
                {
                    serializeExpression(sb, param);
                    return;
                }
            }
        }
        possiblyAddParenthesis(sb, outerFunc, param);
    }

    /**
     * Add parentheses for the RIGHT operand of a binary operator.
     * In MINIMAL mode: strictly higher precedence on right = no parens.
     * In EXPLICIT mode: always check (delegates to possiblyAddParenthesis).
     */
    private void possiblyAddParenthesisRight(final StringBuilder sb,
            final String outerFunc, final ValueSpecification param)
    {
        if (parenMode == ParenthesisMode.MINIMAL && param instanceof FunctionExpression sfe)
        {
            String innerFunc = String.valueOf(sfe._functionName());
            int outerPrec = precedenceOf(outerFunc);
            int innerPrec = innerPrecedenceOf(sfe, innerFunc);
            // Right side: strictly higher prec = no parens
            if (outerPrec >= 0 && innerPrec > outerPrec)
            {
                serializeExpression(sb, param);
                return;
            }
        }
        possiblyAddParenthesis(sb, outerFunc, param);
    }

    private static boolean isArithmeticOp(final String funcName)
    {
        return "plus".equals(funcName) || "minus".equals(funcName)
                || "times".equals(funcName) || "divide".equals(funcName);
    }

    private boolean isOperatorContext(final String func)
    {
        return "and".equals(func) || "or".equals(func)
                || "plus".equals(func)
                || "minus".equals(func)
                || "times".equals(func)
                || "divide".equals(func)
                || "not".equals(func)
                || "equal".equals(func)
                || "lessThan".equals(func)
                || "greaterThan".equals(func)
                || "lessThanEqual".equals(func)
                || "greaterThanEqual".equals(func);
    }

    private boolean isInfixOperator(final String funcName)
    {
        return "plus".equals(funcName) || "minus".equals(funcName)
                || "times".equals(funcName)
                || "divide".equals(funcName)
                || "equal".equals(funcName)
                || "lessThan".equals(funcName)
                || "greaterThan".equals(funcName)
                || "lessThanEqual".equals(funcName)
                || "greaterThanEqual".equals(funcName)
                || "and".equals(funcName) || "or".equals(funcName);
    }

    /**
     * Extract a string value from a ValueSpecification.
     * For AtomicValue, returns the first value as a string.
     */
    private String extractStringValue(final ValueSpecification vs)
    {
        if (vs instanceof AtomicValue av)
        {
            Object value = av._value();
            if (value != null)
            {
                return String.valueOf(value);
            }
        }
        return String.valueOf(vs);
    }

    private String getOperatorSymbol(final String funcName)
    {
        switch (funcName)
        {
            case "plus": return "+";
            case "minus": return "-";
            case "times": return "*";
            case "divide": return "/";
            case "equal": return "==";
            case "lessThan": return "<";
            case "greaterThan": return ">";
            case "lessThanEqual": return "<=";
            case "greaterThanEqual": return ">=";
            case "and": return "&&";
            case "or": return "||";
            default: return funcName;
        }
    }

    private void serializeProperty(final StringBuilder sb, final Property prop)
    {
        sb.append("  ");

        // Stereotypes
        serializeStereotypes(sb, prop._stereotypes());

        // Tagged values
        serializeTaggedValues(sb, prop._taggedValues());

        // Aggregation kind
        if (prop._aggregation() != null)
        {
            AggregationKind agg = prop._aggregation();
            sb.append("(").append(agg.name().toLowerCase()).append(") ");
        }

        sb.append(prop._name());
        sb.append(": ");

        // Type from genericType
        serializeGenericType(sb, prop._genericType());

        sb.append(serializeMultiplicity(prop._multiplicity()));

        // Default value
        if (prop._defaultValue() != null)
        {
            LambdaFunction defaultLambda = prop._defaultValue();
            MutableList<ValueSpecification> exprSeq = defaultLambda._expressionSequence();
            if (exprSeq != null && !exprSeq.isEmpty())
            {
                sb.append(" = ");
                serializeDefaultValueExpression(sb, exprSeq.get(0));
            }
        }

        sb.append(";\n");
    }

    private void serializeDefaultValueExpression(final StringBuilder sb, final ValueSpecification vs)
    {
        // Array: Collection containing ValueSpecification children
        if (vs instanceof Collection col)
        {
            MutableList<ValueSpecification> values = col._values();
            if (values != null && !values.isEmpty())
            {
                sb.append("[");
                for (int i = 0; i < values.size(); i++)
                {
                    if (i > 0)
                    {
                        sb.append(", ");
                    }
                    serializeExpression(sb, values.get(i));
                }
                sb.append("]");
                return;
            }
        }
        // Single expression (expressionInstance, literal, etc.)
        serializeExpression(sb, vs);
    }

/**
 * Serialize a qualified property inside a class body.
 * Format: stereotypes taggedValues name(params) { body }: ReturnType[mult];
 */
private void serializeQualifiedProperty(
        final StringBuilder sb,
        final QualifiedProperty qp)
{
    sb.append("  ");

    // Stereotypes
    serializeStereotypes(sb, qp._stereotypes());

    // Tagged values
    serializeTaggedValues(sb, qp._taggedValues());

    // Name
    sb.append(qp._name());

    // Parameters (skip the first one - it's the implicit 'this')
    sb.append("(");
    MutableList<VariableExpression> params = qp._parameters();
    appendJoining(sb, params, ", ", (b, param) ->
    {
        b.append(param._name()).append(": ");
        serializeGenericType(b, param._genericType());
        b.append(serializeMultiplicity(param._multiplicity()));
    });
    sb.append(") {");

    // Expression sequence (body)
    if (qp._expressionSequence() != null)
    {
        int size = qp._expressionSequence().size();
        if (size == 1)
        {
            // Single expression: inline
            serializeExpression(sb, qp._expressionSequence().get(0));
        }
        else
        {
            // Multiple expressions: one per line
            sb.append("\n");
            for (ValueSpecification expr : qp._expressionSequence())
            {
                serializeExpression(sb, expr);
                sb.append(";\n");
            }
        }
    }

    sb.append("}: ");

    // Return type and multiplicity
    serializeGenericType(sb, qp._genericType());
    sb.append(serializeMultiplicity(qp._multiplicity()));
    sb.append(";\n");
}

    private void serializeStereotypes(final StringBuilder sb, final MutableList<Stereotype_Pointer> stereotypes)
    {
        if (stereotypes != null && !stereotypes.isEmpty())
        {
            sb.append("<<");
            appendJoining(sb, stereotypes, ", ", (b, st) ->
            {
                b.append(st._pointerValue());
                if (st._extraPointerValues() != null && !st._extraPointerValues().isEmpty())
                {
                    b.append(".").append(st._extraPointerValues().getFirst()._value());
                }
            });
            sb.append(">> ");
        }
    }

    private void serializeTaggedValues(final StringBuilder sb, final MutableList<TaggedValue> taggedValues)
    {
        if (taggedValues != null && !taggedValues.isEmpty())
        {
            sb.append("{");
            appendJoining(sb, taggedValues, ", ", (b, tv) ->
            {
                if (tv._tag() != null)
                {
                    b.append(tv._tag()._pointerValue());
                    if (tv._tag()._extraPointerValues() != null && !tv._tag()._extraPointerValues().isEmpty())
                    {
                        b.append(".").append(tv._tag()._extraPointerValues().getFirst()._value());
                    }
                    b.append(" = '")
                      .append(tv._value())
                      .append("'");
                }
            });
            sb.append("} ");
        }
    }

    private String getFullQualifiedName(final meta.pure.protocol.grammar.PackageableElement element)
    {
        StringBuilder sb = new StringBuilder();
        if (element._package() != null && element._package()._pointerValue() != null)
        {
            String packageName = String.valueOf(element._package()._pointerValue());
            if (!packageName.isEmpty())
            {
                sb.append(packageName);
                sb.append("::");
            }
        }
        sb.append(element._name());
        return sb.toString();
    }

    private String serializeMultiplicity(final Multiplicity mult)
    {
        if (mult == null)
        {
            return "[1]";
        }

        // Check for multiplicity parameter reference
        if (mult._multiplicityParameter() != null)
        {
            return "[" + mult._multiplicityParameter() + "]";
        }

        int lower = 0;
        int upper = -1;

        if (mult._lowerBound() != null && mult._lowerBound()._value() != null)
        {
            lower = ((Number) mult._lowerBound()._value()).intValue();
        }

        if (mult._upperBound() != null
                && mult._upperBound()._value() != null)
        {
            upper = ((Number) mult._upperBound()._value()).intValue();
        }

        if (upper == -1)
        {
            if (lower == 0)
            {
                return "[*]";
            }
            return "[" + lower + "..*]";
        }
        else if (lower == upper)
        {
            return "[" + lower + "]";
        }
        else
        {
            return "[" + lower + ".." + upper + "]";
        }
    }

    /**
     * Serialize an enum definition to Pure code.
     *
     * @param enumDef enum definition
     * @return Pure code string
     */
    public String serializeEnum(final Enumeration enumDef)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("Enum ");

        // Stereotypes
        serializeStereotypes(sb, enumDef._stereotypes());

        // Tagged values
        serializeTaggedValues(sb, enumDef._taggedValues());

        // Full qualified name
        sb.append(getFullQualifiedName(enumDef));

        sb.append("\n{\n");

        // Enum values (stored as properties)
        if (enumDef._properties() != null)
        {
            appendJoining(sb, enumDef._properties(), ",\n", (b, prop) ->
            {
                b.append("  ");
                serializeStereotypes(b, prop._stereotypes());
                serializeTaggedValues(b, prop._taggedValues());
                b.append(prop._name());
            });
            sb.append("\n");
        }

        sb.append("}");

        return sb.toString();
    }

    /**
     * Serialize an association definition to Pure code.
     *
     * @param assoc association definition
     * @return Pure code string
     */
    public String serializeAssociation(final Association assoc)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("Association ");

        // Stereotypes
        serializeStereotypes(sb, assoc._stereotypes());

        // Tagged values
        serializeTaggedValues(sb, assoc._taggedValues());

        // Full qualified name
        sb.append(getFullQualifiedName(assoc));

        sb.append("\n{\n");

        // Properties
        for (Property prop : assoc._properties())
        {
            serializeProperty(sb, prop);
        }

        // Qualified properties
        for (QualifiedProperty qp : assoc._qualifiedProperties())
        {
            serializeQualifiedProperty(sb, qp);
        }

        sb.append("}");

        return sb.toString();
    }

    /**
     * Serialize a profile definition to Pure code.
     *
     * @param profile profile definition
     * @return Pure code string
     */
    public String serializeProfile(final Profile profile)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("Profile ");
        sb.append(getFullQualifiedName(profile));
        sb.append("\n{\n");

        // Stereotypes
        if (profile._p_stereotypes() != null && !profile._p_stereotypes().isEmpty())
        {
            sb.append("  stereotypes: [");
            appendJoining(sb, profile._p_stereotypes(), ", ", (b, st) -> b.append(st._value()));
            sb.append("];\n");
        }

        // Tags
        if (profile._p_tags() != null && !profile._p_tags().isEmpty())
        {
            sb.append("  tags: [");
            appendJoining(sb, profile._p_tags(), ", ", (b, tag) -> b.append(tag._value()));
            sb.append("];\n");
        }

        sb.append("}");

        return sb.toString();
    }




    /**
     * Serialize a list of elements to Pure code.
     *
     * @param elements packageable elements
     * @return Pure code string
     */
    public String serializeElements(
            final java.util.List<meta.pure.protocol.grammar.PackageableElement> elements)
    {
        StringBuilder sb = new StringBuilder();

        for (meta.pure.protocol.grammar.PackageableElement element : elements)
        {
            switch (element)
            {
                case meta.pure.protocol.grammar.type.Class cls -> sb.append(serializeClass(cls));
                case Enumeration enumDef -> sb.append(serializeEnum(enumDef));
                case Association assoc -> sb.append(serializeAssociation(assoc));
                case Profile prof -> sb.append(serializeProfile(prof));
                case PrimitiveType pt -> sb.append(serializePrimitiveType(pt));
                case Measure m -> sb.append(serializeMeasure(m));
                case NativeFunction nf -> sb.append(serializeNativeFunction(nf));
                case UserDefinedFunction func -> sb.append(serializeFunction(func));
                default -> { /* skip unknown element types */ }
            }
            sb.append("\n\n");
        }

        return sb.toString().trim() + "\n";
    }

    /**
     * Serialize a primitive type definition to Pure code.
     * E.g., Primitive Varchar(x: Integer[1]) extends String
     * [
     * $this.x > 0
     * ]
     */
    public String serializePrimitiveType(final PrimitiveType primType)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("Primitive ");

        // Stereotypes
        serializeStereotypes(sb, primType._stereotypes());

        // Tagged values
        serializeTaggedValues(sb, primType._taggedValues());

        // Full qualified name
        sb.append(getFullQualifiedName(primType));

        // Type variable parameters
        if (primType._typeVariables() != null && !primType._typeVariables().isEmpty())
        {
            sb.append("(");
            appendJoining(sb, primType._typeVariables(), ", ", (b, param) ->
            {
                b.append(param._name()).append(": ");
                serializeGenericType(b, param._genericType());
                b.append(serializeMultiplicity(param._multiplicity()));
            });
            sb.append(")");
        }

        // Extends (generalization)
        if (primType._generalizations() != null && !primType._generalizations().isEmpty())
        {
            sb.append(" extends ");
            Generalization gen = primType._generalizations().get(0);
            if (gen._general() != null)
            {
                serializeGenericType(sb, gen._general());
            }
        }

        // Constraints
        serializeConstraints(sb, primType._constraints());

        return sb.toString();
    }

    /**
     * Serialize a measure definition to Pure code.
     */
    public String serializeMeasure(final Measure measure)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("Measure ");

        // Stereotypes
        serializeStereotypes(sb, measure._stereotypes());

        // Tagged values
        serializeTaggedValues(sb, measure._taggedValues());

        // Full qualified name
        sb.append(getFullQualifiedName(measure));

        sb.append("\n{\n");

        // Canonical unit (prefixed with *)
        if (measure._canonicalUnit() != null)
        {
            sb.append("*");
            serializeUnit(sb, measure._canonicalUnit());
        }

        // Non-canonical units
        for (Unit unit : measure._nonCanonicalUnits())
        {
            serializeUnit(sb, unit);
        }

        sb.append("}");

        return sb.toString();
    }

    private void serializeUnit(final StringBuilder sb, final Unit unit)
    {
        sb.append(unit._name());
        if (unit._conversionFunction() != null)
        {
            sb.append(": ");
            // Conversion function: paramName -> body
            FunctionDefinition_Protocol cf = unit._conversionFunction();
            if (cf instanceof meta.pure.protocol.grammar.function.FunctionDefinition fd
                    && fd._parameters() != null && !fd._parameters().isEmpty())
            {
                sb.append(fd._parameters().get(0)._name());
            }
            sb.append(" -> ");
            serializeFunctionBody(sb, cf);
        }
        sb.append(";\n");
    }

    /**
     * Append elements of a list to a StringBuilder, separated by the given separator.
     */
    private <T> void appendJoining(final StringBuilder sb, final MutableList<? extends T> items,
            final String separator, final BiConsumer<StringBuilder, T> serializer)
    {
        for (int i = 0; i < items.size(); i++)
        {
            if (i > 0)
            {
                sb.append(separator);
            }
            serializer.accept(sb, items.get(i));
        }
    }
}
