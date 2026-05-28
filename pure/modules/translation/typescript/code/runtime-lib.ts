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

// Pure -> TypeScript translator: shared JS runtime helpers.
//
// The translator emits calls like `__eq(a, b)`, `__fold(...)`, `__instanceOf(v, t)`.
// This file provides their implementations. It is bundled into the typescript
// Truffle extension as a classpath resource (see the extension pom.xml) and
// prepended automatically to every transpile target by TypeScriptCompileNatives.
//
// Keep this file dependency-free: it executes inside a GraalJS Context with no
// access to node_modules; everything must be reachable via the standard JS
// global namespace.

function __assert(c: any, m: any): boolean { if (!c) { throw new Error(String(m)); } return true; }

// Pure-style structural equality on the translated value graph. Both `==`
// (assertEquals) and array comparisons use this. Primitives fall back to ===;
// arrays and plain objects walk structurally. Skips translator-internal
// metadata keys (`_kind`, `_type`) — these are tagged by the newCoder for
// classifier round-trip, not user data.
function __eq(a: any, b: any): boolean {
  if (a === b) return true;
  if (a == null || b == null) return a === b;
  // Date equality by time value, since two distinct Date instances pointing
  // at the same instant must compare equal in Pure-style semantics.
  if (a instanceof Date && b instanceof Date) return a.getTime() === b.getTime();
  // Mixed Date <-> string comparison: Pure's assertEquals('2014-01-02...', ...->toString())
  // hits this path, so coerce both sides through __toString and compare.
  if (a instanceof Date || b instanceof Date) return __toString(a) === __toString(b);
  if (Array.isArray(a) || Array.isArray(b)) {
    const aa = Array.isArray(a) ? a : [a];
    const bb = Array.isArray(b) ? b : [b];
    if (aa.length !== bb.length) return false;
    for (let i = 0; i < aa.length; i++) { if (!__eq(aa[i], bb[i])) return false; }
    return true;
  }
  if (typeof a !== "object" || typeof b !== "object") return false;
  const filt = (k: string): boolean => !k.startsWith("_");
  const ka = Object.keys(a).filter(filt); const kb = Object.keys(b).filter(filt);
  if (ka.length !== kb.length) return false;
  for (const k of ka) { if (!__eq((a as any)[k], (b as any)[k])) return false; }
  return true;
}

// Pure-style `format`: %s (any), %d/%i (integer with optional 0-pad width like
// %05d), %f (float, default 6-digit precision, optional .N like %.4f for
// trailing zeros / rounding), %r (toRepresentation). Args consumed positionally.
function __format(tpl: string, args: any): string {
  const arr: any[] = Array.isArray(args) ? args : (args === undefined ? [] : [args]);
  let i = 0;
  return tpl.replace(/%(0?[0-9]+)?(?:\.([0-9]+))?([sdifr])/g, (_m: string, width: string, prec: string, kind: string): string => {
    const v = arr[i++];
    if (v === undefined) return "";
    switch (kind) {
      case "s": return __toString(v);
      case "d":
      case "i": {
        let n = Math.trunc(Number(v)).toString();
        if (width && width.startsWith("0")) {
          const w = parseInt(width, 10);
          while (n.length < w) n = "0" + n;
        }
        return n;
      }
      case "f": {
        const p = prec !== undefined ? parseInt(prec, 10) : 6;
        return Number(v).toFixed(p);
      }
      case "r": return __toRepresentation(v);
      default:  return __toString(v);
    }
  });
}

// `toRepresentation`: primitives render as Pure literals, anything structured
// falls back to JSON.stringify so PCT failure messages still surface meaningful
// content. Dates render with the `%` Pure-literal tag. Strings re-render as
// Pure single-quoted literals — backslashes, quotes, newlines, tabs, and
// carriage returns escape so the round-tripped form is valid Pure source.
function __toRepresentation(v: any): string {
  if (typeof v === "string") {
    let out = "";
    for (let i = 0; i < v.length; i++) {
      const ch = v.charAt(i);
      if (ch === "\\")       out += "\\\\";
      else if (ch === "'")   out += "\\'";
      else if (ch === "\n")  out += "\\n";
      else if (ch === "\t")  out += "\\t";
      else if (ch === "\r")  out += "\\r";
      else                   out += ch;
    }
    return "'" + out + "'";
  }
  if (v === undefined || v === null) return "";
  if (v instanceof Date) return "%" + __toString(v);
  if (Array.isArray(v)) return "[" + v.map(__toRepresentation).join(", ") + "]";
  return String(v);
}

