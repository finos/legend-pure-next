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
  // Pure Maps (`{__mapEntries:[[k,v],...]}`) compare by entries — order
  // independent, key matched by `===`. Handled explicitly because the generic
  // comparison below skips the `_`-prefixed `__mapEntries` key.
  if (__isMap(a) || __isMap(b)) {
    if (!__isMap(a) || !__isMap(b)) return false;
    const ea = a.__mapEntries, eb = b.__mapEntries;
    if (ea.length !== eb.length) return false;
    for (const [k, va] of ea) {
      const i = __mapFindIdx(eb, k);
      if (i < 0 || !__eq(va, eb[i][1])) return false;
    }
    return true;
  }
  // Compare own keys, skipping `_`-prefixed internals and treating an
  // `undefined` value as an absent key — an unset Pure `[0..1]` property is
  // empty, but a captured PDO serializes it as `prop: undefined` while an
  // inline `^X(...)` omits it; both must compare equal.
  const keysOf = (o: any): string[] =>
    Object.keys(o).filter(k => !k.startsWith("_") && o[k] !== undefined);
  const ka = keysOf(a); const kb = keysOf(b);
  if (ka.length !== kb.length) return false;
  for (const k of ka) { if (!__eq((a as any)[k], (b as any)[k])) return false; }
  return true;
}

