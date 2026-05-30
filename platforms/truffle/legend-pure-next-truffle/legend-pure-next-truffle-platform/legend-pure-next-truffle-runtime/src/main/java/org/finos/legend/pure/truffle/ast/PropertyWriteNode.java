// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.nodes.Node;
import org.finos.legend.pure.truffle.runtime.PropertyAccessor;

/**
 * Property assignment helper used by construction sites
 * ({@link org.finos.legend.pure.truffle.ast.natives.meta.NewWithKeysNode},
 * {@link org.finos.legend.pure.truffle.ast.natives.meta.CopyWithKeysNode},
 * {@link PropertyAssignNode}) and the reverse-association walker.
 *
 * <p>The receiver is always a {@link PropertyAccessor} — either a
 * {@link org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject} (the
 * universal Pure-object kind) or a hand-written collection implementation
 * (e.g. {@link org.finos.legend.pure.truffle.ast.natives.collection.MapImpl}).
 * Both route through {@code writeProperty(name, value)} which does the
 * per-receiver coercion and storage.</p>
 *
 * <p>{@link #ensureEnumCGT} back-fills {@code classifierGenericType} on Java
 * enum constants assigned to Pure properties — Java {@code Enum} produced
 * outside the Pure compiler (e.g. via a native that returns a codegen'd
 * {@code XEnum} constant) doesn't carry its CGT and downstream type-equality
 * checks depend on it. Gated by an {@code instanceof Enum} fast filter so the
 * boundary call elides on the common write paths.</p>
 */
public final class PropertyWriteNode extends Node
{
    public void execute(Object target, String propName, Object value)
    {
        ((PropertyAccessor) target).writeProperty(propName, value);
    }
}
