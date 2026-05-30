// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime.dynobj;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Collections;

/**
 * Identity-keyed registry: {@link PureDynamicObject} enum-value singleton →
 * Pure path of its owning Enumeration. Populated at codegen class-load via
 * each generated {@code XEnum}'s static block; consumed by
 * {@link org.finos.legend.pure.truffle.PureContext#classifierGenericType}
 * when it sees an enum-value PDO with no CGT slot set — the resolver isn't
 * available at class-load, so CGT can't be built then.
 *
 * <p>Replaces the previous derive-Pure-path-from-Java-class-FQN trick that
 * worked when enum values were JVM {@code Enum} constants. PDO singletons
 * all share the same Java class, so identity-keyed lookup is the only
 * remaining bridge.</p>
 */
public final class PureEnumRegistry
{
    private PureEnumRegistry() {}

    private static final Map<PureDynamicObject, String> ENUMERATION_PATH =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /** Called from generated {@code XEnum.makeValue} for each singleton. */
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
