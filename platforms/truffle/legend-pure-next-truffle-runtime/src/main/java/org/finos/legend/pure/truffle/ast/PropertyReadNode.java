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

    @CompilationFinal
    private Class<?> cachedClass;

    public Object execute(Object target, String propName)
    {
        Object result = executeOrAbsent(target, propName);
        return result == ABSENT ? org.finos.legend.pure.truffle.types.PureSequence.EMPTY : result;
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
        // RawClosure wraps a LambdaFunction in a plain record (capturedValues,
        // callTarget, ...) and doesn't implement the LambdaFunction interface.
        // Delegate property reads to the underlying lambda — otherwise
        // `$closure.expressionSequence` etc. silently return EMPTY, which made
        // testShortCircuitInDynamicEvaluation observe a lambda with an empty
        // body when it copied `$fn.expressionSequence` to ^LambdaFunction(...).
        if (target instanceof RawClosure rc)
        {
            target = rc.lambda();
        }
        Class<?> tc = target.getClass();
        if (tc == cachedClass)
        {
            // PE knows tc is the cached class — the cast then devirtualizes
            //   readProperty to that exact class's implementation, and with
            //   constant propName the inner switch collapses to a single
            //   typed accessor call.
            return ((PropertyAccessor) target).readProperty(propName);
        }
        if (cachedClass == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            // Only cache when the target actually implements PropertyAccessor
            // — caching a non-PropertyAccessor class (e.g. a Java enum like
            //   GenericTypeOperationTypeEnum, which the metamodel uses for
            //   relation operations) would cause the fast-path cast above to
            //   fail with ClassCastException on subsequent calls of the same
            //   shape.
            if (target instanceof PropertyAccessor pa)
            {
                cachedClass = tc;
                return pa.readProperty(propName);
            }
            return ABSENT;
        }
        // Polymorphic call site — virtual dispatch through the interface.
        return target instanceof PropertyAccessor pa ? pa.readProperty(propName) : ABSENT;
    }
}
