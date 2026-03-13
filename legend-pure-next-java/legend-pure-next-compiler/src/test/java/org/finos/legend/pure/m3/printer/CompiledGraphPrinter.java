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

package org.finos.legend.pure.m3.printer;

import meta.pure.metamodel.Inferred;
import meta.pure.metamodel.Package;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.PropertyOwner;
import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.constraint.Constraint;
import meta.pure.metamodel.extension.ElementWithStereotypes;
import meta.pure.metamodel.extension.ElementWithTaggedValues;
import meta.pure.metamodel.extension.Profile;
import meta.pure.metamodel.extension.Stereotype;
import meta.pure.metamodel.extension.Tag;
import meta.pure.metamodel.extension.TaggedValue;
import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.function.NativeFunction;
import meta.pure.metamodel.function.PackageableFunction;
import meta.pure.metamodel.function.UserDefinedFunction;
import meta.pure.metamodel.function.property.AbstractProperty;
import meta.pure.metamodel.function.property.QualifiedProperty;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.multiplicity.MultiplicityValue;
import meta.pure.metamodel.multiplicity.PackageableMultiplicity;
import meta.pure.metamodel.relation.Column;
import meta.pure.metamodel.relation.GenericTypeOperation;
import meta.pure.metamodel.relation.RelationType;
import meta.pure.metamodel.relationship.Association;
import meta.pure.metamodel.type.Any;
import meta.pure.metamodel.type.Class;
import meta.pure.metamodel.type.Enumeration;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.Measure;
import meta.pure.metamodel.type.PrimitiveType;
import meta.pure.metamodel.type.Unit;
import meta.pure.metamodel.type.generics.ConcreteGenericType;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.ResolvedMultiplicityParameter;
import meta.pure.metamodel.type.generics.ResolvedTypeParameter;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.protocol.grammar.Package_Pointer;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;

import java.util.List;

/**
 * Produces the human-readable {@code ###CompiledGraph} text block for a list of
 * compiled {@link PackageableElement}s.
 *
 * <p>Format (one line per node, indented 2 spaces per level):
 * <pre>
 * class a::MyClass
 *   property name :String [1]
 *
 * function pack::greet(name:String[1]):String[1]
 *   FunctionApplication plus_String_1__String_1__String_1_ :String[1]  @5:3-5:20
 *     AtomicValue :String[1]  'Hello '
 *     Variable name :String[1]
 * </pre>
 */
public final class CompiledGraphPrinter
{
    // Prefixes stripped from well-known paths to keep output compact
    private static final String META_PREFIX = "meta::pure::metamodel::";
    private static final String FUNCTIONS_PREFIX = "meta::pure::functions::";
    private static final String PRIMITIVES_PREFIX = META_PREFIX + "type::primitives::";
    private static final String MULTIPLICITY_PREFIX = META_PREFIX + "multiplicity::";

