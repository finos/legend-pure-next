// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.runtime.PropertyAccessor;
import org.finos.legend.pure.truffle.runtime.dynobj.PureClassInfo;
import org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry;
import org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * Direct {@code $obj.foo} property access — emitted by
 * {@code PureASTBuilder.lowerPropertyAccess} for non-QP, non-enum property
 * reads. Folds to a constant-indexed array load under Graal PE:
 * <pre>
 *   mov rax, [pdo + slots_offset]
 *   mov rax, [rax + 16 + slotIdx*8]
 * </pre>
 *
 * <p>The slot index is baked at AST-build time via
 * {@link PureClassRegistry#globalSlot(String)} — slot indices are <b>global</b>
 * (every class with property "foo" stores it at the same slot N), so resolving
 * once at AST construction yields a constant valid for every receiver subclass.
 * That constant is stored {@code final}, which lets PE fold the array offset
 * to a literal in the compiled method.</p>
 *
 * <p>{@link RawClosure} unwrap remains a runtime-representation bridge — Pure
 * compiler types lambdas as {@code FunctionDefinition} but at runtime a
 * captured lambda is wrapped in {@code RawClosure} which isn't itself a PDO.</p>
 */
@NodeInfo(shortName = "directPropAccess")
public final class DirectPropertyAccessNode extends PureNode
{
    @Child
    private PureNode targetNode;

    @CompilationFinal
    private final String propertyName;

    /** Slot index — global, baked at AST build. {@link PureClassInfo} guarantees
     *  inheritance-preserved indices, so this int is valid for every subclass
     *  receiver. {@code final} so PE folds the array offset to a literal. */
    private final int slot;

    public DirectPropertyAccessNode(PureNode targetNode, String propertyName)
    {
        this.targetNode = targetNode;
        this.propertyName = propertyName;
        this.slot = PureClassRegistry.globalSlot(propertyName);
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object target = targetNode.executeGeneric(frame);
        if (target instanceof PureDynamicObject pdo)
        {
            Object v = pdo.readSlot(slot);
            return v == null ? PureSequence.EMPTY : v;
        }
        return slowPath(target);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object slowPath(Object target)
    {
        if (target == null || (target instanceof PureSequence ps && ps.isEmpty()))
        {
            return PureSequence.EMPTY;
        }
        if (target instanceof RawClosure rc)
        {
            Object inner = rc.lambda();
            if (inner instanceof PureDynamicObject pdo)
            {
                Object v = pdo.readSlot(slot);
                return v == null ? PureSequence.EMPTY : v;
            }
            target = inner;
        }
        if (target instanceof PropertyAccessor pa)
        {
            Object v = pa.readProperty(propertyName);
            return v == null || v == PropertyAccessor.ABSENT ? PureSequence.EMPTY : v;
        }
        return PureSequence.EMPTY;
    }
}
