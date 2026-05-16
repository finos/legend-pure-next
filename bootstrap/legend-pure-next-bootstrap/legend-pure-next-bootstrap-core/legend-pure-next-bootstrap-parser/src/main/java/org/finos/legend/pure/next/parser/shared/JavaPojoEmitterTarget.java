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

package org.finos.legend.pure.next.parser.shared;

import org.finos.legend.pure.next.parser.codegen.EmitterTarget;

import java.util.List;

/**
 * Generic emitter target that produces Java POJO-style code: {@code new TImpl()._k(v)._k(v)}
 * for construction, {@code receiver._x(value)} for setters, {@code receiver._x()} for getters.
 *
 * <p>No knowledge of any specific protocol or Pure metamodel — subclasses provide the class
 * header (imports + class declaration) and override {@link #setterMethod(String)} or
 * {@link #dispatchSectionElementType()} when they need anything language-specific.</p>
 *
 * <p>Extension points:
 * <ul>
 *   <li>{@link #emitClassHeader(StringBuilder, String)} — abstract; subclass must implement.</li>
 *   <li>{@link #setterMethod(String)} — defaults to {@code "._" + fieldName}. Override to add
 *       per-protocol renames (e.g. Pure-protocol's {@code _p_sourceInformation} disambiguation).</li>
 *   <li>{@link #dispatchSectionElementType()} — element type of the {@code MutableList<E>}
 *       returned by the {@code dispatchSection} helper. Defaults to {@code Object}; protocol
 *       targets typically override to a typed {@code PackageableElement} interface.</li>
 * </ul></p>
 */
public abstract class JavaPojoEmitterTarget implements EmitterTarget
{
    @Override
    public abstract void emitClassHeader(StringBuilder sb, String dslFileName);

    @Override
    public void emitClassFooter(StringBuilder sb)
    {
        sb.append("}\n");
    }

    @Override
    public void emitTopLevelVisitWrapper(StringBuilder sb, String visitName, String buildName,
                                         String ctxType, String pureType)
    {
        sb.append("\n    @Override\n");
        sb.append("    public Object ").append(visitName).append("(final ").append(ctxType).append(" ctx)\n    {\n");
        sb.append("        ").append(resultType(pureType)).append(" __built = ").append(buildName).append("(ctx);\n");
        sb.append("        elements.add(__built);\n");
        sb.append("        return __built;\n    }\n");
    }

    @Override
    public String resultType(String pureType)
    {
        return pureType + "Impl";
    }

    @Override
    public String abstractType(String dslType)
    {
        return dslType;
    }

    @Override
    public String letDeclType(String dslType)
    {
        return dslType;
    }

    @Override
    public String constructExpression(String pureType, List<String[]> kvs)
    {
        StringBuilder out = new StringBuilder();
        out.append("new ").append(pureType).append("Impl()");
        for (String[] kv : kvs)
        {
            out.append(setterMethod(kv[0])).append('(').append(kv[1]).append(')');
        }
        return out.toString();
    }

    @Override
    public void emitSetterStatement(StringBuilder sb, String indent, String receiver, String fieldName, String valueExpr)
    {
        sb.append(indent).append(receiver).append(setterMethod(fieldName))
                .append('(').append(valueExpr).append(");\n");
    }

    @Override
    public void emitConditionalSetter(StringBuilder sb, String indent, String predJava,
                                      String receiver, String fieldName, String valueExpr)
    {
        sb.append(indent).append("if (").append(predJava).append(") ")
                .append(receiver).append(setterMethod(fieldName))
                .append('(').append(valueExpr).append(");\n");
    }

    @Override
    public String getterCall(String receiverExpr, String getterName)
    {
        return receiverExpr + "." + getterName + "()";
    }

