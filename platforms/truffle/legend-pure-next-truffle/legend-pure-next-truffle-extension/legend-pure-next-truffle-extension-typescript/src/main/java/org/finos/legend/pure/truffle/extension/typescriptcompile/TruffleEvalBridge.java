// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.extension.typescriptcompile;

import org.finos.legend.pure.truffle.PureContext;
import org.finos.legend.pure.truffle.PureLanguage;
import org.finos.legend.pure.truffle.ast.PropertyReadNode;
import org.finos.legend.pure.truffle.ast.natives.lang.EvalNode;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.types.PureSequence;
import org.graalvm.polyglot.Value;

import java.util.List;

/**
 * <h2>TEMPORARY — Truffle bridge for reflective invocation from JS.</h2>
 *
 * <p>When the translated TypeScript code reaches a reflectively-obtained Pure
 * callable (a {@code Property} / {@code QualifiedProperty} / {@code
 * FunctionDefinition} / {@code NativeFunction} / {@code LambdaFunction}
 * pointer via {@code <Class>.properties->filter(...)->toOne()->eval(...)}),
 * the JS side has only a {@code __pureResolve} Proxy carrying the Pure path.
 * The Proxy isn't callable, and the JS runtime has no machinery to invoke a
 * Pure callable of any of those five kinds — call dispatch lives in
 * Truffle's {@link EvalNode}.</p>
 *
 * <p>This bridge plugs the gap by exposing one JS-callable host helper
 * (<code>__metadataInvoke(path, args)</code>) that:</p>
 * <ol>
 *   <li>resolves the path to the live Pure value via the active resolver,</li>
 *   <li>marshals JS args back to Pure values through
 *       {@link TypeScriptCompileNatives#toPureValuePublic} (the same
 *       JS→Pure marshaler the top-level {@code invoke} entry uses), and</li>
 *   <li>dispatches via {@link EvalNode#dispatch} — the SAME dispatch path
 *       the Pure runtime's native {@code eval}/{@code evaluate} call. So
 *       all five callable kinds work without any JS-side per-kind
 *       casing.</li>
 * </ol>
 *
 * <p><b>Why this is isolated:</b> the long-term plan is to bootstrap-translate
 * the Pure translator itself to JS. Once that lands, a reflective JS-side
 * invocation translates the callee's body to JS in-process and evals — the
 * Truffle hop becomes unnecessary and this entire class deletes. Keep all
 * bridge-only state and logic here so the day we cut over, deleting one file
 * + removing one binding in {@link TypeScriptCompileNatives#context} closes
 * the chapter.</p>
 */
final class TruffleEvalBridge
{
    private TruffleEvalBridge() {}

    /**
     * JS entry point: {@code __metadataInvoke(path: string, args: any[]): any}.
     *
     * <p>Returns the dispatch result, re-marshaled to JS shape (scalar
     * passes through; PDOs become {@code {__purePath}} stubs;
     * {@link PureSequence} becomes a JS array of the element marshals).</p>
     *
     * @throws RuntimeException if the path is unknown, the value isn't a
     *         dispatchable Pure callable, or the active execute() context is
     *         missing (the bridge is only valid mid-{@code invoke}).
     */
    static Object metadataInvoke(Object pathRaw, Object argsRaw,
                                 TruffleMetadataAccess resolver,
                                 PureContext ctxFromCallback)
    {
        if (!(pathRaw instanceof String path))
        {
            throw new RuntimeException("__metadataInvoke: expected String path, got "
                    + (pathRaw == null ? "null" : pathRaw.getClass().getName()));
        }
        Object fn = TypeScriptCompileNatives.resolveAddressPublic(path, resolver);
        // PolyglotList (when host-access allows it) or Value (raw interop) are
        // both possible shapes for the JS rest-args array `__eval(fn, ...args)`
        // marshals across. Handle both.
        Object[] pureArgs = marshalArgs(argsRaw, resolver);
        // PureLanguage.get(null) returns null on the callback thread — caller
        // passes the snapshot captured at invoke() time.
        if (ctxFromCallback == null)
        {
            throw new RuntimeException("__metadataInvoke called outside an active execute(...) — no PureContext in scope");
        }
        // A fresh PropertyReadNode is fine — the no-arg form has no bound
        // property and uses {@code executeOrAbsent} per call. (The bound-name
        // form would optimise repeat reads, irrelevant for a once-per-invoke
        // path.)
        PropertyReadNode propertyReader = new PropertyReadNode();
        Object result = EvalNode.dispatch(ctxFromCallback, fn, pureArgs, propertyReader);
        return marshalResult(result, path, resolver);
    }

    /**
     * Marshal JS-side rest-args to Pure values. GraalJS hands us a
     * {@link java.util.List} (when host-access is permissive, the default
     * here), a {@link Value} with array elements (raw interop), or null
     * (no args).
     */
    private static Object[] marshalArgs(Object argsRaw, TruffleMetadataAccess resolver)
    {
        if (argsRaw == null) return new Object[0];
        if (argsRaw instanceof List<?> list)
        {
            Object[] out = new Object[list.size()];
            for (int i = 0; i < list.size(); i++)
            {
                Object elem = list.get(i);
                out[i] = elem instanceof Value v
                        ? TypeScriptCompileNatives.toPureValuePublic(v, resolver)
                        : elem;
            }
            return out;
        }
        if (argsRaw instanceof Value v && v.hasArrayElements())
        {
            int n = (int) v.getArraySize();
            Object[] out = new Object[n];
            for (int i = 0; i < n; i++)
            {
                out[i] = TypeScriptCompileNatives.toPureValuePublic(v.getArrayElement(i), resolver);
            }
            return out;
        }
        throw new RuntimeException("__metadataInvoke: expected JS array for args, got "
                + argsRaw.getClass().getName());
    }

    /**
     * Marshal the dispatch result back to JS shape. {@link PureSequence}
     * becomes a {@code Object[]} (GraalJS interops as a JS array);
     * everything else routes through the same single-value marshaler
     * {@link TypeScriptCompileNatives#metadataRead} uses.
     */
    private static Object marshalResult(Object result, String path, TruffleMetadataAccess resolver)
    {
        if (result instanceof PureSequence ps)
        {
            Object[] arr = new Object[ps.size()];
            for (int i = 0; i < ps.size(); i++)
            {
                arr[i] = TypeScriptCompileNatives.marshalSinglePublic(ps.getBoxed(i), path + "$result/" + i, resolver);
            }
            return arr;
        }
        return TypeScriptCompileNatives.marshalSinglePublic(result, path + "$result", resolver);
    }
}