// Runs `thunk()`; if it throws, verifies the thrown message contains the
// expected substring; if it doesn't throw, raises an assertion failure.
function __assertError(thunk: () => any, expected: any): boolean {
  try { thunk(); }
  catch (e: any) {
    const msg = String(e && e.message ? e.message : e);
    if (msg.includes(String(expected))) return true;
    throw new Error("assertError: expected " + String(expected) + " in " + msg);
  }
  throw new Error("assertError: expected exception was not thrown");
}

function __chunk(coll: any[], n: number): any[][] {
  const out: any[][] = [];
  for (let i = 0; i < coll.length; i += n) out.push(coll.slice(i, i + n));
  return out;
}

// Pure-style zip producing `Pair`-shaped entries `{ first, second }`.
// Length = min(a, b).
function __zip(a: any[], b: any[]): any[] {
  const n = Math.min(a.length, b.length);
  const out = [];
  for (let i = 0; i < n; i++) out.push({ first: a[i], second: b[i] });
  return out;
}

// Pure-style short-circuit evaluator. Walks the thunk list, returns the first
// non-empty result without invoking later thunks. Empty = undefined OR empty
// array. Returns undefined when all thunks produce empty.
function __firstNonEmpty(thunks: any[]): any {
  for (const t of (thunks ?? [])) {
    const v = (t as any)();
    if (v === undefined || v === null) continue;
    if (Array.isArray(v) && v.length === 0) continue;
    return v;
  }
  return undefined;
}

// Produces Pure's TryResult<V|m> shape `{ value, failure }`. On success:
// `{value: thunk(), failure: undefined}`. On throw: `{value: undefined,
// failure: {message: String(e), stack: []}}`. Both the outer TryResult and
// the nested Error carry `classifierGenericType: { rawType: __ptr('...') }`
// so the Java-side toPureValue can lift each level to the right Pure class
// without the caller threading per-slot type info — the JS object is
// self-describing.
function __tryEval(thunk: () => any): any {
  try {
    return {
      value: thunk(),
      failure: undefined,
      classifierGenericType: { rawType: __ptr("meta::pure::functions::lang::TryResult") }
    };
  }
  catch (e: any) {
    const msg = e && e.message !== undefined ? String(e.message) : String(e);
    return {
      value: undefined,
      failure: {
        message: msg,
        stack: [],
        classifierGenericType: { rawType: __ptr("meta::pure::functions::lang::Error") }
      },
      classifierGenericType: { rawType: __ptr("meta::pure::functions::lang::TryResult") }
    };
  }
}

// Dedup using an eq function (binary) or key extractor (unary). Pure overloads
// both shapes.
function __removeDuplicatesBy(coll: any[], eqOrKey: Function): any[] {
  const out: any[] = [];
  const isBinary = (eqOrKey as any).length >= 2;
  if (isBinary) {
    for (const x of coll) if (!out.some(y => (eqOrKey as any)(x, y))) out.push(x);
  } else {
    const seen = new Set();
    for (const x of coll) { const k = (eqOrKey as any)(x); if (!seen.has(k)) { seen.add(k); out.push(x); } }
  }
  return out;
}

// Pure-style typed match. Each arm: `{ check: (v) => boolean, run: (v, with?) => any }`.
// The first arm whose check returns true gets `run(v, withArg)`. For the 2-arg
// `match(var, fns)` form, withArg is undefined and arms ignore the second
// parameter via JS arity.
function __match(v: any, arms: Array<{check: (x: any) => boolean, run: (...xs: any[]) => any}>, withArg?: any): any {
  for (const arm of arms) { if (arm.check(v)) return arm.run(v, withArg); }
  throw new Error("match: no arm matched value " + __toRepresentation(v));
}

