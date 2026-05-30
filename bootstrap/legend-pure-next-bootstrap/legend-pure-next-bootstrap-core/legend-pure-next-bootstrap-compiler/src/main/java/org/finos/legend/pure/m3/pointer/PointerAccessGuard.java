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
 * Runtime guard for {@code TempCompilerPointer} subtypes: reading any
 * non-pointer-native property on a pointer instance throws — surfacing the
 * bug where compile-pure produced an unresolved pointer and a downstream
 * consumer dereferenced into it as if it were a real element.
 *
 * <p>"Native" here means properties declared on {@code TempCompilerPointer}
 * or one of its descendants (the pointer hierarchy): {@code path} on
 * {@code TempCompilerPointer}, {@code element} on {@code PropertyPointer}
 * and {@code QualifiedPropertyPointer}, etc. Properties inherited from a
 * non-pointer parent ({@code Property._name}, {@code Property._genericType}
 * and the like) are <em>not</em> native — they exist on a real Property
 * instance but are unset on the pointer wrapper. Reading those always
 * throws, in every backend, in every build.</p>
 *
 * <p>The guard is unconditional by design: a toggle would let buggy code
 * paths silently bypass it (which is exactly how the
 * {@code test-pure-self-host} regression hid a class of compile-pure bugs
 * until 2026-05-27). Any access that lands here is a real bug — fix at the
 * producer (wrap the right pointer type at construction) or at the consumer
 * (dereference via {@code derefTypeIfPointer} inside compile-pure, or rely
 * on the {@code resolveAndReturnGraph} native that fires at compile()'s end
 * so post-compile consumers never see pointers in the first place).</p>
 *
 * <p>Generated impl classes (see {@code RdfJavaGenerator}) emit a
 * {@link #checkAccess} call at the top of every getter for an inherited
 * property on a {@code @Pointer}-annotated class.</p>
 */
public final class PointerAccessGuard
{
    private PointerAccessGuard() {}

    /**
     * Called from generated pointer-impl getters for inherited properties.
     * Throws unless the property is framework-required (currently only
     * {@code classifierGenericType}, needed by every dispatch — match /
     * genericType / subtypeOf — even on pointer instances). All other
     * inherited reads on a pointer indicate a missing upstream deref.
     */
    public static void checkAccess(String pointerClass, String property)
    {
        // Framework-required slots — dispatch + subtypeOf need to read these
        // on every value, pointer included. Mirrors the Truffle whitelist in
        // PureDynamicObject.isPointerNativeSlot.
        //   - classifierGenericType: returns whatever the producer set (null
        //     on bare to*Pointer() instances); downstream getRawValueType
        //     falls back to Any.
        //   - generalizations: returns empty on a bare pointer — the
        //     semantically correct answer (a pointer instance has no real
        //     ancestors at the Pure-data level; its metaclass hierarchy is
        //     walked separately when the live target is reached). subtypeOf
        //     on a pointer thus correctly returns false for non-trivial sup,
        //     matching the pre-strict-guard behavior.
        if ("classifierGenericType".equals(property) || "generalizations".equals(property))
        {
            return;
        }
        throw new IllegalStateException(
                "Inherited property '" + property + "' read on pointer '"
                        + pointerClass
                        + "' — pointer must be dereferenced before non-pointer-native fields are read."
                        + " (See PointerAccessGuard for the rationale.)");
    }
}
