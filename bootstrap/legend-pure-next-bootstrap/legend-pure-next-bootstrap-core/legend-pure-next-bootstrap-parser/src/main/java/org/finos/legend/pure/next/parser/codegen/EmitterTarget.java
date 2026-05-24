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

package org.finos.legend.pure.next.parser.codegen;

import java.util.List;

/**
 * Extension point for the M3 visitor-mapping compiler. Each backend (protocol
 * Impl emission, Truffle PureDynamicObject emission, future Rust/TypeScript
 * emitters) implements this interface to control the language-specific bits.
 *
 * The compiler emits all construction in statement form ({@code T __result =
 * construct(...); writeX; return __result;}); the target controls how
 * {@code construct(...)} and {@code writeX} read on the wire.
 */
public interface EmitterTarget
{
    // -------------------- class structure --------------------

    /** Package decl, imports, class decl, accumulator field — everything up to the first build method. */
    void emitClassHeader(StringBuilder sb, String dslFileName);

    /** Closing brace and any trailing helpers. Typically just {@code }\n}. */
    void emitClassFooter(StringBuilder sb);

    /**
     * Visit wrapper for a top-level rule:
     * {@code @Override public Object visit{Name}(ctx) { ... elements.add(__built); return __built; }}.
     * Target controls the declared type for {@code __built}.
     */
    void emitTopLevelVisitWrapper(StringBuilder sb, String visitName, String buildName,
                                  String ctxType, String pureType);

    // -------------------- type mapping --------------------

    /** Java declaration type for a built object of pure-type {@code pureType}. */
    String resultType(String pureType);

    /** Java declaration type when alts produce different concrete types. */
    String abstractType(String dslType);

    /** Java declaration type for user-declared {@code let TYPE NAME = …} and helper return types. */
    String letDeclType(String dslType);

    // -------------------- construction --------------------

    /**
     * Single Java expression that constructs a fresh instance of {@code pureType} with the
     * given field assignments. Each {@code kvs[i]} is {@code [pureFieldName, javaExprForValue]}.
     * Protocol: {@code new TImpl()._k(v)._k(v)}.
     * Truffle:  {@code PureObjBuilder.of("path::T", resolver).put("k", v).put("k", v).build()}.
     */
    String constructExpression(String pureType, List<String[]> kvs);

    /** Unconditional setter as a statement: {@code receiver._setK(value);} (Protocol) or {@code PureObj.write(receiver, "k", value);} (Truffle). */
    void emitSetterStatement(StringBuilder sb, String indent, String receiver, String fieldName, String valueExpr);

    /** {@code if (pred) <setterStatement>}. */
    void emitConditionalSetter(StringBuilder sb, String indent, String predJava,
                               String receiver, String fieldName, String valueExpr);

    // -------------------- list construction --------------------

    /**
     * Java expression for an empty list literal ({@code []} in the DSL).
     * Protocol: {@code Lists.mutable.empty()}.
     * Truffle:  {@code PureSequence.EMPTY}.
     */
    String listExpressionEmpty();

    /**
     * Opening fragment for a non-empty list literal ({@code [a, b, c]} in
     * the DSL). The generator emits {@code listExpressionOpen() + a + ", " +
     * b + ", " + c + listExpressionClose()}.
     *
     * <p>Protocol: {@code Lists.mutable.with(}.
     * Truffle:  {@code new ObjectSequence(new Object[]{}.</p>
     */
    String listExpressionOpen();

    /** Closing fragment for a non-empty list literal — see {@link #listExpressionOpen}. */
    String listExpressionClose();

    /**
     * Wrap an arbitrary value expression that is about to be stored as a
     * Pure-on-target slot value. Called by {@link #constructExpression} for
     * every {@code put("k", value)} fragment and by {@link #emitSetterStatement}
     * for explicit setter calls. Bootstrap targets pass the value through
     * unchanged. Truffle targets wrap a parser-side {@code MutableList} /
     * {@code Iterable} into a {@code PureSequence} — the boundary between the
     * Java parser (which uses Eclipse Collections internally) and the Pure
     * runtime (which only holds {@code PureSequence}).
     */
    String wrapForSlotAssignment(String valueExpr);

    // -------------------- property read --------------------

    /**
     * Read on a built object — invoked when a chain segment whose member name starts with {@code _}
     * is applied to a non-{@code $ctx}, non-{@code $it} primary (i.e. on a built protocol/PDO value).
     * Protocol: {@code receiver._getter()}; Truffle: {@code PureObj.read(receiver, "<pureProp>")}.
     */
    String getterCall(String receiverExpr, String getterName);

    // -------------------- top-level scaffolding --------------------

    /**
     * Emit the {@code dispatchSection} scaffolding (fields, constructor, dispatch method,
     * companion {@code computeFirstNonNewlineLine} helper). Triggered when the DSL uses
     * the {@code dispatchSection} primitive (see {@code top-mappings.dsl}).
     *
     * <p>The default implementation throws — targets that consume DSLs with
     * {@code dispatchSection} must override (e.g. {@code TopLevelProtocolEmitterTarget},
     * {@code TruffleTopLevelEmitterTarget}).</p>
     */
    default void emitDispatchSectionScaffolding(StringBuilder sb, String className)
    {
        throw new UnsupportedOperationException(
                "EmitterTarget " + getClass().getName() + " does not support dispatchSection — "
                        + "use a top-level-aware subclass.");
    }
}