// Element-type + multiplicity check. `typePath` is the FULL classifier path
// (e.g. `meta::pure::functions::lang::tests::model::MA_Address`). For Pure
// primitives we check via `typeof` / `instanceof Date`; for user classes
// we read the element's `classifierGenericType.rawType.path` and ask the
// host `__pureHost.instanceOf(elemPath, typePath)` — same subtype walk
// Pure's native `match` uses. Multiplicity is checked against array length;
// a scalar non-undefined value counts as size 1.
function __matchType(v: any, typePath: string, lower: number, upper: number | undefined): boolean {
  const isArr = Array.isArray(v);
  const sz = isArr ? v.length : (v === undefined || v === null ? 0 : 1);
  if (sz < lower) return false;
  if (upper !== undefined && sz > upper) return false;
  const elems: any[] = isArr ? v : (sz === 0 ? [] : [v]);
  // Trailing segment used for the primitive dispatch — Pure primitives have
  // well-known leaf names (`String`, `Integer`, …) under
  // `meta::pure::metamodel::type::primitives::`; comparing leaves avoids
  // hardcoding the full path everywhere.
  const leaf = typePath.split("::").pop() || typePath;
  const checkOne = (e: any): boolean => {
    switch (leaf) {
      case "String":  return typeof e === "string";
      case "Integer": case "Float": case "Number": case "Decimal":
        return typeof e === "number";
      case "Boolean": return typeof e === "boolean";
      case "Date":
      case "StrictDate":
      case "DateTime":
      case "StrictTime":
        return e instanceof Date;
      case "Any":     return e !== undefined && e !== null;
      default: {
        if (e === undefined || e === null) return false;
        // `__lambdaPtr(arrow, idx)` reports `typeof === "function"` (it's a
        // Proxy wrapping an arrow). Treat callables as instances that may
        // carry a classifierGenericType via the host proxy.
        if (typeof e !== "object" && typeof e !== "function") return false;
        // `__ptr`-shape (PE pointer): host's `instanceOf` reads the value's
        // classifier and walks from there. Right for the value-as-instance
        // case (`Boolean->instanceOf(PrimitiveType)`, enum-value match).
        if (e.__ptr) return __pureHost.instanceOf(e.__ptr, typePath);
        // Self-describing instance: `classifierGenericType.rawType.path`
        // already points at the value's classifier — feed it to `subtypeOf`
        // directly (no second classifier-of hop, which would jump up to
        // `Class` and break `^CO_Person->match([p:CO_Person[1]|...])`).
        if (e.classifierGenericType
            && e.classifierGenericType.rawType
            && e.classifierGenericType.rawType.path) {
          return __pureHost.subtypeOf(e.classifierGenericType.rawType.path, typePath);
        }
        // Last-resort: object with no classifier tag — accept conservatively,
        // matching the prior `typeof === 'object'` behaviour.
        return true;
      }
    }
  };
  return elems.every(checkOne);
}

// Map helpers backing Pure's `Map<K, V>` ops. JS objects serve as the
// K-of-string-style backing; non-string keys are coerced via String(). Returns
// are non-mutating to match Pure's value semantics. `__newMap(pairs)` tolerates
// a single Pair, an array of Pairs, or empty. Each Pair is `{first, second}`.
function __newMap(pairs: any): any {
  if (pairs === undefined) return {};
  const arr = Array.isArray(pairs) ? pairs : [pairs];
  const out: any = {};
  for (const p of arr) out[String(p.first)] = p.second;
  return out;
}
function __mapPut(m: any, k: any, v: any): any { return { ...m, [String(k)]: v }; }
function __mapRemoveAll(m: any, keys: any): any {
  const out = { ...m };
  const ks = __asArr(keys);
  for (const k of ks) delete out[String(k)];
  return out;
}
function __mapKeyValues(m: any): any[] {
  return Object.entries(m || {}).map(([k, v]) => ({ first: k, second: v }));
}
// Pure semantics: return value at key, computing keyFn(key) lazily if absent.
// Map is treated as immutable so we return the value (the outer caller threads
// any mutation through put if needed).
function __mapGetIfAbsentPut(m: any, k: any, keyFn: any): any {
  const key = String(k);
  return (m && m[key] !== undefined) ? m[key] : keyFn(k);
}

// Overloaded: String x String -> substring containment; Collection x Any ->
// structural-equality contains. JS Array.includes is reference-only; Pure
// compares non-primitive class instances structurally.
function __contains(coll: any, x: any): boolean {
  if (typeof coll === "string") return coll.includes(String(x));
  return __asArr(coll).some((y: any) => __eq(x, y));
}
function __containsBy(coll: any, x: any, eqFn: any): boolean {
  return __asArr(coll).some((y: any) => eqFn(x, y));
}

// Walks the pair list (each entry is `{first: condFn, second: valueFn}`),
// returns the first matching value; defaults if none match.
function __multiIf(pairs: any, defaultFn: any): any {
  for (const p of __asArr(pairs)) {
    if ((p.first as any)()) return (p.second as any)();
  }
  return (defaultFn as any)();
}
function __mapPutAll(m: any, kvs: any[]): any {
  const out = { ...m };
  for (const kv of kvs) out[String(kv.first)] = kv.second;
  return out;
}
function __mapRemove(m: any, k: any): any { const { [String(k)]: _, ...rest } = m; return rest; }
function __mapGet(m: any, k: any): any {
  const v = m?.[String(k)];
  return v === undefined ? [] : v;
}

// Produces a JS object whose values are arrays of grouped items.
function __groupBy(coll: any[], keyFn: (x: any) => any): any {
  const out: any = {};
  for (const x of coll) {
    const k = String(keyFn(x));
    (out[k] ||= []).push(x);
  }
  return out;
}

// Pure date semantics can't be fully recreated in JS, but a real Date object
// is closer than the prior `%`-tagged string. JS parses ISO strings natively;
// downstream date arithmetic / accessors operate on the resulting Date.
function __parseDate(s: string): Date {
  return new Date(s.replace(/^%/, ""));
}

