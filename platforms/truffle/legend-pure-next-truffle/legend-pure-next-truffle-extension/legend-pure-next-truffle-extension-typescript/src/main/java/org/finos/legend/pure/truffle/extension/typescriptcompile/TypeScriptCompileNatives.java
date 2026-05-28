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

import org.finos.legend.pure.truffle.types.PureSequence;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * TypeScript runtime bridge. Two phases:
 *
 * <ul>
 *   <li>{@link #compile} — {@code ts.transpileModule(src, …)} → eval the
 *       resulting JS as a CommonJS module → return a
 *       {@link TypeScriptCompilationContext} wrapping {@code module.exports}.</li>
 *   <li>{@link #invoke} — look up {@code fnName} on the captured exports,
 *       call with args, map the return value back to Pure types.</li>
 * </ul>
 *
 * <p>Holds a singleton GraalJS {@link Context} with the official
 * {@code typescript.js} compiler pre-loaded. Also binds a global
 * {@code __pureResolve(path)} helper — the translator's metadata-strategy
 * entry point: emitted source calls {@code __pureResolve('some::path')}
 * (or {@code 'some::path$sub/path/0/@TypeName'} for sub-PE addresses) to
 * fetch a Pure metamodel value at runtime. The handler dispatches to the
 * current Truffle resolver.</p>
 *
 * <p>Return-value mapping: scalars flow as-is; null/undefined → empty
 * sequence; JS objects with {@code classifierGenericType.rawType.path}
 * lift into PDOs of that class (the translator emits this tag on every
 * Pure instance it produces); other objects come back as Java Maps.</p>
 *
 * <p>Thread safety: {@link Context} is not safe for concurrent use, so all
 * entry points synchronize on {@link #LOCK}.</p>
 */
final class TypeScriptCompileNatives
{
    private static final String TYPESCRIPT_JS_RESOURCE = "/typescript.js";
    private static final String RUNTIME_LIB_TS_RESOURCE = "/runtime-lib.ts";

    private static final Object LOCK = new Object();
    private static Context jsContext;
    private static String runtimeLib;

    /** Sentinel address prefix for nodes injected via {@code execute(..., graph)}.
     *  Translator-emitted addresses targeting an in-memory canonical (no PDB
     *  backing) carry this prefix; {@link #resolveAddress} reads from the
     *  thread-local injected graph instead of the resolver. */
    static final String LOCAL_ROOT_ADDRESS = "__local::root";

    /** In-memory graph injected for the duration of {@code execute(...)}.
     *  Set by {@link #invoke} just before the JS call and cleared after, so
     *  {@code __metadataRead} sees it during the call's lifetime. */
    private static final ThreadLocal<Object> INJECTED_GRAPH = new ThreadLocal<>();

    private TypeScriptCompileNatives() {}

    static TypeScriptCompilationContext compile(String source)
    {
        synchronized (LOCK)
        {
            Context ctx = context();
            // Prepend the shared runtime helpers (`__eq`, `__fold`,
            // `__instanceOf`, …) so the translator can emit calls to them
            // without owning the implementation. The lib ships in this
            // extension jar; loading it here keeps Pure code clean of
            // large embedded JS strings.
            ctx.getBindings("js").putMember("__pureTsSource", runtimeLib() + source);
            String js = transpile(ctx);
            return new TypeScriptCompilationContext(evalToExports(ctx, js));
        }
    }

    static Object invoke(TypeScriptCompilationContext compCtx, String fnName, Object[] args, Object graph)
    {
        synchronized (LOCK)
        {
            Value fn = compCtx.moduleExports.getMember(fnName);
            if (fn == null || !fn.canExecute())
            {
                throw new RuntimeException("Export '" + fnName + "' is not a function (got "
                        + (fn == null ? "undefined" : fn) + ")");
            }
            INJECTED_GRAPH.set(graph);
            try
            {
                return toPureValue(fn.execute(args), currentResolver());
            }
            finally
            {
                INJECTED_GRAPH.remove();
            }
        }
    }

    private static String runtimeLib()
    {
        if (runtimeLib == null)
        {
            try (InputStream in = TypeScriptCompileNatives.class.getResourceAsStream(RUNTIME_LIB_TS_RESOURCE))
            {
                if (in == null)
                {
                    throw new RuntimeException(
                            "runtime-lib.ts not found on classpath at '" + RUNTIME_LIB_TS_RESOURCE +
                            "'. The pom copies it from pure/modules/translation/typescript/code/runtime-lib.ts " +
                            "at process-resources; check that resource was packaged.");
                }
                runtimeLib = new String(in.readAllBytes(), StandardCharsets.UTF_8) + "\n";
            }
            catch (IOException e)
            {
                throw new RuntimeException("Failed to read bundled runtime-lib.ts", e);
            }
        }
        return runtimeLib;
    }

    private static Context context()
    {
        if (jsContext == null)
        {
            Context c = Context.newBuilder("js")
                    .allowHostAccess(HostAccess.ALL)
                    .allowAllAccess(true)
                    .build();
            c.eval(typescriptCompilerSource());
            // Metadata-strategy entry points (JS-native API — see
            // runtime-lib.ts header). The JS-side `__pureResolve` Proxy
            // routes property reads through `__metadataRead`. All three
            // are designed to be backed by either this Truffle bridge or
            // an in-JS PDB reader (standalone TS platform).
            c.getBindings("js").putMember("__metadataRead",
                    (java.util.function.BiFunction<Object, Object, Object>)
                            (Object addr, Object prop) -> metadataRead(addr, prop, currentResolver()));
            c.getBindings("js").putMember("__metadataSubtypeOf",
                    (java.util.function.BiFunction<Object, Object, Object>)
                            (Object sub, Object sup) -> metadataSubtypeOf(sub, sup, currentResolver()));
            c.getBindings("js").putMember("__metadataInstanceOf",
                    (java.util.function.BiFunction<Object, Object, Object>)
                            (Object valuePath, Object typePath) -> metadataInstanceOf(valuePath, typePath, currentResolver()));
            jsContext = c;
        }
        return jsContext;
    }

    private static org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess currentResolver()
    {
        return org.finos.legend.pure.truffle.PureLanguage.get(null).resolver();
    }

    /**
     * {@code __metadataRead(address, prop)} — read {@code prop} off the
     * node at {@code address} and marshal the result to a JS-native value.
     *
     * <p>{@code address} = {@code <PE_path>} (a top-level PE) or
     * {@code <PE_path>$<sub-path>} where the sub-path is a JSON-pointer-style
     * traversal spec with optional {@code @TypeName} cast segments. Today
     * only the {@code <PE_path>$lambda/<idx>} short-form is wired (positional
     * lambda in traversal order — bridges the current lambdaIndex approach
     * to the path-keyed API). Formal JSON-pointer walker lands next.</p>
     *
     * <p>Marshaling rules (Pure → JS):
     * <ul>
     *   <li>Scalars (String/Long/Double/Boolean): pass through.</li>
     *   <li>PureSequence: marshaled element-by-element.</li>
     *   <li>PDOs: marshaled as a stub {@code {__purePath: '<address>'}};
     *       the JS-side __rewrapStubs converts these to nested Proxies.</li>
     * </ul></p>
     */
    private static Object metadataRead(Object addressRaw, Object propRaw,
                                       org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (!(addressRaw instanceof String address))
        {
            throw new RuntimeException("__metadataRead: expected String address, got "
                    + (addressRaw == null ? "null" : addressRaw.getClass().getName()));
        }
        if (!(propRaw instanceof String prop))
        {
            throw new RuntimeException("__metadataRead: expected String prop, got "
                    + (propRaw == null ? "null" : propRaw.getClass().getName()));
        }
        Object node = resolveAddress(address, resolver);
        Object value = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(node, prop);
        return marshalToJs(value, address, prop, resolver);
    }

    /**
     * {@code __metadataSubtypeOf(subPath, supPath)} — type-walk on the
     * resolved PEs. Kept as a dedicated host helper because the cache-backed
     * Java impl ({@code _Type.subtypeOf}) is much cheaper than walking
     * generalizations from JS one property read at a time.
     */
    private static Object metadataSubtypeOf(Object subRaw, Object supRaw,
                                            org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        Object sub = resolver.getElement((String) subRaw);
        Object sup = resolver.getElement((String) supRaw);
        if (sub == null || sup == null) return false;
        return org.finos.legend.pure.truffle.runtime.helper._Type.subtypeOf(sub, sup, resolver);
    }

    /**
     * {@code __metadataInstanceOf(valuePath, typePath)} — same walk as
     * subtypeOf, expressed value-to-type for ergonomics.
     */
    private static Object metadataInstanceOf(Object valuePathRaw, Object typePathRaw,
                                             org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        return metadataSubtypeOf(valuePathRaw, typePathRaw, resolver);
    }

    /**
     * Resolve a {@code <PE_path>[$<sub-path>]} address to the live PDO.
     * Today supports the {@code $lambda/<idx>} short-form (positional
     * lambda within the PE). JSON-pointer walker for arbitrary sub-paths
     * lands next.
     *
     * <p>Addresses prefixed with {@link #LOCAL_ROOT_ADDRESS} route to the
     * thread-local graph injected via {@code execute(..., graph)} instead
     * of the resolver — used by adapter flows that translate canonical
     * lambdas built in memory (no PDB backing).</p>
     */
    private static Object resolveAddress(String address,
                                         org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        int dollar = address.indexOf('$');
        String pePath = dollar < 0 ? address : address.substring(0, dollar);
        Object pe;
        if (LOCAL_ROOT_ADDRESS.equals(pePath))
        {
            pe = INJECTED_GRAPH.get();
            if (pe == null)
            {
                throw new RuntimeException("__metadataRead: address '" + address
                        + "' targets the injected graph but no graph was passed to execute(...)");
            }
        }
        else
        {
            pe = resolver.getElement(pePath);
            if (pe == null)
            {
                throw new RuntimeException("__metadataRead: unknown element '" + pePath + "'");
            }
        }
        if (dollar < 0)
        {
            return pe;
        }
        String subPath = address.substring(dollar + 1);
        if (subPath.startsWith("lambda/"))
        {
            int idx = Integer.parseInt(subPath.substring("lambda/".length()));
            return findLambdaByIndex(pe, idx);
        }
        throw new RuntimeException("__metadataRead: sub-path not yet supported: '" + subPath
                + "' (only `lambda/<idx>` for now)");
    }

    /**
     * Walk {@code root}'s value-specification tree collecting LambdaFunctions
     * in traversal order; return the {@code idx}-th. Must match the Pure-side
     * {@code collectLambdasInOrder} walker used to compute lambda addresses.
     */
    private static Object findLambdaByIndex(Object root, int idx)
    {
        java.util.List<Object> found = new java.util.ArrayList<>();
        collectLambdasInOrder(root, found);
        if (idx < 0 || idx >= found.size())
        {
            throw new RuntimeException("__metadataRead: lambda index " + idx + " out of range (have "
                    + found.size() + ")");
        }
        return found.get(idx);
    }

    private static void collectLambdasInOrder(Object node, java.util.List<Object> out)
    {
        if (node == null) return;
        // FunctionDefinition.expressionSequence + LambdaFunction.expressionSequence
        Object exprs = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(node, "expressionSequence");
        if (exprs instanceof org.finos.legend.pure.truffle.types.PureSequence ps)
        {
            for (int i = 0; i < ps.size(); i++) collectLambdasInOrder(ps.getBoxed(i), out);
        }
        // AtomicValue.value (often holds a LambdaFunction)
        Object val = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(node, "value");
        if (val != null)
        {
            if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(val,
                    "meta::pure::metamodel::function::LambdaFunction", currentResolver()))
            {
                out.add(val);
                collectLambdasInOrder(val, out);
            }
            else if (val instanceof org.finos.legend.pure.truffle.types.PureSequence vps)
            {
                for (int i = 0; i < vps.size(); i++) collectLambdasInOrder(vps.getBoxed(i), out);
            }
            else
            {
                collectLambdasInOrder(val, out);
            }
        }
        // FunctionExpression.parametersValues
        Object params = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(node, "parametersValues");
        if (params instanceof org.finos.legend.pure.truffle.types.PureSequence pps)
        {
            for (int i = 0; i < pps.size(); i++) collectLambdasInOrder(pps.getBoxed(i), out);
        }
        // Collection.values
        Object vals = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(node, "values");
        if (vals instanceof org.finos.legend.pure.truffle.types.PureSequence vps2)
        {
            for (int i = 0; i < vps2.size(); i++) collectLambdasInOrder(vps2.getBoxed(i), out);
        }
    }

    /**
     * Marshal a Pure value to JS-native shape. Scalars pass through;
     * sequences become JS arrays; PDOs become stubs {@code {__purePath}}
     * carrying a sub-address relative to {@code parentAddress}.
     */
    private static Object marshalToJs(Object value, String parentAddress, String prop,
                                      org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (value == null) return null;
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence ps)
        {
            Object[] arr = new Object[ps.size()];
            for (int i = 0; i < ps.size(); i++)
            {
                Object elem = ps.getBoxed(i);
                String childAddr = buildSubAddress(parentAddress, prop) + "/" + i;
                arr[i] = marshalSingle(elem, childAddr, resolver);
            }
            return arr;
        }
        return marshalSingle(value, buildSubAddress(parentAddress, prop), resolver);
    }

    private static String buildSubAddress(String parentAddress, String prop)
    {
        return parentAddress.indexOf('$') >= 0
                ? parentAddress + "/" + prop
                : parentAddress + "$" + prop;
    }

    private static Object marshalSingle(Object value, String childAddr,
                                        org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (value == null) return null;
        if (value instanceof String || value instanceof Long || value instanceof Double
                || value instanceof Boolean || value instanceof Integer || value instanceof Float)
        {
            return value;
        }
        if (value instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdo)
        {
            // Prefer the PDO's canonical resolver path when it IS a top-level
            // PE — keeps identity stable, avoids needless sub-address chains.
            String canonical = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pdo, resolver);
            String address = (canonical != null && !canonical.isEmpty() && resolver.getElement(canonical) == pdo)
                    ? canonical
                    : childAddr;
            java.util.Map<String, Object> stub = new java.util.HashMap<>();
            stub.put("__purePath", address);
            return stub;
        }
        // Unknown shape — return as-is and hope GraalJS interop handles it.
        return value;
    }

    private static Source typescriptCompilerSource()
    {
        try (InputStream in = TypeScriptCompileNatives.class.getResourceAsStream(TYPESCRIPT_JS_RESOURCE))
        {
            if (in == null)
            {
                throw new RuntimeException(
                        "typescript.js not found on classpath at '" + TYPESCRIPT_JS_RESOURCE +
                        "'. Build pre-req: run `pnpm install` in platforms/typescript so " +
                        "node_modules/typescript/lib/typescript.js exists before building this extension.");
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Source.newBuilder("js", body, "typescript.js").buildLiteral();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to read bundled typescript.js", e);
        }
    }

    // Calls ts.transpileModule on __pureTsSource and returns the emitted JS as
    // a String. Throws on diagnostics so failures surface with file+line info
    // rather than producing nonsense JS that fails on eval.
    private static String transpile(Context ctx)
    {
        String wrapper =
                "(function() {\n" +
                "  var result = ts.transpileModule(__pureTsSource, {\n" +
                "    compilerOptions: { module: 'commonjs', target: 'es2020', strict: false },\n" +
                // `reportDiagnostics: true` — without this the diagnostics
                // array is omitted entirely even on syntax errors, and we
                // would silently get truncated JS that fails later at
                // execution as a misleading ReferenceError / undefined export.
                "    reportDiagnostics: true\n" +
                "  });\n" +
                "  if (result.diagnostics && result.diagnostics.length > 0) {\n" +
                "    var msgs = result.diagnostics.map(function(d) {\n" +
                "      return ts.flattenDiagnosticMessageText(d.messageText, '\\n');\n" +
                "    });\n" +
                "    throw new Error('TypeScript compilation failed:\\n  ' + msgs.join('\\n  '));\n" +
                "  }\n" +
                "  return result.outputText;\n" +
                "})()";
        Value out = ctx.eval(Source.newBuilder("js", wrapper, "<transpile>").buildLiteral());
        if (!out.isString())
        {
            throw new RuntimeException("ts.transpileModule did not return a string");
        }
        return out.asString();
    }

    // Wraps the transpiled JS in an IIFE that provides a CommonJS-style
    // `exports` object, evaluates the module body once, and returns the
    // resulting `module.exports`. The `__pureResolve` global set up in
    // {@link #context()} is visible to the module body and to any closures
    // it creates — no per-module binding needed.
    private static Value evalToExports(Context ctx, String js)
    {
        ctx.getBindings("js").putMember("__pureTsModuleSource", js);
        String wrapper =
                "(function() {\n" +
                "  var exports = {};\n" +
                "  var module  = { exports: exports };\n" +
                "  (new Function('exports', 'module', __pureTsModuleSource))(exports, module);\n" +
                "  return module.exports;\n" +
                "})()";
        return ctx.eval(Source.newBuilder("js", wrapper, "<compile>").buildLiteral());
    }

    private static Object toPureValue(Value v,
                                      org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (v == null || v.isNull())
        {
            return PureSequence.EMPTY;
        }
        if (v.isBoolean())
        {
            return v.asBoolean();
        }
        if (v.isString())
        {
            return v.asString();
        }
        if (v.isNumber())
        {
            // Mirror Pure's Long vs Double split: integer-valued numbers
            // come back as Long so they line up with Pure Integer slots;
            // anything fractional becomes Double.
            if (v.fitsInLong())
            {
                double d = v.asDouble();
                if (d == Math.floor(d) && !Double.isInfinite(d))
                {
                    return v.asLong();
                }
            }
            return v.asDouble();
        }
        if (v.hasArrayElements())
        {
            long n = v.getArraySize();
            Object[] arr = new Object[(int) n];
            for (int i = 0; i < n; i++)
            {
                arr[i] = toPureValue(v.getArrayElement(i), resolver);
            }
            return new org.finos.legend.pure.truffle.types.ObjectSequence(arr);
        }
        if (v.hasMembers())
        {
            // Class-ref shape: JS object with a `path` String member —
            // resolve via the metadata resolver. Keeps identity-stable
            // (e.g. `assertIs(Boolean, pathToElement('...::Boolean'))`).
            if (v.hasMember("path"))
            {
                Value pathVal = v.getMember("path");
                if (pathVal != null && pathVal.isString())
                {
                    Object pe = resolver.getElement(pathVal.asString());
                    return pe != null ? pe : PureSequence.EMPTY;
                }
            }
            // Self-describing Pure instance: every translator-emitted PDO
            // carries `classifierGenericType: { rawType: { path: '…' } }`,
            // mirroring the Pure metamodel slot. Lift into a PDO of that
            // class. JS objects without this tag fall through to a Map —
            // hand-written TS that returns ad-hoc `{key: value}` shapes is
            // not lifted (no Pure type to lift INTO).
            Object selfDescribedClass = readClassifierGenericType(v, resolver);
            if (selfDescribedClass != null)
            {
                return liftToPdo(v, selfDescribedClass, resolver);
            }
            java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
            for (String key : v.getMemberKeys())
            {
                map.put(key, toPureValue(v.getMember(key), resolver));
            }
            return map;
        }
        throw new RuntimeException("Unsupported TypeScript return type: " + v);
    }

    private static Object readClassifierGenericType(Value v,
                                                    org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (!v.hasMember("classifierGenericType")) return null;
        Value cgt = v.getMember("classifierGenericType");
        if (cgt == null || !cgt.hasMembers() || !cgt.hasMember("rawType")) return null;
        Value rt = cgt.getMember("rawType");
        if (rt == null || !rt.hasMembers() || !rt.hasMember("path")) return null;
        Value pathVal = rt.getMember("path");
        if (pathVal == null || !pathVal.isString()) return null;
        return resolver.getElement(pathVal.asString());
    }

    // Special-case: Pure's `Map<U,V>` has no user-visible properties — its
    // entries are stored internally. The JS side represents a Map as a plain
    // object. Trying to PureObj.write each JS key onto a Map PDO fails;
    // return a plain LinkedHashMap instead — Pure assertions compare
    // structurally.
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object liftToPdo(Value v, Object cls,
                                    org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        String classPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(cls, resolver);
        if (classPath == null)
        {
            throw new RuntimeException("liftToPdo: class is not a PackageableElement");
        }
        if ("meta::pure::functions::collection::Map".equals(classPath))
        {
            java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
            for (String key : v.getMemberKeys())
            {
                map.put(key, toPureValue(v.getMember(key), resolver));
            }
            return map;
        }
        Object cgt = org.finos.legend.pure.truffle.PureLanguage.get(null).cgtForType(classPath);
        Object instance = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(classPath, resolver);
        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(instance, "classifierGenericType", cgt);
        for (String key : v.getMemberKeys())
        {
            // Skip translator-internal metadata keys:
            //  - `_`-prefixed (e.g. `_type` ad-hoc classifier chain newCoder emits).
            //  - `classifierGenericType` — the self-describing tag we now embed
            //    on every emitted instance. We already constructed the proper
            //    Pure-side CGT above (cgtForType); the JS-side `{rawType: {path}}`
            //    stub would overwrite it with a malformed value.
            if (key.startsWith("_")) continue;
            if ("classifierGenericType".equals(key)) continue;
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(instance, key,
                    toPureValue(v.getMember(key), resolver));
        }
        return instance;
    }
}
