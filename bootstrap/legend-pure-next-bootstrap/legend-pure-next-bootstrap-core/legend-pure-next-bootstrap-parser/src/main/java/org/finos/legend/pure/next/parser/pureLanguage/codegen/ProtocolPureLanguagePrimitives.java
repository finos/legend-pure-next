// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.next.parser.pureLanguage.codegen;

import org.finos.legend.pure.next.parser.codegen.EmitterTarget;
import org.finos.legend.pure.next.parser.codegen.VisitorMappingGenerator;

import java.util.List;

/**
 * Pure-language-specific primitives for the {@link VisitorMappingGenerator}.
 *
 * <p>None of these primitives need their own target SPI method — every one of
 * them composes a tree of {@link EmitterTarget#constructExpression(String, List)}
 * calls (the single generic type-instantiation hook). The target picks the
 * representation (protocol-Impl chain vs PDO builder); the Pure-flavored shape
 * (which metamodel types compose into which) lives here.</p>
 *
 * <p>Call {@link #register()} once from a {@code main} entry point before
 * invoking {@link VisitorMappingGenerator#compile} on a Pure-language DSL.</p>
 */
public final class ProtocolPureLanguagePrimitives
{
    private ProtocolPureLanguagePrimitives() {}

    /** Register all Pure-language primitives with the generator. Idempotent. */
    public static void register()
    {
        VisitorMappingGenerator.registerPrimitive("primitiveType", ProtocolPureLanguagePrimitives::emitPrimitiveType);
        VisitorMappingGenerator.registerPrimitive("multBounds", ProtocolPureLanguagePrimitives::emitMultBounds);
        VisitorMappingGenerator.registerPrimitive("enumPointer", ProtocolPureLanguagePrimitives::emitEnumPointer);
        VisitorMappingGenerator.registerPrimitive("dateLiteralType", ProtocolPureLanguagePrimitives::emitDateLiteralType);
    }

    /** {@code primitiveType("Integer")} → a {@code UserDefinedGenericType} whose
     *  {@code type} is a {@code Type_Pointer} at the named primitive. */
    private static void emitPrimitiveType(StringBuilder out,
            java.util.List<org.finos.legend.pure.next.parser.codegen.mapping.VisitorMappingParser.ArgContext> args,
            String cachedTextToken, boolean inFoldBody)
    {
        if (args.size() != 1) throw new RuntimeException("primitiveType needs 1 arg");
        String nameLiteral = args.get(0).getText();
        out.append(udgtPointingAt(nameLiteral));
    }

    /** {@code multBounds(low, high)} → a {@code UserDefinedAdHocMultiplicity} with
     *  {@code lowerBound} and {@code upperBound} wrapped as {@code MultiplicityValue}. */
    private static void emitMultBounds(StringBuilder out,
            java.util.List<org.finos.legend.pure.next.parser.codegen.mapping.VisitorMappingParser.ArgContext> args,
            String cachedTextToken, boolean inFoldBody)
    {
        if (args.size() != 2) throw new RuntimeException("multBounds needs 2 args");
        EmitterTarget target = VisitorMappingGenerator.target();
        String lo = VisitorMappingGenerator.emitExpression(args.get(0).expression(), cachedTextToken, inFoldBody);
        String hi = VisitorMappingGenerator.emitExpression(args.get(1).expression(), cachedTextToken, inFoldBody);
        String lowerBound = target.constructExpression("MultiplicityValue",
                List.<String[]>of(new String[] {"value", "(long) (" + lo + ")"}));
        String upperBound = target.constructExpression("MultiplicityValue",
                List.<String[]>of(new String[] {"value", "(long) (" + hi + ")"}));
        out.append(target.constructExpression("UserDefinedAdHocMultiplicity", List.of(
                new String[] {"lowerBound", lowerBound},
                new String[] {"upperBound", upperBound})));
    }

    /** {@code enumPointer(qn, valueExpr)} → an {@code Enum_Pointer} with one
     *  {@code PointerValue} in its {@code extraPointerValues} list. */
    private static void emitEnumPointer(StringBuilder out,
            java.util.List<org.finos.legend.pure.next.parser.codegen.mapping.VisitorMappingParser.ArgContext> args,
            String cachedTextToken, boolean inFoldBody)
    {
        if (args.size() != 2) throw new RuntimeException("enumPointer needs 2 args (qualifiedName, value)");
        EmitterTarget target = VisitorMappingGenerator.target();
        String qn = args.get(0).getText();
        String value = VisitorMappingGenerator.emitExpression(args.get(1).expression(), cachedTextToken, inFoldBody);
        String pointerValue = target.constructExpression("PointerValue",
                List.<String[]>of(new String[] {"value", value}));
        out.append(target.constructExpression("Enum_Pointer", List.of(
                new String[] {"value", qn},
                new String[] {"extraPointerValues", "Lists.mutable.with(" + pointerValue + ")"})));
    }

    /** {@code dateLiteralType(textExpr)} → {@code UDGT(DateTime)} when {@code text}
     *  contains 'T', else {@code UDGT(StrictDate)}. */
    private static void emitDateLiteralType(StringBuilder out,
            java.util.List<org.finos.legend.pure.next.parser.codegen.mapping.VisitorMappingParser.ArgContext> args,
            String cachedTextToken, boolean inFoldBody)
    {
        if (args.size() != 1) throw new RuntimeException("dateLiteralType needs 1 arg");
        String inner = VisitorMappingGenerator.emitExpression(args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append('(').append(inner).append(".contains(\"T\") ? ")
                .append(udgtPointingAt("\"DateTime\""))
                .append(" : ")
                .append(udgtPointingAt("\"StrictDate\""))
                .append(')');
    }

    /** Helper: a {@code UserDefinedGenericType} whose {@code type} is a
     *  {@code Type_Pointer} pointing at the given (string-literal) Pure name. */
    private static String udgtPointingAt(String nameLiteral)
    {
        EmitterTarget target = VisitorMappingGenerator.target();
        String typePointer = target.constructExpression("Type_Pointer",
                List.<String[]>of(new String[] {"value", nameLiteral}));
        return target.constructExpression("UserDefinedGenericType",
                List.<String[]>of(new String[] {"type", typePointer}));
    }
}
