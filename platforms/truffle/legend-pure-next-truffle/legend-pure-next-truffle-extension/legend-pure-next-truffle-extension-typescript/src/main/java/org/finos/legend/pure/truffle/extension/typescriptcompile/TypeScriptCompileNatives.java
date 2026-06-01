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
import org.graalvm.polyglot.proxy.ProxyObject;

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

    /** Resolver captured at {@link #invoke} time. The {@code __metadataRead}
     *  callbacks fire mid-{@code fn.execute()}, where
     *  {@code PureLanguage.get(null)} can return null — so we snapshot the
     *  resolver here (on the Pure execution thread, where it's reliably
     *  available) and read it from the callbacks. */
    private static final ThreadLocal<org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess> ACTIVE_RESOLVER =
            new ThreadLocal<>();

    /** PureContext snapshot for the active {@code invoke} call. The
     *  {@code __metadataInvoke} bridge needs a live {@code PureContext} to
     *  pass into {@link org.finos.legend.pure.truffle.ast.natives.lang.EvalNode#dispatch}
     *  (for call-target lookup, native registry, etc.); same null-on-callback-
     *  thread problem as the resolver. */
    private static final ThreadLocal<org.finos.legend.pure.truffle.PureContext> ACTIVE_CONTEXT =
            new ThreadLocal<>();

    /** Every TS module string handed to {@link #compile} is appended here so
     *  the translation gallery can display the EXACT source that the PCT
     *  adapter compiled and executed (rather than re-deriving it through a
     *  divergent static-translation path). The list is drained per gallery
     *  card via {@link #drainCapturedSources}; non-gallery callers simply let
     *  it accumulate harmlessly (small strings, bounded by test count). */
    private static final java.util.List<String> CAPTURED_SOURCES =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private TypeScriptCompileNatives() {}

    /** Snapshot the captured sources (in compile order) and clear the buffer.
     *  Returned as a Pure {@code String[*]} sequence. */
    static Object drainCapturedSources()
    {
        synchronized (CAPTURED_SOURCES)
        {
            Object[] out = CAPTURED_SOURCES.toArray();
            CAPTURED_SOURCES.clear();
            return new org.finos.legend.pure.truffle.types.ObjectSequence(out);
        }
    }

    static TypeScriptCompilationContext compile(String source)
    {
        CAPTURED_SOURCES.add(source);
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
            org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver = currentResolver();
            org.finos.legend.pure.truffle.PureContext pureCtx = org.finos.legend.pure.truffle.PureLanguage.get(null);
            INJECTED_GRAPH.set(graph);
            ACTIVE_RESOLVER.set(resolver);
            ACTIVE_CONTEXT.set(pureCtx);
            try
            {
                return toPureValue(fn.execute(args), resolver);
            }
            finally
            {
                INJECTED_GRAPH.remove();
                ACTIVE_RESOLVER.remove();
                ACTIVE_CONTEXT.remove();
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
                            (Object addr, Object prop) -> metadataRead(addr, prop, activeResolver()));
            c.getBindings("js").putMember("__metadataSubtypeOf",
                    (java.util.function.BiFunction<Object, Object, Object>)
                            (Object sub, Object sup) -> metadataSubtypeOf(sub, sup, activeResolver()));
            c.getBindings("js").putMember("__metadataInstanceOf",
                    (java.util.function.BiFunction<Object, Object, Object>)
                            (Object valuePath, Object typePath) -> metadataInstanceOf(valuePath, typePath, activeResolver()));
            // pathToElement bridge: resolves a string path (with optional
            // separator) to its canonical address. Returns the canonical path
            // (root → '', missing → null for lenient / null for strict — strict
            // throwing is JS-side). Pure's `pathToElement('')` and
            // `pathToElement('::')` both denote the Root package.
            c.getBindings("js").putMember("__metadataPathToElement",
                    (java.util.function.BiFunction<Object, Object, Object>)
                            (Object path, Object sep) -> metadataPathToElement(path, sep, activeResolver()));
            // ── TEMP Truffle eval bridge ──────────────────────────────────
            // Lives in {@link TruffleEvalBridge}; deletes when the translator
            // self-hosts and JS-side reflection invokes through in-process
            // translate-and-eval (see TruffleEvalBridge javadoc).
            c.getBindings("js").putMember("__metadataInvoke",
                    (java.util.function.BiFunction<Object, Object, Object>)
                            (Object path, Object args) -> TruffleEvalBridge.metadataInvoke(
                                    path, args, activeResolver(), ACTIVE_CONTEXT.get()));
            // ─────────────────────────────────────────────────────────────
            jsContext = c;
        }
        return jsContext;
    }

    private static org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess currentResolver()
    {
        return org.finos.legend.pure.truffle.PureLanguage.get(null).resolver();
    }

    /** Resolver snapshot for the active {@code invoke} call. Used by the
     *  __metadata* callbacks, which fire on a thread/context where
     *  {@code PureLanguage.get(null)} may be null. */
    private static org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess activeResolver()
    {
        org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess r = ACTIVE_RESOLVER.get();
        if (r == null)
        {
            throw new RuntimeException("__metadata* called outside an active execute(...) — no resolver in scope");
        }
        return r;
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
     * {@code __metadataInstanceOf(valuePath, typePath)} — does the value at
     * {@code valuePath} have a runtime type that is a subtype of the type at
     * {@code typePath}? Unlike a plain {@code subtypeOf(value, type)}, this
     * does the classifier hop Pure's {@code instanceOf} native requires: read
     * the value-element's classifier (its {@code classifierGenericType.type})
     * and check THAT against {@code typePath}. So {@code CC_Person->instanceOf(Class)}
     * is {@code subtypeOf(classifierOf(CC_Person)=Class, Class)=true}, not
     * {@code subtypeOf(CC_Person, Class)=false}.
     */
    private static Object metadataInstanceOf(Object valuePathRaw, Object typePathRaw,
                                             org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        Object value = resolveAddress((String) valuePathRaw, resolver);
        if (value == null) return false;
        String classifierPath = classifierPathOf(value, resolver);
        if (classifierPath == null) return false;
        return metadataSubtypeOf(classifierPath, typePathRaw, resolver);
    }

    // The {@code __metadataInvoke} bridge implementation lives in the
    // standalone {@link TruffleEvalBridge} class — see that class for the
    // "why isolated" rationale. These package-private accessors expose the
    // marshalers / resolvers the bridge needs without making them part of
    // the public surface of this class.

    static Object toPureValuePublic(Value v,
                                    org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        return toPureValue(v, resolver);
    }

    static Object resolveAddressPublic(String address,
                                       org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        return resolveAddress(address, resolver);
    }

    static Object marshalSinglePublic(Object value, String childAddr,
                                      org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        return marshalSingle(value, childAddr, resolver);
    }

    /**
     * {@code __metadataPathToElement(path, separator)} — resolve a string
     * path to its canonical {@code ::}-separated address, or null if the
     * element is missing. Mirrors {@code PathToElementNode}: '' / '::' →
     * Root package (return empty string so the JS-side proxy targets the
     * resolver's root); otherwise normalize separator → '::' and check
     * existence. Returns null when the element isn't found — JS-side
     * strict {@code __pathToElement} throws, lenient
     * {@code __lenientPathToElement} returns undefined.
     */
    private static Object metadataPathToElement(Object pathRaw, Object sepRaw,
                                                org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (!(pathRaw instanceof String path))
        {
            throw new RuntimeException("__metadataPathToElement: expected String path, got "
                    + (pathRaw == null ? "null" : pathRaw.getClass().getName()));
        }
        String separator = sepRaw instanceof String s ? s : "::";
        String norm = "::".equals(separator) ? path : path.replace(separator, "::");
        if (norm.isEmpty() || "::".equals(norm))
        {
            // Root package. Resolver canonicalises as either '' or '::' —
            // try both so the JS proxy targets whichever the resolver holds.
            if (resolver.getElement("") != null) return "";
            if (resolver.getElement("::") != null) return "::";
            return "";
        }
        return resolver.getElement(norm) != null ? norm : null;
    }

    /** The path of the value's classifier, via {@code classifierGenericType.type}. */
    private static String classifierPathOf(Object element,
                                           org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        Object cgt = unwrapSingle(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(element, "classifierGenericType"));
        if (cgt == null) return null;
        Object type = unwrapSingle(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(cgt, "type"));
        if (type == null) return null;
        return org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(type, resolver);
    }

    /** Collapse a 1-element {@link PureSequence} to its element; pass scalars through. */
    private static Object unwrapSingle(Object v)
    {
        if (v instanceof org.finos.legend.pure.truffle.types.PureSequence ps)
        {
            return ps.size() == 0 ? null : ps.getBoxed(0);
        }
        return v;
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
        // Backwards-compat short-form for inline lambdas (positional walker).
        // `lambda/<idx>` resolves the idx-th lambda; any trailing segments
        // (e.g. `lambda/0/classifierGenericType/...`) are walked from there,
        // so reflection can chain past the lambda PDO.
        if (subPath.startsWith("lambda/"))
        {
            String rest = subPath.substring("lambda/".length());
            int slash = rest.indexOf('/');
            String idxStr = slash < 0 ? rest : rest.substring(0, slash);
            Object lambda = findLambdaByIndex(pe, Integer.parseInt(idxStr));
            return slash < 0 ? lambda : walkSubPath(lambda, rest.substring(slash + 1));
        }
        return walkSubPath(pe, subPath);
    }

    /**
     * Walk a JSON-pointer-like sub-path from {@code root}. Segments are
     * separated by {@code /}. Each segment is either:
     * <ul>
     *   <li>A digit string (e.g. {@code 0}) — index into the current
     *       {@link org.finos.legend.pure.truffle.types.PureSequence}.</li>
     *   <li>A {@code @TypeName} cast assertion — no-op for the walker
     *       (consumer's responsibility to ensure the runtime class matches).</li>
     *   <li>A property name — {@link
     *       org.finos.legend.pure.truffle.runtime.dynobj.PureObj#read} on
     *       the current node.</li>
     * </ul>
     */
    private static Object walkSubPath(Object root, String subPath)
    {
        Object current = root;
        for (String seg : subPath.split("/"))
        {
            if (seg.isEmpty()) continue;
            if (seg.startsWith("@"))
            {
                // Cast assertion — pass through without action.
                continue;
            }
            if (seg.chars().allMatch(Character::isDigit))
            {
                int idx = Integer.parseInt(seg);
                if (current instanceof org.finos.legend.pure.truffle.types.PureSequence ps)
                {
                    if (idx < 0 || idx >= ps.size())
                    {
                        throw new RuntimeException("walkSubPath: index " + idx + " out of range (size "
                                + ps.size() + ") in '" + subPath + "'");
                    }
                    current = ps.getBoxed(idx);
                    continue;
                }
                throw new RuntimeException("walkSubPath: index segment '" + seg
                        + "' but current node is not a sequence: " + current.getClass().getName());
            }
            current = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(current, seg);
            if (current == null)
            {
                throw new RuntimeException("walkSubPath: property '" + seg
                        + "' is null while walking '" + subPath + "'");
            }
        }
        return current;
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
                    "meta::pure::metamodel::function::LambdaFunction", activeResolver()))
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
            // Java Object[] — GraalJS treats it as a JS array (Array.isArray
            // true, indexable), so __rewrapStubs maps over it natively.
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
            // ProxyObject so `stub.__purePath` reads as a JS member (a raw
            // Java Map exposes entries via get(), not member access, so
            // __rewrapStubs wouldn't detect it).
            return ProxyObject.fromMap(stub);
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
        // GraalJS reports both JS number and JS BigInt as `isNumber()`. Pure
        // Integer is a BigInt; Float/Decimal are JS numbers. Map to Pure's Long
        // vs Double split. NOTE: do NOT call asDouble() to test integrality —
        // it throws on a large BigInt ("lossy primitive coercion"). fitsInLong()
        // already implies an integral value within signed-64 range (a fractional
        // number does not fit a long), so asLong() is safe there.
        if (v.isNumber())
        {
            if (v.fitsInLong())
            {
                return v.asLong();          // Integer (or integral Float) in long range
            }
            if (v.fitsInBigInteger())
            {
                return v.asBigInteger();    // Integer beyond long range (large BigInt)
            }
            return v.asDouble();            // Float (fractional)
        }
        // Pure Decimal is a vendored big.js Big, branded `__isDec` on its prototype.
        // Its toString() is a plain (or scientific) decimal string BigDecimal parses.
        if (v.hasMember("__isDec"))
        {
            return new java.math.BigDecimal(v.invokeMember("toString").asString());
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
            // Pure Map: the translator emits `{ __mapEntries: [[k, v], ...] }`
            // (a JS-native entries list, so non-string keys survive). Lift to a
            // MapImpl via plain member/array access — no JS Map / hash interop.
            if (v.hasMember("__mapEntries"))
            {
                Value entries = v.getMember("__mapEntries");
                org.finos.legend.pure.truffle.ast.natives.collection.MapImpl map =
                        new org.finos.legend.pure.truffle.ast.natives.collection.MapImpl();
                long count = entries.getArraySize();
                for (long i = 0; i < count; i++)
                {
                    Value entry = entries.getArrayElement(i);
                    map.put(toPureValue(entry.getArrayElement(0), resolver),
                            toPureValue(entry.getArrayElement(1), resolver));
                }
                return map;
            }
            // Address-proxy (from __pureResolve): carries `__purePath`, a PE
            // path or sub-PE address. Resolve via the address walker — NOT
            // resolver.getElement, which only handles top-level PE paths and
            // would return null for sub-addresses like `CC_Address$properties/0`.
            // This is how reflected PDOs (class properties, generalizations,
            // …) round-trip back to Pure as live PDOs.
            if (v.hasMember("__purePath"))
            {
                Value addrVal = v.getMember("__purePath");
                if (addrVal != null && addrVal.isString())
                {
                    return resolveAddress(addrVal.asString(), resolver);
                }
            }
            // Date marker (`__pdate`): `__fmt` is the canonical Pure date string
            // (UTC, granularity-preserved). Lift to a PureDate so it compares by
            // value against Pure date literals (PureDate.equals is dateString-based).
            if (v.hasMember("__fmt"))
            {
                Value fmtVal = v.getMember("__fmt");
                if (fmtVal != null && fmtVal.isString())
                {
                    String ds = fmtVal.asString();
                    String typeName = ds.indexOf('T') >= 0 ? "DateTime" : "StrictDate";
                    return org.finos.legend.pure.truffle.types.PureDate.of(ds, typeName);
                }
            }
            // Class-ref shape: JS object with a `path` String member —
            // resolve via the metadata resolver. Keeps identity-stable
            // (e.g. `assertIs(Boolean, pathToElement('...::Boolean'))`).
            if (v.hasMember("path"))
            {
                Value pathVal = v.getMember("path");
                if (pathVal != null && pathVal.isString())
                {
                    String path = pathVal.asString();
                    Object pe = resolver.getElement(path);
                    // Root package: `pathToElement('::')` / `('')` normalise to
                    // an empty or `::` path. Mirror PathToElementNode — the root
                    // is registered under "" (fall back to "::").
                    if (pe == null && (path.isEmpty() || "::".equals(path)))
                    {
                        pe = resolver.getElement("");
                        if (pe == null) pe = resolver.getElement("::");
                    }
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
        // Self-tag uses `type` to mirror Pure's GenericTypeValue.type slot.
        if (cgt == null || !cgt.hasMembers() || !cgt.hasMember("type")) return null;
        Value rt = cgt.getMember("type");
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
        // Enum-value shape: the JS object's `classifierGenericType.type`
        // resolves to an Enumeration metaobject (LA_GeographicEntityType, …),
        // and the object's `name` is the enum value's identifier. Lift as an
        // Enum PDO (NOT an instance of the Enumeration class) + register in
        // PureEnumRegistry so its lazy classifierGenericType resolves to the
        // correct enumeration. Same shape AtomicValueEnumReconstructor builds
        // for PDB-loaded enum values, so Pure-side `==` against
        // `LA_GeographicEntityType.CITY` matches by classifier+name.
        if (cls instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdoCls
                && "meta::pure::metamodel::type::Enumeration".equals(pdoCls.purePath()))
        {
            String enumValueName = v.hasMember("name") && v.getMember("name").isString()
                    ? v.getMember("name").asString()
                    : null;
            if (enumValueName != null)
            {
                org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject enumPdo =
                        new org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject(
                                org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.classInfoFor(
                                        "meta::pure::metamodel::type::Enum", resolver),
                                null, resolver, null);
                enumPdo.writeProperty("name", enumValueName);
                org.finos.legend.pure.truffle.runtime.dynobj.PureEnumRegistry.register(enumPdo, classPath);
                return enumPdo;
            }
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
