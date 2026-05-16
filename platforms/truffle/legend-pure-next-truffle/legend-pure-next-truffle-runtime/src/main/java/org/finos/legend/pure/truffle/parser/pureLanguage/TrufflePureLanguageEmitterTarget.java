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

package org.finos.legend.pure.truffle.parser.pureLanguage;

import org.finos.legend.pure.truffle.parser.shared.TrufflePdoEmitterTarget;

import java.util.Map;
import java.util.Set;

/**
 * Pure-language Truffle emitter target — produces
 * {@code TrufflePureLanguageProtocolBuilder} (the M3-grammar visitor) and its
 * {@code PureDynamicObject} construction code.
 *
 * <p>Inherits the generic PDO emit machinery (PureObjBuilder construction,
 * PureObj.write setters, PureObj.read getters) from
 * {@link TrufflePdoEmitterTarget} and adds three Pure-language specifics:
 * <ul>
 *   <li>The M3-grammar class header (ANTLR imports, the elements accumulator,
 *       the resolver field/constructor).</li>
 *   <li>The 47-entry simple-name → Pure-path map for {@code pureTypePath}.</li>
 *   <li>The Any-collision property rename:
 *       {@code sourceInformation/classifierGenericType/elementOverride} fields
 *       become {@code p_X} properties because {@code M3ProtocolGenerator}
 *       renames them in the generated protocol Pure type.</li>
 * </ul></p>
 */
public class TrufflePureLanguageEmitterTarget extends TrufflePdoEmitterTarget
{
    /**
     * Simple-name → full Pure path. The DSL references types by their simple
     * Pure-protocol name (e.g. {@code Class}); the Truffle factory needs the
     * full path. Derived from the protocol target's import list — each entry
     * mirrors the Java package the protocol target imports the corresponding
     * Impl from.
     */
    private static final Map<String, String> PURE_PATH_BY_SIMPLE_NAME = Map.ofEntries(
            Map.entry("Enum_Pointer", "meta::pure::protocol::grammar::Enum_Pointer"),
            Map.entry("Package_Pointer", "meta::pure::protocol::grammar::Package_Pointer"),
            Map.entry("PackageableElement", "meta::pure::protocol::grammar::PackageableElement"),
            Map.entry("SourceInformation", "meta::pure::protocol::grammar::SourceInformation"),
            Map.entry("Constraint", "meta::pure::protocol::grammar::constraint::Constraint"),
            Map.entry("AnnotatedElement", "meta::pure::protocol::grammar::extension::AnnotatedElement"),
            Map.entry("Profile", "meta::pure::protocol::grammar::extension::Profile"),
            Map.entry("Stereotype", "meta::pure::protocol::grammar::extension::Stereotype"),
            Map.entry("Stereotype_Pointer", "meta::pure::protocol::grammar::extension::Stereotype_Pointer"),
            Map.entry("Tag", "meta::pure::protocol::grammar::extension::Tag"),
            Map.entry("Tag_Pointer", "meta::pure::protocol::grammar::extension::Tag_Pointer"),
            Map.entry("TaggedValue", "meta::pure::protocol::grammar::extension::TaggedValue"),
            Map.entry("LambdaFunction", "meta::pure::protocol::grammar::function::LambdaFunction"),
            Map.entry("NativeFunction", "meta::pure::protocol::grammar::function::NativeFunction"),
            Map.entry("UserDefinedFunction", "meta::pure::protocol::grammar::function::UserDefinedFunction"),
            Map.entry("Property", "meta::pure::protocol::grammar::function::property::Property"),
            Map.entry("QualifiedProperty", "meta::pure::protocol::grammar::function::property::QualifiedProperty"),
            Map.entry("MultiplicityParameter", "meta::pure::protocol::grammar::multiplicity::MultiplicityParameter"),
            Map.entry("MultiplicityValue", "meta::pure::protocol::grammar::multiplicity::MultiplicityValue"),
            Map.entry("Multiplicity_Protocol", "meta::pure::protocol::grammar::multiplicity::Multiplicity_Protocol"),
            Map.entry("UndefinedMultiplicity", "meta::pure::protocol::grammar::multiplicity::UndefinedMultiplicity"),
            Map.entry("UserDefinedAdHocMultiplicity", "meta::pure::protocol::grammar::multiplicity::UserDefinedAdHocMultiplicity"),
            Map.entry("UserDefinedMultiplicityParameter", "meta::pure::protocol::grammar::multiplicity::UserDefinedMultiplicityParameter"),
            Map.entry("PointerValue", "meta::pure::protocol::grammar::PointerValue"),
            Map.entry("Column", "meta::pure::protocol::grammar::relation::Column"),
            Map.entry("GenericTypeOperation", "meta::pure::protocol::grammar::relation::GenericTypeOperation"),
            Map.entry("RelationType", "meta::pure::protocol::grammar::relation::RelationType"),
            Map.entry("Association", "meta::pure::protocol::grammar::relationship::Association"),
            Map.entry("Generalization", "meta::pure::protocol::grammar::relationship::Generalization"),
            Map.entry("Class", "meta::pure::protocol::grammar::type::Class"),
            Map.entry("Enumeration", "meta::pure::protocol::grammar::type::Enumeration"),
            Map.entry("FunctionType", "meta::pure::protocol::grammar::type::FunctionType"),
            Map.entry("PrimitiveType", "meta::pure::protocol::grammar::type::PrimitiveType"),
            Map.entry("Type_Pointer", "meta::pure::protocol::grammar::type::Type_Pointer"),
            Map.entry("GenericType", "meta::pure::protocol::grammar::type::generics::GenericType"),
            Map.entry("TypeParameter", "meta::pure::protocol::grammar::type::generics::TypeParameter"),
            Map.entry("UndefinedGenericType", "meta::pure::protocol::grammar::type::generics::UndefinedGenericType"),
            Map.entry("UserDefinedGenericType", "meta::pure::protocol::grammar::type::generics::UserDefinedGenericType"),
            Map.entry("ArrowInvocation", "meta::pure::protocol::grammar::valuespecification::ArrowInvocation"),
            Map.entry("AtomicValue", "meta::pure::protocol::grammar::valuespecification::AtomicValue"),
            Map.entry("Collection", "meta::pure::protocol::grammar::valuespecification::Collection"),
            Map.entry("CompilerGenericTypeAndMultiplicityHolder", "meta::pure::protocol::grammar::valuespecification::CompilerGenericTypeAndMultiplicityHolder"),
            Map.entry("DotApplication", "meta::pure::protocol::grammar::valuespecification::DotApplication"),
            Map.entry("FunctionInvocation", "meta::pure::protocol::grammar::valuespecification::FunctionInvocation"),
            Map.entry("UserDefinedGenericTypeAndMultiplicityHolder", "meta::pure::protocol::grammar::valuespecification::UserDefinedGenericTypeAndMultiplicityHolder"),
            Map.entry("ValueSpecification", "meta::pure::protocol::grammar::valuespecification::ValueSpecification"),
            Map.entry("VariableExpression", "meta::pure::protocol::grammar::valuespecification::VariableExpression"));