    private CompiledGraphPrinter()
    {
    }

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    /**
     * Print the compiled graph for the given elements.
     *
     * @param elements elements in source order
     * @return the text content for the {@code ###CompiledGraph} section (without the marker line)
     */
    public static String print(List<PackageableElement> elements)
    {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (PackageableElement element : elements)
        {
            if (!first)
            {
                sb.append('\n');
            }
            first = false;
            printElement(element, sb);
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Top-level element dispatch
    // -----------------------------------------------------------------------

    private static void printElement(PackageableElement element, StringBuilder sb)
    {
        switch (element)
        {
            case UserDefinedFunction f -> printUserDefinedFunction(f, sb);
            case NativeFunction f -> printNativeFunction(f, sb);
            case Class c -> printClass(c, sb);
            case Association a -> printAssociation(a, sb);
            case Enumeration e -> printEnumeration(e, sb);
            case Profile p -> printProfile(p, sb);
            case PrimitiveType p -> printPrimitiveType(p, sb);
            case Measure m -> printMeasure(m, sb);
            default -> sb.append("// unsupported: ").append(element.getClass().getSimpleName())
                         .append(' ').append(fullPath(element)).append('\n');
        }
    }

    // -----------------------------------------------------------------------
    // Functions
    // -----------------------------------------------------------------------

    private static void printUserDefinedFunction(UserDefinedFunction f, StringBuilder sb)
    {
        sb.append("function ").append(functionSignature(f));
        appendClassifierGenericType(sb, f);
        sb.append('\n');
        printAnnotations(f, 1, sb);
        f._expressionSequence().forEach(vs -> printValueSpec(vs, 1, sb));
    }

    private static void printNativeFunction(NativeFunction f, StringBuilder sb)
    {
        sb.append("native function ").append(functionSignature(f));
        appendClassifierGenericType(sb, f);
        sb.append('\n');
        printAnnotations(f, 1, sb);
    }

    private static String functionSignature(PackageableFunction f)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(fullPath(f)).append('(');
        MutableList<VariableExpression> params = f._parameters();
        for (int i = 0; i < params.size(); i++)
        {
            if (i > 0)
            {
                sb.append(", ");
            }
            VariableExpression p = params.get(i);
            sb.append(p._name()).append(':').append(printType(p._genericType()))
              .append(printMul(p._multiplicity()));
        }
        sb.append("):").append(printType(f._returnGenericType()))
          .append(printMul(f._returnMultiplicity()));
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Classes, associations, etc.
    // -----------------------------------------------------------------------

    private static void printClass(Class c, StringBuilder sb)
    {
        sb.append("class ").append(fullPath(c));
        if (c._generalizations() != null && c._generalizations().notEmpty())
        {
            sb.append(" extends ");
            sb.append(c._generalizations()
                        .collect(g -> printType(g._general()))
                        .makeString(", "));
        }
        appendClassifierGenericType(sb, c);
        appendSourceInfo(sb, c._sourceInformation());
        sb.append('\n');
        printAnnotations(c, 1, sb);
        // Type variables (e.g., Class Foo(x:Integer[1]))
        if (c._typeVariables() != null && c._typeVariables().notEmpty())
        {
            c._typeVariables().forEach(tv ->
                indent(sb, 1).append("typeVariable ").append(tv._name())
                    .append(" :").append(printType(tv._genericType()))
                    .append(printMul(tv._multiplicity())).append('\n'));
        }
        if (c._properties() != null)
        {
            c._properties().forEach(p ->
            {
                indent(sb, 1).append("property ").append(p._name())
                    .append(" :").append(printType(p._genericType()))
                    .append(printMul(p._multiplicity()));
                GenericType propCgt = ((Any) p)._classifierGenericType();
                if (propCgt != null)
                {
                    sb.append("  ~").append(printType(propCgt));
                }
                sb.append('\n');
                // Print default value lambda if present
                if (p._defaultValue() != null)
                {
                    LambdaFunction lambda = p._defaultValue();
                    indent(sb, 2).append("defaultValue Lambda");
                    if (lambda._parameters() != null && lambda._parameters().notEmpty())
                    {
                        sb.append('(');
                        sb.append(lambda._parameters().collect(lp ->
                            lp._name() + ":" + printType(lp._genericType()) + printMul(lp._multiplicity())
                        ).makeString(", "));
                        sb.append(')');
                    }
                    else
                    {
                        sb.append("()");
                    }
                    sb.append('\n');
                    if (lambda._expressionSequence() != null)
                    {
                        lambda._expressionSequence().forEach(expr ->
                                printValueSpec(expr, 3, sb));
                    }
                }
            });
        }
        printQualifiedProperties(c, 1, sb);
        printConstraints(c._constraints(), 1, sb);
    }

    private static void printQualifiedProperties(PropertyOwner owner, int depth, StringBuilder sb)
    {
        MutableList<QualifiedProperty> qps = owner._qualifiedProperties();
        if (qps == null)
        {
            return;
        }
        qps.forEach(qp ->
        {
            indent(sb, depth).append("qualifiedProperty ").append(qp._name()).append('(');
            // First parameter is the implicit $this — skip it
            MutableList<VariableExpression> params = qp._parameters();
            boolean firstParam = true;
            for (VariableExpression param : params)
            {
                if ("this".equals(param._name()))
                {
                    continue;
                }
                if (!firstParam)
                {
                    sb.append(", ");
                }
                firstParam = false;
                sb.append(param._name()).append(':')
                  .append(printType(param._genericType()))
                  .append(printMul(param._multiplicity()));
            }
            sb.append(')')
                .append(" :").append(printType(qp._genericType()))
                .append(printMul(qp._multiplicity()))
                .append('\n');
            if (qp._expressionSequence() != null)
            {
                qp._expressionSequence().forEach(vs -> printValueSpec(vs, depth + 1, sb));
            }
        });
    }

    private static void printAssociation(Association a, StringBuilder sb)
    {
        sb.append("association ").append(fullPath(a));
        appendClassifierGenericType(sb, a);
    appendSourceInfo(sb, a._sourceInformation());
        sb.append('\n');
        printAnnotations(a, 1, sb);
        if (a._properties() != null)
        {
            a._properties().forEach(p ->
            {
                indent(sb, 1).append("property ").append(p._name())
                    .append(" :").append(printType(p._genericType()))
                    .append(printMul(p._multiplicity()));
                GenericType propCgt = ((Any) p)._classifierGenericType();
                if (propCgt != null)
                {
                    sb.append("  ~").append(printType(propCgt));
                }
                sb.append('\n');
            });
        }
        printQualifiedProperties(a, 1, sb);
    }

    private static void printEnumeration(Enumeration e, StringBuilder sb)
    {
        sb.append("enumeration ").append(fullPath(e));
        if (e._generalizations() != null && e._generalizations().notEmpty())
        {
            sb.append(" extends ");
            sb.append(e._generalizations()
                        .collect(g -> printType(g._general()))
                        .makeString(", "));
        }
        appendClassifierGenericType(sb, e);
        appendSourceInfo(sb, e._sourceInformation());
        sb.append('\n');
        printAnnotations(e, 1, sb);
        if (e._properties() != null)
        {
            e._properties().forEach(p ->
            {
                indent(sb, 1).append("property ").append(p._name());
                if (p._classifierGenericType() != null)
                {
                    sb.append("  ~").append(printType(p._classifierGenericType()));
                }
                if (p._defaultValue() != null
                        && p._defaultValue()._expressionSequence() != null
                        && p._defaultValue()._expressionSequence().notEmpty())
                {
                    ValueSpecification dvs = p._defaultValue()._expressionSequence().getFirst();
                    if (dvs instanceof meta.pure.metamodel.valuespecification.AtomicValue av
                            && av._value() instanceof meta.pure.metamodel.type.Enum enumVal)
                    {
                        sb.append("  = ^").append(fullPath(e)).append("(name='").append(enumVal._name()).append("')");
                    }
                }
                appendSourceInfo(sb, p._sourceInformation());
                sb.append('\n');
            });
        }
    }

    private static void printProfile(Profile p, StringBuilder sb)
    {
        sb.append("profile ").append(fullPath(p));
        appendClassifierGenericType(sb, p);
        sb.append('\n');
        if (p._p_stereotypes() != null)
        {
            p._p_stereotypes().forEach(s ->
                indent(sb, 1).append("stereotype ").append(s._value()).append('\n'));
        }
        if (p._p_tags() != null)
        {
            p._p_tags().forEach(t ->
                indent(sb, 1).append("tag ").append(t._value()).append('\n'));
        }
    }

    private static void printPrimitiveType(PrimitiveType p, StringBuilder sb)
    {
        sb.append("primitive ").append(fullPath(p));
        appendClassifierGenericType(sb, p);
        appendSourceInfo(sb, p._sourceInformation());
        sb.append('\n');
        printAnnotations(p, 1, sb);
        // Type variables (e.g., Primitive Varchar(x:Integer[1]))
        if (p._typeVariables() != null && p._typeVariables().notEmpty())
        {
            p._typeVariables().forEach(tv ->
                indent(sb, 1).append("typeVariable ").append(tv._name())
                    .append(" :").append(printType(tv._genericType()))
                    .append(printMul(tv._multiplicity())).append('\n'));
        }
        printConstraints(p._constraints(), 1, sb);
    }

    private static void printConstraints(MutableList<Constraint> constraints, int depth, StringBuilder sb)
    {
        if (constraints == null || constraints.isEmpty())
        {
            return;
        }
        constraints.forEach(c ->
        {
            indent(sb, depth).append("constraint");
            if (c._name() != null)
            {
                sb.append(' ').append(c._name());
            }
            if (c._owner() != null)
            {
                sb.append(" ~owner:").append(c._owner());
            }
            if (c._externalId() != null)
            {
                sb.append(" ~externalId:").append(c._externalId());
            }
            if (c._enforcementLevel() != null)
            {
                sb.append(" ~enforcementLevel:").append(c._enforcementLevel());
            }
            sb.append('\n');
            printFunctionDefinition(c._functionDefinition(), depth + 1, sb);
            if (c._messageFunction() != null)
            {
                indent(sb, depth + 1).append("messageFunction\n");
                printFunctionDefinition(c._messageFunction(), depth + 2, sb);
            }
        });
    }

    private static void printFunctionDefinition(FunctionDefinition funcDef, int depth, StringBuilder sb)
    {
        if (funcDef instanceof LambdaFunction lambda)
        {
            printLambda(lambda, depth, sb);
        }
    }

    private static void printLambda(LambdaFunction lambda, int depth, StringBuilder sb)
    {
        indent(sb, depth).append("Lambda");
        if (lambda._parameters() != null && lambda._parameters().notEmpty())
        {
            sb.append('(');
            sb.append(lambda._parameters().collect(p ->
                p._name() + ":" + printType(p._genericType()) + printMul(p._multiplicity())
            ).makeString(", "));
            sb.append(')');
        }
        else
        {
            sb.append("()");
        }
        sb.append('\n');
        if (lambda._expressionSequence() != null)
        {
            lambda._expressionSequence().forEach(vs -> printValueSpec(vs, depth + 1, sb));
        }
    }

    private static void printMeasure(Measure m, StringBuilder sb)
{
    sb.append("measure ").append(fullPath(m));
    appendClassifierGenericType(sb, m);
    appendSourceInfo(sb, m._sourceInformation());
    sb.append('\n');
    printAnnotations(m, 1, sb);
    if (m._canonicalUnit() != null)
    {
        printUnit(m._canonicalUnit(), "canonicalUnit", 1, sb);
    }
    if (m._nonCanonicalUnits() != null)
    {
        m._nonCanonicalUnits().forEach(u -> printUnit(u, "unit", 1, sb));
    }
}

private static void printUnit(Unit u, String label, int depth, StringBuilder sb)
{
    indent(sb, depth).append(label).append(' ').append(u._name());
    appendSourceInfo(sb, u._sourceInformation());
    sb.append('\n');
    if (u._conversionFunction() != null)
    {
        printLambda((LambdaFunction) u._conversionFunction(), depth + 1, sb);
    }
}

    // -----------------------------------------------------------------------
    // ValueSpecification printing
    // -----------------------------------------------------------------------

    private static void printValueSpec(ValueSpecification vs, int depth, StringBuilder sb)
    {
        switch (vs)
        {
            case FunctionExpression fe -> printFunctionExpression(fe, depth, sb);
            case AtomicValue av -> printAtomicValue(av, depth, sb);
            case Collection col -> printCollection(col, depth, sb);
            case VariableExpression varExpr -> printVariableExpr(varExpr, depth, sb);
            case GenericTypeAndMultiplicityHolder holder -> printHolder(holder, depth, sb);
            default -> indent(sb, depth).append("// unsupported: ")
                        .append(vs.getClass().getSimpleName()).append('\n');
        }
    }

    private static void printFunctionExpression(FunctionExpression fe, int depth, StringBuilder sb)
    {
        String kind = fe.getClass().getSimpleName()
                         .replace("Impl", "")
                         .replace("Application", "Application");
        indent(sb, depth)
            .append(kind).append(' ');

        // Resolved function name
        if (fe._func() instanceof PackageableFunction fn)
        {
            sb.append(shortPath(fn));
        }
        else if (fe._func() instanceof AbstractProperty prop)
        {
            if (prop._owner() != null)
            {
                sb.append(fullPath(prop._owner())).append('.').append(prop._name());
            }
            else
            {
                sb.append(prop._name());
            }
        }
        else if (fe._functionName() != null)
        {
            sb.append(fe._functionName());
        }
        else
        {
            sb.append("?");
        }

        // Return type
        if (fe._genericType() != null)
        {
            sb.append(" :").append(printType(fe._genericType())).append(printMul(fe._multiplicity()));
        }
        appendVsClassifierGenericType(sb, fe);

        // Source info
        appendSourceInfo(sb, fe._sourceInformation());
        sb.append('\n');

        // Resolved type/multiplicity parameters
        MutableList<ResolvedTypeParameter> rtp = fe._resolvedTypeParameters();
        if (rtp != null && rtp.notEmpty())
        {
            rtp.toSortedListBy(p -> p._name()).forEach(p -> indent(sb, depth + 1)
                    .append("typeParam ").append(p._name())
                    .append(" -> ").append(printType(p._value()))
                    .append('\n'));
        }
        MutableList<ResolvedMultiplicityParameter> rmp = fe._resolvedMultiplicityParameters();
        if (rmp != null && rmp.notEmpty())
        {
            rmp.forEach(p -> indent(sb, depth + 1)
                    .append("mulParam ").append(p._name())
                    .append(" -> ").append(printMul(p._value()))
                    .append('\n'));
        }

        // Children (arguments)
        if (fe._parametersValues() != null)
        {
            fe._parametersValues().forEach(child -> printValueSpec(child, depth + 1, sb));
        }
    }

    private static void printAtomicValue(AtomicValue av, int depth, StringBuilder sb)
    {
        Object value = av._value();
        if (value instanceof LambdaFunction lambda)
        {
            indent(sb, depth).append("Lambda");
            if (lambda._parameters() != null && lambda._parameters().notEmpty())
            {
                sb.append('(');
                sb.append(lambda._parameters().collect(p ->
                    p._name() + ":" + printType(p._genericType()) + printMul(p._multiplicity())
                ).makeString(", "));
                sb.append(')');
            }
            if (av._genericType() != null)
            {
                sb.append(" :").append(printType(av._genericType())).append(printMul(av._multiplicity()));
            }
            appendVsClassifierGenericType(sb, av);
            appendSourceInfo(sb, av._sourceInformation());
            sb.append('\n');
            // Open variables
            if (lambda._openVariables() != null && lambda._openVariables().notEmpty())
            {
                lambda._openVariables().forEach(ov ->
                    indent(sb, depth + 1).append("openVar ").append(ov._name())
                        .append(" :").append(printType(ov._genericType()))
                        .append(printMul(ov._multiplicity())).append('\n'));
            }
            if (lambda._expressionSequence() != null)
            {
                lambda._expressionSequence().forEach(vs -> printValueSpec(vs, depth + 1, sb));
            }
        }
        else
        {
            indent(sb, depth).append("AtomicValue").append("  ").append(formatLiteral(value));
            if (av._genericType() != null)
            {
                sb.append(" :").append(printType(av._genericType())).append(printMul(av._multiplicity()));
            }
            appendVsClassifierGenericType(sb, av);
            appendSourceInfo(sb, av._sourceInformation());
            sb.append('\n');
        }
    }

    private static void printCollection(Collection col, int depth, StringBuilder sb)
    {
        indent(sb, depth).append("Collection");
        if (col._genericType() != null)
        {
            sb.append(" :").append(printType(col._genericType())).append(printMul(col._multiplicity()));
        }
        appendVsClassifierGenericType(sb, col);
        appendSourceInfo(sb, col._sourceInformation());
        sb.append('\n');
        if (col._values() != null)
        {
            col._values().forEach(v -> printValueSpec(v, depth + 1, sb));
        }
    }

    private static void printVariableExpr(VariableExpression varExpr, int depth, StringBuilder sb)
    {
        indent(sb, depth).append("Variable ").append(varExpr._name());
        if (varExpr._genericType() != null)
        {
            sb.append(" :").append(printType(varExpr._genericType())).append(printMul(varExpr._multiplicity()));
        }
        appendVsClassifierGenericType(sb, varExpr);
        appendSourceInfo(sb, varExpr._sourceInformation());
        sb.append('\n');
    }

    private static void printHolder(GenericTypeAndMultiplicityHolder holder, int depth, StringBuilder sb)
    {
        indent(sb, depth).append("TypeHolder");
        if (holder._genericType() != null)
        {
            sb.append(" :").append(printType(holder._genericType())).append(printMul(holder._multiplicity()));
        }
        appendVsClassifierGenericType(sb, holder);
        appendSourceInfo(sb, holder._sourceInformation());
        sb.append('\n');
    }

    // -----------------------------------------------------------------------
    // Type / multiplicity helpers
    // -----------------------------------------------------------------------

    static String printType(GenericType gt)
    {
        if (gt == null)
        {
            return "?";
        }
        String result = printTypeCore(gt);
        if (gt instanceof Inferred)
        {
            return "#" + result + "#";
        }
        return result;
    }

    private static String printTypeCore(GenericType gt)
    {
        // Handle GenericTypeOperation (type algebra: T+R, T-R, T=R, T⊆R)
        if (gt instanceof GenericTypeOperation gto)
        {
            String op = switch (gto._type())
            {
                case UNION -> "+";
                case DIFFERENCE -> "-";
                case EQUAL -> "=";
                case SUBSET -> "⊆";
            };
            return printType(gto._left()) + op + printType(gto._right());
        }
        if (gt._typeParameter() != null)
        {
            return gt._typeParameter()._name();
        }
        if (gt._rawType() == null)
        {
            return "?";
        }
        if (gt._rawType() instanceof FunctionType ft)
        {
            return printFunctionType(ft);
        }
        if (gt._rawType() instanceof RelationType rt)
        {
            return printRelationType(rt);
        }
        if (gt._rawType() instanceof Unit unit)
        {
            // Unit is not PackageableElement — use measure path + ~unitName
            if (unit._measure() != null)
            {
                return shortPath(unit._measure()) + "~" + unit._name();
            }
            return String.valueOf(unit._name());
        }
        String path = shortPath((PackageableElement) gt._rawType());
        // Type arguments and multiplicity arguments (e.g. Property<Owner, String | [1]>)
        MutableList<GenericType> typeArgs = gt._typeArguments();
        MutableList<Multiplicity> mulArgs = gt._multiplicityArguments();
        boolean hasTypeArgs = typeArgs != null && typeArgs.notEmpty();
        boolean hasMulArgs = mulArgs != null && mulArgs.notEmpty();
        if (hasTypeArgs || hasMulArgs)
        {
            path += "<";
            if (hasTypeArgs)
            {
                path += typeArgs.collect(CompiledGraphPrinter::printType).makeString(", ");
            }
            if (hasMulArgs)
            {
                if (hasTypeArgs)
                {
                    path += " | ";
                }
                path += mulArgs.collect(m -> {
                    String s = printMul(m);
                    // Strip surrounding brackets for inline display
                    return s.startsWith("[") && s.endsWith("]") ? s.substring(1, s.length() - 1) : s;
                }).makeString(", ");
            }
            path += ">";
        }
        // Type variable values (e.g. Varchar(200))
        if (gt instanceof ConcreteGenericType cgt && cgt._typeVariableValues() != null && cgt._typeVariableValues().notEmpty())
        {
            path += "(" + cgt._typeVariableValues().collect(CompiledGraphPrinter::printValueInline).makeString(", ") + ")";
        }
        return path;
    }

    private static String printFunctionType(FunctionType ft)
    {
        StringBuilder sb = new StringBuilder("{");
        if (ft._parameters() != null && ft._parameters().notEmpty())
        {
            boolean first = true;
            for (var p : ft._parameters())
            {
                if (p == null)
                {
                    continue;
                }
                if (!first)
                {
                    sb.append(", ");
                }
                first = false;
                sb.append(printType(p._genericType()));
                sb.append(printMul(p._multiplicity()));
            }
        }
        sb.append("->");
        if (ft._returnType() != null)
        {
            sb.append(printType(ft._returnType()));
        }
        if (ft._returnMultiplicity() != null)
        {
            sb.append(printMul(ft._returnMultiplicity()));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String printRelationType(RelationType rt)
    {
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        if (rt._columns() != null)
        {
            for (Column col : rt._columns())
            {
                if (!first)
                {
                    sb.append(", ");
                }
                first = false;
                if (col._nameWildCard() != null && col._nameWildCard())
                {
                    sb.append('?');
                }
                else
                {
                    sb.append(col._name());
                }
                sb.append(':');
                if (col._genericType() != null)
                {
                    sb.append(printType(col._genericType()));
                }
                if (col._multiplicity() != null)
                {
                    sb.append(printMul(col._multiplicity()));
                }
            }
        }
        sb.append(')');
        return sb.toString();
    }

    private static String printValueInline(ValueSpecification vs)
    {
        if (vs instanceof AtomicValue av)
        {
            return String.valueOf(av._value());
        }
        return vs.toString();
    }

    static String printMul(Multiplicity multiplicity)
    {
        if (multiplicity == null)
        {
            return "[?]";
        }
        String result = printMulCore(multiplicity);
        if (multiplicity instanceof Inferred)
        {
            return "#" + result + "#";
        }
        return result;
    }

    private static String printMulCore(Multiplicity multiplicity)
    {
        if (multiplicity._multiplicityParameter() != null)
        {
            return "[" + multiplicity._multiplicityParameter() + "]";
        }
        if (multiplicity instanceof PackageableMultiplicity pm)
        {
            String path = fullPath(pm);
            if (path.startsWith(MULTIPLICITY_PREFIX))
            {
                path = path.substring(MULTIPLICITY_PREFIX.length());
            }
            // Strip "Inferred" prefix (e.g. InferredPureOne → PureOne)
            if (path.startsWith("Inferred"))
            {
                path = path.substring("Inferred".length());
            }
            return switch (path)
            {
                case "PureOne" -> "[1]";
                case "ZeroOne" -> "[0..1]";
                case "ZeroMany" -> "[*]";
                case "OneMany" -> "[1..*]";
                default -> "[" + path + "]";
            };
        }
        // Concrete multiplicity
        String lower = formatBound(multiplicity._lowerBound());
        String upper = formatBound(multiplicity._upperBound());
        if (upper.equals("*"))
        {
            return lower.equals("0") ? "[*]" : "[" + lower + "..*]";
        }
        if (lower.equals(upper))
        {
            return "[" + lower + "]";
        }
        return "[" + lower + ".." + upper + "]";
    }

    private static String formatBound(MultiplicityValue mv)
    {
        if (mv == null)
        {
            return "*";
        }
        Long v = mv._value();
        return v == null ? "*" : String.valueOf(v);
    }

    // -----------------------------------------------------------------------
    // Misc helpers
    // -----------------------------------------------------------------------

    private static String fullPath(PackageableElement element)
    {
        if (element == null)
        {
            return "?";
        }
        MutableList<String> parts = Lists.mutable.empty();
        parts.add(element._name());
        Package pkg = element._package();
        while (pkg != null && pkg._package() != null)
        {
            parts.add(pkg._name());
            pkg = pkg._package();
        }
        return parts.reverseThis().makeString("::");
    }

    /**
     * Return a compact path for the element, stripping well-known prefixes.
     * Types under {@code meta::pure::metamodel::} and functions under
     * {@code meta::pure::functions::} are shortened to their simple name.
     */
    private static String shortPath(PackageableElement element)
    {
        String path = fullPath(element);
        if (path.startsWith(PRIMITIVES_PREFIX))
        {
            return path.substring(PRIMITIVES_PREFIX.length());
        }
        if (path.startsWith(META_PREFIX) || path.startsWith(FUNCTIONS_PREFIX))
        {
            return element._name();
        }
        return path;
    }

    private static void appendSourceInfo(StringBuilder sb, SourceInformation si)
    {
        if (si == null)
        {
            return;
        }
        sb.append("  @").append(si._startLine()).append(':').append(si._startColumn())
          .append('-').append(si._endLine()).append(':').append(si._endColumn());
    }

    /**
     * Print stereotypes and tagged values of any AnnotatedElement.
     * Stereotypes are printed as: {@code  <<profile::path.stereo>}
     * Tagged values are printed as: {@code  {profile::path.tag = 'value'}}
     */
    private static void printAnnotations(ElementWithStereotypes element, int depth, StringBuilder sb)
    {
        MutableList<Stereotype> stereotypes = element._stereotypes();
        if (stereotypes != null && stereotypes.notEmpty())
        {
            indent(sb, depth).append("<<");
            boolean first = true;
            for (Stereotype s : stereotypes)
            {
                if (!first)
                {
                    sb.append(", ");
                }
                first = false;
                if (s._profile() != null)
                {
                    sb.append(fullPath(s._profile())).append('.');
                }
                sb.append(s._value());
            }
            sb.append(">>\n");
        }
        if (element instanceof ElementWithTaggedValues ewt)
        {
            MutableList<TaggedValue> taggedValues = ewt._taggedValues();
            if (taggedValues != null && taggedValues.notEmpty())
            {
                indent(sb, depth).append("{");
                boolean first = true;
                for (TaggedValue tv : taggedValues)
                {
                    if (!first)
                    {
                        sb.append(", ");
                    }
                    first = false;
                    Tag tag = tv._tag();
                    if (tag != null && tag._profile() != null)
                    {
                        sb.append(fullPath(tag._profile())).append('.');
                    }
                    if (tag != null)
                    {
                        sb.append(tag._value());
                    }
                    sb.append(" = '").append(tv._value()).append("'");
                }
                sb.append("}\n");
            }
        }
    }

    /**
     * Append the classifierGenericType of an element inline as {@code  ~<type>}, if present.
     * e.g. {@code  ~Class<a::MyClass>} or {@code  ~UserDefinedFunction<{->String[1]}>}.
     */
    private static void appendClassifierGenericType(StringBuilder sb, PackageableElement element)
    {
        GenericType cgt = ((Any) element)._classifierGenericType();
        if (cgt != null)
        {
            sb.append("  ~").append(printType(cgt));
        }
    }

    /**
     * Append the classifierGenericType of a value specification inline as {@code  ~<type>}, if set.
     */
    private static void appendVsClassifierGenericType(StringBuilder sb, ValueSpecification vs)
    {
        if (vs instanceof Any any)
        {
            GenericType cgt = any._classifierGenericType();
            if (cgt != null)
            {
                sb.append("  ~").append(printType(cgt));
            }
        }
    }

    private static StringBuilder indent(StringBuilder sb, int depth)
    {
        sb.append("  ".repeat(depth));
        return sb;
    }

    private static String formatLiteral(Object value)
    {
        if (value instanceof String s)
        {
            return "'" + s + "'";
        }
        if (value instanceof Package_Pointer pp)
        {
            return pp._pointerValue();
        }
        if (value instanceof PackageableElement pe)
        {
            return fullPath(pe);
        }
        return String.valueOf(value);
    }
}
