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

package org.finos.legend.pure.truffle.parser.topLevel;

import org.finos.legend.pure.next.parser.shared.JavaPojoEmitterTarget;
import org.finos.legend.pure.truffle.parser.shared.TrufflePdoEmitterTarget;

import java.util.Map;
import java.util.Set;

/**
 * Truffle emitter target for the {@code top-mappings.dsl} compilation —
 * produces {@code TruffleTopLevelProtocolBuilder}. Build methods return
 * {@code Object} (PDOs) at {@code meta::pure::protocol::PureFile} and
 * {@code meta::pure::protocol::Section}.
 *
 * <p>Extends {@link TrufflePdoEmitterTarget} directly (NOT the Pure-language
 * {@code TruffleEmitterTarget}) — top-parser types don't have the Pure
 * metamodel's {@code Any}-collision {@code p_X} property rename, so the
 * generic PDO machinery is the right base.</p>
 */
public final class TruffleTopLevelEmitterTarget extends TrufflePdoEmitterTarget
{
    /** Top-parser produces just three Pure types — keep the path map minimal. */
    private static final Map<String, String> PATH_BY_SIMPLE_NAME = Map.of(
            "PureFile", "meta::pure::protocol::PureFile",
            "Section", "meta::pure::protocol::Section",
            "PackageableElement", "meta::pure::protocol::grammar::PackageableElement");

    private static final Set<String> ABSTRACT_NAMES = Set.copyOf(PATH_BY_SIMPLE_NAME.keySet());

    @Override
    public void emitClassHeader(StringBuilder sb, String dslFileName)
    {
        sb.append("// AUTO-GENERATED from ").append(dslFileName).append(" by TruffleTopLevelGenerator — DO NOT EDIT\n");
        sb.append("// Top-level parser visitor for the Truffle path. Produces PureDynamicObject\n");
        sb.append("// PureFile + Section instances directly — no protocol-Impl intermediate, no\n");
        sb.append("// ProtocolTranslator copy.\n");
        sb.append("package org.finos.legend.pure.truffle.parser;\n\n");
        sb.append("import org.antlr.v4.runtime.ParserRuleContext;\n");
        sb.append("import org.antlr.v4.runtime.Token;\n");
        sb.append("import org.eclipse.collections.api.list.MutableList;\n");
        sb.append("import org.eclipse.collections.impl.factory.Lists;\n");
        sb.append("import org.eclipse.collections.impl.list.mutable.ListAdapter;\n");
        sb.append("import org.finos.legend.pure.next.parser.TopParser;\n");
        sb.append("import org.finos.legend.pure.truffle.parser.topLevel.TruffleParserExtension;\n");
        sb.append("import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;\n");
        sb.append("import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;\n");
        sb.append("import org.finos.legend.pure.truffle.runtime.dynobj.PureObjBuilder;\n\n");
        sb.append("public class TruffleTopLevelProtocolBuilder\n");
        sb.append("{\n");
    }

    @Override
    protected String pureTypePath(String pureType)
    {
        String key = pureType.endsWith("Impl") ? pureType.substring(0, pureType.length() - 4) : pureType;
        String path = PATH_BY_SIMPLE_NAME.get(key);
        if (path == null)
        {
            throw new RuntimeException("TruffleTopLevelEmitterTarget: no Pure path registered for type '" + pureType + "'.");
        }
        return path;
    }

    @Override
    protected boolean isAbstractName(String simpleName)
    {
        return ABSTRACT_NAMES.contains(simpleName);
    }

    /** propertyNameFor is inherited identity — top-parser fields don't have the Any-collision rename. */

    @Override
    public void emitDispatchSectionScaffolding(StringBuilder sb, String className)
    {
        sb.append("    private final java.util.Map<String, TruffleParserExtension> parserExtensions;\n");
        sb.append("    private final TruffleMetadataAccess resolver;\n");
        sb.append("    private final String sourceId;\n");
        sb.append("    private final boolean syntheticHeader;\n\n");
        sb.append("    public ").append(className)
                .append("(java.util.Map<String, TruffleParserExtension> parserExtensions, TruffleMetadataAccess resolver, String sourceId, boolean syntheticHeader)\n    {\n");
        sb.append("        this.parserExtensions = parserExtensions;\n");
        sb.append("        this.resolver = resolver;\n");
        sb.append("        this.sourceId = sourceId;\n");
        sb.append("        this.syntheticHeader = syntheticHeader;\n");
        sb.append("    }\n\n");
        sb.append("    /** Look up the section parser by name and apply it to the section body. */\n");
        sb.append("    protected MutableList<Object> dispatchSection(String name, String content, String sourceId, int lineOffset)\n    {\n");
        sb.append("        if (content == null || content.isEmpty()) return Lists.mutable.empty();\n");
        sb.append("        TruffleParserExtension ext = parserExtensions == null ? null : parserExtensions.get(name);\n");
        sb.append("        if (ext == null) throw new RuntimeException(\"No parser registered for section: ###\" + name);\n");
        sb.append("        return Lists.mutable.withAll(ext.parseSection(content, sourceId, lineOffset, resolver));\n");
        sb.append("    }\n\n");
        JavaPojoEmitterTarget.emitFirstNonNewlineLineHelper(sb);
    }
}