// Pure-order fold. Scalars are treated as single-element collections
// (`fold(1, fn, s)` is a fold over `[1]`). undefined treated as empty.
function __fold(coll: any, fn: any, seed: any): any {
  const arr = coll === undefined ? [] : (Array.isArray(coll) ? coll : [coll]);
  let acc = seed;
  for (const item of arr) acc = fn(item, acc);
  return acc;
}

// Scalar-tolerant wrappers for common Pure collection ops. Pure treats a `[1]`
// value as conceptually a sequence of size 1, so the same op must work on
// scalars + arrays uniformly. JS Array methods throw on non-arrays.
function __asArr(v: any): any[] { return v === undefined ? [] : (Array.isArray(v) ? v : [v]); }
function __filter(coll: any, fn: any): any[] { return __asArr(coll).filter(fn); }
function __map(coll: any, fn: any): any[] { return __asArr(coll).map(fn); }
// Pure `at(coll, i)` throws on out-of-range index; mirror that.
function __at(coll: any, i: number): any {
  const arr = __asArr(coll);
  if (i < 0 || i >= arr.length) {
    throw new Error("The system is trying to get an element at offset " + i + " where the collection is of size " + arr.length);
  }
  return arr[i];
}
function __size(coll: any): number { return __asArr(coll).length; }

// Pure passes each positional argument wrapped in a `List` ({values: [...]});
// unwrap each list back into a positional value (single -> scalar, otherwise
// the array).
function __evaluate(lambda: any, paramsList: any): any {
  const params = __asArr(paramsList).map((p: any) => {
    const vs = p && p.values !== undefined ? __asArr(p.values) : [p];
    return vs.length === 1 ? vs[0] : vs;
  });
  return (lambda as any)(...params);
}

// Date arithmetic — operates directly on JS Date instances. Returns a NEW
// Date (Pure date ops are non-mutating). All field accesses use UTC so the
// emitted ISO string parses identically regardless of the host JVM's
// timezone. `units` is a Pure enum string (DurationUnit.DAYS, etc.) reduced
// to its last `::`-segment by upstream stringification.
function __adjust(d: Date, n: number, units: any): Date {
  const dt = new Date(d.getTime());
  const u = String(units).split(".").pop();
  switch (u) {
    case "DAYS":    dt.setUTCDate(dt.getUTCDate() + n); break;
    case "HOURS":   dt.setUTCHours(dt.getUTCHours() + n); break;
    case "MINUTES": dt.setUTCMinutes(dt.getUTCMinutes() + n); break;
    case "SECONDS": dt.setUTCSeconds(dt.getUTCSeconds() + n); break;
    case "WEEKS":   dt.setUTCDate(dt.getUTCDate() + 7 * n); break;
    case "MONTHS":  dt.setUTCMonth(dt.getUTCMonth() + n); break;
    case "YEARS":   dt.setUTCFullYear(dt.getUTCFullYear() + n); break;
  }
  return dt;
}
function __dateDiff(a: Date, b: Date, units: any): number {
  const ms = b.getTime() - a.getTime();
  const u = String(units).split(".").pop();
  switch (u) {
    case "DAYS":    return Math.floor(ms / 86400000);
    case "HOURS":   return Math.floor(ms / 3600000);
    case "MINUTES": return Math.floor(ms / 60000);
    case "SECONDS": return Math.floor(ms / 1000);
    case "WEEKS":   return Math.floor(ms / (7 * 86400000));
    case "MONTHS":  return (b.getUTCFullYear() - a.getUTCFullYear()) * 12 + (b.getUTCMonth() - a.getUTCMonth());
    case "YEARS":   return b.getUTCFullYear() - a.getUTCFullYear();
  }
  return 0;
}
function __datePart(d: Date): Date {
  // Return a Date at midnight UTC for the calendar day part. Tests assert by
  // toString, so the resulting Date renders as 'YYYY-MM-DD' through __toString.
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
}

// Date field getters — UTC throughout. monthNumber is 1-indexed (matches
// Pure semantics) while JS getUTCMonth is 0-indexed.
function __year(d: Date): number        { return d.getUTCFullYear(); }
function __monthNumber(d: Date): number { return d.getUTCMonth() + 1; }
function __dayOfMonth(d: Date): number  { return d.getUTCDate(); }
function __hour(d: Date): number        { return d.getUTCHours(); }
function __minute(d: Date): number      { return d.getUTCMinutes(); }
function __second(d: Date): number      { return d.getUTCSeconds(); }
function __millis(d: Date): number      { return d.getUTCMilliseconds(); }

