// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.parser.shared;

import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * Boundary helper used by the generated Truffle parser to convert a
 * parser-internal Java collection (Eclipse Collections {@code MutableList},
 * {@code Iterable}, plain {@code List}, or {@code Object[]}) into a
 * {@link PureSequence} immediately before it is stored into a PDO slot.
 *
 * <p>The Pure-on-Truffle parser's Java code uses Eclipse Collections
 * (ANTLR yields {@code List}, helpers like {@code ListAdapter.adapt(...).collect(...)}
 * return {@code MutableList}). Pure PDO slots only hold {@link PureSequence}.
 * Rather than coerce inside the runtime ({@code PureObjBuilder.put} stays
 * neutral about types), the Truffle target's {@code constructExpression}
 * wraps each value with {@link #wrap} at the put call site. The conversion
 * is therefore part of the parser-emitted code — a single, target-specific
 * bridge between the Java collection world and the Pure sequence world.</p>
 */
public final class ParserSlotValue
{
    private ParserSlotValue() {}

    public static Object wrap(Object value)
    {
        if (value == null) return null;
        if (value instanceof PureSequence) return value;
        if (value instanceof org.eclipse.collections.api.list.MutableList<?> ml)
        {
            return new ObjectSequence(ml.toArray());
        }
        if (value instanceof java.util.List<?> list)
        {
            return new ObjectSequence(list.toArray());
        }
        if (value instanceof Iterable<?> it)
        {
            java.util.ArrayList<Object> tmp = new java.util.ArrayList<>();
            for (Object e : it) tmp.add(e);
            return new ObjectSequence(tmp.toArray());
        }
        return value;
    }
}
