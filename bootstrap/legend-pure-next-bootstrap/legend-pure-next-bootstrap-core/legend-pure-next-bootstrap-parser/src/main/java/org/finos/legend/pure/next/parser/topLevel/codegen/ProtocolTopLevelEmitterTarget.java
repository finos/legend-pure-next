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

package org.finos.legend.pure.next.parser.topLevel.codegen;

import org.finos.legend.pure.next.parser.shared.JavaPojoEmitterTarget;

/**
 * Protocol emitter target for the {@code top-mappings.dsl} compilation —
 * produces {@code TopLevelProtocolBuilder} in package
 * {@code org.finos.legend.pure.next.parser.topLevel} whose build methods return
 * {@code PureFile}, {@code Section}, and {@code String} (for imports).
 *
 * <p>Extends {@link JavaPojoEmitterTarget} directly (NOT the Pure-language
 * {@code ProtocolEmitterTarget}) since the top-parser types ({@code PureFile},
 * {@code Section}) don't have the Pure metamodel's {@code Any}-collision
 * {@code p_X} setter rename — only their imports + class declaration differ
 * from the generic POJO base.</p>
 */
public final class ProtocolTopLevelEmitterTarget extends JavaPojoEmitterTarget
{
    /** The protocol-Impl element type returned by dispatched section parsers. */
    @Override
    protected String dispatchSectionElementType()
    {
        return "meta.pure.protocol.grammar.PackageableElement";
    }


    @Override
    public void emitClassHeader(StringBuilder sb, String dslFileName)
    {
        sb.append("// AUTO-GENERATED from ").append(dslFileName).append(" by PureLanguageVisitorMappingGenerator — DO NOT EDIT\n");
        sb.append("// Top-level parser visitor. Reuses the bootstrap TopLexer/TopParser ANTLR\n");
        sb.append("// classes; build methods produce a PureFile PDO with its Sections.\n");
        sb.append("package org.finos.legend.pure.next.parser.topLevel;\n\n");
        sb.append("import meta.pure.protocol.PureFile;\n");
        sb.append("import meta.pure.protocol.PureFileImpl;\n");
        sb.append("import meta.pure.protocol.Section;\n");
        sb.append("import meta.pure.protocol.SectionImpl;\n");
        sb.append("import meta.pure.protocol.grammar.PackageableElement;\n");
        sb.append("import org.antlr.v4.runtime.ParserRuleContext;\n");
        sb.append("import org.antlr.v4.runtime.Token;\n");
        sb.append("import org.eclipse.collections.api.list.MutableList;\n");
        sb.append("import org.eclipse.collections.impl.factory.Lists;\n");
        sb.append("import org.eclipse.collections.impl.list.mutable.ListAdapter;\n");
        sb.append("import org.finos.legend.pure.next.parser.TopParser;\n\n");
        sb.append("public class TopLevelProtocolBuilder\n");
        sb.append("{\n");
    }
}