// String + regex helpers.
function __replace(s: string, from: string, to: string): string {
  return String(s).split(from).join(to);
}
function __matches(s: string, pat: string): boolean {
  return new RegExp("^" + pat + "$").test(String(s));
}
function __regexpReplace(s: string, pat: string, repl: string): string {
  return String(s).replace(new RegExp(pat, "g"), repl);
}
function __regexpExtract(s: string, pat: string): string {
  const m = String(s).match(new RegExp(pat));
  return m ? m[0] : "";
}
function __regexpIndexOf(s: string, pat: string): number {
  return String(s).search(new RegExp(pat));
}
function __regexpCount(s: string, pat: string): number {
  const m = String(s).match(new RegExp(pat, "g"));
  return m ? m.length : 0;
}
// Handles multi-byte characters via spread.
function __reverseString(s: string): string { return [...String(s)].reverse().join(""); }

// Pure's `joinStrings` has four overloads (1, 2, 3, 4 args). Encoded as a
// single helper to keep the translator-side coder uniform and to support
// scalar-tolerant collections (a single value is treated as a 1-element
// sequence, matching Pure's `[*]` slot conventions). Stringifies each
// element via `__toString` so dates / enum values / pairs render with
// Pure's conventions, not JS defaults.
function __joinStrings(coll: any, prefixOrSep?: any, sepOrUndef?: any, suffixOrUndef?: any): string {
  const arr = __asArr(coll).map(__toString);
  // 1 arg: joinStrings(coll) -> ''.join (no separator at all)
  if (prefixOrSep === undefined) return arr.join("");
  // 2 args: joinStrings(coll, sep) -> sep-joined
  if (sepOrUndef === undefined) return arr.join(String(prefixOrSep));
  // 3 args: joinStrings(coll, prefix, suffix) is NOT a Pure overload, but
  // the 3-arg form some emitters produce is (coll, sep, suffix). We map to
  // (sep + suffix) here defensively; if Pure callers exercise this we can
  // refine.
  if (suffixOrUndef === undefined) return arr.join(String(prefixOrSep)) + String(sepOrUndef);
  // 4 args: joinStrings(coll, prefix, sep, suffix)
  return String(prefixOrSep) + arr.join(String(sepOrUndef)) + String(suffixOrUndef);
}

// Pure semantics: 'true' (any case) -> true, 'false' (any case) -> false.
function __parseBoolean(s: string): boolean { return String(s).toLowerCase() === "true"; }

function __fail(msg: any): never { throw new Error(String(msg)); }

// Pure metamodel classifier hierarchy. Maps a metaclass path to its supertype
// chain (parent paths up to Any). Used by `__instanceOf` to walk class-ref
// `_kind` chains. Hand-coded because the metamodel is stable; user-class
// hierarchies are added per-source by the translator.
const __metaHierarchy: Record<string, string[]> = {
  "meta::pure::metamodel::type::Any": [],
  "meta::pure::metamodel::type::Type": ["meta::pure::metamodel::PackageableElement", "meta::pure::metamodel::type::Any"],
  "meta::pure::metamodel::type::DataType": ["meta::pure::metamodel::type::Type", "meta::pure::metamodel::PackageableElement", "meta::pure::metamodel::type::Any"],
  "meta::pure::metamodel::type::PrimitiveType": ["meta::pure::metamodel::type::DataType", "meta::pure::metamodel::type::Type", "meta::pure::metamodel::PackageableElement", "meta::pure::metamodel::type::Any"],
  "meta::pure::metamodel::type::Class": ["meta::pure::metamodel::type::Type", "meta::pure::metamodel::PackageableElement", "meta::pure::metamodel::type::Any"],
  "meta::pure::metamodel::type::Enumeration": ["meta::pure::metamodel::type::DataType", "meta::pure::metamodel::type::Type", "meta::pure::metamodel::PackageableElement", "meta::pure::metamodel::type::Any"],
  "meta::pure::metamodel::PackageableElement": ["meta::pure::metamodel::type::Any"]
};

