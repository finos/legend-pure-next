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

import org.finos.legend.pure.next.parser.shared.JavaPojoEmitterTarget;

/**
 * Pure-language protocol emitter target — produces {@code PureLanguageProtocolBuilder}
 * (the M3-grammar visitor) and its bootstrap protocol Impl construction code.
 *
 * <p>Inherits the generic POJO emit machinery (fluent {@code new TImpl()._k(v)},
 * {@code receiver._x()} getters) from {@link JavaPojoEmitterTarget} and adds two
 * Pure-language specifics:
 * <ul>
 *   <li>The M3-grammar class header (Pure protocol imports + accumulator field).</li>
 *   <li>The Any-collision setter rename: {@code sourceInformation} /
 *       {@code classifierGenericType} / {@code elementOverride} fields get a
 *       {@code _p_X} setter because the generated protocol Pure type renames the
 *       property to {@code p_X} (per {@code M3ProtocolGenerator.ANY_PROPERTY_NAMES}).</li>
 * </ul></p>
 */
public class ProtocolPureLanguageEmitterTarget extends JavaPojoEmitterTarget
{
    @Override
    public void emitClassHeader(StringBuilder sb, String dslFileName)
    {
        sb.append("// AUTO-GENERATED from ").append(dslFileName).append(" by PureLanguageVisitorMappingGenerator — DO NOT EDIT\n");
        sb.append("// Concrete parser: extends M3ParserBaseVisitor directly. Contains the elements\n");
        sb.append("// accumulator, parser entry points, build<RuleName> methods, and @Override\n");
        sb.append("// visit wrappers for topLevel rules. Fully self-contained — no hand-written\n");
        sb.append("// parent class. To port to another language, port this generator + the DSL.\n");
        sb.append("package org.finos.legend.pure.next.parser.pureLanguage;\n\n");
        sb.append("import meta.pure.protocol.grammar.Enum_PointerImpl;\n");
        sb.append("import meta.pure.protocol.grammar.Package_PointerImpl;\n");
        sb.append("import meta.pure.protocol.grammar.PackageableElement;\n");
        sb.append("import meta.pure.protocol.grammar.SourceInformation;\n");
        sb.append("import meta.pure.protocol.grammar.SourceInformationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.constraint.Constraint;\n");
        sb.append("import meta.pure.protocol.grammar.constraint.ConstraintImpl;\n");
        sb.append("import meta.pure.protocol.grammar.extension.AnnotatedElement;\n");
        sb.append("import meta.pure.protocol.grammar.extension.ProfileImpl;\n");
        sb.append("import meta.pure.protocol.grammar.extension.StereotypeImpl;\n");
        sb.append("import meta.pure.protocol.grammar.extension.Stereotype_PointerImpl;\n");
        sb.append("import meta.pure.protocol.grammar.extension.TagImpl;\n");
        sb.append("import meta.pure.protocol.grammar.extension.Tag_PointerImpl;\n");
        sb.append("import meta.pure.protocol.grammar.extension.TaggedValueImpl;\n");
        sb.append("import meta.pure.protocol.grammar.function.LambdaFunctionImpl;\n");
        sb.append("import meta.pure.protocol.grammar.function.NativeFunctionImpl;\n");
        sb.append("import meta.pure.protocol.grammar.function.UserDefinedFunctionImpl;\n");
        sb.append("import meta.pure.protocol.grammar.function.property.PropertyImpl;\n");
        sb.append("import meta.pure.protocol.grammar.function.property.QualifiedPropertyImpl;\n");
        sb.append("import meta.pure.protocol.grammar.multiplicity.MultiplicityParameter;\n");
        sb.append("import meta.pure.protocol.grammar.multiplicity.MultiplicityValueImpl;\n");
        sb.append("import meta.pure.protocol.grammar.multiplicity.Multiplicity_Protocol;\n");
        sb.append("import meta.pure.protocol.grammar.multiplicity.UndefinedMultiplicityImpl;\n");
        sb.append("import meta.pure.protocol.grammar.multiplicity.UserDefinedAdHocMultiplicityImpl;\n");
        sb.append("import meta.pure.protocol.grammar.multiplicity.UserDefinedMultiplicityParameterImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.generics.TypeParameterImpl;\n");
        sb.append("import meta.pure.protocol.grammar.PointerValueImpl;\n");
        sb.append("import meta.pure.protocol.grammar.relation.ColumnImpl;\n");
        sb.append("import meta.pure.protocol.grammar.relation.GenericTypeOperationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.relation.RelationTypeImpl;\n");
        sb.append("import meta.pure.protocol.grammar.relationship.AssociationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.relationship.GeneralizationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.ClassImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.EnumerationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.FunctionTypeImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.PrimitiveTypeImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.Type_PointerImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.generics.GenericType;\n");
        sb.append("import meta.pure.protocol.grammar.type.generics.TypeParameter;\n");
        sb.append("import meta.pure.protocol.grammar.type.generics.UndefinedGenericTypeImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.generics.UserDefinedGenericTypeImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.ArrowInvocationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.AtomicValueImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.CollectionImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.CompilerGenericTypeAndMultiplicityHolderImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.DotApplicationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.FunctionInvocationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.UserDefinedGenericTypeAndMultiplicityHolderImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.ValueSpecification;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.VariableExpressionImpl;\n");
        sb.append("import org.antlr.v4.runtime.ParserRuleContext;\n");
        sb.append("import org.antlr.v4.runtime.Token;\n");
        sb.append("import org.eclipse.collections.api.list.MutableList;\n");
        sb.append("import org.eclipse.collections.impl.factory.Lists;\n");
        sb.append("import org.eclipse.collections.impl.list.mutable.ListAdapter;\n");
        sb.append("import org.finos.legend.pure.next.parser.m3.M3Lexer;\n");
        sb.append("import org.finos.legend.pure.next.parser.m3.M3Parser;\n");
        sb.append("import org.finos.legend.pure.next.parser.m3.M3ParserBaseVisitor;\n\n");
        sb.append("public class PureLanguageProtocolBuilder extends M3ParserBaseVisitor<Object>\n");
        sb.append("{\n");
        sb.append("    protected final MutableList<PackageableElement> elements = Lists.mutable.empty();\n\n");
    }

    /**
     * Pure-protocol setter mapping: the {@code sourceInformation} property (and its
     * sibling {@code classifierGenericType} / {@code elementOverride}, when written
     * from the DSL) collides with {@code Any}'s inherited property of the same name,
     * so {@code M3ProtocolGenerator} renames it to {@code p_X}. The Java setter on
     * the generated protocol Impl is therefore {@code _p_sourceInformation}.
     */
    @Override
    protected String setterMethod(String fieldName)
    {
        if ("sourceInformation".equals(fieldName)
                || "classifierGenericType".equals(fieldName)
                || "elementOverride".equals(fieldName))
        {
            return "._p_" + fieldName;
        }
        return "._" + fieldName;
    }

    /** The protocol-Impl element type returned by dispatched section parsers. */
    @Override
    protected String dispatchSectionElementType()
    {
        return "meta.pure.protocol.grammar.PackageableElement";
    }
}
