// Copyright 2026 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
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

import platform.pure.metamodel.PackageableElement;
import platform.pure.metamodel.multiplicity.Multiplicity;
import platform.pure.metamodel.type.generics.UserDefinedGenericTypeImpl;
import platform.pure.metamodel.valuespecification.ArrowInvocationImpl;
import platform.pure.metamodel.valuespecification.AtomicValueImpl;
import platform.pure.metamodel.valuespecification.CollectionImpl;
import platform.pure.metamodel.valuespecification.DotApplicationImpl;
import platform.pure.metamodel.valuespecification.FunctionInvocationImpl;
import platform.pure.metamodel.valuespecification.ValueSpecification;
import platform.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.IslandHandler;
import platform.pure.metamodel.type.generics.CompilerNotSetGenericType;
import platform.pure.metamodel.type.generics.CompilerNotSetGenericTypeImpl;
import platform.pure.metamodel.multiplicity.CompilerNotSetMultiplicity;
import platform.pure.metamodel.multiplicity.CompilerNotSetMultiplicityImpl;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._VariableExpression;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * Compiles grammar-level {@link platform.pure.protocol.grammar.valuespecification.ValueSpecification}
 * into metamodel-level {@link ValueSpecification}.
 */
public final class ValueSpecificationCompiler
{
    private ValueSpecificationCompiler()
    {
    }

    // ---- Grammar → Metamodel compilation ----

    public static ValueSpecification compile(platform.pure.protocol.grammar.valuespecification.ValueSpecification vs, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        return switch (vs)
        {
            case platform.pure.protocol.grammar.valuespecification.ArrowInvocationImpl arrow ->
                    compileArrowInvocation(arrow, imports, model, context);
            case platform.pure.protocol.grammar.valuespecification.VariableExpressionImpl var ->
                    compileVariableExpression(var, model, context);
            case platform.pure.protocol.grammar.valuespecification.AtomicValueImpl av ->
                    compileAtomicValue(av, imports, model, context);
            case platform.pure.protocol.grammar.valuespecification.CollectionImpl col ->
                    compileCollection(col, imports, model, context);
            case platform.pure.protocol.grammar.valuespecification.FunctionInvocationImpl fa ->
                    compileFunctionInvocation(fa, imports, model, context);
            case platform.pure.protocol.grammar.valuespecification.DotApplicationImpl dot ->
                    compileDotApplication(dot, imports, model, context);
            case platform.pure.protocol.grammar.valuespecification.GenericTypeAndMultiplicityHolder holder ->
                    compileGenericTypeAndMultiplicityHolder(holder, imports, model, context);
            case platform.pure.protocol.grammar.valuespecification.IslandImpl island ->
                    compileIsland(island, imports, model, context);
            default -> throw new UnsupportedOperationException(
                    "Unsupported ValueSpecification type: " + vs.getClass().getName());
        };
    }

    // An expression island, compiled by the one handler (of any extension) that claims its text — as the
    // Pure compiler's compileIsland does, with the same errors. Unclaimed or claimed by several, an empty
    // collection stands in for it so compilation carries on and reports every error.
    private static ValueSpecification compileIsland(
            platform.pure.protocol.grammar.valuespecification.IslandImpl island,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        ListIterable<IslandHandler> handlers = context.islandHandlers();
        ListIterable<IslandHandler> claiming = handlers.select(h -> h.claims(island._text()));
        if (claiming.size() == 1)
        {
            return claiming.getOnly().compile(island, imports, model, context);
        }
        String shown = "'#" + island._text() + "#'";
        String message = claiming.size() > 1
                ? "The island " + shown + " is claimed by several handlers: " + claiming.collect(IslandHandler::name).makeString(", ")
                : "No handler claims the island " + shown + (handlers.isEmpty() ? " (no island handlers)" : " (handlers: " + handlers.collect(IslandHandler::name).makeString(", ") + ")");
        return islandErrorAt(message, island._p_sourceInformation(), imports, model, context);
    }

    /**
     * How a handler reports an error — as {@code islandErrorAt} in Pure: {@code message} at {@code at}
     * (usually a range inside the island, from {@link IslandHandler#sourceInformation}), naming no enclosing
     * element; an empty collection stands in for the island so compilation carries on.
     */
    public static ValueSpecification islandErrorAt(String message, platform.pure.protocol.grammar.SourceInformation at,
                                                   MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        context.addErrorWithoutContext(new CompilationError(message, SourceInformationCompiler.compile(at, context.getSourceId(), model)));
        return compile(new platform.pure.protocol.grammar.valuespecification.CollectionImpl()._p_sourceInformation(at), imports, model, context);
    }

