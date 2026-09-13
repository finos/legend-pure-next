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

// Host implementation of the three meta::external::language::javascript natives:
// `compile`, `execute` and `drainCompiledSources`.
//
// The native decl calls itself "an eval pipe plus a resolver" — the resolver half
// is already here (modules/pdb/marshal.js installs __pureResolve and the
// __metadata* globals), so only the eval pipe is needed, and on Node that is
// just eval + a function lookup. Generated code and the metadata store share one
// heap: no language boundary, so nothing to marshal across one.
//
// `compile(source)` returns a HANDLE: each module is evaluated in its OWN
// function scope (as GraalJS evaluated every compile as its own module) and its
// exports come back on the handle, where `execute` looks the function up. A
// shared global scope does not work: adapter modules re-declare the same
// top-level consts (an enumeration's `const DurationUnit = …`), and the second
// declaration throws. The handle also carries the source for diagnostics.

/**
 * Install __hostJsCompile / __hostJsExecute / __hostJsDrainCompiledSources.
 *
 * @param evalJs    the host's evaluator (indirect eval / vm.runInThisContext)
 * @param memModule the registry's in-memory module — `execute`'s `graph`
 *   argument is indexed into it for the duration of the call, so
 *   `__pureResolve` on an address with no PDB backing (a canonical lambda a
 *   test adapter built in memory) resolves against it.
 */
const LOCAL_ROOT = "__local::root";

export function installHostJsNatives(evalJs, memModule = null) {
    // Sources handed to `compile` since the last drain. The gallery drains this
    // per card to show the EXACT JavaScript that ran.
    const compiledSources = [];
    // Each compiled module gets its own file name, so a Pure stack trace maps a
    // frame back to the source that actually ran (runtime-lib __pureStackFrames).
    let compiledCount = 0;
    // Position markers (`/*@…*/`) are comments for stack traces, not code to show.
    const POSITION_MARKER = /\/\*@[^*]*\*\//g;

    // `export` declarations, allowing the position marker the serializer puts
    // between `export` and `function`.
    const EXPORT = /^export\s+(?:\/\*@[^*]*\*\/)?(?:async\s+)?(?:function\*?|const|let|var|class)\s+([A-Za-z_$][\w$]*)/gm;
    globalThis.__hostJsCompile = (source) => {
        const src = String(source ?? "");
        compiledSources.push(src);
        const names = [...src.matchAll(EXPORT)].map((m) => m[1]);
        const body = src.replace(/^export\s+/gm, "");
        const exports = evalJs(`(function () {\n${body}\nreturn { ${names.join(", ")} };\n})()`,
                               `jsCompile-${++compiledCount}.js`);
        return { __jsCompiled: true, source: src, exports };
    };

    globalThis.__hostJsDrainCompiledSources = () =>
        compiledSources.splice(0, compiledSources.length).map((src) => src.replace(POSITION_MARKER, ""));

    // Run a function definition built at run time from an AST
    // (`^LambdaFunction(expressionSequence = ...)->evaluate([])`): runtime-lib
    // routes it here because running it means translating it. Same pipeline as
    // the translator's PCT adapter — convention, translate, serialize, then the
    // compile + execute natives above — minus canonicalization (such a lambda
    // has no open variables) and minus the classifier's function type, which a
    // `^LambdaFunction<{->T[m]}>` instance does not carry: the return type and
    // multiplicity come from the last expression instead.
    const TRANSLATION = "meta::external::language::javascript::translation::";
    const CONVENTION = TRANSLATION + "conventionForRoot_FunctionDefinition_1__Convention_1_";
    const TRANSLATE = TRANSLATION + "translateFunctionDef_FunctionDefinition_1__Convention_1__SourceFile_1_";
    const SERIALIZE = "meta::external::language::javascript::serialization::serialize_SourceFile_1__String_1_";
    globalThis.__hostEvaluateFunctionDefinition = (fn, args) => {
        if (args.length !== 0) {
            throw new Error(`evaluate: a function definition built at run time takes no arguments on this host (got ${args.length})`);
        }
        const resolve = (path, arity) => {
            const f = memModule && memModule.resolveFn(path, arity);
            if (typeof f !== "function") throw new Error(`evaluate: translator function ${path} is not loaded`);
            return f;
        };
        const source = resolve(SERIALIZE, 1)(resolve(TRANSLATE, 2)(fn, resolve(CONVENTION, 1)(fn)));
        const exprs = Array.isArray(fn.expressionSequence) ? fn.expressionSequence : [fn.expressionSequence];
        const last = exprs[exprs.length - 1];
        const ctx = globalThis.__jsCompile(source);
        return globalThis.__jsExecute(ctx, "execute", [], last && last.genericType, last && last.multiplicity, [fn]);
    };

    globalThis.__hostJsExecute = (ctx, fnName, args, _pureReturnType, _pureMultiplicity, graph) => {
        const name = String(fnName ?? "");
        const exported = ctx && ctx.exports ? ctx.exports[name] : undefined;
        const fn = typeof exported === "function" ? exported : globalThis[name];
        if (typeof fn !== "function") {
            throw new Error(
                `javascript::execute: '${name}' is not defined after compile` +
                (ctx && ctx.source ? ` (${ctx.source.length} bytes compiled)` : ""));
        }
        // The graph is in-memory only for this call, matching the native's
        // documented lifetime ("cleaned up after execute returns").
        const added = [];
        const gs = graph === undefined || graph === null ? []
                 : (Array.isArray(graph) ? graph : [graph]);
        for (const g of gs) {
            const path = (() => { try { return globalThis.__elementToPath?.(g); } catch { return undefined; } })();
            if (typeof path === "string" && path && memModule?.addElement) { memModule.addElement(path, g); added.push(path); }
        }
        // The translator addresses the adapter's in-memory canonical as
        // `__local::root`, and its lambdas as `__local::root$lambda/<n>`
        // (translation.pure conventionForRoot): the graph's root answers there.
        // A nested execute gets its own root and restores the caller's after.
        const previousRoot = memModule?.hasElement?.(LOCAL_ROOT) ? memModule.node(LOCAL_ROOT).obj : undefined;
        if (gs.length > 0 && memModule?.addElement) memModule.addElement(LOCAL_ROOT, gs[0]);
        try {
            return fn(...(args === undefined || args === null ? []
                        : (Array.isArray(args) ? args : [args])));
        } finally {
            for (const p of added) memModule.removeElement?.(p);
            if (gs.length > 0 && memModule?.addElement) {
                if (previousRoot !== undefined) memModule.addElement(LOCAL_ROOT, previousRoot);
                else memModule.removeElement?.(LOCAL_ROOT);
            }
        }
    };
}