// Pure-style classifier check.
//  - JS primitives: typeof dispatch (Integer/Float/Number/Decimal map to
//    `typeof === "number"`; String -> "string"; Boolean -> "boolean"; "Any"
//    matches anything).
//  - Class-ref values (with `_kind`): walk the classifier chain from
//    `__metaHierarchy` starting at `_kind`'s path. Returns true iff `t.path`
//    is the classifier or one of its ancestors.
//  - Instance values (with `_type`): treat the instance's class as the chain
//    root; check direct match against `t.path` then walk the embedded
//    user-class hierarchy if present.
function __instanceOf(v: any, t: any): boolean {
  const tPath: string | undefined = (t && typeof t === "object" && t.path) || undefined;
  if (tPath === "meta::pure::metamodel::type::Any") return v !== undefined && v !== null;
  if (v === undefined || v === null) return false;
  // PE pointer: delegate to the resolver. The host walks the classifier +
  // generalizations chain in Java, mirroring Pure's `instanceOf` native.
  // Covers `Boolean->instanceOf(PrimitiveType)`, `CC_Person->instanceOf(Type)`,
  // etc. without needing a JS-side hierarchy table.
  if (typeof v === "object" && v.__ptr && tPath) {
    return __pureHost.instanceOf(v.__ptr, tPath);
  }
  if (v instanceof Date) {
    return tPath === "meta::pure::metamodel::type::primitives::Date"
        || tPath === "meta::pure::metamodel::type::primitives::StrictDate"
        || tPath === "meta::pure::metamodel::type::primitives::DateTime"
        || tPath === "meta::pure::metamodel::type::primitives::StrictTime";
  }
  if (typeof v === "string") {
    return tPath === "meta::pure::metamodel::type::primitives::String";
  }
  if (typeof v === "number") {
    // Integer vs Float distinction: JS Number is one type; Pure separates
    // Integer and Float. Use `Number.isInteger` to split. `Number` and
    // `Decimal` are common supertypes so they match either. Borderline cases
    // (`1.0`) fall on the Integer side.
    const isNum = tPath === "meta::pure::metamodel::type::primitives::Number"
              || tPath === "meta::pure::metamodel::type::primitives::Decimal";
    if (isNum) return true;
    return Number.isInteger(v as number)
      ? tPath === "meta::pure::metamodel::type::primitives::Integer"
      : tPath === "meta::pure::metamodel::type::primitives::Float";
  }
  if (typeof v === "boolean") {
    return tPath === "meta::pure::metamodel::type::primitives::Boolean";
  }
  if (typeof v === "object" && v._kind) {
    const kindPath = "meta::pure::metamodel::type::" + v._kind;
    if (kindPath === tPath) return true;
    const chain = __metaHierarchy[kindPath] || [];
    return chain.includes(tPath || "");
  }
  if (typeof v === "object" && Array.isArray(v._type)) {
    if (v._type.includes(tPath || "")) return true;
  }
  // Self-describing instances carry `classifierGenericType: { rawType:
  // __ptr('classPath') }`. The classifier path is already known, so ask
  // the host for a type-to-type subtype walk (no second classifier hop).
  if (typeof v === "object" && v.classifierGenericType
      && v.classifierGenericType.rawType
      && v.classifierGenericType.rawType.path
      && tPath) {
    return __pureHost.subtypeOf(v.classifierGenericType.rawType.path, tPath);
  }
  return false;
}

// Emit a class-ref shape that the adapter's toPureValue resolver detects
// (any JS object with a `path` string member that maps to a real PE). The
// resolver returns the canonical Pure-side element, so
// `assertIs(Boolean, pathToElement('...::Boolean'))` is identity-stable on
// the Pure side. The 2-arg form (`pathToElement(p, sep)`) normalizes to `::`
// separator so resolver.getElement finds the canonical path. The `name` slot
// is just the last `::`-segment; it's informational and gets dropped by the
// lift.
function __pathToElement(p: string, sep?: string): any {
  const norm = sep && sep !== "::" ? p.split(sep).join("::") : p;
  return { name: norm.split("::").pop() || "", path: norm };
}

// Runtime classifier introspection on PDOs created via newCoder. PDOs are
// tagged with `_type: {name, path}` at construction; if absent (e.g. a raw
// primitive), return undefined / a stub so downstream `.type` chains don't
// crash.
function __type(v: any): any {
  if (v && typeof v === "object" && v._type) return v._type;
  return undefined;
}
function __genericType(v: any): any {
  return { type: __type(v) };
}

// Identity assertion. Falls back to structural identity on the `.path` slot
// (the only stable identifier for metamodel-element stubs produced by
// `_type`/class-ref/enum-value emitters).
function __assertIs(expected: any, actual: any): boolean {
  if (expected === actual) return true;
  if (expected && actual && typeof expected === "object" && typeof actual === "object") {
    if (expected.path !== undefined && expected.path === actual.path) return true;
    if (expected.name !== undefined && expected.name === actual.name) return true;
  }
  throw new Error("assertIs: expected " + __toRepresentation(expected) + " is " + __toRepresentation(actual));
}

// Pure RegexpParameter enum values become JS regex flag chars:
//   CASE_INSENSITIVE -> "i"
//   MULTILINE        -> "m"
//   NON_NEWLINE_SENSITIVE (Pure semantic: `.` matches `\n`) -> "s" (dotAll)
//   CASE_SENSITIVE is the JS default - no flag.
// Each entry is either a string name (after JS toString of a PDO) or an
// object exposing `name`. We tolerate both shapes.
function __regexpLike(str: string, pat: string, flags: any[]): boolean {
  let f = "";
  const arr = Array.isArray(flags) ? flags : [flags];
  for (const v of arr) {
    const n = (v && typeof v === "object" && v.name !== undefined) ? String(v.name) : String(v);
    const last = n.split(".").pop();
    if (last === "CASE_INSENSITIVE") f += "i";
    else if (last === "MULTILINE") f += "m";
    else if (last === "NON_NEWLINE_SENSITIVE") f += "s";
  }
  return new RegExp(pat, f).test(str);
}