    @Override
    public void emitDispatchSectionScaffolding(StringBuilder sb, String className)
    {
        // The three fields covered by this scaffolding:
        //   parserExtensions — section-name → ParserExtension map (caller-supplied)
        //   sourceId         — file identifier, passed through to section parsers
        //   syntheticHeader  — true when the caller prepended ###Pure to a header-less source
        String elementType = dispatchSectionElementType();
        sb.append("    private final java.util.Map<String, org.finos.legend.pure.next.parser.ParserExtension> parserExtensions;\n");
        sb.append("    private final String sourceId;\n");
        sb.append("    private final boolean syntheticHeader;\n\n");
        sb.append("    public ").append(className)
                .append("(java.util.Map<String, org.finos.legend.pure.next.parser.ParserExtension> parserExtensions, String sourceId, boolean syntheticHeader)\n    {\n");
        sb.append("        this.parserExtensions = parserExtensions;\n");
        sb.append("        this.sourceId = sourceId;\n");
        sb.append("        this.syntheticHeader = syntheticHeader;\n");
        sb.append("    }\n\n");
        sb.append("    /** Look up the section parser by name and apply it to the section body. */\n");
        sb.append("    protected org.eclipse.collections.api.list.MutableList<").append(elementType)
                .append("> dispatchSection(String name, String content, String sourceId, int lineOffset)\n    {\n");
        sb.append("        if (content == null || content.isEmpty()) return org.eclipse.collections.impl.factory.Lists.mutable.empty();\n");
        sb.append("        org.finos.legend.pure.next.parser.ParserExtension ext = parserExtensions == null ? null : parserExtensions.get(name);\n");
        sb.append("        if (ext == null) throw new RuntimeException(\"No parser registered for section: ###\" + name);\n");
        sb.append("        return org.eclipse.collections.impl.factory.Lists.mutable.withAll(ext.parseSection(content, sourceId, lineOffset));\n");
        sb.append("    }\n\n");
        emitFirstNonNewlineLineHelper(sb);
    }

    /**
     * Java type for the element of the list returned by the {@code dispatchSection} helper.
     * Defaults to {@code Object}; protocol targets typically override to a typed parent.
     */
    protected String dispatchSectionElementType()
    {
        return "Object";
    }

    /**
     * Map a DSL field name to its Java setter accessor (including the leading dot).
     * Defaults to {@code ._<fieldName>}; subclasses with name disambiguation override
     * (e.g. Pure protocol renames {@code sourceInformation} to {@code _p_sourceInformation}).
     */
    protected String setterMethod(String fieldName)
    {
        return "._" + fieldName;
    }

    /** Shared {@code computeFirstNonNewlineLine} helper — same for every protocol target. */
    public static void emitFirstNonNewlineLineHelper(StringBuilder sb)
    {
        sb.append("    /** Line number (0-based, source-relative) of the first non-NEWLINE child of {@code ctx}.\n");
        sb.append("     *  Subtracts an extra 1 when {@code syntheticHeader} is true (i.e. the {@code ###Pure}\n");
        sb.append("     *  header was prepended by the caller and shouldn't count toward the offset). */\n");
        sb.append("    protected int computeFirstNonNewlineLine(org.antlr.v4.runtime.ParserRuleContext ctx, boolean syntheticHeader)\n    {\n");
        sb.append("        if (ctx == null) return 0;\n");
        sb.append("        for (int i = 0; i < ctx.getChildCount(); i++)\n");
        sb.append("        {\n");
        sb.append("            org.antlr.v4.runtime.tree.ParseTree child = ctx.getChild(i);\n");
        sb.append("            if (child instanceof org.antlr.v4.runtime.tree.TerminalNode tn\n");
        sb.append("                    && tn.getSymbol().getType() == org.antlr.v4.runtime.Token.EOF) continue;\n");
        sb.append("            String text = child.getText();\n");
        sb.append("            if (text != null && text.trim().isEmpty()) continue;\n");
        sb.append("            int line = (child instanceof org.antlr.v4.runtime.tree.TerminalNode tn2)\n");
        sb.append("                    ? tn2.getSymbol().getLine()\n");
        sb.append("                    : ((org.antlr.v4.runtime.ParserRuleContext) child).getStart().getLine();\n");
        sb.append("            int offset = line - 1;\n");
        sb.append("            if (syntheticHeader) offset -= 1;\n");
        sb.append("            return offset;\n");
        sb.append("        }\n");
        sb.append("        return 0;\n");
        sb.append("    }\n\n");
    }
}
