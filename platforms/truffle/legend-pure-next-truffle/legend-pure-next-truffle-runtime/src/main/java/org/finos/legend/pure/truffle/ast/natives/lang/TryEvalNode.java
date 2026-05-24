// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.ast.natives.lang;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawClosure;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;
import org.finos.legend.pure.truffle.runtime.PureStackFormatter;
import org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * {@code tryEval(Function<{->V[m]}>[1]) : TryResult<V|m>[1]} — run the closure
 * and return a TryResult carrying either the closure's return (success) or a
 * captured Error with message + Pure stack frames (failure).
 *
 * <p>Used by the Pure test runners ({@code runTests}, {@code runPCTTests}) so
 * a single failing test doesn't unwind the whole run.</p>
 */
@NodeInfo(shortName = "tryEval")
public final class TryEvalNode extends PureNode
{
    private static final String TRY_RESULT_PATH = "meta::pure::functions::lang::TryResult";
    private static final String ERROR_PATH = "meta::pure::functions::lang::Error";
    private static final String LAMBDA_PATH = "meta::pure::metamodel::function::LambdaFunction";
    private static final String STACK_HEADER = "\nPure stack trace:";

    @Child
    private PureNode fnArg;

    @Child
    private RawLambdaCallNode bodyCallNode = new RawLambdaCallNode();

    public TryEvalNode(PureNode fnArg)
    {
        this.fnArg = fnArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return doTryEval(fnArg.executeGeneric(frame), getResolver());
    }

    @TruffleBoundary
    private Object doTryEval(Object rawFn, TruffleMetadataAccess resolver)
    {
        Object tryResult = TruffleInstanceFactory.createInstance(TRY_RESULT_PATH, resolver);
        try
        {
            Object value = invokeClosure(rawFn);
            PureObj.write(tryResult, "value", value);
        }
        catch (Throwable t)
        {
            PureObj.write(tryResult, "failure", buildError(t, resolver));
        }
        return tryResult;
    }

    private Object invokeClosure(Object rawFn)
    {
        if (rawFn instanceof RawClosure rc)
        {
            return bodyCallNode.call(rc);
        }
        if (PureObj.pureTypeIs(rawFn, LAMBDA_PATH))
        {
            return bodyCallNode.call(new RawClosure(rawFn, new Object[0], new String[0], null));
        }
        throw new RuntimeException("tryEval: argument is not a function: "
                + (rawFn == null ? "null" : rawFn.getClass().getName()));
    }

    private static Object buildError(Throwable t, TruffleMetadataAccess resolver)
    {
        Object error = TruffleInstanceFactory.createInstance(ERROR_PATH, resolver);
        String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        // Strip "\nPure stack trace:..." suffix — frames live in Error.stack
        // now; the message shouldn't duplicate the stack.
        int idx = msg.indexOf(STACK_HEADER);
        if (idx >= 0)
        {
            msg = msg.substring(0, idx);
        }
        PureObj.write(error, "message", msg);
        java.util.List<String> frames = PureStackFormatter.frames(t);
        PureObj.write(error, "stack",
                frames.isEmpty() ? PureSequence.EMPTY : new ObjectSequence(frames.toArray()));
        return error;
    }
}
