package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.Node;
import org.finos.legend.pure.truffle.runtime.PropertyAccessor;

/**
 * Pure {@code $obj.foo} property dispatch. All generated metamodel classes
 * (XImpl + XFlatBufferWrapper) implement {@link PropertyAccessor#readProperty}
 * — a single virtual call into a generated {@code switch} on the property
 * name. Replaces a previous {@link java.lang.invoke.MethodHandle}-based
 * lookup that was opaque to Graal partial evaluation and contributed to
 * "Too deep inlining" compilation failures during self-host.
 *
 * <p>Per-call-site monomorphic inline cache: the first execution stores the
 * receiver's exact class as {@link CompilationFinal}, so subsequent calls of
 * the same shape (the common case for {@code $obj.foo} where the static type
 * is fixed) let Graal devirtualize {@code readProperty} to the cached class's
 * implementation. With {@code propName} also constant at the AST level, the
 * generated {@code switch} inside that class collapses to a single
 * {@code _foo()} accessor call after partial evaluation.</p>
 *
 * <p>Receivers that aren't a {@link PropertyAccessor} (e.g. {@code RawClosure}
 * wrapping a lambda) are unwrapped or fall through to {@link #ABSENT}; no
 * reflection path remains.</p>
 */
public final class PropertyReadNode extends Node
{
    /**
     * Sentinel returned by {@link #executeOrAbsent} when the target has no
     * such property at all (vs. has it and it's empty). Callers that need
     * to distinguish those two cases — e.g. enumeration property access,
     * which falls back to enum-value lookup only when the property doesn't
     * exist — should use {@code executeOrAbsent} and check for this token.
     */
    public static final Object ABSENT = PropertyAccessor.ABSENT;

    /**
     * Property name bound at AST-build time when the call site has a fixed
     * name (the {@link RawPropertyAccessNode} case — every {@code $obj.foo}
     * literal in Pure source). {@code @CompilationFinal} so PE bakes it in
     * per-instance and the {@code readProperty(boundName)} switch in the
     * generated {@code XImpl}/{@code XFlatBufferWrapper} folds to a direct
     * typed accessor (e.g. {@code _foo()}). When {@code null}, the call
     * site is dynamic (e.g. deep-path readers in {@code CopyWithKeysNode}
     * or {@code ToStringNode} where the property is computed) and the
     * back-compat {@code execute(target, propName)} overload uses the
     * runtime argument.
     */
    @CompilationFinal
    private final String boundName;

    /**
     * 2-entry inline cache. Compiler-pure property-read sites overwhelmingly
     * see exactly two classes (the {@code XImpl} compiled in-memory and the
     * {@code XFlatBufferWrapper} loaded from a PDB), so a single-entry cache
     * misses ~half the time and the polymorphic fallthrough can't fold the
     * readProperty(name) switch through interface dispatch. Two slots fit
     * the common case exactly: at most two transferToInterpreterAndInvalidate
     * deopts during warmup, then steady state with both classes hot.
     *
     * <p>An earlier 4-entry attempt regressed by ~5% — the extra deopts cost
     * more than the higher hit rate saved. 2 is empirically the right shape
     * for this workload's class diversity.</p>
     */
    @CompilationFinal
    private Class<?> cachedClass;
    @CompilationFinal
    private Class<?> cachedClass2;

    public PropertyReadNode()
    {
        this.boundName = null;
    }

    public PropertyReadNode(String propertyName)
    {
        this.boundName = propertyName;
    }

    public Object execute(Object target, String propName)
    {
        Object result = executeOrAbsent(target, propName);
        if (result == ABSENT || result == null)
        {
            return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        }
        return result;
    }

    /**
     * Like {@link #execute}, but returns {@link #ABSENT} when the target
     * class has no getter for the property (rather than masking that as an
     * empty sequence).
     */
    public Object executeOrAbsent(Object target, String propName)
    {
        if (target == null)
        {
            return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        }
        if (target instanceof RawClosure rc)
        {
            target = rc.lambda();
        }
        // Prefer the bound name when present — guarantees PE sees a
        // per-instance constant for the readProperty(name) switch even
        // if the caller site doesn't propagate {@code propName} as a PE
        // constant through the call boundary.
        String name = (boundName != null) ? boundName : propName;
        Class<?> tc = target.getClass();
        if (tc == cachedClass || tc == cachedClass2)
        {
            // PE has both cached classes baked in via @CompilationFinal —
            // the disjunction folds to a known-class check at each site,
            // and the readProperty(name) switch reduces to the typed
            // accessor for that class.
            return ((PropertyAccessor) target).readProperty(name);
        }
        return cacheMissOrPoly(target, name);
    }

    private Object cacheMissOrPoly(Object target, String name)
    {
        if (cachedClass == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            if (target instanceof PropertyAccessor pa)
            {
                cachedClass = target.getClass();
                return pa.readProperty(name);
            }
            return ABSENT;
        }
        if (cachedClass2 == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            if (target instanceof PropertyAccessor pa)
            {
                cachedClass2 = target.getClass();
                return pa.readProperty(name);
            }
            return ABSENT;
        }
        // Both slots filled with different classes — third+ class is fully
        // polymorphic. PE can't fold readProperty switch here.
        return target instanceof PropertyAccessor pa ? pa.readProperty(name) : ABSENT;
    }
}
