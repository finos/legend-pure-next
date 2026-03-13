package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.valuespecification.ArrowInvocationImpl;
import meta.pure.metamodel.valuespecification.AtomicValueImpl;
import meta.pure.metamodel.valuespecification.CollectionImpl;
import meta.pure.metamodel.valuespecification.DotApplicationImpl;
import meta.pure.metamodel.valuespecification.FunctionInvocationImpl;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Compiles grammar-level {@link meta.pure.protocol.grammar.valuespecification.ValueSpecification}
 * into metamodel-level {@link ValueSpecification}.
 */
public final class ValueSpecificationCompiler
{
    private ValueSpecificationCompiler()
    {
    }

    // ---- Grammar → Metamodel compilation ----

    public static ValueSpecification compile(meta.pure.protocol.grammar.valuespecification.ValueSpecification vs, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        return switch (vs)
        {
            case meta.pure.protocol.grammar.valuespecification.ArrowInvocationImpl arrow ->
                    compileArrowInvocation(arrow, imports, model, context);
            case meta.pure.protocol.grammar.valuespecification.VariableExpressionImpl var ->
                    compileVariableExpression(var);
            case meta.pure.protocol.grammar.valuespecification.AtomicValueImpl av ->
                    compileAtomicValue(av, imports, model, context);
            case meta.pure.protocol.grammar.valuespecification.CollectionImpl col ->
                    compileCollection(col, imports, model, context);
            case meta.pure.protocol.grammar.valuespecification.FunctionInvocationImpl fa ->
                    compileFunctionInvocation(fa, imports, model, context);
            case meta.pure.protocol.grammar.valuespecification.DotApplicationImpl dot ->
                    compileDotApplication(dot, imports, model, context);
            case meta.pure.protocol.grammar.valuespecification.GenericTypeAndMultiplicityHolder holder ->
                    compileGenericTypeAndMultiplicityHolder(holder, imports, model, context);
            default -> throw new UnsupportedOperationException(
                    "Unsupported ValueSpecification type: " + vs.getClass().getName());
        };
    }