    private static ArrowInvocationImpl compileArrowInvocation(
            platform.pure.protocol.grammar.valuespecification.ArrowInvocationImpl arrow,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        ArrowInvocationImpl result = new ArrowInvocationImpl(model)
                ._functionName(arrow._functionName())
                ._parametersValues(arrow._parametersValues()
                        .collect(pv -> compile(pv, imports, model, context)));
        if (arrow._p_sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(arrow._p_sourceInformation(), context.getSourceId(), model));
        }
        return result;
    }

    private static ValueSpecification compileFunctionInvocation(
            platform.pure.protocol.grammar.valuespecification.FunctionInvocationImpl fa,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        MutableList<ValueSpecification> params = fa._parametersValues()
                .collect(pv -> compile(pv, imports, model, context));

        // Constant-fold unary minus on a numeric literal:
        //   minus(AtomicValue(N)) → AtomicValue(-N), preserving generic type/multiplicity.
        // Mirrors the symmetric fold in compiler-pure (compileFunctionInvocation).
        ValueSpecification folded = tryFoldUnaryMinus(fa, params, model, context);
        if (folded != null)
        {
            return folded;
        }

        // Arity-based name dispatch for column-spec builders. Parser emits the
        // unsuffixed funcColSpec / aggColSpec / funcColSpecArray / aggColSpecArray;
        // the compiler rewrites to the *2 variant when the lambda has 3 params
        // (Relation, Window, U). Pure's function mangling collapses Function<{...}>
        // argument types, so we can't merge them as overloads — name-rewrite is
        // the path. Symmetric to compiler-pure.
        String effectiveName = dispatchColSpecArity(fa._functionName(), params);

        FunctionInvocationImpl result = new FunctionInvocationImpl(model)
                ._functionName(effectiveName)
                ._parametersValues(params);
        if (fa._p_sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(fa._p_sourceInformation(), context.getSourceId(), model));
        }
        return result;
    }

    private static String dispatchColSpecArity(String name, MutableList<ValueSpecification> params)
    {
        if (params.isEmpty())
        {
            return name;
        }
        if ("funcColSpec".equals(name) || "aggColSpec".equals(name))
        {
            return firstParamIsLambdaOfArity(params, 3) ? name + "2" : name;
        }
        if ("funcColSpecArray".equals(name) || "aggColSpecArray".equals(name))
        {
            return firstCollectionElementUsesArity2(params) ? name + "2" : name;
        }
        return name;
    }

    private static boolean firstParamIsLambdaOfArity(MutableList<ValueSpecification> params, int arity)
    {
        ValueSpecification first = params.get(0);
        if (!(first instanceof AtomicValueImpl av))
        {
            return false;
        }
        Object inner = av._value();
        if (!(inner instanceof platform.pure.metamodel.function.LambdaFunction lambda))
        {
            return false;
        }
        return lambda._parameters() != null && lambda._parameters().size() == arity;
    }

    private static boolean firstCollectionElementUsesArity2(MutableList<ValueSpecification> params)
    {
        ValueSpecification first = params.get(0);
        if (!(first instanceof CollectionImpl coll))
        {
            return false;
        }
        if (coll._values() == null || coll._values().isEmpty())
        {
            return false;
        }
        Object firstVal = coll._values().get(0);
        if (!(firstVal instanceof FunctionInvocationImpl inner))
        {
            return false;
        }
        String n = inner._functionName();
        return "funcColSpec2".equals(n) || "aggColSpec2".equals(n);
    }

    private static ValueSpecification tryFoldUnaryMinus(
            platform.pure.protocol.grammar.valuespecification.FunctionInvocationImpl fa,
            MutableList<ValueSpecification> params,
            MetadataAccess model,
            CompilationContext context)
    {
        if (!"minus".equals(fa._functionName()) || params.size() != 1)
        {
            return null;
        }
        if (!(params.get(0) instanceof AtomicValueImpl av))
        {
            return null;
        }
        Object v = av._value();
        if (!(v instanceof Number num) || v instanceof ValueSpecification)
        {
            return null;
        }
        // Fold only Integer/Float-typed literals. Skip Decimal (BigDecimal encoding
        // varies across compiler backends and folding it breaks PDB parity with
        // the Truffle compiler) and any other Number subtype that doesn't come
        // from a parser-produced literal token.
        Object negated;
        if (num instanceof Long l) { negated = -l; }
        else if (num instanceof Double d) { negated = -d; }
        else if (num instanceof Integer i) { negated = -i; }
        else if (num instanceof Float f) { negated = -f; }
        else { return null; }

        AtomicValueImpl folded = new AtomicValueImpl(model);
        folded._value(negated);
        if (av._genericType() != null)
        {
            folded._genericType(av._genericType());
        }
        if (av._multiplicity() != null)
        {
            folded._multiplicity(av._multiplicity());
        }
        if (fa._p_sourceInformation() != null)
        {
            folded._sourceInformation(SourceInformationCompiler.compile(fa._p_sourceInformation(), context.getSourceId(), model));
        }
        return folded;
    }

    private static DotApplicationImpl compileDotApplication(
            platform.pure.protocol.grammar.valuespecification.DotApplicationImpl dot,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        // the name read as a string literal is ($x.'\'a\'' reads the property or column 'a', quotes included)
        DotApplicationImpl result = new DotApplicationImpl(model)
                ._functionName(dot._functionName() == null ? null : unescapePureString(dot._functionName()))
                ._parametersValues(dot._parametersValues()
                        .collect(pv -> compile(pv, imports, model, context)));
        if (dot._p_sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(dot._p_sourceInformation(), context.getSourceId(), model));
        }
        return result;
    }

    private static ValueSpecification compileGenericTypeAndMultiplicityHolder(
            platform.pure.protocol.grammar.valuespecification.GenericTypeAndMultiplicityHolder holder,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        if (holder instanceof platform.pure.protocol.grammar.valuespecification.CompilerGenericTypeAndMultiplicityHolder)
        {
            return new platform.pure.metamodel.valuespecification.CompilerGenericTypeAndMultiplicityHolderImpl();
        }
        else if (holder instanceof platform.pure.protocol.grammar.valuespecification.UserDefinedGenericTypeAndMultiplicityHolder)
        {
            platform.pure.metamodel.type.generics.GenericType heldGT = holder._genericType() != null
                    ? GenericTypeCompiler.compile(holder._genericType(), imports, model, context)
                    : new CompilerNotSetGenericTypeImpl();
            Multiplicity heldMul = holder._multiplicity() != null
                    ? MultiplicityCompiler.compile(holder._multiplicity(), model)
                    : new CompilerNotSetMultiplicityImpl();

            // Build classifier type: GenericType(type=UserDefinedGenericTypeAndMultiplicityHolder, typeArguments=[heldGT])
            platform.pure.metamodel.type.Type holderType = (platform.pure.metamodel.type.Type) model.getElement(
                    "platform::pure::metamodel::valuespecification::UserDefinedGenericTypeAndMultiplicityHolder");
            UserDefinedGenericTypeImpl classifierGT = _GenericType.buildUserDefinedGenericType(holderType, model);
            if (!(heldGT instanceof CompilerNotSetGenericType))
            {
                classifierGT._typeArguments(org.eclipse.collections.impl.factory.Lists.mutable.with(heldGT));
            }
            if (!(heldMul instanceof CompilerNotSetMultiplicity))
            {
                classifierGT._multiplicityArguments(org.eclipse.collections.impl.factory.Lists.mutable.with(heldMul));
            }

            return new platform.pure.metamodel.valuespecification.UserDefinedGenericTypeAndMultiplicityHolderImpl()
                    ._classifierGenericType(classifierGT)
                    ._genericType(classifierGT)
                    ._multiplicity((Multiplicity) model.getElement("platform::pure::metamodel::multiplicity::PureOne"))
                    ._sourceInformation(holder._p_sourceInformation() != null ? SourceInformationCompiler.compile(holder._p_sourceInformation(), context.getSourceId(), model) : null);
        }
        else
        {
            throw new UnsupportedOperationException(
                    "Unsupported GenericTypeAndMultiplicityHolder type: " + holder.getClass().getName());
        }
    }

    private static VariableExpressionImpl compileVariableExpression(
            platform.pure.protocol.grammar.valuespecification.VariableExpressionImpl var, MetadataAccess model, org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationContext context)
    {
        VariableExpressionImpl result = _VariableExpression.newVariableExpression(model)
                ._name(var._name() != null ? var._name() : "")
                ._genericType(new CompilerNotSetGenericTypeImpl())
                ._multiplicity(new CompilerNotSetMultiplicityImpl());
        if (var._p_sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(var._p_sourceInformation(), context.getSourceId(), model));
        }
        return result;
    }

    private static AtomicValueImpl compileAtomicValue(
            platform.pure.protocol.grammar.valuespecification.AtomicValueImpl av,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        AtomicValueImpl result = new AtomicValueImpl(model);
        if (av._p_sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(av._p_sourceInformation(), context.getSourceId(), model));
        }
        result._genericType(av._genericType() != null
                ? GenericTypeCompiler.compile(av._genericType(), imports, model, context)
                : new CompilerNotSetGenericTypeImpl());
        result._multiplicity(av._multiplicity() != null
                ? MultiplicityCompiler.compile(av._multiplicity(), model)
                : (Multiplicity)model.getElement("platform::pure::metamodel::multiplicity::PureOne"));

        // Compile the single value: recursively compile inner ValueSpecification,
        // compile LambdaFunction (grammar -> metamodel), or pass through raw primitives as-is
        Object value = av._value();
        if (value instanceof platform.pure.protocol.grammar.function.LambdaFunction grammarLambda)
        {
            result._value(LambdaCompiler.compile(grammarLambda, imports, model, context));
        }
        else if (value instanceof platform.pure.protocol.grammar.valuespecification.ValueSpecification vs)
        {
            result._value(compile(vs, imports, model, context));
        }
        else if (value != null)
        {
            // Unescape string literals: the parser stores raw content with
            // Pure escape sequences intact (e.g., \' \\ \n \t)
            if (value instanceof String s)
            {
                result._value(unescapePureString(s));
            }
            else
            {
                result._value(value);
            }
        }

        // Detect date literals by their genericType (set by parser: DateTime, StrictDate, StrictTime, Date)
        // and normalize timezone offsets to UTC
        Object compiledValue = result._value();
        if (compiledValue instanceof String dateBody)
        {
            String genericTypeName = getGenericTypeName(result);
            if ("DateTime".equals(genericTypeName) || "StrictDate".equals(genericTypeName) || "Date".equals(genericTypeName) || "StrictTime".equals(genericTypeName))
            {
                // Normalize dates with timezone offsets to UTC
                if (dateBody.contains("T") && hasTimezoneOffset(dateBody))
                {
                    dateBody = normalizeToUTC(dateBody);
                    result._value(dateBody);
                }

                String dateTypePath;
                if (dateBody.contains("T"))
                {
                    dateTypePath = "platform::pure::metamodel::type::primitives::DateTime";
                }
                else if (dateBody.length() <= 7)
                {
                    // YYYY or YYYY-MM — Date
                    dateTypePath = "platform::pure::metamodel::type::primitives::Date";
                }
                else
                {
                    // YYYY-MM-DD — StrictDate
                    dateTypePath = "platform::pure::metamodel::type::primitives::StrictDate";
                }
                PackageableElement dateType = model.getElement(dateTypePath);
                if (dateType instanceof platform.pure.metamodel.type.Type)
                {
                    UserDefinedGenericTypeImpl gt = _GenericType.buildUserDefinedGenericType(null, model);
                    gt._type((platform.pure.metamodel.type.Type) dateType);
                    result._genericType(gt);
                }
            }
        }

        return result;
    }

    private static CollectionImpl compileCollection(
            platform.pure.protocol.grammar.valuespecification.CollectionImpl col,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        CollectionImpl result = new CollectionImpl(model);
        if (col._p_sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(col._p_sourceInformation(), context.getSourceId(), model));
        }
        if (col._multiplicity() != null)
        {
            result._multiplicity(MultiplicityCompiler.compile(col._multiplicity(), model));
        }

        // Compile values: recursively compile inner ValueSpecifications
        org.eclipse.collections.api.list.MutableList<platform.pure.protocol.grammar.valuespecification.ValueSpecification> values = col._values();
        if (values != null)
        {
            org.eclipse.collections.api.list.MutableList<ValueSpecification> compiledValues = org.eclipse.collections.impl.factory.Lists.mutable.empty();
            for (platform.pure.protocol.grammar.valuespecification.ValueSpecification v : values)
            {
                compiledValues.add(compile(v, imports, model, context));
            }
            result._values(compiledValues);
        }
        return result;
    }


    /**
     * Process Pure string literal escape sequences.
     * The parser strips the outer quote delimiters but leaves escape sequences
     * as literal two-character sequences: backslash-[' \ n t r] or backslash-uXXXX
     * This converts them to their actual single characters.
     */
    static String unescapePureString(String s)
    {
        if (s.indexOf('\\') < 0)
        {
            return s; // No escapes — fast path
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length())
            {
                char next = s.charAt(i + 1);
                switch (next)
                {
                    case '\'': sb.append('\''); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case 'n':  sb.append('\n'); i++; break;
                    case 't':  sb.append('\t'); i++; break;
                    case 'r':  sb.append('\r'); i++; break;
                    case 'u':
                        // backslash-uXXXX — up to 4 hex digits (lexer allows 1..4)
                        int hexStart = i + 2;
                        int hexEnd = hexStart;
                        while (hexEnd < s.length() && hexEnd - hexStart < 4
                                && isHexDigit(s.charAt(hexEnd)))
                        {
                            hexEnd++;
                        }
                        if (hexEnd > hexStart)
                        {
                            sb.append((char) Integer.parseInt(s.substring(hexStart, hexEnd), 16));
                            i = hexEnd - 1;
                        }
                        else
                        {
                            sb.append(c); // malformed — preserve backslash
                        }
                        break;
                    default:   sb.append(c); break;
                }
            }
            else
            {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isHexDigit(char c)
    {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Extract the simple type name from an AtomicValue's genericType.
     * Handles protocol-level pointer types and metamodel-level resolved types.
     */
    private static String getGenericTypeName(AtomicValueImpl av)
    {
        var gt = av._genericType();
        if (gt == null) { return null; }
        if (gt instanceof platform.pure.metamodel.type.generics.GenericTypeValue gtv)
        {
            var type = gtv._type();
            if (type instanceof platform.pure.protocol.grammar.type.Type_Pointer ptr) { return ptr._value(); }
            if (type instanceof PackageableElement pe) { return pe._name(); }
        }
        return null;
    }

    /**
     * Check whether a date body string contains a timezone offset.
     * Pure timezone offsets are in the format +HHMM or -HHMM (no colon),
     * appearing after the time portion (after 'T').
     */
    private static boolean hasTimezoneOffset(String dateBody)
    {
        int tPos = dateBody.indexOf('T');
        if (tPos < 0)
        {
            return false;
        }
        String timePart = dateBody.substring(tPos + 1);
        return timePart.contains("+") || timePart.contains("-");
    }

    // Flexible formatter that handles single-digit months/days/hours (e.g. 2014-1-1T0:00:00.0+0000)
    // and optional seconds (e.g. 2014-1-1T0:00+0000)
    private static final DateTimeFormatter PURE_OFFSET_PARSER = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('-')
            .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .appendLiteral('-')
            .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .appendLiteral('T')
            .appendValue(ChronoField.HOUR_OF_DAY, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .optionalEnd()
            .appendOffset("+HHmm", "+0000")
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter();

    /**
     * Convert a date body with timezone offset to UTC.
     * e.g. "2014-02-27T10:01:35.231+0530" becomes "2014-02-27T04:31:35.231"
     * Preserves the original sub-second precision from the input.
     */
    private static String normalizeToUTC(String dateBody)
    {
        // Extract original fractional part before parsing (to preserve precision)
        String origFrac = "";
        int tIdx = dateBody.indexOf('T');
        if (tIdx >= 0)
        {
            String afterT = dateBody.substring(tIdx + 1);
            int dotIdx = afterT.indexOf('.');
            if (dotIdx >= 0)
            {
                // Extract from dot to the offset sign (+ or -)
                int endIdx = afterT.length();
                for (int i = dotIdx + 1; i < afterT.length(); i++)
                {
                    char c = afterT.charAt(i);
                    if (c == '+' || c == '-')
                    {
                        endIdx = i;
                        break;
                    }
                }
                origFrac = afterT.substring(dotIdx, endIdx); // e.g. ".0000"
            }
        }

        OffsetDateTime odt = OffsetDateTime.parse(dateBody, PURE_OFFSET_PARSER);
        OffsetDateTime utc = odt.withOffsetSameInstant(ZoneOffset.UTC);

        // Format back preserving original precision (no offset suffix since it's now UTC)
        StringBuilder sb = new StringBuilder();
        sb.append(utc.toLocalDate());
        sb.append('T');
        sb.append(String.format("%02d:%02d", utc.getHour(), utc.getMinute()));

        // Only include seconds if the original had seconds
        boolean hadSeconds = dateBody.substring(tIdx + 1).chars().filter(c -> c == ':').count() >= 2;
        if (hadSeconds)
        {
            sb.append(String.format(":%02d", utc.getSecond()));
        }
        sb.append(origFrac);
        return sb.toString();
    }
}
