// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.truffle.extension.javascriptcompile;

import org.finos.legend.pure.truffle.PureContext;
import org.finos.legend.pure.truffle.interpreter.ast.property.PropertyReadNode;
import org.finos.legend.pure.truffle.interpreter.ast.natives.lang.EvalNode;
import org.finos.legend.pure.truffle.runtime.module.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.types.PureSequence;
import org.graalvm.polyglot.Value;

import java.util.List;

/**
 * <h2>TEMPORARY — Truffle bridge for reflective invocation from JS.</h2>
 *
 * <p>When the translated JavaScript code reaches a reflectively-obtained Pure
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
 *       {@link JavaScriptCompileNatives#toPureValuePublic} (the same
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
 * + removing one binding in {@link JavaScriptCompileNatives#context} closes
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
        // PureLanguage.get(null) returns null on the callback thread — caller
        // passes the snapshot captured at invoke() time.
        if (ctxFromCallback == null)
        {
            throw new RuntimeException("__metadataInvoke called outside an active execute(...) — no PureContext in scope");
        }
        // The whole body runs with the Pure context entered: argument
        // marshaling (liftToPdo → cgtForType), dispatch, and result
        // marshaling all rely on the static PureLanguage.get(...) lookups.
        return enteringPure(ctxFromCallback, () ->
        {
            Object fn = JavaScriptCompileNatives.resolveAddressPublic(path, resolver);
            // PolyglotList (when host-access allows it) or Value (raw interop) are
            // both possible shapes for the JS rest-args array `__eval(fn, ...args)`
            // marshals across. Handle both.
            Object[] pureArgs = marshalArgs(argsRaw, resolver);
            // A fresh PropertyReadNode is fine — the no-arg form has no bound
            // property and uses {@code executeOrAbsent} per call. (The bound-name
            // form would optimise repeat reads, irrelevant for a once-per-invoke
            // path.)
            PropertyReadNode propertyReader = new PropertyReadNode();
            Object result = EvalNode.dispatch(ctxFromCallback, fn, pureArgs, propertyReader);
            return marshalResult(result, path, resolver);
        });
    }

    /**
     * Run {@code body} with the Pure Truffle context entered. On the GraalJS
     * callback thread the innermost entered context is the JS one, so the
     * static {@code PureLanguage.get(...)}/{@code getContext()} lookups the
     * interpreter relies on (AST lowering, {@code RawUserFunctionCallNode},
     * lambda compilation) all return null unless we re-enter Pure's context
     * first. Entering is reentrant-safe: the CLI thread already holds it
     * further down the stack.
     */
    private static Object enteringPure(PureContext ctx, java.util.function.Supplier<Object> body)
    {
        com.oracle.truffle.api.TruffleContext tc = ctx.truffleContext();
        if (tc == null)
        {
            return body.get();
        }
        Object prev = tc.enter(null);
        try
        {
            return body.get();
        }
        finally
        {
            tc.leave(null, prev);
        }
    }

    /**
     * __hostCompileSource bridge: marshal the JS-side PureFile and dependency
     * names to Pure values, run the SAME implementation as the interpreter's
     * compileSource native ({@link org.finos.legend.pure.truffle.interpreter.ast.natives.meta.CompileSourceNode}),
     * and marshal the CompileSourceResult back to the GraalJS side.
     */
    static Object compileSource(Object fileRaw, Object depsRaw,
                                TruffleMetadataAccess resolver,
                                PureContext ctxFromCallback)
    {
        if (ctxFromCallback == null)
        {
            throw new RuntimeException("__hostCompileSource called outside an active execute(...) — no PureContext in scope");
        }
        // The whole body runs with the Pure context entered: marshaling in
        // (liftToPdo → cgtForType), compiling, and marshaling out all rely
        // on the static PureLanguage.get(...) lookups.
        return enteringPure(ctxFromCallback, () ->
        {
            Object file = toPure(fileRaw, resolver);
            // A Pure String[*] with one element crosses the boundary as a bare
            // string (the translated call site passes the scalar directly), not
            // an array — normalize before the array-shaped marshaling.
            Object[] deps;
            if (depsRaw == null)
            {
                deps = new Object[0];
            }
            else if (depsRaw instanceof String s)
            {
                deps = new Object[]{s};
            }
            else if (depsRaw instanceof Value v && v.isString())
            {
                deps = new Object[]{v.asString()};
            }
            else
            {
                deps = marshalArgs(depsRaw, resolver);
            }
            Object result = org.finos.legend.pure.truffle.interpreter.ast.natives.meta.CompileSourceNode
                    .doCompile(ctxFromCallback, file, deps);
            // Register the CompileSourceResult as a dynamic graph root: the JS
            // side gets a `__dyn::<n>` stub, and every downstream read
            // (`.elements`, filter/instanceOf via classifiers, evaluate via
            // __metadataInvoke -> EvalNode.dispatch) walks the live PDO graph
            // through the generic sub-address resolver — same machinery as the
            // `__local::root` canonical-lambda injection.
            String root = JavaScriptCompileNatives.registerDynamicGraph(result);
            return JavaScriptCompileNatives.marshalSinglePublic(result, root, resolver);
        });
    }

    /**
     * Marshal JS-side rest-args to Pure values. GraalJS hands us a
     * {@link java.util.List} (when host-access is permissive, the default
     * here), a {@link Value} with array elements (raw interop), or null
     * (no args).
     */
    /**
     * Marshal one JS-side value to Pure shape. GraalJS host interop hands
     * {@code Object}-typed parameters to us in THREE shapes: a raw
     * {@link Value} (raw interop), a host proxy ({@code PolyglotMap} /
     * {@code PolyglotList} — a JS object/array auto-wrapped as {@code Map} /
     * {@code List}), or an already-host primitive (String, Long, …).
     * {@link Value#asValue} restores the original guest Value from a proxy,
     * so proxies route through the same marshaler instead of leaking raw
     * host maps into Pure (where every property read comes back empty).
     */
    private static Object toPure(Object raw, TruffleMetadataAccess resolver)
    {
        if (raw instanceof Value v)
        {
            return JavaScriptCompileNatives.toPureValuePublic(v, resolver);
        }
        if (raw instanceof java.util.Map<?, ?> || raw instanceof List<?>)
        {
            return JavaScriptCompileNatives.toPureValuePublic(Value.asValue(raw), resolver);
        }
        return raw;
    }

    private static Object[] marshalArgs(Object argsRaw, TruffleMetadataAccess resolver)
    {
        if (argsRaw == null) return new Object[0];
        if (argsRaw instanceof List<?> list)
        {
            Object[] out = new Object[list.size()];
            for (int i = 0; i < list.size(); i++)
            {
                out[i] = toPure(list.get(i), resolver);
            }
            return out;
        }
        if (argsRaw instanceof Value v && v.hasArrayElements())
        {
            int n = (int) v.getArraySize();
            Object[] out = new Object[n];
            for (int i = 0; i < n; i++)
            {
                out[i] = JavaScriptCompileNatives.toPureValuePublic(v.getArrayElement(i), resolver);
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
     * {@link JavaScriptCompileNatives#metadataRead} uses.
     */
    private static Object marshalResult(Object result, String path, TruffleMetadataAccess resolver)
    {
        if (result instanceof PureSequence ps)
        {
            Object[] arr = new Object[ps.size()];
            for (int i = 0; i < ps.size(); i++)
            {
                arr[i] = JavaScriptCompileNatives.marshalSinglePublic(ps.getBoxed(i), path + "$result/" + i, resolver);
            }
            return arr;
        }
        return JavaScriptCompileNatives.marshalSinglePublic(result, path + "$result", resolver);
    }
}