    private static ArrowInvocationImpl compileArrowInvocation(
            meta.pure.protocol.grammar.valuespecification.ArrowInvocationImpl arrow,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        ArrowInvocationImpl result = new ArrowInvocationImpl()
                ._functionName(arrow._functionName())
                ._parametersValues(arrow._parametersValues()
                        .collect(pv -> compile(pv, imports, model, context)));
        if (arrow._sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(arrow._sourceInformation()));
        }
        return result;
    }

    private static FunctionInvocationImpl compileFunctionInvocation(
            meta.pure.protocol.grammar.valuespecification.FunctionInvocationImpl fa,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        FunctionInvocationImpl result = new FunctionInvocationImpl()
                ._functionName(fa._functionName())
                ._parametersValues(fa._parametersValues()
                        .collect(pv -> compile(pv, imports, model, context)));
        if (fa._sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(fa._sourceInformation()));
        }
        return result;
    }

    private static DotApplicationImpl compileDotApplication(
            meta.pure.protocol.grammar.valuespecification.DotApplicationImpl dot,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        DotApplicationImpl result = new DotApplicationImpl()
                ._functionName(dot._functionName())
                ._parametersValues(dot._parametersValues()
                        .collect(pv -> compile(pv, imports, model, context)));
        if (dot._sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(dot._sourceInformation()));
        }
        return result;
    }

    private static ValueSpecification compileGenericTypeAndMultiplicityHolder(
            meta.pure.protocol.grammar.valuespecification.GenericTypeAndMultiplicityHolder holder,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        if (holder instanceof meta.pure.protocol.grammar.valuespecification.CompilerGenericTypeAndMultiplicityHolder)
        {
            return new meta.pure.metamodel.valuespecification.CompilerGenericTypeAndMultiplicityHolderImpl();
        }
        else if (holder instanceof meta.pure.protocol.grammar.valuespecification.UserDefinedGenericTypeAndMultiplicityHolder)
        {
            return new meta.pure.metamodel.valuespecification.UserDefinedGenericTypeAndMultiplicityHolderImpl()
                    ._genericType(holder._genericType() != null ? GenericTypeCompiler.compile(holder._genericType(), imports, model, context) : null)
                    ._multiplicity(holder._multiplicity() != null ? MultiplicityCompiler.compile(holder._multiplicity(), model) : null)
                    ._sourceInformation(holder._sourceInformation() != null ? SourceInformationCompiler.compile(holder._sourceInformation()) : null);
        }
        else
        {
            throw new UnsupportedOperationException(
                    "Unsupported GenericTypeAndMultiplicityHolder type: " + holder.getClass().getName());
        }
    }

    private static VariableExpressionImpl compileVariableExpression(
            meta.pure.protocol.grammar.valuespecification.VariableExpressionImpl var)
    {
        VariableExpressionImpl result = new VariableExpressionImpl()
                ._name(var._name());
        if (var._sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(var._sourceInformation()));
        }
        return result;
    }

    private static AtomicValueImpl compileAtomicValue(
            meta.pure.protocol.grammar.valuespecification.AtomicValueImpl av,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        AtomicValueImpl result = new AtomicValueImpl();
        if (av._sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(av._sourceInformation()));
        }
        if (av._genericType() != null)
        {
            result._genericType(GenericTypeCompiler.compile(av._genericType(), imports, model, context));
        }
        if (av._multiplicity() != null)
        {
            result._multiplicity(MultiplicityCompiler.compile(av._multiplicity(), model));
        }
        else
        {
            // AtomicValues are inherently single-valued [1]
            result._multiplicity((Multiplicity)model.getElement("meta::pure::metamodel::multiplicity::PureOne"));
        }

        // Compile the single value: recursively compile inner ValueSpecification,
        // compile LambdaFunction (grammar -> metamodel), or pass through raw primitives as-is
        Object value = av._value();
        if (value instanceof meta.pure.protocol.grammar.function.LambdaFunction grammarLambda)
        {
            result._value(LambdaCompiler.compile(grammarLambda, imports, model, context));
        }
        else if (value instanceof meta.pure.protocol.grammar.valuespecification.ValueSpecification vs)
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

        // Override genericType for date literals stored as %-prefixed strings
        Object compiledValue = result._value();
        if (compiledValue instanceof String s && s.length() > 1 && s.charAt(0) == '%' && Character.isDigit(s.charAt(1)))
        {
            String dateTypePath;
            String dateBody = s.substring(1);

            // Normalize dates with timezone offsets to UTC
            if (dateBody.contains("T") && hasTimezoneOffset(dateBody))
            {
                dateBody = normalizeToUTC(dateBody);
                result._value("%" + dateBody);
            }

            if (dateBody.contains("T"))
            {
                dateTypePath = "meta::pure::metamodel::type::primitives::DateTime";
            }
            else if (dateBody.length() <= 7)
            {
                // %YYYY or %YYYY-MM — Date
                dateTypePath = "meta::pure::metamodel::type::primitives::Date";
            }
            else
            {
                // %YYYY-MM-DD — StrictDate
                dateTypePath = "meta::pure::metamodel::type::primitives::StrictDate";
            }
            PackageableElement dateType = model.getElement(dateTypePath);
            if (dateType instanceof meta.pure.metamodel.type.Type)
            {
                ConcreteGenericTypeImpl gt = new ConcreteGenericTypeImpl();
                gt._rawType((meta.pure.metamodel.type.Type) dateType);
                result._genericType(gt);
            }
        }

        return result;
    }

    private static CollectionImpl compileCollection(
            meta.pure.protocol.grammar.valuespecification.CollectionImpl col,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        CollectionImpl result = new CollectionImpl();
        if (col._sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(col._sourceInformation()));
        }
        if (col._multiplicity() != null)
        {
            result._multiplicity(MultiplicityCompiler.compile(col._multiplicity(), model));
        }

        // Compile values: recursively compile inner ValueSpecifications
        org.eclipse.collections.api.list.MutableList<meta.pure.protocol.grammar.valuespecification.ValueSpecification> values = col._values();
        if (values != null)
        {
            org.eclipse.collections.api.list.MutableList<ValueSpecification> compiledValues = org.eclipse.collections.impl.factory.Lists.mutable.empty();
            for (meta.pure.protocol.grammar.valuespecification.ValueSpecification v : values)
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
     * as literal two-character sequences: \' \\ \n \t \r
     * This converts them to their actual single characters.
     */
    private static String unescapePureString(String s)
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

    /**
     * Check whether a date body string (after the % prefix) contains a timezone offset.
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
