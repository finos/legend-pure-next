// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.truffle.runtime.module;

import org.finos.legend.pure.truffle.runtime.pdo.PureDynamicObject;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide enum-value PDO singletons, keyed by
 * {@code <enumerationPath>.<valueName>}, plus the identity-keyed reverse
 * map singleton → Pure path of its owning Enumeration. Replaces the
 * generated {@code XEnum} classes (AggregationKindEnum,
 * GenericTypeOperationTypeEnum): same construction — a name-bearing
 * {@link PureDynamicObject} of class
 * {@code meta::pure::metamodel::type::Enum} — but built on demand for ANY
 * enumeration instead of two compile-time-known ones.
 *
 * <p>{@code PureContext#classifierGenericType} consults
 * {@link #enumerationPathOf} when it sees an enum-value PDO with no CGT
 * slot set — the resolver isn't available at mint time, so CGT is built
 * lazily on first read. The reverse map is identity-keyed (all singletons
 * share the same Java class); this replaces the previous
 * derive-Pure-path-from-Java-class-FQN trick that worked when enum values
 * were JVM {@code Enum} constants.</p>
 *
 * <p>Singleton-caching preserves the generated classes' identity semantics:
 * two reads of the same enum-valued property return the SAME PDO, so
 * identity-based comparison and the identity-keyed reverse lookup keep
 * working.</p>
 */
public final class EnumValueSingletons
{
    private static final ConcurrentHashMap<String, PureDynamicObject> VALUES = new ConcurrentHashMap<>();
    private static final Map<PureDynamicObject, String> ENUMERATION_PATH =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private EnumValueSingletons() {}

    public static PureDynamicObject valueOf(String enumerationPath, String valueName)
    {
        return VALUES.computeIfAbsent(enumerationPath + "." + valueName,
                k -> makeValue(enumerationPath, valueName));
    }

    private static PureDynamicObject makeValue(String enumerationPath, String valueName)
    {
        PureDynamicObject pdo = new PureDynamicObject(
                PureClassRegistry.enumClassInfo(), null, null, null);
        pdo.writeProperty("name", valueName);
        register(pdo, enumerationPath);
        return pdo;
    }

    /** Record the owning Enumeration for a singleton. Also called by
     *  {@code AtomicValueEnumReconstructor} for PDB-decoded enum values. */
    public static void register(PureDynamicObject value, String enumerationPath)
    {
        ENUMERATION_PATH.put(value, enumerationPath);
    }

    /** Pure path of the Enumeration this PDO is a value of, or {@code null}
     *  if {@code value} isn't a registered enum-value singleton. */
    public static String enumerationPathOf(PureDynamicObject value)
    {
        return ENUMERATION_PATH.get(value);
    }
}