    /** Simple names that map to {@code Object} at the Java declaration level. */
    private static final Set<String> PURE_ABSTRACT_NAMES = Set.copyOf(PURE_PATH_BY_SIMPLE_NAME.keySet());

    @Override
    public void emitClassHeader(StringBuilder sb, String dslFileName)
    {
        sb.append("// AUTO-GENERATED from ").append(dslFileName).append(" by TrufflePureLanguageMappingGenerator — DO NOT EDIT\n");
        sb.append("// Concrete parser: extends M3ParserBaseVisitor directly. Builds PureDynamicObject\n");
        sb.append("// instances via TruffleInstanceFactory + PureObj.write — no protocol Impl objects,\n");
        sb.append("// no ProtocolTranslator copy step.\n");
        sb.append("package org.finos.legend.pure.truffle.parser.pureLanguage;\n\n");
        sb.append("import org.antlr.v4.runtime.ParserRuleContext;\n");
        sb.append("import org.antlr.v4.runtime.Token;\n");
        sb.append("import org.eclipse.collections.api.list.MutableList;\n");
        sb.append("import org.eclipse.collections.impl.factory.Lists;\n");
        sb.append("import org.eclipse.collections.impl.list.mutable.ListAdapter;\n");
        sb.append("import org.finos.legend.pure.next.parser.m3.M3Parser;\n");
        sb.append("import org.finos.legend.pure.next.parser.m3.M3ParserBaseVisitor;\n");
        sb.append("import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;\n");
        sb.append("import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;\n");
        sb.append("import org.finos.legend.pure.truffle.runtime.dynobj.PureObjBuilder;\n\n");
        sb.append("public class TrufflePureLanguageProtocolBuilder extends M3ParserBaseVisitor<Object>\n");
        sb.append("{\n");
        sb.append("    protected final MutableList<Object> elements = Lists.mutable.empty();\n");
        sb.append("    protected final TruffleMetadataAccess resolver;\n\n");
        sb.append("    public TrufflePureLanguageProtocolBuilder(TruffleMetadataAccess resolver)\n    {\n        this.resolver = resolver;\n    }\n\n");
        sb.append("    public TrufflePureLanguageProtocolBuilder()\n    {\n        this(null);\n    }\n\n");
    }

    @Override
    protected String pureTypePath(String pureType)
    {
        String key = pureType.endsWith("Impl") ? pureType.substring(0, pureType.length() - 4) : pureType;
        String path = PURE_PATH_BY_SIMPLE_NAME.get(key);
        if (path == null)
        {
            throw new RuntimeException("TruffleEmitterTarget: no Pure path registered for type '" + pureType + "'. "
                    + "Add an entry to PURE_PATH_BY_SIMPLE_NAME.");
        }
        return path;
    }

    @Override
    protected boolean isAbstractName(String simpleName)
    {
        return PURE_ABSTRACT_NAMES.contains(simpleName);
    }

    /**
     * Pure-language property-name rewrite: the {@code sourceInformation /
     * classifierGenericType / elementOverride} trio is renamed to {@code p_X}
     * on the generated protocol Pure types to dodge collision with the
     * {@code Any}-inherited properties of the same name
     * (per {@code M3ProtocolGenerator.ANY_PROPERTY_NAMES}).
     */
    @Override
    protected String propertyNameFor(String dslFieldName)
    {
        return ("sourceInformation".equals(dslFieldName)
                || "classifierGenericType".equals(dslFieldName)
                || "elementOverride".equals(dslFieldName))
                ? "p_" + dslFieldName
                : dslFieldName;
    }
}
