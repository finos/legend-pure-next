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

package org.finos.legend.pure.truffle.parser.shared;

import org.finos.legend.pure.next.parser.codegen.EmitterTarget;

import java.util.List;
import java.util.Set;

/**
 * Generic Truffle emitter target — produces code that builds
 * {@link org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject}s via
 * {@code PureObjBuilder.of(purePath, resolver).put(k, v).build()} and reads via
 * {@code PureObj.read(receiver, "x")}.
 *
 * <p>Knows nothing about specific Pure types — subclasses provide:
 * <ul>
 *   <li>{@link #emitClassHeader(StringBuilder, String)} — package decl + imports
 *       + class declaration + resolver field/constructor.</li>
 *   <li>{@link #pureTypePath(String)} — map a DSL simple type name to its full
 *       Pure path used by {@code PureObjBuilder.of(...)}.</li>
 *   <li>{@link #isAbstractName(String)} — true when a DSL-declared simple name
 *       refers to a Pure-protocol/metamodel type that collapses to {@code Object}
 *       at the Java declaration level (typed PDOs aren't representable).</li>
 *   <li>{@link #propertyNameFor(String)} — opportunity to rewrite DSL field
 *       names into the actual Pure property name (e.g. Pure-language's
 *       {@code sourceInformation} → {@code p_sourceInformation} rename). Defaults
 *       to identity.</li>
 * </ul></p>
 */
public abstract class TrufflePdoEmitterTarget implements EmitterTarget
{
    /** Java primitives + ANTLR-runtime types + collection wrappers that stay typed. */
    private static final Set<String> PRESERVED_NAMES = Set.of(
            "String", "Long", "Integer", "Double", "Float", "Boolean", "Object",
            "boolean", "int", "long", "double", "float", "char", "byte", "short", "void",
            "Token", "ParserRuleContext", "RuleContext");

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
        sb.append("        Object __built = ").append(buildName).append("(ctx);\n");
        sb.append("        elements.add(__built);\n");
        sb.append("        return __built;\n    }\n");
    }

    /** Every PDO is an {@code Object} at the Java level — no typed XPDBHelper interfaces post-flip. */
    @Override
    public String resultType(String pureType) { return "Object"; }

    @Override
    public String abstractType(String dslType) { return mapDeclType(dslType); }

    @Override
    public String letDeclType(String dslType) { return mapDeclType(dslType); }

    @Override
    public String constructExpression(String pureType, List<String[]> kvs)
    {
        String purePath = pureTypePath(pureType);
        StringBuilder out = new StringBuilder();
        out.append("PureObjBuilder.of(\"").append(purePath).append("\", resolver)");
        for (String[] kv : kvs)
        {
            out.append(".put(\"").append(propertyNameFor(kv[0])).append("\", ").append(kv[1]).append(')');
        }
        out.append(".build()");
        return out.toString();
    }

    @Override
    public void emitSetterStatement(StringBuilder sb, String indent, String receiver, String fieldName, String valueExpr)
    {
        sb.append(indent).append("PureObj.write(").append(receiver).append(", \"")
                .append(propertyNameFor(fieldName)).append("\", ").append(valueExpr).append(");\n");
    }

    @Override
    public void emitConditionalSetter(StringBuilder sb, String indent, String predJava,
                                      String receiver, String fieldName, String valueExpr)
    {
        sb.append(indent).append("if (").append(predJava).append(") PureObj.write(")
                .append(receiver).append(", \"").append(propertyNameFor(fieldName)).append("\", ")
                .append(valueExpr).append(");\n");
    }

    @Override
    public String getterCall(String receiverExpr, String getterName)
    {
        // getterName is "_X" — strip the leading underscore to recover the Pure
        // property name. Subclasses that re-prefix the Pure side (e.g. p_X for
        // Any-collision) should leave the `p_` prefix intact; do NOT strip it here.
        String prop = getterName.startsWith("_") ? getterName.substring(1) : getterName;
        return "PureObj.read(" + receiverExpr + ", \"" + prop + "\")";
    }

    // -------------------- subclass extension points --------------------

    /** Map a DSL simple type name to its full Pure path. */
    protected abstract String pureTypePath(String pureType);

    /** True when the DSL-declared {@code simpleName} is a Pure type that maps to
     *  {@code Object} at the Java declaration level. */
    protected abstract boolean isAbstractName(String simpleName);

    /** Rewrite a DSL field name into the actual Pure property name. Defaults to identity. */
    protected String propertyNameFor(String dslFieldName) { return dslFieldName; }

    // -------------------- internal --------------------

    /**
     * Rewrite a DSL-declared type to its Java declaration form. Any subclass-
     * managed simple name (with or without {@code Impl} suffix) collapses to
     * {@code Object}. Primitives and ANTLR runtime types stay verbatim.
     * Parameterised types recurse on their type arguments.
     *
     * <p>The {@code Impl} suffix here is the DSL convention (see
     * {@code pure-language-mappings.dsl}) — distinct from the renamed
     * codegen output suffix ({@code PDBHelper}). The DSL writes
     * {@code let TImpl __r = newImpl(T, ...)} and the strip-suffix step
     * collapses {@code TImpl} to {@code T} before the abstract-name lookup.</p>
     */
    private String mapDeclType(String dslType)
    {
        if (dslType == null) return "Object";
        String t = dslType.trim();
        int lt = t.indexOf('<');
        if (lt >= 0 && t.endsWith(">"))
        {
            String outer = t.substring(0, lt);
            String inner = t.substring(lt + 1, t.length() - 1);
            return outer + "<" + mapDeclType(inner) + ">";
        }
        if (t.contains(","))
        {
            String[] parts = t.split(",");
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < parts.length; i++)
            {
                if (i > 0) out.append(", ");
                out.append(mapDeclType(parts[i]));
            }
            return out.toString();
        }
        String key = t.endsWith("Impl") ? t.substring(0, t.length() - "Impl".length()) : t;
        if (isAbstractName(key)) return "Object";
        if (PRESERVED_NAMES.contains(t)) return t;
        // Unknown — preserve verbatim. Likely a parameter name like 'String' inside a more
        // complex type already split out, or a fully qualified Java type we don't need to remap.
        return t;
    }
}