// `__pureHost` is the Java-side resolver bridge bound by
// TypeScriptCompileNatives. Its `.get(path, propName)` reads the named
// property off the PE at `path` via the same TruffleMetadataAccess resolver
// the rest of Pure uses, and returns the result as a sequence. The
// metamodel graph (Classes, Multiplicities, ...) stays in PDB; only the
// slots tests actually read are paid for.
declare const __pureHost: {
  get(path: string, propName: string): any[];
  elementRef(path: string): any;
  instanceOf(valuePath: string, typePath: string): boolean;
  subtypeOf(subPath: string, supPath: string): boolean;
  lambdaProp(idx: number, propName: string): any[];
};

// ============================================================================
// Phase 2 (path-keyed metadata strategy) — the JS-native API the translator
// emits. Designed to be backed by EITHER the Truffle host bridge (today) or
// an in-JS PDB reader (standalone TS platform tomorrow). The contract is:
//
//   __metadataRead(address, prop)   — read a slot's value off the PE/sub-PE
//                                     at `address`. Returns plain JS values
//                                     only; PDO references come back as
//                                     stubs `{__purePath: '<sub-address>'}`
//                                     that __pureResolve can wrap.
//
//   __metadataSubtypeOf(a, b)       — type-walk; cheap on Java (TypeCache),
//                                     expensive in pure JS so kept as a
//                                     dedicated host helper.
//
//   __metadataInstanceOf(addr, t)   — instanceOf walk; same rationale.
//
// __pureResolve / __pdo / __lambda are pure-JS helpers (no host bridge of
// their own) that build on these three primitives. Swap-in a PDB reader by
// reimplementing the three globals; the helpers below are unchanged.
// ============================================================================

declare function __metadataRead(address: string, prop: string): any;

// JS-side: build a Proxy keyed by `address`. Each property read routes
// through __metadataRead. Returned PDO stubs (`{__purePath: '<addr>'}`) get
// recursively wrapped in __pureResolve so JS code can chain naturally:
//   `__pureResolve('CC_Address').properties.at(0).name`
function __pureResolve(address: string): any {
  return new Proxy({ __purePath: address, path: address }, {
    get(_target, prop) {
      if (typeof prop !== 'string') return undefined;
      if (prop === '__purePath' || prop === 'path') return address;
      if (__reservedProxyProps.has(prop)) return undefined;
      const raw = __metadataRead(address, prop);
      return __rewrapStubs(raw);
    }
  });
}

// If a returned value carries `__purePath`, rewrap as a Proxy. Recurse
// through arrays so a sequence of PDOs becomes a sequence of proxies.
function __rewrapStubs(v: any): any {
  if (v === undefined || v === null) return v;
  if (Array.isArray(v)) return v.map(__rewrapStubs);
  if (typeof v === 'object' && typeof v.__purePath === 'string'
      && Object.keys(v).length <= 2) {
    return __pureResolve(v.__purePath);
  }
  return v;
}

// Resolve a tagged-JS value (lambda) to its Pure PDO proxy. Pass-through
// for values that don't carry __purePath (already a proxy, or a non-Pure
// scalar/object). Translator wraps every reflection-target receiver in
// __pdo so the call site doesn't need to know whether the value is a raw
// arrow, a proxy, or something else.
function __pdo(v: any): any {
  if (typeof v === 'function' && typeof v.__purePath === 'string') {
    return __pureResolve(v.__purePath);
  }
  return v;
}

// Tag a JS arrow with its synthetic path. The arrow stays callable; the
// tag lets __pdo find the lambda's Pure PDO on demand.
function __lambda(fn: Function, syntheticPath: string): any {
  (fn as any).__purePath = syntheticPath;
  return fn;
}

// JS property names we MUST NOT route through the resolver. These are
// internal hooks JS engines look up on every object (toJSON, then,
// Symbol.toPrimitive, ...); dispatching them to `__pureHost.get` would
// either deadlock the JS engine or emit a spurious empty-array. Listed
// once so both `__ptr` and `__lambdaWithMeta` Proxies share the filter.
const __reservedProxyProps = new Set([
  "then", "toJSON", "toString", "valueOf", "asymmetricMatch",
  "constructor", "Symbol.toPrimitive", "Symbol.iterator", "@@toPrimitive",
  "@@iterator", "@@toStringTag"
]);

