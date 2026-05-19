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

package org.finos.legend.pure.m3.pointer;

/**
 * Runtime guard for {@code TempCompilerPointer} subtypes: when
 * {@link #STRICT} is enabled, reading any non-pointer-native property on a
 * pointer instance throws — surfacing the bug where compile-pure produced
 * an unresolved pointer and a downstream consumer dereferenced into it as
 * if it were a real element.
 *
 * <p>"Native" here means properties declared on {@code TempCompilerPointer}
 * or one of its descendants (the pointer hierarchy): {@code path} on
 * {@code TempCompilerPointer}, {@code element} on {@code PropertyPointer}
 * and {@code QualifiedPropertyPointer}, etc. Properties inherited from a
 * non-pointer parent ({@code Property._name}, {@code Property._genericType}
 * and the like) are <em>not</em> native — they exist on a real Property
 * instance but are unset on the pointer wrapper. Strict mode throws on
 * those accesses, soft mode (default) returns the field's null/empty
 * default.</p>
 *
 * <p>Strict mode is enabled via the system property
 * {@code legend.pure.strictPointerAccess=true}, set automatically by
 * surefire for {@code mvn test} runs. Production CLI / IDE runs leave the
 * flag off — the JIT folds the {@code if (STRICT) throw} check away once
 * the static-final boolean is observed as false, so there's no hot-path
 * cost.</p>
 *
 * <p>Generated impl classes (see {@code RdfJavaGenerator}) emit a
 * {@link #checkAccess} call at the top of every getter for an inherited
 * property on a {@code @Pointer}-annotated class.</p>
 */
public final class PointerAccessGuard
{
    /**
     * When {@code true}, accessing a non-pointer-native property on a
     * {@code TempCompilerPointer} subtype throws. Set via system property
     * {@code -Dlegend.pure.strictPointerAccess=true} (surefire sets this
     * for {@code mvn test}).
     */
    public static final boolean STRICT = Boolean.getBoolean("legend.pure.strictPointerAccess");

    private PointerAccessGuard() {}

    /**
     * Called from generated pointer-impl getters for inherited properties.
     * Throws when strict mode is on; no-op otherwise. The static-final
     * {@link #STRICT} read is JIT-folded in production, so the call site
     * is dead in non-strict runs.
     */
    public static void checkAccess(String pointerClass, String property)
    {
        if (STRICT)
        {
            throw new IllegalStateException(
                    "Inherited property '" + property + "' read on pointer '"
                            + pointerClass
                            + "' — pointer must be dereferenced before non-pointer-native fields are read."
                            + " (See PointerAccessGuard for the strict-mode rationale.)");
        }
    }
}
