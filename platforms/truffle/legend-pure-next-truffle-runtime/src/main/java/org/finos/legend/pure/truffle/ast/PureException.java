package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

/**
 * Truffle-native exception for Pure runtime errors.
 * Carries the source Node location so Truffle builds proper guest language stack traces
 * with Pure file:line information.
 *
 * Use subclasses for specific error categories:
 * <ul>
 *   <li>{@link AssertionError} — Pure assert/assertEquals failures</li>
 *   <li>{@link PureException} — general runtime errors (unknown variable, toOne, cast, etc.)</li>
 * </ul>
 */
public class PureException extends AbstractTruffleException
{
    public PureException(String message, Node location)
    {
        super(message, location);
    }

    /** Pure assertion failure — thrown by assert/assertEquals/assertIs. */
    public static final class AssertionError extends PureException
    {
        public AssertionError(String message, Node location)
        {
            super(message, location);
        }
    }
}
