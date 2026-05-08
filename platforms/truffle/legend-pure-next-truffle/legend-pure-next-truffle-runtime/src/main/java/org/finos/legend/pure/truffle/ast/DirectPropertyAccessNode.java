// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.runtime.PropertyAccessor;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * Direct {@code $obj.foo} property access AST node — a self-contained
 * {@link PureNode} with the receiver as a single {@code @Child} (no
 * args[] allocation per call) and the property name baked in as
 * {@code @CompilationFinal} (PE folds the {@code readProperty} switch
 * to a typed accessor at every site).
 *
 * <p>Replaces the helper pattern of {@code RawPropertyAccessNode} →
 * {@link PropertyReadNode} for the common case (non-QP, non-enum target).
 * For the cached path PE produces machine code equivalent to a direct
 * Java field load:
 * <pre>
 *   mov rax, [target_class_pointer]   ; class word
 *   cmp rax, cached_class             ; class identity
 *   jne slow                          ; rare: fall to enum/QP/poly path
 *   mov rax, [target+offset_of_foo]   ; field load (PE-folded readProperty switch)
 *   ret
 * </pre>
 *
 * <p>Enum-target / RawClosure / null-target cases fall through to
 * {@link RawPropertyAccessNode}-equivalent slow logic via
 * {@link #slowPath(Object)} — the AST builder uses this node only when
 * the static type can't be an Enumeration; for enumerations it emits
 * the full {@code RawPropertyAccessNode}.</p>
 */
@NodeInfo(shortName = "directPropAccess")
public final class DirectPropertyAccessNode extends PureNode
{
    @Child
    private PureNode targetNode;

    @CompilationFinal
    private final String propertyName;

    @CompilationFinal
    private Class<?> cachedClass;

    @CompilationFinal
    private Class<?> cachedClass2;

    public DirectPropertyAccessNode(PureNode targetNode, String propertyName)
    {
        this.targetNode = targetNode;
        this.propertyName = propertyName;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object target = targetNode.executeGeneric(frame);
        if (target == null
                || (target instanceof PureSequence ps && ps.isEmpty()))
        {
            return PureSequence.EMPTY;
        }
        Class<?> tc = target.getClass();
        // Bi-morphic class cache — compiler-pure property sites typically
        // see XImpl (built in-memory) and XFlatBufferWrapper (loaded from
        // PDB). Both fast paths PE-fold to a direct readProperty call
        // with the constant propertyName, which then folds to a typed
        // accessor (e.g. _rawType()) and ultimately a single field load.
        if (tc == cachedClass || tc == cachedClass2)
        {
            Object r = ((PropertyAccessor) target).readProperty(propertyName);
            return (r == PropertyAccessor.ABSENT || r == null) ? PureSequence.EMPTY : r;
        }
        return slowPath(target);
    }

    private Object slowPath(Object target)
    {
        // RawClosure unwrap — closures over lambda functions don't implement
        // PropertyAccessor; we delegate to the underlying lambda for property
        // access (e.g. $closure.expressionSequence in test code).
        if (target instanceof RawClosure rc)
        {
            target = rc.lambda();
            if (target == null) return PureSequence.EMPTY;
        }
        if (cachedClass == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            if (target instanceof PropertyAccessor pa)
            {
                cachedClass = target.getClass();
                Object r = pa.readProperty(propertyName);
                return (r == PropertyAccessor.ABSENT || r == null) ? PureSequence.EMPTY : r;
            }
            return PureSequence.EMPTY;
        }
        if (cachedClass2 == null && target.getClass() != cachedClass)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            if (target instanceof PropertyAccessor pa)
            {
                cachedClass2 = target.getClass();
                Object r = pa.readProperty(propertyName);
                return (r == PropertyAccessor.ABSENT || r == null) ? PureSequence.EMPTY : r;
            }
            return PureSequence.EMPTY;
        }
        // Both slots filled with different classes — third+ class is fully
        // polymorphic. PE can't fold readProperty switch here.
        if (target instanceof PropertyAccessor pa)
        {
            Object r = pa.readProperty(propertyName);
            return (r == PropertyAccessor.ABSENT || r == null) ? PureSequence.EMPTY : r;
        }
        return PureSequence.EMPTY;
    }
}