// Pure-style `format`: %s (any), %d/%i (integer with optional 0-pad width like
// %05d), %f (float, default 6-digit precision, optional .N like %.4f for
// trailing zeros / rounding), %r (toRepresentation). Args consumed positionally.
// Pure date-format directive `%t{pattern}` — a SimpleDateFormat subset:
// yyyy/MM/dd, HH (24h) / hh,h (12h), mm/ss/SSS, a (AM/PM), Z (+0000) / X (Z when
// UTC), "literal" runs, and an optional leading [ZONE] that shifts to a fixed
// offset (the tests use EST=-5, CET=+1; no DST).
function __formatDatePattern(d: any, pattern: string): string {
  if (!(d instanceof Date)) return __toString(d);
  let offMin = 0, pat = pattern;
  const zm = /^\[([A-Za-z]+)\]/.exec(pattern);
  if (zm !== null) {
    const z = zm[1].toUpperCase();
    const off: { [k: string]: number } = { GMT: 0, UTC: 0, EST: -300, EDT: -240, CST: -360, CET: 60, CEST: 120, PST: -480, PDT: -420 };
    offMin = off[z] || 0;
    pat = pattern.slice(zm[0].length);
  }
  const t = new Date(d.getTime() + offMin * 60000);  // shift to zone-local
  const p2 = (n: number) => String(n).padStart(2, "0");
  const H = t.getUTCHours(), h12 = ((H + 11) % 12) + 1;
  const ao = Math.abs(offMin), offStr = (offMin >= 0 ? "+" : "-") + p2(Math.floor(ao / 60)) + p2(ao % 60);
  const tok: { [k: string]: string } = {
    yyyy: String(t.getUTCFullYear()).padStart(4, "0"), MM: p2(t.getUTCMonth() + 1), dd: p2(t.getUTCDate()),
    HH: p2(H), hh: p2(h12), h: String(h12), mm: p2(t.getUTCMinutes()), ss: p2(t.getUTCSeconds()),
    SSS: String(t.getUTCMilliseconds()).padStart(3, "0"), a: H < 12 ? "AM" : "PM",
    Z: offStr, X: offMin === 0 ? "Z" : offStr,
  };
  const keys = ["yyyy", "SSS", "MM", "dd", "HH", "hh", "mm", "ss", "h", "a", "Z", "X"];
  let out = "", i = 0;
  while (i < pat.length) {
    if (pat[i] === '"') {  // quoted literal run
      const j = pat.indexOf('"', i + 1);
      out += pat.slice(i + 1, j < 0 ? pat.length : j);
      i = j < 0 ? pat.length : j + 1;
      continue;
    }
    const k = keys.find(kk => pat.startsWith(kk, i));
    if (k !== undefined) { out += tok[k]; i += k.length; }
    else { out += pat[i]; i++; }
  }
  return out;
}
function __format(tpl: string, args: any): string {
  const arr: any[] = Array.isArray(args) ? args : (args === undefined ? [] : [args]);
  let i = 0;
  return tpl.replace(/%(0?[0-9]+)?(?:\.([0-9]+))?(?:([sdifr])|(t)(?:\{([^}]*)\})?)/g,
    (_m: string, width: string, prec: string, kind: string, tflag: string, datePat: string): string => {
    const v = arr[i++];
    // `%t{pattern}` formats a date; bare `%t` is the date's default toString.
    if (tflag === "t") return datePat !== undefined ? __formatDatePattern(v, datePat) : __toString(v);
    if (v === undefined) return "";
    switch (kind) {
      case "s": return __toString(v);
      case "d":
      case "i": {
        const n = Math.trunc(Number(v));
        let s = Math.abs(n).toString();
        if (width && width.startsWith("0")) {
          // Pure's `%0Nd` pads the DIGITS to N; the sign is extra (`%05d` of -3
          // is "-00003", unlike Java's "-0003").
          const w = parseInt(width, 10);
          while (s.length < w) s = "0" + s;
        }
        return (n < 0 ? "-" : "") + s;
      }
      // `%f` without explicit precision -> natural float (no spurious trailing
      // zeros); `%.Nf` -> fixed N decimals (Pure renders a value that rounds to
      // zero as "0.00", not JS's "-0.00").
      case "f": {
        if (prec === undefined) return __floatStr1(Number(v));
        let s = Number(v).toFixed(parseInt(prec, 10));
        if (parseFloat(s) === 0) s = s.replace("-", "");
        return s;
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
  // Resolver proxy / class-ref (Class, function, ...) — its full path is the
  // representation (`toRepresentation(someFn)` -> 'pkg::fn_…'). Avoids
  // `String(proxy)` throwing "Cannot convert object to primitive value".
  if ((typeof v === "object" || typeof v === "function") && typeof v.__purePath === "string") return v.__purePath;
  if (typeof v === "object") return __toString(v);
  return String(v);
}

// Pure Float toString/toRepresentation: always a decimal point, never
// exponential. JS `String(17.0)` is "17" and `String(1.3421e-8)` is
// "1.3421e-8"; Pure wants "17.0" and "0.000000013421". Float vs Integer is a
// static-type distinction (both are JS number at runtime), so the toString /
// toRepresentation coders opt into this only for Float-typed arguments.
function __floatStr(v: any): any {
  return Array.isArray(v) ? v.map(__floatStr1) : __floatStr1(v);
}
function __floatStr1(n: any): string {
  if (typeof n !== "number" || !isFinite(n)) return String(n);
  if (Number.isInteger(n)) return n.toFixed(1);
  const s = String(n);
  return /[eE]/.test(s) ? __expandExp(s) : s;
}
// Expand exponential notation to plain decimal via string manipulation (not
// toFixed, which introduces float-precision artifacts) so the shortest
// round-trippable digits are preserved.
function __expandExp(s: string): string {
  const m = /^(-?)(\d+)(?:\.(\d+))?[eE]([+-]?\d+)$/.exec(s);
  if (m === null) return s;
  const sign = m[1], digits = m[2] + (m[3] || ""), exp = parseInt(m[4], 10);
  const point = m[2].length + exp;
  let out: string;
  if (point <= 0) out = "0." + "0".repeat(-point) + digits;
  else if (point >= digits.length) out = digits + "0".repeat(point - digits.length) + ".0";
  else out = digits.slice(0, point) + "." + digits.slice(point);
  if (out.includes(".")) out = out.replace(/(\.\d*?)0+$/, "$1").replace(/\.$/, ".0");
  return sign + out;
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
function __pair(first: any, second: any): any {
  return { first, second, classifierGenericType: { type: __pureResolve('meta::pure::functions::collection::Pair'), typeArguments: [{}, {}] } };
}
function __zip(a: any, b: any): any[] {
  const A = __asArr(a), B = __asArr(b);
  const n = Math.min(A.length, B.length);
  const out = [];
  for (let i = 0; i < n; i++) out.push(__pair(A[i], B[i]));
  return out;
}
// Pure slice(low, high) clamps both bounds to [0, size]; unlike JS
// Array.slice it never treats a negative low as a from-the-end offset.
function __slice(coll: any, low: number, high: number): any[] {
  const a = __asArr(coll);
  const lo = Math.max(0, Math.min(low, a.length));
  const hi = Math.max(lo, Math.min(high, a.length));
  return a.slice(lo, hi);
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
// the nested Error carry `classifierGenericType: { type: __pureResolve('...') }`
// so the Java-side toPureValue can lift each level to the right Pure class
// without the caller threading per-slot type info — the JS object is
// self-describing.
function __tryEval(thunk: () => any): any {
  try {
    return {
      value: thunk(),
      failure: undefined,
      classifierGenericType: { type: __pureResolve("meta::pure::functions::lang::TryResult") }
    };
  }
  catch (e: any) {
    const msg = e && e.message !== undefined ? String(e.message) : String(e);
    return {
      value: undefined,
      failure: {
        message: msg,
        stack: [],
        classifierGenericType: { type: __pureResolve("meta::pure::functions::lang::Error") }
      },
      classifierGenericType: { type: __pureResolve("meta::pure::functions::lang::TryResult") }
    };
  }
}

// Dedup using an eq function (binary) or key extractor (unary). Pure overloads
// both shapes.
// Pure removeDuplicates forms:
//   removeDuplicates(coll, eql)         — eql(keptElem, candidate) decides dup
//   removeDuplicates(coll, keyFn, eql)  — eql(keptKey, candidateKey) on derived keys
// Arg order matters for asymmetric comparators: Pure compares the
// already-kept element/key as the FIRST arg, the candidate as the second.
function __removeDuplicatesBy(coll: any, fn1: Function, fn2?: Function): any[] {
  const arr = __asArr(coll);
  const out: any[] = [];
  if (fn2 !== undefined) {
    const keptKeys: any[] = [];
    for (const x of arr) {
      const kx = (fn1 as any)(x);
      if (!keptKeys.some(ky => (fn2 as any)(ky, kx))) { keptKeys.push(kx); out.push(x); }
    }
  } else if ((fn1 as any).length >= 2) {
    for (const x of arr) if (!out.some(y => (fn1 as any)(y, x))) out.push(x);
  } else {
    const seen = new Set();
    for (const x of arr) { const k = (fn1 as any)(x); if (!seen.has(k)) { seen.add(k); out.push(x); } }
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
// we read the element's `classifierGenericType.type.path` and ask the
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
        // `__pureResolve`-proxy (PE pointer): the host's instanceOf reads the
        // value-element's classifier and walks from there. Right for the
        // value-as-instance case (`Boolean->instanceOf(PrimitiveType)`,
        // enum-value match).
        if (e.__purePath) return __metadataInstanceOf(e.__purePath, typePath);
        // Self-describing instance: `classifierGenericType.type.path`
        // already points at the value's classifier — feed it to `subtypeOf`
        // directly (no second classifier-of hop, which would jump up to
        // `Class` and break `^CO_Person->match([p:CO_Person[1]|...])`).
        if (e.classifierGenericType
            && e.classifierGenericType.type
            && e.classifierGenericType.type.path) {
          return __metadataSubtypeOf(e.classifierGenericType.type.path, typePath);
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
// Pure Maps are a JS-native plain object `{ __mapEntries: [[k, v], ...] }` —
// an entries list rather than a `{stringKey: value}` object, so non-string
// keys (e.g. a class instance from `groupBy(p|$p.address)`) keep their
// identity. `toPureValue` lifts this to a Pure `MapImpl` by reading the
// `__mapEntries` array (plain member access — no JS Map / hash interop).
// Key matching is `===`: value-equality for primitives, identity for objects
// (Pure's semantics for keyless classes).
function __isMap(m: any): boolean { return !!m && typeof m === "object" && Array.isArray(m.__mapEntries); }
function __mapEntriesOf(m: any): any[] { return __isMap(m) ? m.__mapEntries : []; }
function __mapFindIdx(entries: any[], k: any): number {
  for (let i = 0; i < entries.length; i++) { if (entries[i][0] === k) return i; }
  return -1;
}
function __mapSet(entries: any[], k: any, v: any): void {
  const i = __mapFindIdx(entries, k);
  if (i >= 0) { entries[i] = [k, v]; } else { entries.push([k, v]); }
}
function __newMap(pairs: any): any {
  const entries: any[] = [];
  if (pairs !== undefined) for (const p of (Array.isArray(pairs) ? pairs : [pairs])) __mapSet(entries, p.first, p.second);
  return { __mapEntries: entries };
}
function __mapPut(m: any, k: any, v: any): any {
  const e = __mapEntriesOf(m).map((x: any) => x.slice());
  __mapSet(e, k, v);
  return { __mapEntries: e };
}
function __mapRemoveAll(m: any, keys: any): any {
  const ks = __asArr(keys);
  return { __mapEntries: __mapEntriesOf(m).filter((x: any) => !ks.some((k: any) => k === x[0])) };
}
function __mapKeyValues(m: any): any[] {
  return __mapEntriesOf(m).map(([k, v]: any) => ({ first: k, second: v }));
}
function __mapKeys(m: any): any[] { return __mapEntriesOf(m).map((x: any) => x[0]); }
function __mapValues(m: any): any[] { return __mapEntriesOf(m).map((x: any) => x[1]); }
// Pure semantics: return value at key, computing keyFn(key) lazily if absent.
// Map is treated as immutable so we return the value (the outer caller threads
// any mutation through put if needed).
function __mapGetIfAbsentPut(m: any, k: any, keyFn: any): any {
  const i = __mapFindIdx(__mapEntriesOf(m), k);
  return i >= 0 ? __mapEntriesOf(m)[i][1] : keyFn(k);
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
// Pure `putAll` is overloaded: putAll(Map, Pair[*]) and putAll(Map, Map). The
// second arg arrives as either a pairs array/single pair (`{first, second}`)
// or a map object (`{k: v}`); merge accordingly.
function __mapPutAll(m: any, kvs: any): any {
  const e = __mapEntriesOf(m).map((x: any) => x.slice());
  if (Array.isArray(kvs)) {
    for (const kv of kvs) __mapSet(e, kv.first, kv.second);
  } else if (__isMap(kvs)) {
    for (const [k, v] of kvs.__mapEntries) __mapSet(e, k, v);
  } else if (kvs && typeof kvs === "object" && "first" in kvs && "second" in kvs) {
    __mapSet(e, kvs.first, kvs.second);
  }
  return { __mapEntries: e };
}
function __mapRemove(m: any, k: any): any {
  return { __mapEntries: __mapEntriesOf(m).filter((x: any) => x[0] !== k) };
}
function __mapGet(m: any, k: any): any {
  const e = __mapEntriesOf(m);
  const i = __mapFindIdx(e, k);
  return i >= 0 ? e[i][1] : [];
}

// Pure `groupBy` returns Map<K, List<V>> — each group is a List (`{values}`),
// not a bare array, so `groupBy(...)->get(k).values` reads the members and
// `groupBy(...)->keys()` returns the (possibly non-primitive) key objects.
// Backed by a JS Map so object keys keep their identity.
function __groupBy(coll: any, keyFn: (x: any) => any): any {
  const entries: any[] = [];
  for (const x of __asArr(coll)) {
    const k = keyFn(x);
    let i = __mapFindIdx(entries, k);
    if (i < 0) { entries.push([k, { values: [] }]); i = entries.length - 1; }
    entries[i][1].values.push(x);
  }
  return { __mapEntries: entries };
}

// Pure date semantics can't be fully recreated in JS, but a real Date object
// is closer than the prior `%`-tagged string. JS parses ISO strings natively;
// downstream date arithmetic / accessors operate on the resulting Date.
function __parseDate(s: string): Date {
  return __pdate(String(s).replace(/^%/, ""));
}
// parseDecimal(s, precision, scale) — round to `scale` decimal places.
function __parseDecimalScaled(s: any, scale: any): number {
  return Number(parseFloat(String(s)).toFixed(Number(scale)));
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
// Empty sequences are normalised to `undefined` at the slot read
// (__rewrapStubs), so only undefined needs handling here.
function __asArr(v: any): any[] { return v === undefined ? [] : (Array.isArray(v) ? v : [v]); }
function __isEmpty(v: any): boolean { return __asArr(v).length === 0; }
function __isNotEmpty(v: any): boolean { return __asArr(v).length > 0; }
function __filter(coll: any, fn: any): any[] { return __asArr(coll).filter(fn); }
// Pure `map` returns V[n*m]: when the mapping fn yields a collection, the
// results are flattened one level (Pure has no nested collections). JS `.map`
// would build an array-of-arrays, so flatMap + __asArr per element matches
// Pure semantics (scalar fn results pass through, multi-valued ones flatten).
function __map(coll: any, fn: any): any[] { return __asArr(coll).flatMap((x: any) => __asArr(fn(x))); }
// Pure `at(coll, i)` throws on out-of-range index; mirror that.
function __at(coll: any, i: number): any {
  const arr = __asArr(coll);
  if (i < 0 || i >= arr.length) {
    throw new Error("The system is trying to get an element at offset " + i + " where the collection is of size " + arr.length);
  }
  return arr[i];
}
function __size(coll: any): number { return __asArr(coll).length; }
// Pure take/drop clamp the count (no JS negative-index wraparound): take(n<=0)
// is empty, drop(n<=0) is the whole collection.
function __take(coll: any, n: number): any[] { return n <= 0 ? [] : __asArr(coll).slice(0, n); }
function __drop(coll: any, n: number): any[] { return __asArr(coll).slice(n < 0 ? 0 : n); }
// Pure natural ordering: numbers compare numerically (JS `.sort()` defaults to
// lexicographic, so `[1,171,2]` would mis-sort); everything else by toString.
function __defaultCompare(a: any, b: any): number {
  if (typeof a === "number" && typeof b === "number") return a - b;
  const sa = __toString(a), sb = __toString(b);
  return sa < sb ? -1 : (sa > sb ? 1 : 0);
}
// sort(coll[, key][, comp]) — sorts a copy by `comp(key(a), key(b))`.
function __sort(coll: any, key?: any, comp?: any): any[] {
  const k = key || ((x: any) => x);
  const c = comp || __defaultCompare;
  return __asArr(coll).slice().sort((a: any, b: any) => c(k(a), k(b)));
}
// orElse(v[0..1], default) — `default` only when v is an empty sequence
// (undefined/null/[]); a present scalar like '' or 0 is kept (unlike `??`).
function __orElse(v: any, def: any): any { return __isEmpty(v) ? def : v; }
// Pure `toOne(coll)` returns the single element of a [1] collection. The
// translator treats scalars and 1-element arrays interchangeably, so unwrap
// to the first element (scalars pass through via __asArr). This matters when
// the receiver is an array — `coll.filter(...).toOne().prop` must read `.prop`
// off the element, not the array (arrays have no Pure properties).
function __toOne(v: any): any { return __asArr(v)[0]; }

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
// Map Pure RegexpParameter enum values to JS regex flags (plus `g` when
// global). Each param is `{name: 'CASE_INSENSITIVE'}` (or a proxy / string).
function __regexpFlags(global: boolean, params: any): string {
  let f = global ? "g" : "";
  for (const p of __asArr(params)) {
    const nm = (p && typeof p === "object" && p.name !== undefined) ? p.name : p;
    const last = String(nm).split(".").pop();
    if (last === "CASE_INSENSITIVE") f += "i";
    else if (last === "MULTILINE") f += "m";
    else if (last === "NON_NEWLINE_SENSITIVE") f += "s";
  }
  return f;
}
function __regexpReplace(s: string, pat: string, repl: string, replaceAll?: boolean, params?: any): string {
  // replaceAll=false replaces only the first occurrence (no `g` flag).
  return String(s).replace(new RegExp(pat, __regexpFlags(replaceAll === true, params)), repl);
}
function __regexpExtract(s: string, pat: string, extractAll?: boolean, groupNumber?: any, params?: any): any {
  const g = Number(groupNumber) || 0;
  const flags = __regexpFlags(false, params);
  if (extractAll === true) {
    const re = new RegExp(pat, flags + "g");
    const out: any[] = [];
    let m: RegExpExecArray | null;
    while ((m = re.exec(String(s))) !== null) {
      out.push(m[g]);
      if (m.index === re.lastIndex) re.lastIndex++;
    }
    return out;
  }
  const m = String(s).match(new RegExp(pat, flags));
  return m ? m[g] : "";
}
function __regexpIndexOf(s: string, pat: string, groupNumber?: any, params?: any): number {
  const g = Number(groupNumber) || 0;
  // `d` flag exposes per-group start/end offsets via `m.indices`.
  const m = new RegExp(pat, __regexpFlags(false, params) + "d").exec(String(s));
  if (m === null) return -1;
  if (g === 0) return m.index;
  const idx = (m as any).indices;
  return (idx && idx[g]) ? idx[g][0] : -1;
}
function __regexpCount(s: string, pat: string, params?: any): number {
  const m = String(s).match(new RegExp(pat, __regexpFlags(true, params)));
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
  // PE pointer (a __pureResolve proxy): delegate to the resolver. The host
  // reads the value-element's classifier and walks classifier +
  // generalizations in Java, mirroring Pure's `instanceOf` native. Covers
  // `Boolean->instanceOf(PrimitiveType)`, `CC_Person->instanceOf(Type)`,
  // etc. without needing a JS-side hierarchy table.
  if (typeof v === "object" && v.__purePath && tPath) {
    return __metadataInstanceOf(v.__purePath, tPath);
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
  // Self-describing instances carry `classifierGenericType: { type:
  // <classifier> }`. The classifier is either a __pureResolve proxy (has
  // `.path` -> host subtype walk) or a runtime-built element (newClass /
  // newEnumeration, no path -> walk its generalizations in JS).
  if (typeof v === "object" && v.classifierGenericType
      && v.classifierGenericType.type && tPath) {
    return __subtypeViaGeneralizations(v.classifierGenericType.type, tPath);
  }
  return false;
}

// True if `cls` is, or transitively generalizes to, the type at `tPath`.
// Resolvable classifiers (with `.path`) delegate to the host subtype walk;
// runtime-built ones (no path) are walked via `generalizations[].general.type`.
function __subtypeViaGeneralizations(cls: any, tPath: string): boolean {
  if (cls === undefined || cls === null) return false;
  if (typeof cls.path === "string") {
    return cls.path === tPath || __metadataSubtypeOf(cls.path, tPath);
  }
  for (const g of __asArr(cls.generalizations)) {
    const gt = g && g.general && g.general.type;
    if (gt && __subtypeViaGeneralizations(gt, tPath)) return true;
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
declare function __metadataSubtypeOf(subPath: string, supPath: string): boolean;
declare function __metadataInstanceOf(valuePath: string, typePath: string): boolean;

// JS-side: build a Proxy keyed by `address`. Each property read routes
// through __metadataRead. Returned PDO stubs (`{__purePath: '<addr>'}`) get
// recursively wrapped in __pureResolve so JS code can chain naturally:
//   `__pureResolve('CC_Address').properties.at(0).name`
// Memoised by address so two references to the same element are `===` —
// Pure's `assertIs(Class, x)` is a reference-identity check, and a fresh
// Proxy per call would never compare equal.
const __resolveCache: { [addr: string]: any } = {};
function __pureResolve(address: string): any {
  const hit = __resolveCache[address];
  if (hit !== undefined) return hit;
  const p = new Proxy({ __purePath: address, path: address }, {
    get(_target, prop) {
      if (typeof prop !== 'string') return undefined;
      if (prop === '__purePath' || prop === 'path') return address;
      if (__reservedProxyProps.has(prop)) return undefined;
      const raw = __metadataRead(address, prop);
      return __rewrapStubs(raw);
    }
  });
  __resolveCache[address] = p;
  return p;
}

// If a returned value carries `__purePath`, rewrap as a Proxy. Recurse
// through arrays so a sequence of PDOs becomes a sequence of proxies.
// Normalise the empty-slot representation at the read: a Java null (an unset
// [0..1] slot) becomes `undefined` so the rest of the runtime has a single
// empty representation and consumers don't each have to handle null.
function __rewrapStubs(v: any): any {
  if (v === undefined || v === null) return undefined;
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
// tag lets __pdo find the lambda's Pure PDO on demand. `openVars` (optional)
// captures the lambda's open variables {name: value} at construction time, so
// `openVariableValues(lambda)` can report them — a JS closure's captured
// bindings aren't otherwise introspectable.
function __lambda(fn: Function, syntheticPath: string, openVars?: any): any {
  (fn as any).__purePath = syntheticPath;
  if (openVars !== undefined) (fn as any).__openVars = openVars;
  return fn;
}

// Pure `openVariableValues(lambda)` -> Map<String, List<Any>>: each captured
// open variable mapped to a `List` ({values: [...]}) of its value(s). Reads
// the {name: value} snapshot stashed on the arrow by __lambda.
function __openVariableValues(lam: any): any {
  const ov = (lam && (lam as any).__openVars) || {};
  const entries: any[] = [];
  for (const k of Object.keys(ov)) entries.push([k, { values: __asArr(ov[k]) }]);
  return { __mapEntries: entries };
}

// Construction context for Pure's `~` parent-reference. A `^Class(...)` whose
// key expressions reference `~`/`~.~` (the object being built, or an enclosing
// one) pushes the object onto __ctorStack BEFORE evaluating its keys, so those
// references resolve to the in-progress instance. Keys are assigned in source
// order, so a later key (e.g. classifierGenericType) can read an earlier one
// (e.g. typeParameters) via `~`. `__ctorAt(n)` reads n levels out from the
// top (`~` -> 0, `~.~` -> 1, `~.~.~` -> 2).
const __ctorStack: any[] = [];
function __ctorAt(depth: number): any { return __ctorStack[__ctorStack.length - 1 - depth]; }
function __newObj(base: any, fill: (self: any) => void): any {
  __ctorStack.push(base);
  try { fill(base); } finally { __ctorStack.pop(); }
  return base;
}
// `^$x(k = ~...)` — copy `src`, then fill with the copy on the stack so its
// key expressions can reference `~` (the copy itself / enclosing instances).
function __copyCtx(src: any, fill: (self: any) => void): any {
  const self = (src !== null && typeof src === "object" && !Array.isArray(src)) ? { ...src } : src;
  __ctorStack.push(self);
  try { fill(self); } finally { __ctorStack.pop(); }
  return self;
}

// Pure `genericType(v)` -> a GenericTypeValue {type: <classifier>, ...}.
// Object values either carry `classifierGenericType` (self-describing
// instances) or expose it lazily via the resolver proxy (element refs), so
// `__pdo(v).classifierGenericType` yields the right GenericTypeValue. JS
// primitives have no Pure metadata, so map their runtime type to the Pure
// primitive type element. `%2014-01` and `%2015-03-14` are both JS `Date`s —
// indistinguishable here — so a Date maps to the `Date` primitive type
// (matching `type()`'s expectation for partial dates).
function __genericType(v: any): any {
  if (v === undefined || v === null) return undefined;
  if (typeof v === "number") {
    return { type: __pureResolve(Number.isInteger(v)
      ? "meta::pure::metamodel::type::primitives::Integer"
      : "meta::pure::metamodel::type::primitives::Float") };
  }
  if (typeof v === "string") {
    return { type: __pureResolve("meta::pure::metamodel::type::primitives::String") };
  }
  if (typeof v === "boolean") {
    return { type: __pureResolve("meta::pure::metamodel::type::primitives::Boolean") };
  }
  if (v instanceof Date) {
    return { type: __pureResolve("meta::pure::metamodel::type::primitives::Date") };
  }
  return __pdo(v).classifierGenericType;
}

// JS property names we MUST NOT route through the resolver. These are
// internal hooks JS engines look up on every object (toJSON, then,
// Symbol.toPrimitive, ...); dispatching them to `__metadataRead` would
// either deadlock the JS engine or emit a spurious empty-array. Used by the
// `__pureResolve` Proxy's `get` trap.
const __reservedProxyProps = new Set([
  "then", "toJSON", "toString", "valueOf", "asymmetricMatch",
  "constructor", "Symbol.toPrimitive", "Symbol.iterator", "@@toPrimitive",
  "@@iterator", "@@toStringTag"
]);

// Pure-style toString. Recognizes:
//   - Date                            -> ISO `YYYY-MM-DDThh:mm:ss[.fff]` (no Z)
//   - Pair  ({first, second})         -> `<first, second>`
//   - List  ({values: [...]})         -> `[a, b, c]`
//   - Array literals                  -> `[a, b, c]`
//   - Enumeration values ({name:...}) -> the name
//   - everything else                 -> JS String(...)
// Pure date literal -> JS Date that remembers its source literal. The literal
// carries granularity (date-only vs datetime, sub-second digit count) that a
// JS Date loses. A literal with no timezone is parsed as UTC (append Z) so the
// host's local zone never shifts it; an explicit offset is converted to UTC.
function __pdate(lit: string): Date {
  // Reconstruct a strictly-ISO, UTC, zero-padded, 3-digit-millis string for the
  // JS Date constructor. Pure literals may be unpadded (`2014-1-1T0:00:00`) and
  // carry non-3-digit sub-seconds, both of which JS Date rejects. The compiler
  // has already folded any timezone offset into the (UTC) literal, so no offset
  // conversion is needed here. __lit keeps the original for granularity.
  const m = /^(\d{1,4})(?:-(\d{1,2}))?(?:-(\d{1,2}))?(?:T(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?(?:\.(\d+))?)?([zZ]|[+-]\d{2}:?\d{2})?$/.exec(lit);
  let parse = lit;
  if (m !== null) {
    const Y = m[1].padStart(4, "0"), Mo = (m[2] || "01").padStart(2, "0"), Da = (m[3] || "01").padStart(2, "0");
    const H = (m[4] || "00").padStart(2, "0"), Mi = (m[5] || "00").padStart(2, "0"), S = (m[6] || "00").padStart(2, "0");
    const ms = m[7] ? (m[7] + "000").slice(0, 3) : "000";
    parse = Y + "-" + Mo + "-" + Da + "T" + H + ":" + Mi + ":" + S + "." + ms + (m[8] || "Z");
  }
  const d = new Date(parse);
  (d as any).__lit = lit;
  // Pre-compute the canonical Pure date string (UTC, granularity-preserved) so
  // the Java-side toPureValue can round-trip the Date to a PureDate by value.
  (d as any).__fmt = __formatPureDate(d);
  return d;
}
// Format a Date the Pure way: UTC components (timezone already folded in),
// rendered to the granularity of the originating literal (`__lit`).
function __formatPureDate(d: Date): string {
  const lit: string | undefined = (d as any).__lit;
  const p2 = (n: number) => String(n).padStart(2, "0");
  if (lit === undefined) {
    let s = d.toISOString();
    if (s.endsWith("Z")) s = s.slice(0, -1);
    if (s.endsWith(".000")) s = s.slice(0, -4);
    return s;
  }
  const Y = String(d.getUTCFullYear()).padStart(4, "0");
  const Mo = p2(d.getUTCMonth() + 1), Da = p2(d.getUTCDate());
  const tIdx = lit.indexOf("T");
  if (tIdx < 0) {
    const parts = lit.split("-").length;
    return parts === 1 ? Y : (parts === 2 ? Y + "-" + Mo : Y + "-" + Mo + "-" + Da);
  }
  const H = p2(d.getUTCHours()), Mi = p2(d.getUTCMinutes()), S = p2(d.getUTCSeconds());
  // Time granularity from the literal: `hh:mm` (1 colon) omits seconds;
  // `hh:mm:ss` (2 colons) includes them. Sub-second digits come verbatim from
  // the literal (a timezone offset shifts hours/minutes, not the fraction).
  const timeNoTz = lit.slice(tIdx + 1).replace(/([zZ]|[+-]\d{2}:?\d{2})$/, "");
  const colons = (timeNoTz.match(/:/g) || []).length;
  const dot = /\.(\d+)/.exec(timeNoTz);
  let out = Y + "-" + Mo + "-" + Da + "T" + H + ":" + Mi;
  if (colons >= 2) out += ":" + S;
  if (dot !== null) out += "." + dot[1];
  return out;
}

function __toString(v: any): string {
  if (v === undefined || v === null) return "";
  if (v instanceof Date) return __formatPureDate(v);
  if (Array.isArray(v)) return "[" + v.map(__toString).join(", ") + "]";
  if (typeof v === "object") {
    // Resolver proxy / class-ref (Class, Enumeration, ...) — render its name
    // (the simple name slot, else the last path segment). Checked first
    // because the proxy also exposes nested slots that would match below.
    if (typeof v.__purePath === "string") {
      const nm = Array.isArray(v.name) ? v.name[0] : v.name;
      if (typeof nm === "string" && nm.length) return nm;
      return v.__purePath.split("::").pop();
    }
    // Pair `<a, b>` and List `[a, b, c]`. Self-describing instances also carry
    // a `classifierGenericType` key, so match on the payload shape only — not
    // an exact key count.
    if (v.first !== undefined && v.second !== undefined)
      return "<" + __toString(v.first) + ", " + __toString(v.second) + ">";
    if (Array.isArray(v.values))
      return "[" + v.values.map(__toString).join(", ") + "]";
    // `{name: 'X'}` enum-value / named-element literal.
    if (typeof v.name === "string") return v.name;
    return JSON.stringify(v);
  }
  return String(v);
}
