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

package org.finos.legend.pure.truffle.runtime.dynobj;

import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;

/**
 * Rebuild an Enum-value {@link PureDynamicObject} from the path-string the
 * bootstrap PDB writer emits for {@code AtomicValue.value} when the held
 * value is a Pure Enum instance.
 *
 * <p>The bootstrap writer encodes an Enum instance as a {@code StringValueDef}
 * containing {@code "Owner::Enumeration.ValueName"} (or just
 * {@code "ValueName"} when the owner type was unknown at write time). No
 * dedicated FlatBuffer union member exists for enum values — Enum PDOs are
 * too small to pay the table overhead, and the union is otherwise reserved
 * for primitive literals and structured lambdas. The Java-direct wrapper
 * does the reconstruction inline; the Truffle generated
 * {@code XPDBHelper.__load_value} delegates here so the AST stays small.</p>
 *
 * <p>The {@code classifierGenericType} slot is intentionally left unset on
 * the reconstructed PDO — instead we register the value with
 * {@link PureEnumRegistry}, which {@code PureContext#classifierGenericType}
 * consults to lazily build the CGT on first read. This matches the
 * codegen-emitted singleton enum-value pattern; the reconstructor reuses
 * the same lazy-CGT path rather than depending on the runtime-side
 * {@code _GenericType} helper (which would create a codegen→runtime cycle).</p>
 *
 * <p>Invariants on the input:
 * <ul>
 *   <li>{@code rawType} is whatever {@code AtomicValue.genericType.rawType}
 *       resolved to — usually an Enumeration PDO, sometimes {@code null} (the
 *       writer didn't know the type), and in primitive cases a String/Integer/…
 *       primitive type PDO. Only the Enumeration case triggers reconstruction;
 *       everything else returns the raw string unchanged.</li>
 *   <li>{@code raw} is non-null when called from generated code (the call
 *       site guards with {@code if (d != null)}).</li>
 * </ul>
 */
public final class AtomicValueEnumReconstructor
{
    private static final String ENUMERATION_PURE_PATH = "meta::pure::metamodel::type::Enumeration";
    private static final String ENUM_PURE_PATH = "meta::pure::metamodel::type::Enum";

    private AtomicValueEnumReconstructor() {}

    /**
     * @param raw           the StringValueDef payload — either the bare value
     *                      name or {@code Owner.ValueName}
     * @param genericType   the AtomicValue's {@code genericType} property —
     *                      a PureDynamicObject or PropertyAccessor whose
     *                      {@code rawType} we extract. {@code null} means the
     *                      writer skipped the type, in which case we pass the
     *                      raw string through unchanged.
     * @param resolver      cross-module resolver; used to look up the
     *                      Enumeration's canonical path.
     */
    public static Object reconstruct(String raw, Object genericType, TruffleMetadataAccess resolver)
    {
        if (raw == null) return null;
        // legend-pure-next stores the type as `type` on GenericTypeValue
        // subtypes (UserDefinedGenericType, InferredGenericType, …). Older
        // metamodel callers reach for `rawType`, but that's a Pure-level
        // dispatch helper (genericTypeRawType) — at the FBS / slot level the
        // property is `type`. Other GenericType subtypes
        // (CompilerNotSetGenericType, GenericTypeOperation, …) don't carry a
        // type at all, and readSlot returns null for them.
        Object enumType = readSlot(genericType, "type");
        if (!(enumType instanceof PureDynamicObject pdoType)
                || !ENUMERATION_PURE_PATH.equals(pdoType.purePath()))
        {
            // Not an enum-typed AtomicValue — the union value really is a
            // String literal. Pass through unchanged.
            return raw;
        }
        int dot = raw.lastIndexOf('.');
        String valueName = dot < 0 ? raw : raw.substring(dot + 1);
        String enumerationPath = resolver == null ? null : resolver.pathOf(pdoType);
        return buildEnumValue(valueName, enumerationPath, resolver);
    }

    private static Object readSlot(Object holder, String name)
    {
        if (holder instanceof PureDynamicObject pdo) return pdo.readProperty(name);
        if (holder instanceof org.finos.legend.pure.truffle.runtime.PropertyAccessor pa) return pa.readProperty(name);
        return null;
    }

    private static Object buildEnumValue(String valueName, String enumerationPath,
                                         TruffleMetadataAccess resolver)
    {
        PureDynamicObject pdo = new PureDynamicObject(
                PureClassRegistry.classInfoFor(ENUM_PURE_PATH, resolver),
                null, resolver, null);
        pdo.writeProperty("name", valueName);
        // Register with PureEnumRegistry so PureContext can lazily compute
        // classifierGenericType when callers do cast(@Enum) or instanceOf(Enum).
        // Skipped when the Enumeration's path can't be derived — the consumer
        // still gets a name-bearing PDO, only the typed-cast path degrades.
        if (enumerationPath != null)
        {
            PureEnumRegistry.register(pdo, enumerationPath);
        }
        return pdo;
    }
}