// `__ptr(path)` — JS-side proxy for a Pure PackageableElement. Property
// access dispatches to `__pureHost.get(path, prop)` so reads are lazy and
// PDB-backed. The proxy preserves `.path` for identity-style assertions
// (`assertIs(a, b)` compares `a.path === b.path`).
//
// Result unwrapping: the host always returns a sequence (Object[]) to
// mirror Pure's `[*]` slot semantics, but most Pure properties are `[1]`
// or `[0..1]` and JS callers expect scalars (`enumVal.name === 'X'`,
// `myClass.properties.filter(...)` etc). Unwrap singletons here so the
// most common case is ergonomic; multi-element results stay as arrays.
function __ptr(path: string): any {
  return new Proxy({ __ptr: path, path: path }, {
    get(t: any, prop: any): any {
      if (typeof prop !== "string") return Reflect.get(t, prop);
      if (prop === "__ptr" || prop === "path") return path;
      if (__reservedProxyProps.has(prop)) return undefined;
      const result = __pureHost.get(path, prop);
      // The host always returns a Java `Object[]`. GraalJS treats foreign
      // arrays as JS-truthy, so an empty `Object[0]` would still make
      // `if (obj.prop)` pass and confuse downstream checks. Collapse the
      // 0-length case to `undefined` (no value) and the 1-length case to
      // the scalar (singleton-unwrap).
      if (Array.isArray(result)) {
        if (result.length === 0) return undefined;
        if (result.length === 1) return result[0];
      }
      return result;
    }
  });
}

// `__lambdaPtr(arrow, idx)` — JS-side proxy for a translated lambda. The
// arrow stays callable (Proxy's default apply trap dispatches to it), but
// property reads route through `__pureHost.lambdaProp(idx, propName)`,
// which walks the canonical PDO the translator was given to find lambda
// `idx`'s metadata. So `.expressionSequence`, `.parameters`,
// `.classifierGenericType`, etc. all come from the graph rather than
// inline JS data — symmetric with how `__ptr(path)` handles PE refs.
//
// Singleton-unwrap mirrors `__ptr`: most lambda slots are `[1]` and JS
// callers expect a scalar (`lam.classifierGenericType.rawType`); host
// returns `[v]` and we unwrap to `v` here.
function __lambdaPtr(arrow: Function, idx: number): any {
  return new Proxy(arrow, {
    get(target: any, prop: any): any {
      if (typeof prop !== "string") return Reflect.get(target, prop);
      // JS Function built-ins (length, name, call, apply, bind, prototype):
      // pass through to the underlying arrow.
      if (prop in target) return Reflect.get(target, prop);
      if (__reservedProxyProps.has(prop)) return undefined;
      const result = __pureHost.lambdaProp(idx, prop);
      // Same 0/1/many collapse as `__ptr`: 0 → undefined (otherwise the
      // empty `Object[0]` is JS-truthy via Polyglot interop and breaks
      // `if (lam.classifierGenericType)` style guards in `__matchType`).
      if (Array.isArray(result)) {
        if (result.length === 0) return undefined;
        if (result.length === 1) return result[0];
      }
      return result;
    }
  });
}

// Pure-style toString. Recognizes:
//   - Date                            -> ISO `YYYY-MM-DDThh:mm:ss[.fff]` (no Z)
//   - Pair  ({first, second})         -> `<first, second>`
//   - List  ({values: [...]})         -> `[a, b, c]`
//   - Array literals                  -> `[a, b, c]`
//   - Enumeration values ({name:...}) -> the name
//   - everything else                 -> JS String(...)
function __toString(v: any): string {
  if (v === undefined || v === null) return "";
  if (v instanceof Date) {
    // Format the Date as the Pure-canonical ISO string. Strip the trailing
    // `Z` JS adds for UTC, and the trailing `.000` when sub-second precision
    // is zero so we don't print spurious milliseconds.
    let s = v.toISOString();
    if (s.endsWith("Z")) s = s.slice(0, -1);
    if (s.endsWith(".000")) s = s.slice(0, -4);
    return s;
  }
  if (Array.isArray(v)) return "[" + v.map(__toString).join(", ") + "]";
  if (typeof v === "object") {
    if (v.first !== undefined && v.second !== undefined && Object.keys(v).length === 2)
      return "<" + __toString(v.first) + ", " + __toString(v.second) + ">";
    if (Array.isArray(v.values) && Object.keys(v).length === 1)
      return "[" + v.values.map(__toString).join(", ") + "]";
    // Both `{name: 'X'}` literals AND `pdoProxy`-wrapped enum values match
    // here: literals have `Object.keys.length === 1`, the proxy returns
    // `[]` for `Object.keys` (it doesn't enumerate its underlying slots).
    if (typeof v.name === "string" && Object.keys(v).length <= 1) return v.name;
    return JSON.stringify(v);
  }
  return String(v);
}
