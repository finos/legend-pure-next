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

const Big = function() {
  "use strict";
  var Big2, DP = 20, RM = 1, MAX_DP = 1e6, MAX_POWER = 1e6, NE = -7, PE = 21, STRICT = false, NAME = "[big.js] ", INVALID = NAME + "Invalid ", INVALID_DP = INVALID + "decimal places", INVALID_RM = INVALID + "rounding mode", DIV_BY_ZERO = NAME + "Division by zero", P = {}, UNDEFINED = void 0, NUMERIC = /^-?(\d+(\.\d*)?|\.\d+)(e[+-]?\d+)?$/i;
  function _Big_() {
    function Big3(n) {
      var x = this;
      if (!(x instanceof Big3)) return n === UNDEFINED ? _Big_() : new Big3(n);
      if (n instanceof Big3) {
        x.s = n.s;
        x.e = n.e;
        x.c = n.c.slice();
      } else {
        if (typeof n !== "string") {
          if (Big3.strict === true && typeof n !== "bigint") {
            throw TypeError(INVALID + "value");
          }
          n = n === 0 && 1 / n < 0 ? "-0" : String(n);
        }
        parse(x, n);
      }
      x.constructor = Big3;
    }
    Big3.prototype = P;
    Big3.DP = DP;
    Big3.RM = RM;
    Big3.NE = NE;
    Big3.PE = PE;
    Big3.strict = STRICT;
    Big3.roundDown = 0;
    Big3.roundHalfUp = 1;
    Big3.roundHalfEven = 2;
    Big3.roundUp = 3;
    return Big3;
  }
  function parse(x, n) {
    var e, i, nl;
    if (!NUMERIC.test(n)) {
      throw Error(INVALID + "number");
    }
    x.s = n.charAt(0) == "-" ? (n = n.slice(1), -1) : 1;
    if ((e = n.indexOf(".")) > -1) n = n.replace(".", "");
    if ((i = n.search(/e/i)) > 0) {
      if (e < 0) e = i;
      e += +n.slice(i + 1);
      n = n.substring(0, i);
    } else if (e < 0) {
      e = n.length;
    }
    nl = n.length;
    for (i = 0; i < nl && n.charAt(i) == "0"; ) ++i;
    if (i == nl) {
      x.c = [x.e = 0];
    } else {
      for (; nl > 0 && n.charAt(--nl) == "0"; ) ;
      x.e = e - i - 1;
      x.c = [];
      for (e = 0; i <= nl; ) x.c[e++] = +n.charAt(i++);
    }
    return x;
  }
  function round(x, sd, rm, more) {
    var xc = x.c;
    if (rm === UNDEFINED) rm = x.constructor.RM;
    if (rm !== 0 && rm !== 1 && rm !== 2 && rm !== 3) {
      throw Error(INVALID_RM);
    }
    if (sd < 1) {
      more = rm === 3 && (more || !!xc[0]) || sd === 0 && (rm === 1 && xc[0] >= 5 || rm === 2 && (xc[0] > 5 || xc[0] === 5 && (more || xc[1] !== UNDEFINED)));
      xc.length = 1;
      if (more) {
        x.e = x.e - sd + 1;
        xc[0] = 1;
      } else {
        xc[0] = x.e = 0;
      }
    } else if (sd < xc.length) {
      more = rm === 1 && xc[sd] >= 5 || rm === 2 && (xc[sd] > 5 || xc[sd] === 5 && (more || xc[sd + 1] !== UNDEFINED || xc[sd - 1] & 1)) || rm === 3 && (more || !!xc[0]);
      xc.length = sd;
      if (more) {
        for (; ++xc[--sd] > 9; ) {
          xc[sd] = 0;
          if (sd === 0) {
            ++x.e;
            xc.unshift(1);
            break;
          }
        }
      }
      for (sd = xc.length; !xc[--sd]; ) xc.pop();
    }
    return x;
  }
  function stringify(x, doExponential, isNonzero) {
    var e = x.e, s = x.c.join(""), n = s.length;
    if (doExponential) {
      s = s.charAt(0) + (n > 1 ? "." + s.slice(1) : "") + (e < 0 ? "e" : "e+") + e;
    } else if (e < 0) {
      for (; ++e; ) s = "0" + s;
      s = "0." + s;
    } else if (e > 0) {
      if (++e > n) {
        for (e -= n; e--; ) s += "0";
      } else if (e < n) {
        s = s.slice(0, e) + "." + s.slice(e);
      }
    } else if (n > 1) {
      s = s.charAt(0) + "." + s.slice(1);
    }
    return x.s < 0 && isNonzero ? "-" + s : s;
  }
  P.abs = function() {
    var x = new this.constructor(this);
    x.s = 1;
    return x;
  };
  P.cmp = function(y) {
    var isneg, x = this, xc = x.c, yc = (y = new x.constructor(y)).c, i = x.s, j = y.s, k = x.e, l = y.e;
    if (!xc[0] || !yc[0]) return !xc[0] ? !yc[0] ? 0 : -j : i;
    if (i != j) return i;
    isneg = i < 0;
    if (k != l) return k > l ^ isneg ? 1 : -1;
    j = (k = xc.length) < (l = yc.length) ? k : l;
    for (i = -1; ++i < j; ) {
      if (xc[i] != yc[i]) return xc[i] > yc[i] ^ isneg ? 1 : -1;
    }
    return k == l ? 0 : k > l ^ isneg ? 1 : -1;
  };
  P.div = function(y) {
    var x = this, Big3 = x.constructor, a = x.c, b = (y = new Big3(y)).c, k = x.s == y.s ? 1 : -1, dp = Big3.DP;
    if (dp !== ~~dp || dp < 0 || dp > MAX_DP) {
      throw Error(INVALID_DP);
    }
    if (!b[0]) {
      throw Error(DIV_BY_ZERO);
    }
    if (!a[0]) {
      y.s = k;
      y.c = [y.e = 0];
      return y;
    }
    var bl, bt, n, cmp, ri, bz = b.slice(), ai = bl = b.length, al = a.length, r = a.slice(0, bl), rl = r.length, q = y, qc = q.c = [], qi = 0, p = dp + (q.e = x.e - y.e) + 1;
    q.s = k;
    k = p < 0 ? 0 : p;
    bz.unshift(0);
    for (; rl++ < bl; ) r.push(0);
    do {
      for (n = 0; n < 10; n++) {
        if (bl != (rl = r.length)) {
          cmp = bl > rl ? 1 : -1;
        } else {
          for (ri = -1, cmp = 0; ++ri < bl; ) {
            if (b[ri] != r[ri]) {
              cmp = b[ri] > r[ri] ? 1 : -1;
              break;
            }
          }
        }
        if (cmp < 0) {
          for (bt = rl == bl ? b : bz; rl; ) {
            if (r[--rl] < bt[rl]) {
              ri = rl;
              for (; ri && !r[--ri]; ) r[ri] = 9;
              --r[ri];
              r[rl] += 10;
            }
            r[rl] -= bt[rl];
          }
          for (; !r[0]; ) r.shift();
        } else {
          break;
        }
      }
      qc[qi++] = cmp ? n : ++n;
      if (r[0] && cmp) r[rl] = a[ai] || 0;
      else r = [a[ai]];
    } while ((ai++ < al || r[0] !== UNDEFINED) && k--);
    if (!qc[0] && qi != 1) {
      qc.shift();
      q.e--;
      p--;
    }
    if (qi > p) round(q, p, Big3.RM, r[0] !== UNDEFINED);
    return q;
  };
  P.eq = function(y) {
    return this.cmp(y) === 0;
  };
  P.gt = function(y) {
    return this.cmp(y) > 0;
  };
  P.gte = function(y) {
    return this.cmp(y) > -1;
  };
  P.lt = function(y) {
    return this.cmp(y) < 0;
  };
  P.lte = function(y) {
    return this.cmp(y) < 1;
  };
  P.minus = P.sub = function(y) {
    var i, j, t, xlty, x = this, Big3 = x.constructor, a = x.s, b = (y = new Big3(y)).s;
    if (a != b) {
      y.s = -b;
      return x.plus(y);
    }
    var xc = x.c.slice(), xe = x.e, yc = y.c, ye = y.e;
    if (!xc[0] || !yc[0]) {
      if (yc[0]) {
        y.s = -b;
      } else if (xc[0]) {
        y = new Big3(x);
      } else {
        y.s = 1;
      }
      return y;
    }
    if (a = xe - ye) {
      if (xlty = a < 0) {
        a = -a;
        t = xc;
      } else {
        ye = xe;
        t = yc;
      }
      t.reverse();
      for (b = a; b--; ) t.push(0);
      t.reverse();
    } else {
      j = ((xlty = xc.length < yc.length) ? xc : yc).length;
      for (a = b = 0; b < j; b++) {
        if (xc[b] != yc[b]) {
          xlty = xc[b] < yc[b];
          break;
        }
      }
    }
    if (xlty) {
      t = xc;
      xc = yc;
      yc = t;
      y.s = -y.s;
    }
    if ((b = (j = yc.length) - (i = xc.length)) > 0) for (; b--; ) xc[i++] = 0;
    for (b = i; j > a; ) {
      if (xc[--j] < yc[j]) {
        for (i = j; i && !xc[--i]; ) xc[i] = 9;
        --xc[i];
        xc[j] += 10;
      }
      xc[j] -= yc[j];
    }
    for (; xc[--b] === 0; ) xc.pop();
    for (; xc[0] === 0; ) {
      xc.shift();
      --ye;
    }
    if (!xc[0]) {
      y.s = 1;
      xc = [ye = 0];
    }
    y.c = xc;
    y.e = ye;
    return y;
  };
  P.mod = function(y) {
    var ygtx, x = this, Big3 = x.constructor, a = x.s, b = (y = new Big3(y)).s;
    if (!y.c[0]) {
      throw Error(DIV_BY_ZERO);
    }
    x.s = y.s = 1;
    ygtx = y.cmp(x) == 1;
    x.s = a;
    y.s = b;
    if (ygtx) return new Big3(x);
    a = Big3.DP;
    b = Big3.RM;
    Big3.DP = Big3.RM = 0;
    x = x.div(y);
    Big3.DP = a;
    Big3.RM = b;
    return this.minus(x.times(y));
  };
  P.neg = function() {
    var x = new this.constructor(this);
    x.s = -x.s;
    return x;
  };
  P.plus = P.add = function(y) {
    var e, k, t, x = this, Big3 = x.constructor;
    y = new Big3(y);
    if (x.s != y.s) {
      y.s = -y.s;
      return x.minus(y);
    }
    var xe = x.e, xc = x.c, ye = y.e, yc = y.c;
    if (!xc[0] || !yc[0]) {
      if (!yc[0]) {
        if (xc[0]) {
          y = new Big3(x);
        } else {
          y.s = x.s;
        }
      }
      return y;
    }
    xc = xc.slice();
    if (e = xe - ye) {
      if (e > 0) {
        ye = xe;
        t = yc;
      } else {
        e = -e;
        t = xc;
      }
      t.reverse();
      for (; e--; ) t.push(0);
      t.reverse();
    }
    if (xc.length - yc.length < 0) {
      t = yc;
      yc = xc;
      xc = t;
    }
    e = yc.length;
    for (k = 0; e; xc[e] %= 10) k = (xc[--e] = xc[e] + yc[e] + k) / 10 | 0;
    if (k) {
      xc.unshift(k);
      ++ye;
    }
    for (e = xc.length; xc[--e] === 0; ) xc.pop();
    y.c = xc;
    y.e = ye;
    return y;
  };
  P.pow = function(n) {
    var x = this, one = new x.constructor("1"), y = one, isneg = n < 0;
    if (n !== ~~n || n < -MAX_POWER || n > MAX_POWER) {
      throw Error(INVALID + "exponent");
    }
    if (isneg) n = -n;
    for (; ; ) {
      if (n & 1) y = y.times(x);
      n >>= 1;
      if (!n) break;
      x = x.times(x);
    }
    return isneg ? one.div(y) : y;
  };
  P.prec = function(sd, rm) {
    if (sd !== ~~sd || sd < 1 || sd > MAX_DP) {
      throw Error(INVALID + "precision");
    }
    return round(new this.constructor(this), sd, rm);
  };
  P.round = function(dp, rm) {
    if (dp === UNDEFINED) dp = 0;
    else if (dp !== ~~dp || dp < -MAX_DP || dp > MAX_DP) {
      throw Error(INVALID_DP);
    }
    return round(new this.constructor(this), dp + this.e + 1, rm);
  };
  P.sqrt = function() {
    var r, c, t, x = this, Big3 = x.constructor, s = x.s, e = x.e, half = new Big3("0.5");
    if (!x.c[0]) return new Big3(x);
    if (s < 0) {
      throw Error(NAME + "No square root");
    }
    s = Math.sqrt(+stringify(x, true, true));
    if (s === 0 || s === 1 / 0) {
      c = x.c.join("");
      if (!(c.length + e & 1)) c += "0";
      s = Math.sqrt(c);
      e = ((e + 1) / 2 | 0) - (e < 0 || e & 1);
      r = new Big3((s == 1 / 0 ? "5e" : (s = s.toExponential()).slice(0, s.indexOf("e") + 1)) + e);
    } else {
      r = new Big3(s + "");
    }
    e = r.e + (Big3.DP += 4);
    do {
      t = r;
      r = half.times(t.plus(x.div(t)));
    } while (t.c.slice(0, e).join("") !== r.c.slice(0, e).join(""));
    return round(r, (Big3.DP -= 4) + r.e + 1, Big3.RM);
  };
  P.times = P.mul = function(y) {
    var c, x = this, Big3 = x.constructor, xc = x.c, yc = (y = new Big3(y)).c, a = xc.length, b = yc.length, i = x.e, j = y.e;
    y.s = x.s == y.s ? 1 : -1;
    if (!xc[0] || !yc[0]) {
      y.c = [y.e = 0];
      return y;
    }
    y.e = i + j;
    if (a < b) {
      c = xc;
      xc = yc;
      yc = c;
      j = a;
      a = b;
      b = j;
    }
    for (c = new Array(j = a + b); j--; ) c[j] = 0;
    for (i = b; i--; ) {
      b = 0;
      for (j = a + i; j > i; ) {
        b = c[j] + yc[i] * xc[j - i - 1] + b;
        c[j--] = b % 10;
        b = b / 10 | 0;
      }
      c[j] = b;
    }
    if (b) ++y.e;
    else c.shift();
    for (i = c.length; !c[--i]; ) c.pop();
    y.c = c;
    return y;
  };
  P.toExponential = function(dp, rm) {
    var x = this, n = x.c[0];
    if (dp !== UNDEFINED) {
      if (dp !== ~~dp || dp < 0 || dp > MAX_DP) {
        throw Error(INVALID_DP);
      }
      x = round(new x.constructor(x), ++dp, rm);
      for (; x.c.length < dp; ) x.c.push(0);
    }
    return stringify(x, true, !!n);
  };
  P.toFixed = function(dp, rm) {
    var x = this, n = x.c[0];
    if (dp !== UNDEFINED) {
      if (dp !== ~~dp || dp < 0 || dp > MAX_DP) {
        throw Error(INVALID_DP);
      }
      x = round(new x.constructor(x), dp + x.e + 1, rm);
      for (dp = dp + x.e + 1; x.c.length < dp; ) x.c.push(0);
    }
    return stringify(x, false, !!n);
  };
  P.toJSON = P.toString = function() {
    var x = this, Big3 = x.constructor;
    return stringify(x, x.e <= Big3.NE || x.e >= Big3.PE, !!x.c[0]);
  };
  P.toNumber = function() {
    var n = +stringify(this, true, true);
    if (this.constructor.strict === true && !this.eq(n.toString())) {
      throw Error(NAME + "Imprecise conversion");
    }
    return n;
  };
  P.toPrecision = function(sd, rm) {
    var x = this, Big3 = x.constructor, n = x.c[0];
    if (sd !== UNDEFINED) {
      if (sd !== ~~sd || sd < 1 || sd > MAX_DP) {
        throw Error(INVALID + "precision");
      }
      x = round(new Big3(x), sd, rm);
      for (; x.c.length < sd; ) x.c.push(0);
    }
    return stringify(x, sd <= x.e || x.e <= Big3.NE || x.e >= Big3.PE, !!n);
  };
  P.valueOf = function() {
    var x = this, Big3 = x.constructor;
    if (Big3.strict === true) {
      throw Error(NAME + "valueOf disallowed");
    }
    return stringify(x, x.e <= Big3.NE || x.e >= Big3.PE, true);
  };
  Big2 = _Big_();
  Big2["default"] = Big2.Big = Big2;
  return Big2;
}();
Big.prototype.__isDec = true;
Big.DP = 40;
Big.RM = 2;
function __isDec(x) {
  return x instanceof Big;
}
function __dec(x) {
  if (x instanceof Big) return x;
  if (typeof x === "bigint") return new Big(x.toString());
  return new Big(x);
}
function __parseDec(x) {
  return new Big(String(x).trim().replace(/[dD]$/, "").replace(/^\+/, ""));
}
function __assert(c, m) {
  if (!c) {
    throw new Error(String(m));
  }
  return true;
}
// assertEquals / assertSize — the VALUE IS A PARAMETER here, so each argument
// expression is evaluated exactly once and the failure message is built only on
// failure. The coders used to place the translated `actual` AST into the output
// twice (once for the comparison, once interpolated into an eagerly-built
// message), which silently RE-RAN it. Harmless for `1 + 2`; for a PCT adapter
// lambda it meant a second canonicalize + translate + compile + eval on every
// PASSING assertion — 2x the work, and 2x the sources the gallery displayed.
function __unwrapAtomicValue(v) {
  while (v !== null && typeof v === "object" && !Array.isArray(v) && Object.prototype.hasOwnProperty.call(v, "value")) {
    const cgt = v.classifierGenericType;
    const singleton = cgt && cgt.__purePath;
    const t = cgt && cgt.type;
    const isAtomic = typeof singleton === "string"
      ? singleton.endsWith("GenericType_meta_pure_metamodel_valuespecification_AtomicValue")
      : (t && t.__purePath) === "meta::pure::metamodel::valuespecification::AtomicValue";
    if (!isAtomic || v.value === undefined || v.value === null) break;
    v = v.value;
  }
  return v;
}
// ---------------------------------------------------------------------------
// Pure stack traces. The serializer leaves position markers in generated code
// (serialization.pure): `/*@L:C*/` before a call site, `/*@fn sid:L:C name*/`
// before a function, `/*@lambda sid:L:C*/` before a lambda's arrow. When an
// error reaches Pure (assertError, tryEval) its V8 call sites are mapped back
// through them: a frame whose enclosing function literal is immediately
// preceded by a function or lambda marker is a Pure frame, located at the
// nearest call marker before the frame's position. Every other frame
// (runtime-lib, host code, coder IIFEs) is skipped. The format matches
// Truffle's PureStackFormatter: `<name> (<sourceId>:<line>c<column>)`.
// Needs V8 call sites and a host that returns a frame's source text
// (__hostSourceText); elsewhere the trace is empty.
// ---------------------------------------------------------------------------
if (typeof Error.captureStackTrace === "function" && !globalThis.__pureCallSitesInstalled) {
  globalThis.__pureCallSitesInstalled = true;
  const previous = Error.prepareStackTrace;
  Error.prepareStackTrace = function (error, sites) {
    if (error !== null && typeof error === "object") {
      try {
        Object.defineProperty(error, "__callSites", { value: sites, configurable: true, enumerable: false });
      } catch {
      }
    }
    if (typeof previous === "function") return previous(error, sites);
    let head;
    try {
      head = String(error);
    } catch {
      head = "Error";
    }
    return head + sites.map((site) => "\n    at " + site).join("");
  };
  // Pure call chains run deeper than V8's default 10 frames.
  if (Error.stackTraceLimit < 100) Error.stackTraceLimit = 100;
}
const __markerIndexCache = new Map();
function __markerIndex(fileName) {
  const text = typeof globalThis.__hostSourceText === "function" ? globalThis.__hostSourceText(fileName) : void 0;
  // Keyed by file name but checked against the text: a host may re-evaluate
  // different code under the same name.
  const cached = __markerIndexCache.get(fileName);
  if (cached !== void 0 && cached.text === text) return cached.index;
  let index = null;
  if (typeof text === "string" && text.indexOf("/*@") >= 0) {
    const lineStarts = [0];
    for (let i = text.indexOf("\n"); i >= 0; i = text.indexOf("\n", i + 1)) lineStarts.push(i + 1);
    const calls = [];
    const definitions = new Map();
    const marker = /\/\*@(?:(fn|lambda) (.+?):(\d+):(\d+)(?: (\S+))?|(\d+):(\d+))\*\//g;
    for (let m; (m = marker.exec(text)) !== null;) {
      const end = m.index + m[0].length;
      if (m[1] !== void 0) definitions.set(end, { kind: m[1], sourceId: m[2], line: m[3], column: m[4], name: m[5] });
      else calls.push([end, m[6], m[7]]);
    }
    index = { lineStarts, calls, definitions };
  }
  __markerIndexCache.set(fileName, { text, index });
  return index;
}
function __pureStackFrames(error) {
  if (error === null || typeof error !== "object") return [];
  try {
    void error.stack;
  } catch {
    return [];
  }
  const sites = error.__callSites;
  if (!Array.isArray(sites)) return [];
  const frames = [];
  for (const site of sites) {
    if (typeof site.getEnclosingLineNumber !== "function") break;
    const file = site.getFileName();
    const index = file ? __markerIndex(file) : null;
    if (index === null) continue;
    const offset = (line, column) => index.lineStarts[line - 1] + column - 1;
    const enclosing = offset(site.getEnclosingLineNumber(), site.getEnclosingColumnNumber());
    const definition = index.definitions.get(enclosing);
    if (definition === void 0) continue;
    const position = offset(site.getLineNumber(), site.getColumnNumber());
    let lo = 0, hi = index.calls.length - 1, best = -1;
    while (lo <= hi) {
      const mid = lo + hi >> 1;
      if (index.calls[mid][0] <= position) {
        best = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    const call = best >= 0 && index.calls[best][0] >= enclosing ? index.calls[best] : void 0;
    const name = definition.kind === "fn" ? definition.name : "lambda";
    frames.push(name + " (" + definition.sourceId + ":" + (call ? call[1] : definition.line) + "c" + (call ? call[2] : definition.column) + ")");
  }
  return frames;
}
function __pureStackTrace(error) {
  const frames = __pureStackFrames(error);
  return frames.length === 0 ? "" : "\nPure stack trace:" + frames.map((frame) => "\n    at " + frame).join("");
}
function __assertEq(expected, actual) {
  if (__eq(expected, actual)) return true;
  throw new Error("expected " + __json(expected) + ", got " + __json(actual));
}
function __assertSize(coll, n) {
  const len = Array.isArray(coll) ? coll.length : 1;
  if (__eq(len, n)) return true;
  throw new Error("assertSize failed");
}
function __compare(a, b) {
  const rank = (x) => {
    if (x === null || x === void 0) return 0;
    if (typeof x === "boolean") return 1;
    if (typeof x === "number" || typeof x === "bigint" || x instanceof Big) return 2;
    if (x instanceof Date) return 3;
    if (typeof x === "string") return 4;
    return 5;
  };
  const ra = rank(a), rb = rank(b);
  if (ra !== rb) return ra < rb ? -1 : 1;
  if (a instanceof Big || b instanceof Big) return __dec(a).cmp(__dec(b));
  if (a instanceof Date && b instanceof Date) {
    const ta = a.getTime(), tb = b.getTime();
    if (ta !== tb) return ta < tb ? -1 : 1;
    const fa = a.__fmt, fb = b.__fmt;
    if (fa !== void 0 && fb !== void 0 && fa !== fb) return fa < fb ? -1 : 1;
    return 0;
  }
  return a < b ? -1 : a > b ? 1 : 0;
}
function __num(x) {
  return x instanceof Big ? Number(x.toString()) : typeof x === "bigint" ? Number(x) : x;
}
function __json(v) {
  const seen = /* @__PURE__ */ new WeakSet();
  return JSON.stringify(v, (_k, val) => {
    if (typeof val === "bigint") return val.toString();
    if (val instanceof Big) return val.toString();
    if (val !== null && typeof val === "object") {
      if (seen.has(val)) return "[Circular]";
      seen.add(val);
    }
    return val;
  });
}
function __bi(x) {
  return typeof x === "bigint" ? x : BigInt(Math.trunc(Number(x)));
}
function __add(a, b) {
  if (a instanceof Big || b instanceof Big) return __dec(a).plus(__dec(b));
  if (typeof a === "bigint" && typeof b === "bigint") return a + b;
  if (typeof a === "string" || typeof b === "string") return a + b;
  return __num(a) + __num(b);
}
function __sub(a, b) {
  return a instanceof Big || b instanceof Big ? __dec(a).minus(__dec(b)) : typeof a === "bigint" && typeof b === "bigint" ? a - b : __num(a) - __num(b);
}
function __mul(a, b) {
  return a instanceof Big || b instanceof Big ? __dec(a).times(__dec(b)) : typeof a === "bigint" && typeof b === "bigint" ? a * b : __num(a) * __num(b);
}
function __rem(a, b) {
  return a instanceof Big || b instanceof Big ? __dec(a).mod(__dec(b)) : typeof a === "bigint" && typeof b === "bigint" ? a % b : __num(a) % __num(b);
}
function __mod(a, b) {
  if (a instanceof Big || b instanceof Big) {
    const A = __dec(a), B = __dec(b);
    return A.mod(B).plus(B).mod(B);
  }
  if (typeof a === "bigint" && typeof b === "bigint") return (a % b + b) % b;
  const na = __num(a), nb = __num(b);
  return (na % nb + nb) % nb;
}
function __div(a, b) {
  return __num(a) / __num(b);
}
function __divScale(a, b, scale) {
  return __dec(a).div(__dec(b)).round(Number(scale));
}
function __abs(x) {
  return x instanceof Big ? x.abs() : typeof x === "bigint" ? x < 0n ? -x : x : Math.abs(x);
}
function __sign(x) {
  return x > 0 ? 1n : x < 0 ? -1n : 0n;
}
function __pow(a, b) {
  return Math.pow(__num(a), __num(b));
}
function __sqrtN(x) {
  return Math.sqrt(__num(x));
}
function __round1(x) {
  return typeof x === "bigint" ? x : BigInt(__dec(x).round(0).toString());
}
function __round2(x, scale) {
  return x instanceof Big ? x.round(Number(scale)) : Number(__dec(x).round(Number(scale)).toString());
}
function __floorI(x) {
  return typeof x === "bigint" ? x : BigInt(Math.floor(__num(x)));
}
function __ceilI(x) {
  return typeof x === "bigint" ? x : BigInt(Math.ceil(__num(x)));
}
function __min2(a, b) {
  return a <= b ? a : b;
}
function __max2(a, b) {
  return a >= b ? a : b;
}
function __minColl(c) {
  const a = __asArr(c);
  return a.length ? [a.reduce((x, y) => x <= y ? x : y)] : []; // [0..1]
}
function __maxColl(c) {
  const a = __asArr(c);
  return a.length ? [a.reduce((x, y) => x >= y ? x : y)] : []; // [0..1]
}
function __sumColl(c) {
  const a = __asArr(c);
  return a.reduce((x, y) => __add(x, y), a.some((v) => typeof v === "bigint") ? 0n : 0);
}
function __avgColl(c) {
  const a = __asArr(c);
  return a.reduce((x, y) => Number(x) + Number(y), 0) / a.length;
}
function __eq(a, b) {
  if (a === b) return true;
  // An AtomicValue compares as the value it wraps (Truffle EqualNode's
  // normalizeForEquals): a ValueSpecification lifted out of an
  // expressionSequence equals the literal it holds.
  a = __unwrapAtomicValue(a);
  b = __unwrapAtomicValue(b);
  if (a === b) return true;
  const aEmpty = a === void 0 || a === null || Array.isArray(a) && a.length === 0;
  const bEmpty = b === void 0 || b === null || Array.isArray(b) && b.length === 0;
  if (aEmpty || bEmpty) return aEmpty && bEmpty;
  if (a instanceof Date && b instanceof Date) {
    if (a.__fmt !== void 0 && b.__fmt !== void 0) return a.__fmt === b.__fmt;
    return a.getTime() === b.getTime();
  }
  if (a instanceof Date || b instanceof Date) return __toString(a) === __toString(b);
  if (Array.isArray(a) || Array.isArray(b)) {
    const aa = Array.isArray(a) ? a : [a];
    const bb = Array.isArray(b) ? b : [b];
    if (aa.length !== bb.length) return false;
    for (let i = 0; i < aa.length; i++) {
      if (!__eq(aa[i], bb[i])) return false;
    }
    return true;
  }
  const aNum = a instanceof Big || typeof a === "number" || typeof a === "bigint";
  const bNum = b instanceof Big || typeof b === "number" || typeof b === "bigint";
  if (aNum && bNum) {
    if (a instanceof Big || b instanceof Big) return __dec(a).eq(__dec(b));
    if (typeof a === "bigint" && typeof b === "bigint") return a === b;
    return Number(a) === Number(b);
  }
  if (aNum || bNum) return false;
  if (typeof a !== "object" || typeof b !== "object") return false;
  if (a.__variantJson !== void 0 || b.__variantJson !== void 0) {
    if (a.__variantJson === void 0 || b.__variantJson === void 0) return false;
    // Canonical compact serialization doubles as structural equality
    return __variantJsonStr(a.__variantJson) === __variantJsonStr(b.__variantJson);
  }
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
  const ppA = a.__purePath, ppB = b.__purePath;
  if (typeof ppA === "string" || typeof ppB === "string") return ppA === ppB;
  // Structural object compare, cycle-safe: the graph holds genuinely cyclic
  // instances (e.g. a colSpec RelationType whose classifier typeArgument is
  // its own wrapping GenericType — the JVM's AncestorRef shape), and a
  // key-by-key descent into two DISTINCT objects that each contain such a
  // cycle never terminates (the a === b fast path only covers identity).
  // Coinductive rule: a pair already under comparison is presumed equal —
  // two cyclic structures are equal iff no finite disagreement exists.
  // (Mirrors the JVM, where pureEquals' identity fast-path is what fires in
  // practice; the guard only changes the previously-non-terminating case.)
  let seenB = __EQ_IN_PROGRESS.get(a);
  if (seenB !== undefined && seenB.has(b)) return true;
  if (seenB === undefined) { seenB = new Set(); __EQ_IN_PROGRESS.set(a, seenB); }
  seenB.add(b);
  try {
    const cgtA = a.classifierGenericType, cgtB = b.classifierGenericType;
    const eqKeysA = cgtA && cgtA.__equalityKeys, eqKeysB = cgtB && cgtB.__equalityKeys;
    const eqKeys = Array.isArray(eqKeysA) ? eqKeysA : Array.isArray(eqKeysB) ? eqKeysB : void 0;
    if (eqKeys !== void 0) {
      const pa = cgtA && cgtA.type && (cgtA.type.__purePath ?? cgtA.type.path);
      const pb = cgtB && cgtB.type && (cgtB.type.__purePath ?? cgtB.type.path);
      if (pa != null && pb != null && pa !== pb) return false;
      if (eqKeys.length === 0) return a === b;
      for (const k of eqKeys) {
        if (!__eq(a[k], b[k])) return false;
      }
      return true;
    }
    const keysOf = (o) => Object.keys(o).filter((k) => !k.startsWith("_") && o[k] !== void 0);
    const ka = keysOf(a);
    const kb = keysOf(b);
    if (ka.length !== kb.length) return false;
    for (const k of ka) {
      if (!__eq(a[k], b[k])) return false;
    }
    return true;
  } finally {
    seenB.delete(b);
  }
}
const __EQ_IN_PROGRESS = new WeakMap();
function __formatDatePattern(d, pattern) {
  if (!(d instanceof Date)) return __toString(d);
  let offMin = 0, pat = pattern;
  const zm = /^\[([A-Za-z]+)\]/.exec(pattern);
  if (zm !== null) {
    const z = zm[1].toUpperCase();
    const off = { GMT: 0, UTC: 0, EST: -300, EDT: -240, CST: -360, CET: 60, CEST: 120, PST: -480, PDT: -420 };
    offMin = off[z] || 0;
    pat = pattern.slice(zm[0].length);
  }
  const t = new Date(d.getTime() + offMin * 6e4);
  const p2 = (n) => String(n).padStart(2, "0");
  const H = t.getUTCHours(), h12 = (H + 11) % 12 + 1;
  const ao = Math.abs(offMin), offStr = (offMin >= 0 ? "+" : "-") + p2(Math.floor(ao / 60)) + p2(ao % 60);
  const tok = {
    yyyy: String(t.getUTCFullYear()).padStart(4, "0"),
    MM: p2(t.getUTCMonth() + 1),
    dd: p2(t.getUTCDate()),
    HH: p2(H),
    hh: p2(h12),
    h: String(h12),
    mm: p2(t.getUTCMinutes()),
    ss: p2(t.getUTCSeconds()),
    SSS: String(t.getUTCMilliseconds()).padStart(3, "0"),
    a: H < 12 ? "AM" : "PM",
    Z: offStr,
    X: offMin === 0 ? "Z" : offStr
  };
  const keys = ["yyyy", "SSS", "MM", "dd", "HH", "hh", "mm", "ss", "h", "a", "Z", "X"];
  let out = "", i = 0;
  while (i < pat.length) {
    if (pat[i] === '"') {
      const j = pat.indexOf('"', i + 1);
      out += pat.slice(i + 1, j < 0 ? pat.length : j);
      i = j < 0 ? pat.length : j + 1;
      continue;
    }
    const k = keys.find((kk) => pat.startsWith(kk, i));
    if (k !== void 0) {
      out += tok[k];
      i += k.length;
    } else {
      out += pat[i];
      i++;
    }
  }
  return out;
}
function __format(tpl, args) {
  const arr = Array.isArray(args) ? args : args === void 0 ? [] : [args];
  let i = 0;
  return tpl.replace(
    /%(0?[0-9]+)?(?:\.([0-9]+))?(?:([sdifr])|(t)(?:\{([^}]*)\})?)/g,
    (_m, width, prec, kind, tflag, datePat) => {
      const v = arr[i++];
      if (tflag === "t") return datePat !== void 0 ? __formatDatePattern(v, datePat) : __toString(v);
      if (v === void 0) return "";
      switch (kind) {
        case "s":
          return __toString(v);
        case "d":
        case "i": {
          const neg = typeof v === "bigint" ? v < 0n : Math.trunc(Number(v)) < 0;
          let s = typeof v === "bigint" ? (v < 0n ? -v : v).toString() : Math.abs(Math.trunc(Number(v))).toString();
          if (width && width.startsWith("0")) {
            const w = parseInt(width, 10);
            while (s.length < w) s = "0" + s;
          }
          return (neg ? "-" : "") + s;
        }
        // `%f` without explicit precision -> natural float (no spurious trailing
        // zeros); `%.Nf` -> fixed N decimals (Pure renders a value that rounds to
        // zero as "0.00", not JS's "-0.00").
        case "f": {
          if (prec === void 0) return __floatStr1(Number(v));
          let s = Number(v).toFixed(parseInt(prec, 10));
          if (parseFloat(s) === 0) s = s.replace("-", "");
          return s;
        }
        case "r":
          return __toRepresentation(v);
        default:
          return __toString(v);
      }
    }
  );
}
function __toRepresentation(v) {
  if (v instanceof Big) return v.toString() + "D";
  if (typeof v === "string") {
    let out = "";
    for (let i = 0; i < v.length; i++) {
      const ch = v.charAt(i);
      if (ch === "\\") out += "\\\\";
      else if (ch === "'") out += "\\'";
      else if (ch === "\n") out += "\\n";
      else if (ch === "	") out += "\\t";
      else if (ch === "\r") out += "\\r";
      else out += ch;
    }
    return "'" + out + "'";
  }
  if (v === void 0 || v === null) return "";
  if (v instanceof Date) return "%" + __toString(v);
  if (Array.isArray(v)) return "[" + v.map(__toRepresentation).join(", ") + "]";
  if ((typeof v === "object" || typeof v === "function") && typeof v.__purePath === "string") return v.__purePath;
  if (typeof v === "object") return __toString(v);
  return String(v);
}
function __floatStr(v) {
  return Array.isArray(v) ? v.map(__floatStr1) : __floatStr1(v);
}
function __floatStr1(n) {
  if (typeof n !== "number" || !isFinite(n)) return String(n);
  if (Number.isInteger(n)) return n.toFixed(1);
  const s = String(n);
  return /[eE]/.test(s) ? __expandExp(s) : s;
}
function __expandExp(s) {
  const m = /^(-?)(\d+)(?:\.(\d+))?[eE]([+-]?\d+)$/.exec(s);
  if (m === null) return s;
  const sign = m[1], digits = m[2] + (m[3] || ""), exp = parseInt(m[4], 10);
  const point = m[2].length + exp;
  let out;
  if (point <= 0) out = "0." + "0".repeat(-point) + digits;
  else if (point >= digits.length) out = digits + "0".repeat(point - digits.length) + ".0";
  else out = digits.slice(0, point) + "." + digits.slice(point);
  if (out.includes(".")) out = out.replace(/(\.\d*?)0+$/, "$1").replace(/\.$/, ".0");
  return sign + out;
}
function __assertError(thunk, expected) {
  try {
    thunk();
  } catch (e) {
    let msg = String(e && e.message ? e.message : e);
    // Truffle and bootstrap match against the message WITH its Pure stack
    // trace, so cross-engine tests can pin frames; append it the same way.
    if (msg.indexOf("\nPure stack trace:") < 0) msg += __pureStackTrace(e);
    if (msg.includes(String(expected))) return true;
    throw new Error("assertError: expected " + String(expected) + " in " + msg);
  }
  throw new Error("assertError: expected exception was not thrown");
}
function __chunk(coll, n) {
  const N = Number(n);
  const out = [];
  for (let i = 0; i < coll.length; i += N) out.push(coll.slice(i, i + N));
  return out;
}
// meta::pure::functions::binary — byte-level primitives backing binary
// serialization (the self-hosted PDB writer). Pure Integer is a bigint, so
// each returned byte is a bigint 0n..255n; byte-identity with the JVM
// implementations is the PCT contract.
function __intToLeBytes(value, width) {
  // BigInt bitwise ops emulate two's complement, so negatives come out right.
  const w = Number(width);
  let v = BigInt(value);
  const out = new Array(w);
  for (let i = 0; i < w; i++) {
    out[i] = v & 0xFFn;
    v >>= 8n;
  }
  return out;
}
function __floatToLeBytes(value) {
  const buf = new ArrayBuffer(8);
  new DataView(buf).setFloat64(0, Number(value), true);
  const bytes = new Uint8Array(buf);
  return Array.from(bytes, (b) => BigInt(b));
}
// UTF-8 codecs: TextEncoder/TextDecoder when the host provides them (Node,
// browsers), else a hand-rolled fallback — a bare ECMAScript host ships neither,
// since TextEncoder/TextDecoder are WHATWG web APIs, not ECMAScript ones. The
// fallback assumes well-formed input (Pure strings encode; the JVM writer
// produces valid UTF-8 to decode), which is all the runtime ever feeds it.
function __utf8Encode(s) {
  const out = [];
  for (const ch of s) { // for..of iterates by code point (surrogate pairs join)
    const cp = ch.codePointAt(0);
    if (cp < 0x80) out.push(cp);
    else if (cp < 0x800) out.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
    else if (cp < 0x10000) out.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    else out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
  }
  return out;
}
function __utf8Decode(u8) {
  let out = "";
  for (let i = 0; i < u8.length; ) {
    const b0 = u8[i++];
    let cp;
    if (b0 < 0x80) cp = b0;
    else if ((b0 & 0xe0) === 0xc0) cp = ((b0 & 0x1f) << 6) | (u8[i++] & 0x3f);
    else if ((b0 & 0xf0) === 0xe0) cp = ((b0 & 0x0f) << 12) | ((u8[i++] & 0x3f) << 6) | (u8[i++] & 0x3f);
    else cp = ((b0 & 0x07) << 18) | ((u8[i++] & 0x3f) << 12) | ((u8[i++] & 0x3f) << 6) | (u8[i++] & 0x3f);
    out += String.fromCodePoint(cp);
  }
  return out;
}
function __stringToUtf8Bytes(s) {
  const bytes = typeof TextEncoder !== "undefined" ? new TextEncoder().encode(s) : __utf8Encode(s);
  return Array.from(bytes, (b) => BigInt(b));
}
function __utf8BytesToString(bytes) {
  const arr = __asArr(bytes);
  const u8 = new Uint8Array(arr.length);
  for (let i = 0; i < arr.length; i++) u8[i] = Number(arr[i]) & 0xff;
  return typeof TextDecoder !== "undefined" ? new TextDecoder().decode(u8) : __utf8Decode(u8);
}
function __leBytesToFloat(bytes) {
  const arr = __asArr(bytes);
  if (arr.length !== 8) throw new Error("leBytesToFloat expects exactly 8 bytes, got " + arr.length);
  const buf = new ArrayBuffer(8);
  const dv = new DataView(buf);
  for (let i = 0; i < 8; i++) dv.setUint8(i, Number(arr[i]) & 0xff);
  return dv.getFloat64(0, true);
}
// Path-keyed CLASS METADATA registry. `__registerClass` statements (emitted by
// translateClassDecl) publish a class's static metadata — property and
// qualified-property entries whose `eval` closures are TRANSLATED bodies —
// and `__classRef(path)` reads it back at reference sites. Keying by path
// avoids `const <ShortName>` globals (collision-prone; a Pure class named
// `Map` or `Error` would shadow the JS builtin for the whole realm). The
// registered companion carries `__purePath` so element-flavored operations
// (`__eq` identity, `__elementToPath`, metadata instanceOf) treat it as the
// class element; unregistered paths fall back to the `__pureResolve` proxy,
// whose property reads route through the metadata globals (PDB-backed hosts).
const __classRegistry = new Map();
function __registerClass(path, decl) {
  decl.__purePath = path;
  decl.path = path;
  __classRegistry.set(path, decl);
  return decl;
}
function __classRef(path) {
  const d = __classRegistry.get(path);
  return d !== undefined ? d : __pureResolve(path);
}
// Qualified-property call dispatched on the receiver's RUNTIME class: walk from
// its class up the generalizations (breadth-first) to the first registered
// class declaring `name` at this arity — the eval arrow's `.length` counts the
// receiver plus supplied args — and fall back to the static owner.
function __qp(ownerPath, name, recv, ...rest) {
  const arity = rest.length + 1;
  const declared = (qps) => __asArr(qps).find((q) => __eq(q.name, name) && q.eval.length === arity);
  const pathOf = (t) => t && (t.__purePath !== void 0 ? t.__purePath : t.path);
  const cgt = recv !== null && typeof recv === "object" ? __pdo(recv).classifierGenericType : undefined;
  const queue = [pathOf(cgt && cgt.type)].filter((p) => typeof p === "string");
  const seen = new Set();
  while (queue.length) {
    const path = queue.shift();
    if (seen.has(path)) continue;
    seen.add(path);
    const decl = __classRegistry.get(path);
    const q = decl && declared(decl.qualifiedProperties);
    if (q) return q.eval(recv, ...rest);
    if (path === ownerPath || path === "meta::pure::metamodel::type::Any") continue;
    for (const g of __asArr(__pureResolve(path).generalizations)) {
      const p = pathOf(g && g.general && g.general.type);
      if (typeof p === "string") queue.push(p);
    }
  }
  const fallback = ownerPath ? declared(__classRef(ownerPath).qualifiedProperties) : void 0;
  if (!fallback) throw new Error("qualified property " + name + "/" + (arity - 1) + " not found on " + ownerPath);
  return fallback.eval(recv, ...rest);
}
// Reconstruct a GenericTypeAndMultiplicityHolder constant (`@X` literal)
// against the metadata globals: classifier info uses __pureResolve POINTER
// IDENTITIES (the holder class element, the UserDefinedGenericType classifier
// singleton), and the cyclic self-reference (holder.genericType -> its own
// classifierGenericType) is closed imperatively — an object-literal expression
// cannot express the cycle, which is why translatePureValueToTs routes holder
// values here rather than serializing them structurally.
// compileSource(sourceId, content, dependencies) — dynamic compilation is a
// HOST capability (it needs a compiler and a way to make the result
// invokable), so runtime-lib only routes: the standalone JS platform installs
// __hostCompileSource (execution host); environments without one throw.
function __compileSource(file, dependencies) {
  if (typeof globalThis.__hostCompileSource === "function") {
    // In-process hosts return rich objects (>2 keys), which __rewrapStubs
    // passes through. The stub-rewrap path remains for any host that returns a
    // bare {__purePath} addressing the result graph instead.
    return __rewrapStubs(globalThis.__hostCompileSource(file, dependencies));
  }
  throw new Error("compileSource: no host implementation in this environment");
}
// meta::external::language::javascript::{compile,execute,drainCompiledSources}
// — evaluating JavaScript is a HOST capability, so runtime-lib only routes. The
// standalone JS platform installs these from src/execution/js-natives.js; an
// environment without them throws rather than silently degrading.
function __jsCompile(source) {
  if (typeof globalThis.__hostJsCompile === "function") return globalThis.__hostJsCompile(source);
  throw new Error("javascript::compile: no host implementation in this environment");
}
function __jsExecute(ctx, fnName, args, pureReturnType, pureMultiplicity, graph) {
  if (typeof globalThis.__hostJsExecute === "function") {
    return __rewrapStubs(globalThis.__hostJsExecute(ctx, fnName, args, pureReturnType, pureMultiplicity, graph));
  }
  throw new Error("javascript::execute: no host implementation in this environment");
}
function __jsDrainCompiledSources() {
  if (typeof globalThis.__hostJsDrainCompiledSources === "function") {
    return globalThis.__hostJsDrainCompiledSources();
  }
  throw new Error("javascript::drainCompiledSources: no host implementation in this environment");
}
function __gtmHolder(clsPath, typeArgTypes) {
  const udgCgt = __pureResolve("meta::pure::metamodel::type::generics::optimization::GenericType_meta_pure_metamodel_type_generics_UserDefinedGenericType");
  const cgt = { type: __pureResolve(clsPath), classifierGenericType: udgCgt };
  cgt.typeArguments = __asArr(typeArgTypes).map((t) => ({ type: t, classifierGenericType: udgCgt }));
  const holder = { classifierGenericType: cgt };
  holder.genericType = cgt; // self-reference: the holder is described by its own CGT
  return holder;
}
function __pair(first, second) {
  // Same shape the translated pair() ctor emits — including __equalityKeys,
  // so __eq compares by first/second even against a constant-folded bare
  // `{first, second}` literal (the translator emits those for pair(...) in
  // expected-value positions).
  return { first, second, classifierGenericType: { type: __pureResolve("meta::pure::functions::collection::Pair"), typeArguments: [{}, {}], __equalityKeys: ["first", "second"] } };
}
function __zip(a, b) {
  const A = __asArr(a), B = __asArr(b);
  const n = Math.min(A.length, B.length);
  const out = [];
  for (let i = 0; i < n; i++) out.push(__pair(A[i], B[i]));
  return out;
}
function __slice(coll, low, high) {
  const a = __asArr(coll);
  const lo = Math.max(0, Math.min(Number(low), a.length));
  const hi = Math.max(lo, Math.min(Number(high), a.length));
  return a.slice(lo, hi);
}
function __firstNonEmpty(thunks) {
  // Returns the first non-empty result ([0..1]/[*], always an array). Thunks
  // yield arrays under the [0..1]=array convention.
  for (const t of thunks ?? []) {
    const v = t();
    if (v === void 0 || v === null) continue;
    if (Array.isArray(v) && v.length === 0) continue;
    return v;
  }
  return [];
}
function __tryEval(thunk) {
  try {
    return {
      value: thunk(),
      failure: void 0,
      classifierGenericType: { type: __pureResolve("meta::pure::functions::lang::TryResult") }
    };
  } catch (e) {
    const msg = e && e.message !== void 0 ? String(e.message) : String(e);
    // Pure frames when the generated code carries position markers (Truffle's
    // PureStackFormatter.frames); otherwise the host's JavaScript frames.
    let frames = __pureStackFrames(e);
    const rawStack = frames.length === 0 && e && typeof e.stack === "string" ? e.stack : void 0;
    if (rawStack) {
      const lines = rawStack.split("\n").map((s) => s.trim()).filter((s) => s.length > 0);
      frames = lines.filter((s) => s.startsWith("at ") || s.includes("@"));
      if (frames.length === 0 && lines.length > 0) frames = lines;
    }
    return {
      value: void 0,
      failure: {
        message: msg,
        stack: frames,
        classifierGenericType: { type: __pureResolve("meta::pure::functions::lang::Error") }
      },
      classifierGenericType: { type: __pureResolve("meta::pure::functions::lang::TryResult") }
    };
  }
}
function __removeDuplicatesBy(coll, fn1, fn2) {
  const arr = __asArr(coll);
  const out = [];
  if (fn2 !== void 0) {
    const keptKeys = [];
    for (const x of arr) {
      const kx = fn1(x);
      if (!keptKeys.some((ky) => fn2(ky, kx))) {
        keptKeys.push(kx);
        out.push(x);
      }
    }
  } else if (fn1.length >= 2) {
    for (const x of arr) if (!out.some((y) => fn1(y, x))) out.push(x);
  } else {
    const seen = /* @__PURE__ */ new Set();
    for (const x of arr) {
      const k = fn1(x);
      if (!seen.has(k)) {
        seen.add(k);
        out.push(x);
      }
    }
  }
  return out;
}
function __match(v, arms, withArg) {
  for (const arm of arms) {
    if (arm.check(v)) {
      const bound = arm.unwrap ? __asArr(v)[0] : v;
      return arm.run(bound, withArg);
    }
  }
  throw new Error("match: no arm matched value " + __toRepresentation(v));
}
function __lambdaParamType(lam) {
  const path = lam && lam.__purePath;
  if (!path) return null;
  const pdo = __pureResolve(path);
  const params = __asArr(pdo.parameters);
  if (params.length === 0) return null;
  const p = params[0];
  const t = p.genericType && p.genericType.type;
  const typePath = t && (t.__purePath ?? t.path) || "meta::pure::metamodel::type::Any";
  let lower = 0;
  let upper;
  const m = p.multiplicity;
  if (m) {
    const lb = m.lowerBound;
    if (lb != null && lb.value != null) lower = lb.value;
    const ub = m.upperBound;
    if (ub != null && ub.value != null) upper = ub.value;
  }
  return { typePath, lower, upper };
}
function __matchDynamic(value, lambdas, withArg) {
  for (const lam of __asArr(lambdas)) {
    const pt = __lambdaParamType(lam);
    if (pt && __matchType(value, pt.typePath, pt.lower, pt.upper)) {
      const bound = pt.upper === 1 ? __asArr(value)[0] : value;
      return withArg !== void 0 ? lam(bound, withArg) : lam(bound);
    }
  }
  throw new Error("match: no arm matched value " + __toRepresentation(value));
}
function __matchType(v, typePath, lower, upper) {
  const isArr = Array.isArray(v);
  const sz = isArr ? v.length : v === void 0 || v === null ? 0 : 1;
  if (sz < lower) return false;
  if (upper !== void 0 && sz > upper) return false;
  const elems = isArr ? v : sz === 0 ? [] : [v];
  const leaf = typePath.split("::").pop() || typePath;
  const checkOne = (e) => {
    switch (leaf) {
      case "String":
        return typeof e === "string";
      // Integer is a bigint, Float a number, Decimal a Big (big.js) instance —
      // the numeric types are DISJOINT, exactly as in Pure (3 is an Integer,
      // 3.0 a Float; a Float match arm must not swallow an Integer or a match
      // over [i: Integer, f: Float] mis-dispatches whichever arm comes first).
      case "Integer":
        return typeof e === "bigint";
      case "Float":
        return typeof e === "number";
      case "Decimal":
        return e instanceof Big;
      case "Number":
        return typeof e === "number" || typeof e === "bigint" || e instanceof Big;
      case "Boolean":
        return typeof e === "boolean";
      case "Date":
      case "StrictDate":
      case "DateTime":
      case "StrictTime":
        return e instanceof Date;
      case "Any":
        return e !== void 0 && e !== null;
      default: {
        if (e === void 0 || e === null) return false;
        // A Big (Decimal) is a primitive number, not an arbitrary object — it
        // only matches the numeric cases above, never a class type. Without this
        // it would fall through to the permissive `return true` below and match
        // e.g. LambdaFunction (a `3.14d` literal compiled as a Lambda).
        if (e instanceof Big) return false;
        // Same for a Date: a primitive, matched only by the date cases above.
        // Without this a captured `%2014-02-01` matched the compiler's protocol
        // LambdaFunction arm and recompiled as an empty lambda.
        if (e instanceof Date) return false;
        if (typeof e !== "object" && typeof e !== "function") return false;
        // Path-addressed values ask the metadata registry; dynamically
        // compiled elements carry a __purePath the (boot-immutable) registry
        // doesn't know, so on a miss fall THROUGH to the classifier the value
        // itself carries — dynamic values are self-describing by design.
        if (e.__purePath && __metadataInstanceOf(e.__purePath, typePath)) return true;
        const cgt = e.classifierGenericType && e.classifierGenericType.type;
        const cgtPath = cgt && (cgt.__purePath !== void 0 ? cgt.__purePath : cgt.path);
        if (typeof cgtPath === "string") {
          return __metadataSubtypeOf(cgtPath, typePath);
        }
        if (e.__purePath) return false;
        return true;
      }
    }
  };
  return elems.every(checkOne);
}
function __isMap(m) {
  return !!m && typeof m === "object" && Array.isArray(m.__mapEntries);
}
function __mapEntriesOf(m) {
  return __isMap(m) ? m.__mapEntries : [];
}
function __mapFindIdx(entries, k) {
  for (let i = 0; i < entries.length; i++) {
    if (entries[i][0] === k) return i;
  }
  return -1;
}
function __mapSet(entries, k, v) {
  const i = __mapFindIdx(entries, k);
  if (i >= 0) {
    entries[i] = [k, v];
  } else {
    entries.push([k, v]);
  }
}
function __newMap(pairs) {
  const entries = [];
  if (pairs !== void 0) for (const p of Array.isArray(pairs) ? pairs : [pairs]) __mapSet(entries, p.first, p.second);
  return { __mapEntries: entries };
}
function __mapPut(m, k, v) {
  const e = __mapEntriesOf(m).map((x) => x.slice());
  __mapSet(e, k, v);
  return { __mapEntries: e };
}
function __mapRemoveAll(m, keys) {
  const ks = __asArr(keys);
  return { __mapEntries: __mapEntriesOf(m).filter((x) => !ks.some((k) => k === x[0])) };
}
// meta::pure::functions::collection::removeAll(set, other): `set` minus every
// element __eq to one in `other` (so <<equality.Key>> classes compare by key).
// A map receiver keeps map semantics.
function __removeAll(set, other) {
  if (set !== null && typeof set === "object" && !Array.isArray(set) && set.__mapEntries !== undefined) {
    return __mapRemoveAll(set, other);
  }
  const os = __asArr(other);
  return __asArr(set).filter((x) => !os.some((o) => __eq(x, o)));
}
function __mapKeyValues(m) {
  return __mapEntriesOf(m).map(([k, v]) => ({ first: k, second: v }));
}
function __mapKeys(m) {
  return __mapEntriesOf(m).map((x) => x[0]);
}
function __mapValues(m) {
  return __mapEntriesOf(m).map((x) => x[1]);
}
function __mapGetIfAbsentPut(m, k, keyFn) {
  const i = __mapFindIdx(__mapEntriesOf(m), k);
  return i >= 0 ? __mapEntriesOf(m)[i][1] : keyFn(k);
}
function __contains(coll, x) {
  if (typeof coll === "string") return coll.includes(String(x));
  return __asArr(coll).some((y) => __eq(x, y));
}
function __containsBy(coll, x, eqFn) {
  return __asArr(coll).some((y) => eqFn(x, y));
}
function __multiIf(pairs, defaultFn) {
  for (const p of __asArr(pairs)) {
    if (p.first()) return p.second();
  }
  return defaultFn();
}
function __mapPutAll(m, kvs) {
  const e = __mapEntriesOf(m).map((x) => x.slice());
  if (Array.isArray(kvs)) {
    for (const kv of kvs) __mapSet(e, kv.first, kv.second);
  } else if (__isMap(kvs)) {
    for (const [k, v] of kvs.__mapEntries) __mapSet(e, k, v);
  } else if (kvs && typeof kvs === "object" && "first" in kvs && "second" in kvs) {
    __mapSet(e, kvs.first, kvs.second);
  }
  return { __mapEntries: e };
}
function __mapRemove(m, k) {
  return { __mapEntries: __mapEntriesOf(m).filter((x) => x[0] !== k) };
}
function __mapGet(m, k) {
  // Pure `map->get(k)` is V[0..1] -> array representation ([] or [value]).
  const e = __mapEntriesOf(m);
  const i = __mapFindIdx(e, k);
  return i >= 0 ? [e[i][1]] : [];
}
function __groupBy(coll, keyFn) {
  const entries = [];
  for (const x of __asArr(coll)) {
    const k = keyFn(x);
    let i = __mapFindIdx(entries, k);
    if (i < 0) {
      entries.push([k, { values: [] }]);
      i = entries.length - 1;
    }
    entries[i][1].values.push(x);
  }
  return { __mapEntries: entries };
}
function __parseDate(s) {
  return __pdate(String(s).replace(/^%/, ""));
}
function __parseInteger(s) {
  // Pure Integer = BigInt. parseLong-compatible: optional '+', surrounding
  // whitespace; throws on anything else (BigInt is strict past that).
  return BigInt(String(s).trim().replace(/^\+/, ""));
}
function __parseDecimalScaled(s, scale) {
  return Number(parseFloat(String(s)).toFixed(Number(scale)));
}
function __fold(coll, fn, seed) {
  const arr = coll === void 0 ? [] : Array.isArray(coll) ? coll : [coll];
  let acc = seed;
  for (const item of arr) acc = fn(item, acc);
  return acc;
}
function __asArr(v) {
  return v === void 0 || v === null ? [] : Array.isArray(v) ? v : [v];
}
// [0..1] producers (return an array: [] or [element]) per the convention that
// [0..1]/[*] are arrays and [1] is a scalar. __asArr tolerates scalar slots.
function __head(x) { return __asArr(x).slice(0, 1); }
function __last(x) { const a = __asArr(x); return a.length ? a.slice(-1) : []; }
function __find(c, pred) { for (const e of __asArr(c)) { if (pred(e)) return [e]; } return []; }
// exists/forAll over a [*] (tolerate an absent/undefined collection as []).
function __exists(c, pred) { return __asArr(c).some(pred); }
function __forAll(c, pred) { return __asArr(c).every(pred); }
function __concat(a, b) {
  return [...__asArr(a), ...__asArr(b)];
}
function __sameElements(a, b) {
  const aa = __asArr(a);
  const bb = __asArr(b);
  if (aa.length !== bb.length) return false;
  const matched = new Array(bb.length).fill(false);
  outer: for (const x of aa) {
    for (let i = 0; i < bb.length; i++) {
      if (matched[i]) continue;
      if (__eq(x, bb[i])) {
        matched[i] = true;
        continue outer;
      }
    }
    return false;
  }
  return true;
}
function __isEmpty(v) {
  return __asArr(v).length === 0;
}
function __isNotEmpty(v) {
  return __asArr(v).length > 0;
}
function __filter(coll, fn) {
  return __asArr(coll).filter(fn);
}
function __map(coll, fn) {
  // `fn` is usually a translated arrow, but Pure also maps with a function
  // VALUE — a Property (`$people->map($lastNameProperty)`) or a metadata
  // function proxy — which __eval knows how to apply.
  const f = typeof fn === "function" ? fn : (x) => __eval(fn, x);
  return __asArr(coll).flatMap((x) => __asArr(f(x)));
}
function __at(coll, i) {
  const arr = __asArr(coll);
  const idx = Number(i);
  if (idx < 0 || idx >= arr.length) {
    throw new Error("The system is trying to get an element at offset " + idx + " where the collection is of size " + arr.length);
  }
  return arr[idx];
}
function __size(coll) {
  return BigInt(__asArr(coll).length);
}
function __take(coll, n) {
  const N = Number(n);
  return N <= 0 ? [] : __asArr(coll).slice(0, N);
}
function __drop(coll, n) {
  const N = Number(n);
  return __asArr(coll).slice(N < 0 ? 0 : N);
}
function __range(a, b, step) {
  const start = b === void 0 ? 0n : BigInt(a);
  const end = b === void 0 ? BigInt(a) : BigInt(b);
  const s = step === void 0 ? 1n : BigInt(step);
  // Contract message — pinned by tests::range::testRangeStepError.
  if (s === 0n) throw new Error("range step must not be 0");
  if (s === 1n) {
    if (start >= end) return [];
    const n = Number(end - start);
    const out = new Array(n);
    for (let i = 0; i < n; i++) out[i] = start + BigInt(i);
    return out;
  }
  // Stepped (possibly negative): stop before `end` in the direction of travel.
  const out = [];
  if (s > 0n) for (let i = start; i < end; i += s) out.push(i);
  else for (let i = start; i > end; i += s) out.push(i);
  return out;
}
const POINTER_PREFIX = "meta::pure::metamodel::pointer::";
function __pgr_pointerPath(v) {
  if (!v || typeof v !== "object") return null;
  const cgt = v.classifierGenericType;
  if (!cgt || typeof cgt !== "object" || !cgt.type || typeof cgt.type !== "object") return null;
  const typePath = cgt.type.path;
  if (typeof typePath !== "string" || !typePath.startsWith(POINTER_PREFIX)) return null;
  // Property/QualifiedProperty pointers carry an `element` (the property name);
  // their `path` is the OWNER, not a target element — don't resolve them (they
  // stay as pointers, like on the JVM). Only element-level pointers (path IS the
  // full element path) get resolved.
  if (v.element !== void 0) return null;
  return typeof v.path === "string" ? v.path : null;
}
function __pgr_resolveTarget(path, byPath, resolver) {
  // Empty path is the root package: leave the pointer as-is (there is no element
  // to inline; __pureResolve always returns a truthy proxy, so we must special-
  // case before resolving or we'd read properties off a non-existent element).
  if (path === "") return void 0;
  if (path in byPath) return byPath[path];
  const live = resolver(path);
  if (live !== void 0 && live !== null) return live;
  return void 0;
}
function __pgr_process(v, byPath, copies) {
  if (v === null || v === void 0) return v;
  if (typeof v !== "object") return v;
  if (typeof v === "bigint") return v;
  if (v instanceof Big) return v;
  if (v instanceof Date) return v;
  if (Array.isArray(v.__mapEntries)) {
    const out = [];
    for (const [k, val] of v.__mapEntries) out.push([__pgr_process(k, byPath, copies), __pgr_process(val, byPath, copies)]);
    return { __mapEntries: out };
  }
  if (Array.isArray(v)) return v.map((e) => __pgr_process(e, byPath, copies));
  const ptr = __pgr_pointerPath(v);
  if (ptr !== null) {
    const target = __pgr_resolveTarget(ptr, byPath, __pureResolve);
    if (target == null) return v;
    return __pgr_process(target, byPath, copies);
  }
  if (typeof v.__purePath === "string") return v;
  const seen = copies.get(v);
  if (seen !== void 0) return seen;
  const clone = {};
  copies.set(v, clone);
  for (const k of Object.keys(v)) {
    if (k.startsWith("__")) {
      clone[k] = v[k];
      continue;
    }
    clone[k] = __pgr_process(v[k], byPath, copies);
  }
  return clone;
}
function __rawType(gt) {
  if (!gt || typeof gt !== "object") return void 0;
  return gt.type !== void 0 ? gt.type : void 0;
}
function __resolveAndReturnGraph(m) {
  const entries = __mapEntriesOf(m);
  if (entries.length === 0) return [];
  const byPath = {};
  for (const [k, v] of entries) byPath[k] = v;
  const copies = /* @__PURE__ */ new WeakMap();
  const out = new Array(entries.length);
  for (let i = 0; i < entries.length; i++) out[i] = __pgr_process(entries[i][1], byPath, copies);
  return out;
}
function __pureTypeNameOf(v) {
  if (typeof v === "bigint") return "Integer";
  if (v instanceof Big) return "Decimal";
  if (typeof v === "number") return "Float";
  if (typeof v === "string") return "String";
  if (typeof v === "boolean") return "Boolean";
  if (v instanceof Date) return "Date";
  return "";
}
function __defaultCompare(a, b) {
  if ((typeof a === "number" || typeof a === "bigint") && (typeof b === "number" || typeof b === "bigint")) return a < b ? -1 : a > b ? 1 : 0;
  // Mixed primitive types order by TYPE NAME first (Pure's compare semantics:
  // a sorted [1, 2, '1'] keeps Integers before Strings — 'Integer' < 'String'),
  // except numerics, which compare numerically above regardless of subtype.
  const ta = __pureTypeNameOf(a), tb = __pureTypeNameOf(b);
  if (ta !== tb && ta !== "" && tb !== "") return ta < tb ? -1 : 1;
  const sa = __toString(a), sb = __toString(b);
  return sa < sb ? -1 : sa > sb ? 1 : 0;
}
function __sort(coll, key, comp) {
  const k = key || ((x) => x);
  const c = comp || __defaultCompare;
  // Pure comparators return an Integer (bigint); Array.sort requires a number.
  // Number() preserves the sign (all sort needs).
  return __asArr(coll).slice().sort((a, b) => Number(c(k(a), k(b))));
}
function __orElse(v, def) {
  return __isEmpty(v) ? def : v;
}
function __toOne(v, message) {
  const arr = __asArr(v);
  // Message pinned by Truffle's ToOneNode and bootstrap's CollectionNatives;
  // toOne(values, message) substitutes the caller's message.
  if (arr.length !== 1) {
    throw new Error(message !== undefined ? String(message) : "toOne expected exactly 1 element, got " + arr.length);
  }
  return arr[0];
}
function __eval(fn, ...args) {
  if (typeof fn === "function") return fn(...args);
  if (fn && typeof fn === "object") {
    if (typeof fn.eval === "function") return fn.eval(...args);
    // A metadata-marshaled Property applied as a function (`prop->eval(inst)`)
    // is a slot read off the receiver — mirrors the interpreter's property
    // application. (QualifiedProperties with bodies dispatch through the
    // class registry's translated closures instead.)
    const cls = fn.classifierGenericType && fn.classifierGenericType.type;
    const clsPath = cls && (cls.__purePath !== void 0 ? cls.__purePath : cls.path);
    if (typeof fn.name === "string" && typeof clsPath === "string" && clsPath.endsWith("::property::Property")) {
      return __pdo(args[0])[fn.name];
    }
    if (typeof fn.__purePath === "string") {
      return __rewrapStubs(__metadataInvoke(fn.__purePath, args));
    }
    // A function definition built at run time from an AST (`^LambdaFunction(
    // expressionSequence = ...)`) has no translated body to call. Running it
    // means translating it now — a HOST capability (it needs the translator),
    // so runtime-lib only routes; hosts without one fall through to the throw.
    if (fn.expressionSequence !== undefined && typeof globalThis.__hostEvaluateFunctionDefinition === "function") {
      return globalThis.__hostEvaluateFunctionDefinition(fn, args);
    }
  }
  throw new TypeError("__eval: not callable: " + (fn === null ? "null" : typeof fn));
}
function __evaluate(lambda, paramsList) {
  const params = __asArr(paramsList).map((p) => {
    const vs = p && p.values !== void 0 ? __asArr(p.values) : [p];
    return vs.length === 1 ? vs[0] : vs;
  });
  return __eval(lambda, ...params);
}
function __durationName(units) {
  if (units && typeof units === "object" && typeof units.name === "string") return units.name;
  const s = String(units);
  return s.split(".").pop() || s;
}
const __GRAN_ORDER = ["Y", "YM", "YMD", "YMDH", "YMDHM", "YMDHMS"];
function __unitMin(unit) {
  switch (unit) {
    case "YEARS":
      return { gran: "Y", fracDigits: 0 };
    case "MONTHS":
      return { gran: "YMD", fracDigits: 0 };
    case "WEEKS":
      return { gran: "YMD", fracDigits: 0 };
    case "DAYS":
      return { gran: "YMD", fracDigits: 0 };
    case "HOURS":
      return { gran: "YMDH", fracDigits: 0 };
    case "MINUTES":
      return { gran: "YMDHM", fracDigits: 0 };
    case "SECONDS":
      return { gran: "YMDHMS", fracDigits: 0 };
    case "MILLISECONDS":
      return { gran: "YMDHMS", fracDigits: 3 };
    case "MICROSECONDS":
      return { gran: "YMDHMS", fracDigits: 6 };
    case "NANOSECONDS":
      return { gran: "YMDHMS", fracDigits: 9 };
  }
  return { gran: "Y", fracDigits: 0 };
}
function __maxGranularity(a, b) {
  return __GRAN_ORDER.indexOf(a) >= __GRAN_ORDER.indexOf(b) ? a : b;
}
function __parseLitToFields(lit) {
  const m = /^([+-]?\d+)(?:-(\d{1,2}))?(?:-(\d{1,2}))?(?:T(\d{1,2})(?::(\d{1,2})(?::(\d{1,2})(?:\.(\d+))?)?)?)?([zZ]|[+-]\d{2}:?\d{2})?$/.exec(lit);
  if (m === null) {
    return { Y: 0n, Mo: 1, Da: 1, H: 0, Mi: 0, S: 0, frac: 0n, fracDigits: 0, gran: "Y" };
  }
  const Y = BigInt(m[1]);
  const Mo = m[2] !== void 0 ? parseInt(m[2], 10) : 1;
  const Da = m[3] !== void 0 ? parseInt(m[3], 10) : 1;
  const H = m[4] !== void 0 ? parseInt(m[4], 10) : 0;
  const Mi = m[5] !== void 0 ? parseInt(m[5], 10) : 0;
  const S = m[6] !== void 0 ? parseInt(m[6], 10) : 0;
  const fracStr = m[7] || "";
  const fracDigits = fracStr.length;
  const frac = fracDigits === 0 ? 0n : BigInt(fracStr);
  let gran;
  if (m[4] === void 0) {
    gran = m[3] === void 0 ? m[2] === void 0 ? "Y" : "YM" : "YMD";
  } else if (m[5] === void 0) {
    gran = "YMDH";
  } else if (m[6] === void 0) {
    gran = "YMDHM";
  } else {
    gran = "YMDHMS";
  }
  return { Y, Mo, Da, H, Mi, S, frac, fracDigits, gran };
}
function __daysFromCivil(y, m, d) {
  const yy = m <= 2 ? y - 1n : y;
  const era = (yy >= 0n ? yy : yy - 399n) / 400n;
  const yoe = yy - era * 400n;
  const mAdj = m > 2 ? m - 3 : m + 9;
  const doy = BigInt(Math.floor((153 * mAdj + 2) / 5) + d - 1);
  const doe = yoe * 365n + yoe / 4n - yoe / 100n + doy;
  return era * 146097n + doe - 719468n;
}
function __civilFromDays(zArg) {
  const z = zArg + 719468n;
  const era = (z >= 0n ? z : z - 146096n) / 146097n;
  const doe = z - era * 146097n;
  const yoe = (doe - doe / 1460n + doe / 36524n - doe / 146096n) / 365n;
  const y = yoe + era * 400n;
  const doy = doe - (365n * yoe + yoe / 4n - yoe / 100n);
  const mp = (5n * doy + 2n) / 153n;
  const d = Number(doy - (153n * mp + 2n) / 5n) + 1;
  const m = Number(mp < 10n ? mp + 3n : mp - 9n);
  return { Y: y + (m <= 2 ? 1n : 0n), Mo: m, Da: d };
}
function __isLeapYear(Y) {
  return Y % 4n === 0n && Y % 100n !== 0n || Y % 400n === 0n;
}
function __lastDayOfMonth(Y, Mo) {
  if (Mo === 4 || Mo === 6 || Mo === 9 || Mo === 11) return 30;
  if (Mo !== 2) return 31;
  return __isLeapYear(Y) ? 29 : 28;
}
function __padYear(Y) {
  return Y >= 0n ? Y.toString().padStart(4, "0") : "-" + (-Y).toString().padStart(4, "0");
}
function __renderLit(f, gran) {
  const p2 = (n) => String(n).padStart(2, "0");
  const Y = __padYear(f.Y);
  if (gran === "Y") return Y;
  const Mo = p2(f.Mo);
  if (gran === "YM") return Y + "-" + Mo;
  const Da = p2(f.Da);
  if (gran === "YMD") return Y + "-" + Mo + "-" + Da;
  const H = p2(f.H);
  if (gran === "YMDH") return Y + "-" + Mo + "-" + Da + "T" + H;
  const Mi = p2(f.Mi);
  if (gran === "YMDHM") return Y + "-" + Mo + "-" + Da + "T" + H + ":" + Mi;
  const S = p2(f.S);
  const base = Y + "-" + Mo + "-" + Da + "T" + H + ":" + Mi + ":" + S;
  if (f.fracDigits === 0) return base;
  return base + "." + f.frac.toString().padStart(f.fracDigits, "0");
}
function __dateToFields(d) {
  const ms = d.getUTCMilliseconds();
  return {
    Y: BigInt(d.getUTCFullYear()),
    Mo: d.getUTCMonth() + 1,
    Da: d.getUTCDate(),
    H: d.getUTCHours(),
    Mi: d.getUTCMinutes(),
    S: d.getUTCSeconds(),
    frac: BigInt(ms),
    fracDigits: ms === 0 ? 0 : 3,
    gran: "YMDHMS"
  };
}
function __addMonthsFields(f, N) {
  const totalMonths = f.Y * 12n + BigInt(f.Mo - 1) + N;
  let newY, newMo;
  if (totalMonths >= 0n) {
    newY = totalMonths / 12n;
    newMo = totalMonths % 12n;
  } else {
    const q = (-totalMonths + 11n) / 12n;
    newY = -q;
    newMo = totalMonths - newY * 12n;
  }
  f.Y = newY;
  f.Mo = Number(newMo) + 1;
  const last = __lastDayOfMonth(f.Y, f.Mo);
  if (f.Da > last) f.Da = last;
}
function __addDaysFields(f, deltaDays) {
  const jd = __daysFromCivil(f.Y, f.Mo, f.Da) + deltaDays;
  const ymd = __civilFromDays(jd);
  f.Y = ymd.Y;
  f.Mo = ymd.Mo;
  f.Da = ymd.Da;
}
function __addTimeFields(f, deltaValue, deltaUnitDigits) {
  const N = f.fracDigits;
  const scaledDelta = N >= deltaUnitDigits ? deltaValue * 10n ** BigInt(N - deltaUnitDigits) : deltaValue / 10n ** BigInt(deltaUnitDigits - N);
  const scale = N === 0 ? 1n : 10n ** BigInt(N);
  const ticksPerMinute = 60n * scale;
  const ticksPerHour = 3600n * scale;
  const ticksPerDay = 86400n * scale;
  let total = BigInt(f.H) * ticksPerHour + BigInt(f.Mi) * ticksPerMinute + BigInt(f.S) * scale + f.frac + scaledDelta;
  let dayDelta;
  if (total >= 0n) {
    dayDelta = total / ticksPerDay;
    total = total % ticksPerDay;
  } else {
    const q = (-total + ticksPerDay - 1n) / ticksPerDay;
    dayDelta = -q;
    total = total - dayDelta * ticksPerDay;
  }
  if (dayDelta !== 0n) __addDaysFields(f, dayDelta);
  f.H = Number(total / ticksPerHour);
  total = total % ticksPerHour;
  f.Mi = Number(total / ticksPerMinute);
  total = total % ticksPerMinute;
  f.S = Number(total / scale);
  f.frac = total % scale;
}
function __upgradeFrac(f, target) {
  if (target <= f.fracDigits) return;
  f.frac = f.frac * 10n ** BigInt(target - f.fracDigits);
  f.fracDigits = target;
}
function __adjust(d, n, units) {
  const u = __durationName(units);
  const srcLit = d.__lit;
  const f = typeof srcLit === "string" ? __parseLitToFields(srcLit) : __dateToFields(d);
  const N = typeof n === "bigint" ? n : BigInt(n);
  const min = __unitMin(u);
  if (min.fracDigits > 0) __upgradeFrac(f, min.fracDigits);
  switch (u) {
    case "YEARS":
      __addMonthsFields(f, 12n * N);
      break;
    case "MONTHS":
      __addMonthsFields(f, N);
      break;
    case "WEEKS":
      __addDaysFields(f, 7n * N);
      break;
    case "DAYS":
      __addDaysFields(f, N);
      break;
    case "HOURS":
      __addTimeFields(f, N * 3600n, 0);
      break;
    case "MINUTES":
      __addTimeFields(f, N * 60n, 0);
      break;
    case "SECONDS":
      __addTimeFields(f, N, 0);
      break;
    case "MILLISECONDS":
      __addTimeFields(f, N, 3);
      break;
    case "MICROSECONDS":
      __addTimeFields(f, N, 6);
      break;
    case "NANOSECONDS":
      __addTimeFields(f, N, 9);
      break;
  }
  f.gran = __maxGranularity(f.gran, min.gran);
  // A real Date whenever the result fits JS Date's range, so getters and date
  // arithmetic (dateDiff) read its value. __pdate keeps the literal (__lit /
  // __fmt) and falls back to an invalid-Date carrier only for years beyond
  // ±275760, which only the literal can represent.
  return __pdate(__renderLit(f, f.gran));
}
function __dateDiff(a, b, units) {
  const ms = b.getTime() - a.getTime();
  const u = __durationName(units);
  let n = 0;
  switch (u) {
    case "DAYS":
      n = Math.floor(ms / 864e5);
      break;
    case "HOURS":
      n = Math.floor(ms / 36e5);
      break;
    case "MINUTES":
      n = Math.floor(ms / 6e4);
      break;
    case "SECONDS":
      n = Math.floor(ms / 1e3);
      break;
    case "MILLISECONDS":
      n = Math.floor(ms);
      break;
    // Pure WEEKS counts week boundaries (Sundays) crossed in the direction
    // of travel: forward counts Sundays in (a, b]; backward counts Sundays
    // in [b, a). That yields the asymmetric behaviour the tests pin:
    // Sat→Sun = +1 (forward crosses a Sun), Sun→Sat = 0 (backward starts ON
    // a Sun which doesn't count), Sun→Sun-7d = -1 (backward reaches a Sun).
    case "WEEKS":
      n = __dateDiffWeeks(a, b);
      break;
    case "MONTHS":
      n = (b.getUTCFullYear() - a.getUTCFullYear()) * 12 + (b.getUTCMonth() - a.getUTCMonth());
      break;
    case "YEARS":
      n = b.getUTCFullYear() - a.getUTCFullYear();
      break;
  }
  return BigInt(n);
}
function __dateDiffWeeks(a, b) {
  const dayA = Math.floor(a.getTime() / 864e5);
  const dayB = Math.floor(b.getTime() / 864e5);
  const sunIdx = (day) => Math.floor((day + 4) / 7);
  return dayB >= dayA ? sunIdx(dayB) - sunIdx(dayA) : sunIdx(dayB - 1) - sunIdx(dayA - 1);
}
function __datePart(d) {
  const out = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
  const Y = String(out.getUTCFullYear()).padStart(4, "0");
  const Mo = String(out.getUTCMonth() + 1).padStart(2, "0");
  const Da = String(out.getUTCDate()).padStart(2, "0");
  out.__lit = Y + "-" + Mo + "-" + Da;
  out.__fmt = Y + "-" + Mo + "-" + Da;
  return out;
}
function __year(d) {
  return BigInt(d.getUTCFullYear());
}
function __monthNumber(d) {
  return BigInt(d.getUTCMonth() + 1);
}
function __dayOfMonth(d) {
  return BigInt(d.getUTCDate());
}
function __hour(d) {
  return BigInt(d.getUTCHours());
}
function __minute(d) {
  return BigInt(d.getUTCMinutes());
}
function __second(d) {
  return BigInt(d.getUTCSeconds());
}
function __millis(d) {
  return BigInt(d.getUTCMilliseconds());
}
function __replace(s, from, to) {
  return String(s).split(from).join(to);
}
function __matches(s, pat) {
  return new RegExp("^" + pat + "$").test(String(s));
}
function __regexpFlags(global, params) {
  let f = global ? "g" : "";
  for (const p of __asArr(params)) {
    const nm = p && typeof p === "object" && p.name !== void 0 ? p.name : p;
    const last = String(nm).split(".").pop();
    if (last === "CASE_INSENSITIVE") f += "i";
    else if (last === "MULTILINE") f += "m";
    else if (last === "NON_NEWLINE_SENSITIVE") f += "s";
  }
  return f;
}
function __regexpReplace(s, pat, repl, replaceAll, params) {
  return String(s).replace(new RegExp(pat, __regexpFlags(replaceAll === true, params)), repl);
}
function __regexpExtract(s, pat, extractAll, groupNumber, params) {
  const g = Number(groupNumber) || 0;
  const flags = __regexpFlags(false, params);
  if (extractAll === true) {
    const re = new RegExp(pat, flags + "g");
    const out = [];
    let m2;
    while ((m2 = re.exec(String(s))) !== null) {
      out.push(m2[g]);
      if (m2.index === re.lastIndex) re.lastIndex++;
    }
    return out;
  }
  const m = String(s).match(new RegExp(pat, flags));
  return m ? m[g] : "";
}
function __regexpIndexOf(s, pat, groupNumber, params) {
  const g = Number(groupNumber) || 0;
  const m = new RegExp(pat, __regexpFlags(false, params) + "d").exec(String(s));
  if (m === null) return -1;
  if (g === 0) return m.index;
  const idx = m.indices;
  return idx && idx[g] ? idx[g][0] : -1;
}
function __regexpCount(s, pat, params) {
  const m = String(s).match(new RegExp(pat, __regexpFlags(true, params)));
  return m ? m.length : 0;
}
function __reverseString(s) {
  return [...String(s)].reverse().join("");
}
function __joinStrings(coll, prefixOrSep, sepOrUndef, suffixOrUndef) {
  const arr = __asArr(coll).map(__toString);
  if (prefixOrSep === void 0) return arr.join("");
  if (sepOrUndef === void 0) return arr.join(String(prefixOrSep));
  if (suffixOrUndef === void 0) return arr.join(String(prefixOrSep)) + String(sepOrUndef);
  return String(prefixOrSep) + arr.join(String(sepOrUndef)) + String(suffixOrUndef);
}
function __parseBoolean(s) {
  return String(s).toLowerCase() === "true";
}
function __fail(msg) {
  throw new Error(String(msg));
}
const __metaHierarchy = {
  "meta::pure::metamodel::type::Any": [],
  "meta::pure::metamodel::type::Type": ["meta::pure::metamodel::PackageableElement", "meta::pure::metamodel::type::Any"],
  "meta::pure::metamodel::type::DataType": ["meta::pure::metamodel::type::Type", "meta::pure::metamodel::PackageableElement", "meta::pure::metamodel::type::Any"],
  "meta::pure::metamodel::type::PrimitiveType": ["meta::pure::metamodel::type::DataType", "meta::pure::metamodel::type::Type", "meta::pure::metamodel::PackageableElement", "meta::pure::metamodel::type::Any"],
  "meta::pure::metamodel::type::Class": ["meta::pure::metamodel::type::Type", "meta::pure::metamodel::PackageableElement", "meta::pure::metamodel::type::Any"],
  "meta::pure::metamodel::type::Enumeration": ["meta::pure::metamodel::type::DataType", "meta::pure::metamodel::type::Type", "meta::pure::metamodel::PackageableElement", "meta::pure::metamodel::type::Any"],
  "meta::pure::metamodel::PackageableElement": ["meta::pure::metamodel::type::Any"]
};
function __instanceOf(v, t) {
  const tPath = t && typeof t === "object" && t.path || void 0;
  if (tPath === "meta::pure::metamodel::type::Any") return v !== void 0 && v !== null;
  if (v === void 0 || v === null) return false;
  // Translated closures carry their Pure address too (`__lambda(arrow, path)`,
  // e.g. a captured lambda value); their classifier comes from that address.
  if ((typeof v === "object" || typeof v === "function") && v.__purePath && tPath) {
    // Registry miss falls through to the value's own classifier — dynamically
    // compiled elements carry a __purePath the boot-immutable registry
    // doesn't know; they are self-describing (see __matchType).
    if (__metadataInstanceOf(v.__purePath, tPath)) return true;
    const cgt = v.classifierGenericType && v.classifierGenericType.type;
    const cgtPath = cgt && (cgt.__purePath !== void 0 ? cgt.__purePath : cgt.path);
    if (typeof cgtPath === "string") return __metadataSubtypeOf(cgtPath, tPath);
    return false;
  }
  if (v instanceof Date) {
    return tPath === "meta::pure::metamodel::type::primitives::Date" || tPath === "meta::pure::metamodel::type::primitives::StrictDate" || tPath === "meta::pure::metamodel::type::primitives::DateTime" || tPath === "meta::pure::metamodel::type::primitives::StrictTime";
  }
  if (typeof v === "string") {
    return tPath === "meta::pure::metamodel::type::primitives::String";
  }
  if (typeof v === "bigint") {
    return tPath === "meta::pure::metamodel::type::primitives::Integer" || tPath === "meta::pure::metamodel::type::primitives::Number";
  }
  if (v instanceof Big) {
    return tPath === "meta::pure::metamodel::type::primitives::Decimal" || tPath === "meta::pure::metamodel::type::primitives::Number";
  }
  if (typeof v === "number") {
    return tPath === "meta::pure::metamodel::type::primitives::Float" || tPath === "meta::pure::metamodel::type::primitives::Number";
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
  if (typeof v === "object" && v.classifierGenericType && v.classifierGenericType.type && tPath) {
    return __subtypeViaGeneralizations(v.classifierGenericType.type, tPath);
  }
  return false;
}
function __subtypeViaGeneralizations(cls, tPath) {
  if (cls === void 0 || cls === null) return false;
  if (typeof cls.path === "string") {
    return cls.path === tPath || __metadataSubtypeOf(cls.path, tPath);
  }
  for (const g of __asArr(cls.generalizations)) {
    const gt = g && g.general && g.general.type;
    if (gt && __subtypeViaGeneralizations(gt, tPath)) return true;
  }
  return false;
}
function __validateCgt(userCgt, holderTypePath) {
  if (userCgt === null || typeof userCgt !== "object") return userCgt;
  const t = userCgt.type;
  if (t === null || typeof t !== "object") return userCgt;
  const tp = typeof t.path === "string" ? t.path : typeof t.__purePath === "string" ? t.__purePath : "";
  // (1) Same raw type \u2014 the common case.
  if (tp === holderTypePath) return userCgt;
  // (2) Proposed type is a compile-pure pointer (TempCompilerPointer) whose target
  // is being built right now \u2014 e.g. buildEnumerationSkeleton wires an enum value's
  // classifier as a pointer to the enum it's about to register. A pointer is
  // compile-internal, not a user raw-type swap; accept (mirrors the JVM's
  // validateClassifierOverride). Must run before the generalization walk: pointers
  // carry only `path`, so resolving their generalizations would be wrong.
  const tcgt = t.classifierGenericType;
  if (tcgt && typeof tcgt === "object" && tcgt.type && typeof tcgt.type.path === "string"
      && tcgt.type.path.startsWith(POINTER_PREFIX)) return userCgt;
  // (3) Proposed is a subtype of the expected metaclass \u2014 the enum-value pattern
  // re-classifying ^Enum(...) as a specific user enumeration.
  if (__subtypeViaGeneralizations(t, holderTypePath)) return userCgt;
  throw new Error("Cannot change classifierGenericType.type from '" + holderTypePath + "' to '" + tp + "'. The classifier's raw type is system-managed (derived from the instance's metaclass) \u2014 only typeArguments, multiplicityArguments and typeVariableValues are user-customizable. Use meta::pure::functions::lang::new(GenericType[1]) to construct an instance with a different metaclass.");
}
function __toCanonicalCgt(gt) {
  if (gt === null || typeof gt !== "object") return gt;
  if (gt.type === void 0 && gt.classifierGenericType !== void 0) {
    gt = gt.classifierGenericType;
  }
  const t0 = gt.type;
  const tp0 = t0 && typeof t0 === "object" ? typeof t0.path === "string" ? t0.path : typeof t0.__purePath === "string" ? t0.__purePath : void 0 : void 0;
  if (tp0 === "meta::pure::metamodel::valuespecification::GenericTypeAndMultiplicityHolder" && Array.isArray(gt.typeArguments) && gt.typeArguments.length > 0) {
    gt = gt.typeArguments[0];
  }
  const t = gt.type;
  if (t === null || typeof t !== "object") return gt;
  const tp = typeof t.path === "string" ? t.path : typeof t.__purePath === "string" ? t.__purePath : void 0;
  if (typeof tp !== "string") return gt;
  if (tp === "meta::pure::metamodel::type::Class") {
    const tas = gt.typeArguments;
    if (!Array.isArray(tas) || tas.length === 0) {
      throw new Error("Cannot instantiate Class<Class<T>> because the typeArgs are not set for the typeParam");
    }
  }
  if (!tp.startsWith("meta::pure::metamodel::")) return gt;
  // Only canonicalize a BARE (argument-less) generic type to the optimization
  // singleton — a parameterized one (e.g. Property<Owner, Value|mul>) must keep
  // its type/multiplicity arguments, which the singleton doesn't carry.
  const tas = gt.typeArguments, mas = gt.multiplicityArguments;
  if ((Array.isArray(tas) && tas.length > 0) || (Array.isArray(mas) && mas.length > 0)) return gt;
  return __pureResolve("meta::pure::metamodel::type::generics::optimization::GenericType_" + tp.split("::").join("_"));
}
// elementToPath(element[, separator]) — Truffle ElementToPathNode parity: the
// root's path is '', and a separator other than '::' replaces every '::'.
function __elementToPath(v, separator) {
  const path = __pathOf(v);
  if (typeof path !== "string") return path;
  if (path === "" || path === "::") return "";
  return separator === undefined || separator === "::" ? path : path.split("::").join(String(separator));
}
// elementPath(element) — Truffle ElementPathNode parity: the ancestry chain,
// outermost first. The canonical root ('::' / '') is omitted; an ephemeral
// parentless package (`^Package(name='Other')`) is a real entry and is kept.
function __elementPath(v) {
  const out = [];
  for (let pe = v; pe !== undefined && pe !== null && typeof pe === "object"; pe = __pdo(pe).package) {
    const name = __pdo(pe).name;
    const path = __pathOf(pe);
    if (typeof name === "string" && name !== "" && path !== "" && path !== "::") out.push(pe);
  }
  return out.reverse();
}
// __purePath is the element's path only for canonical (registered) elements.
// Address-scheme stubs — dynamic compileSource results (`__dyn::<n>$elements/0`),
// injected graphs (`__local::…`), and sub-element addresses (`…$properties/0`) —
// carry an ADDRESS there instead.
function __canonicalPurePath(v) {
  const p = v.__purePath;
  return typeof p === "string" && p.indexOf("$") < 0 && !p.startsWith("__dyn::") && !p.startsWith("__local::")
    ? p : undefined;
}
// The raw path of an element ('::' for the canonical root), before
// elementToPath's normalization.
function __pathOf(v) {
  if (typeof v === "string") return v;
  if (v && typeof v === "object") {
    // Pointers carry their canonical path in `path`; registered elements in
    // __purePath.
    if (typeof v.path === "string") return v.path;
    const canonical = __canonicalPurePath(v);
    if (canonical !== undefined) return canonical;
    // Self-describing source element (parsed/constructed, no stored path):
    // derive the path from package::name. `name` is already the path's last
    // segment (the signature-mangled name for functions).
    if (typeof v.name === "string") {
      if (v.name === "::") return "::";
      const parent = v.package;
      if (parent === undefined || parent === null) return v.name;
      // A parentless package is a root: it names itself but adds nothing to
      // its children's paths — `^Profile(name='X', package=^Package(name='p',
      // package=^Package(name='Other')))` is 'p::X' whatever the root is called.
      const parentPath = __isRootPackage(parent) ? "" : __pathOf(parent);
      return parentPath && parentPath !== "::" ? parentPath + "::" + v.name : v.name;
    }
  }
  return v;
}
function __isRootPackage(p) {
  if (p === null || typeof p !== "object") return false;
  if (typeof p.path === "string") return p.path === "" || p.path === "::";
  const canonical = __canonicalPurePath(p);
  if (canonical !== undefined) return canonical === "" || canonical === "::";
  return p.package === undefined || p.package === null;
}
// Coerce a value into a to-many (array) representation. Pure auto-wraps a [1]
// value assigned to a [*] field; the translator emits __toMany around to-many
// constructor field initializers so a single value becomes a 1-element array
// (idempotent: arrays pass through, empty/undefined -> []).
// Unescape a Pure string literal (mirrors ValueSpecificationCompiler.unescapePureString):
// \' \\ \n \t \r and \uXXXX (1-4 hex); unknown escapes keep the backslash.
function __unescapePureString(s) {
  if (s.indexOf("\\") < 0) return s;
  let sb = "";
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === "\\" && i + 1 < s.length) {
      const next = s[i + 1];
      if (next === "'") { sb += "'"; i++; }
      else if (next === "\\") { sb += "\\"; i++; }
      else if (next === "n") { sb += "\n"; i++; }
      else if (next === "t") { sb += "\t"; i++; }
      else if (next === "r") { sb += "\r"; i++; }
      else if (next === "u") {
        let hs = i + 2, he = hs;
        while (he < s.length && he - hs < 4 && /[0-9a-fA-F]/.test(s[he])) he++;
        if (he > hs) { sb += String.fromCharCode(parseInt(s.slice(hs, he), 16)); i = he - 1; }
        else sb += c;
      } else sb += c;
    } else sb += c;
  }
  return sb;
}
// Normalize a Pure date literal (mirrors StringNatives.normalizePureDate):
// zero-pad components; for DateTimes with a timezone offset, convert to UTC.
function __zeroPadDate(datePart) {
  const p = datePart.split("-");
  const pad = (x) => x.length < 2 ? "0" + x : x;
  if (p.length >= 3) return p[0] + "-" + pad(p[1]) + "-" + pad(p[2]);
  if (p.length === 2) return p[0] + "-" + pad(p[1]);
  return datePart;
}
function __normalizePureDate(dateStr) {
  if (dateStr == null) return dateStr;
  const tIdx = dateStr.indexOf("T");
  if (tIdx < 0) return __zeroPadDate(dateStr);
  try {
    const datePart = dateStr.slice(0, tIdx);
    const timeAndTz = dateStr.slice(tIdx + 1);
    const tzIdx = Math.max(timeAndTz.lastIndexOf("+"), timeAndTz.lastIndexOf("-"));
    let timePart = timeAndTz, tzStr = null;
    if (tzIdx > 0) { timePart = timeAndTz.slice(0, tzIdx); tzStr = timeAndTz.slice(tzIdx); }
    const dp = datePart.split("-");
    let year = parseInt(dp[0], 10), month = dp.length > 1 ? parseInt(dp[1], 10) : 1, day = dp.length > 2 ? parseInt(dp[2], 10) : 1;
    const tp = timePart.split(":");
    let hour = parseInt(tp[0], 10), minute = tp.length > 1 ? parseInt(tp[1], 10) : 0;
    const hasSeconds = tp.length > 2;
    let second = 0, fracStr = "";
    if (hasSeconds) {
      const sec = tp[2], dot = sec.indexOf(".");
      if (dot >= 0) { second = parseInt(sec.slice(0, dot), 10); fracStr = sec.slice(dot); }
      else second = parseInt(sec, 10);
    }
    if (tzStr != null) {
      const sign = tzStr[0] === "-" ? -1 : 1;
      const offMin = sign * (parseInt(tzStr.slice(1, 3), 10) * 60 + (tzStr.length > 3 ? parseInt(tzStr.slice(3, 5), 10) : 0));
      if (offMin !== 0) {
        const d = new Date(Date.UTC(year, month - 1, day, hour, minute, second) - offMin * 60000);
        year = d.getUTCFullYear(); month = d.getUTCMonth() + 1; day = d.getUTCDate();
        hour = d.getUTCHours(); minute = d.getUTCMinutes(); second = d.getUTCSeconds();
      }
    }
    const p2 = (n) => String(n).padStart(2, "0");
    let sb = String(year).padStart(4, "0") + "-" + p2(month) + "-" + p2(day) + "T" + p2(hour) + ":" + p2(minute);
    if (hasSeconds) sb += ":" + p2(second) + fracStr;
    return sb;
  } catch (e) { return dateStr; }
}
function __toMany(v) {
  if (Array.isArray(v)) return v;
  if (v === undefined || v === null) return [];
  return [v];
}
function __pathToElement(p, sep) {
  const resolved = __metadataPathToElement(p, sep ?? "::");
  if (resolved === null || resolved === void 0) {
    // Message matches the Truffle interpreter's wording — it's part of the
    // cross-platform contract (pinned by the compileSource isolation PCT).
    throw new Error("Element not found: " + p);
  }
  return __pureResolve(resolved);
}
function __lenientPathToElement(p, sep) {
  // [0..1] result -> array representation ([] or [element]); the translator
  // compiles Pure collection ops (head()/->at(0)/->isEmpty) assuming arrays.
  const resolved = __metadataPathToElement(p, sep ?? "::");
  return (resolved === null || resolved === void 0) ? [] : [__pureResolve(resolved)];
}
function __assertIs(expected, actual) {
  if (expected === actual) return true;
  // Element identity: a translated function REFERENCE (an arrow tagged with
  // __purePath) and the element proxy pathToElement returns are the same
  // Pure element — compare paths (works across function/object shapes).
  const ppE = expected && expected.__purePath, ppA = actual && actual.__purePath;
  if (typeof ppE === "string" && ppE === ppA) return true;
  if (expected && actual && typeof expected === "object" && typeof actual === "object") {
    if (expected.path !== void 0 && expected.path === actual.path) return true;
    if (expected.name !== void 0 && expected.name === actual.name) return true;
  }
  throw new Error("assertIs: expected " + __toRepresentation(expected) + " is " + __toRepresentation(actual));
}
function __regexpLike(str, pat, flags) {
  let f = "";
  const arr = Array.isArray(flags) ? flags : [flags];
  for (const v of arr) {
    const n = v && typeof v === "object" && v.name !== void 0 ? String(v.name) : String(v);
    const last = n.split(".").pop();
    if (last === "CASE_INSENSITIVE") f += "i";
    else if (last === "MULTILINE") f += "m";
    else if (last === "NON_NEWLINE_SENSITIVE") f += "s";
  }
  return new RegExp(pat, f).test(str);
}
const __P = "meta::pure::metamodel::type::primitives::";
function __pureTypeOf(v) {
  if (v === void 0 || v === null) return void 0;
  if (typeof v === "bigint") return __P + "Integer";
  if (v instanceof Big) return __P + "Decimal";
  if (typeof v === "number") return __P + "Float";
  if (typeof v === "string") return __P + "String";
  if (typeof v === "boolean") return __P + "Boolean";
  if (v instanceof Date) return __P + (v.__fmt && String(v.__fmt).indexOf("T") >= 0 ? "DateTime" : "StrictDate");
  if (typeof v === "object") {
    const cgt = v.classifierGenericType;
    if (cgt && cgt.type) return cgt.type.__purePath ?? cgt.type.path;
    if (typeof v.__purePath === "string") return v.__purePath;
  }
  return void 0;
}
function __directSupertypes(path) {
  const out = [];
  const cls = __pureResolve(path);
  for (const g of __asArr(cls && cls.generalizations)) {
    const gt = g && g.general && g.general.type;
    const p = gt && (gt.__purePath ?? gt.path);
    if (p) out.push(p);
  }
  return out;
}
function __commonAncestor(a, b) {
  if (a === b) return a;
  if (__metadataSubtypeOf(a, b)) return b;
  if (__metadataSubtypeOf(b, a)) return a;
  let frontier = [a];
  const seen = /* @__PURE__ */ new Set();
  while (frontier.length) {
    const next = [];
    for (const t of frontier) {
      if (seen.has(t)) continue;
      seen.add(t);
      if (__metadataSubtypeOf(b, t)) return t;
      for (const s of __directSupertypes(t)) next.push(s);
    }
    frontier = next;
  }
  return "meta::pure::metamodel::type::Any";
}
function __commonType(coll) {
  const arr = __asArr(coll);
  if (arr.length === 0) return "meta::pure::metamodel::type::Any";
  let t = __pureTypeOf(arr[0]) ?? "meta::pure::metamodel::type::Any";
  for (let i = 1; i < arr.length; i++) t = __commonAncestor(t, __pureTypeOf(arr[i]) ?? "meta::pure::metamodel::type::Any");
  return t;
}
function __checkConstraint(ok, name, owner, msgFn) {
  if (ok) return;
  const m = msgFn ? msgFn() : void 0;
  throw new Error("Constraint :[" + name + "] violated in the Class " + owner + (m !== void 0 ? ", Message: " + m : ""));
}
function __assertCastMul(value, witness) {
  const expected = __asArr(witness).length;
  const actual = __asArr(value).length;
  if (actual !== expected) {
    throw new Error("Cast multiplicity error: expected " + expected + ", got " + actual);
  }
  return value;
}
function __throwAssocImmutability(className, propName) {
  throw new Error("Immutability violation: association property '" + propName + "' on '" + className + "' must be instantiated within the new/copy expression. Use `" + propName + " = ^Type(...)`, `" + propName + " = ^$x()`, or `" + propName + " = []`.");
}
let __nextEpoch = 0;
let __epochDepth = 0;
let __currentEpoch = -1;
function __ctorScope(build) {
  const fresh = __epochDepth === 0;
  if (fresh) __currentEpoch = __nextEpoch++;
  __epochDepth++;
  try {
    const v = build();
    __tagEpoch(v, __currentEpoch);
    return __guardPointer(v);
  } finally {
    __epochDepth--;
  }
}
// Pointer instances (`^ClassPointer(path = ...)`) are opaque until resolved:
// only the pointer-native slots are readable. Reading any property the pointer
// inherits from its element class throws, exactly as Truffle's PureDynamicObject
// (isPointerNativeSlot) and bootstrap's PointerAccessGuard do. Only declared
// Pure property names are guarded, so runtime-internal reads pass through.
const __POINTER_NATIVE_SLOTS = new Set(["path", "element", "classifierGenericType", "generalizations"]);
const __POINTER_GENERIC_TYPE = "GenericType_meta_pure_metamodel_pointer_";
function __pointerClassOf(v) {
  if (v === null || typeof v !== "object" || Array.isArray(v)) return undefined;
  const cgt = v.classifierGenericType;
  if (cgt === undefined || cgt === null) return undefined;
  if (typeof cgt.__purePath === "string") {
    const i = cgt.__purePath.indexOf(__POINTER_GENERIC_TYPE);
    return i < 0 ? undefined : "meta::pure::metamodel::pointer::" + cgt.__purePath.slice(i + __POINTER_GENERIC_TYPE.length);
  }
  const tp = cgt.type && cgt.type.__purePath;
  return typeof tp === "string" && tp.startsWith("meta::pure::metamodel::pointer::") ? tp : undefined;
}
function __guardPointer(v) {
  const cls = __pointerClassOf(v);
  if (cls === undefined) return v;
  const guarded = __classPropertyNames(cls);
  return new Proxy(v, {
    get(target, key, receiver) {
      if (typeof key === "string" && guarded.has(key) && !__POINTER_NATIVE_SLOTS.has(key)) {
        throw new Error("Unresolved pointer access: " + cls + "." + key
          + " — pointer reached a non-pointer-native slot read. compile() should resolve every pointer via resolveAndReturnGraph before returning; check whether a producer skipped the boundary");
      }
      return Reflect.get(target, key, receiver);
    },
  });
}
function __tagEpoch(obj, epoch) {
  if (obj === null || typeof obj !== "object" || Array.isArray(obj)) return;
  if (obj.__epoch === void 0) {
    try {
      Object.defineProperty(obj, "__epoch", { value: epoch, enumerable: false, configurable: true, writable: true });
    } catch {
    }
  }
}
function __readTypeVar(self, i) {
  const cgt = self && self.classifierGenericType;
  const tvv = cgt && cgt.typeVariableValues;
  if (!Array.isArray(tvv) || i >= tvv.length) return void 0;
  const slot = tvv[i];
  if (slot === null || slot === void 0) return slot;
  if (typeof slot === "object" && Array.isArray(slot.values)) {
    return slot.values.length === 1 ? slot.values[0] : slot.values;
  }
  return slot;
}
function __castLeaf(p) {
  return p ? String(p).split("::").pop() : "?";
}
function __cast(value, targetPath) {
  if (value === void 0 || value === null) return value;
  if (Array.isArray(value)) {
    for (const e of value) __cast(e, targetPath);
    return value;
  }
  if (__instanceOf(value, { path: targetPath })) return value;
  const vtPath = __pureTypeOf(value);
  if (vtPath && __metadataSubtypeOf(targetPath, vtPath)) return value;
  throw new Error("Cast exception: " + __castLeaf(vtPath) + " cannot be cast to " + __castLeaf(targetPath));
}
const __resolveCache = {};
const __OPT_CGT_PREFIX = "meta::pure::metamodel::type::generics::optimization::GenericType_";
const __OPT_DEFAULTS = {
  typeArguments: () => [],
  multiplicityArguments: () => [],
  typeVariableValues: () => [],
  __equalityKeys: () => []
};
function __pureResolve(address) {
  const hit = __resolveCache[address];
  if (hit !== void 0) return hit;
  const isOptCgt = address.startsWith(__OPT_CGT_PREFIX);
  const optTarget = isOptCgt ? address.substring(__OPT_CGT_PREFIX.length).replace(/_/g, "::") : null;
  const p = new Proxy({ __purePath: address, path: address }, {
    get(_target, prop) {
      if (typeof prop !== "string") return void 0;
      if (prop === "__purePath") return address;
      // `path` mirrors the class-ref `{path}` shape and equals the address
      // only for canonical (registered) elements. Address-scheme stubs
      // (`__dyn::…`, `__local::…`, `…$sub/addresses`) must NOT leak their
      // address as a path — M3 elements carry no literal `path` property
      // (paths are derived from package::name), so answer undefined and let
      // callers (e.g. __elementToPath) derive the real path.
      if (prop === "path") {
        return (address.indexOf("$") < 0
                && !address.startsWith("__dyn::")
                && !address.startsWith("__local::")) ? address : void 0;
      }
      // JS-internal markers (__mapEntries, __isDec, __equalityKeys, …) are never
      // Pure properties; never route them through the metadata globals.
      if (prop.startsWith("__")) return void 0;
      if (__reservedProxyProps.has(prop)) return void 0;
      try {
        const raw = __metadataRead(address, prop);
        return __rewrapStubs(raw);
      } catch (e) {
        if (isOptCgt && /unknown element/.test(String(e?.message ?? ""))) {
          if (prop === "type") return __pureResolve(optTarget);
          const def = __OPT_DEFAULTS[prop];
          if (def) return def();
        }
        throw e;
      }
    }
  });
  __resolveCache[address] = p;
  return p;
}
function __rewrapStubs(v) {
  if (v === void 0 || v === null) return void 0;
  if (Array.isArray(v)) return v.map(__rewrapStubs);
  if (typeof v === "object" && typeof v.__purePath === "string" && Object.keys(v).length <= 2) {
    return __pureResolve(v.__purePath);
  }
  return v;
}
function __pdo(v) {
  if (typeof v === "function" && typeof v.__purePath === "string") {
    return __pureResolve(v.__purePath);
  }
  return v;
}
function __lambda(fn, syntheticPath, openVars) {
  fn.__purePath = syntheticPath;
  if (openVars !== void 0) fn.__openVars = openVars;
  return fn;
}
function __openVariableValues(lam) {
  const ov = lam && lam.__openVars || {};
  const entries = [];
  // Entries are thunks by convention (see wrapLambdaWithAst): the capture is
  // read here, not at lambda construction, so a snapshot never trips a
  // temporal-dead-zone ReferenceError for over-approximated open variables.
  for (const k of Object.keys(ov)) entries.push([k, { values: __asArr(ov[k]()) }]);
  return { __mapEntries: entries };
}
const __ctorStack = [];
function __ctorAt(depth) {
  return __ctorStack[__ctorStack.length - 1 - depth];
}
function __newObj(base, fill) {
  __ctorStack.push(base);
  try {
    fill(base);
  } finally {
    __ctorStack.pop();
  }
  return base;
}
function __spreadEager(src) {
  if (src === null || typeof src !== "object" || Array.isArray(src)) return src;
  const out = "__purePath" in src ? { ...src, classifierGenericType: src.classifierGenericType } : { ...src };
  for (const k of Object.keys(out)) {
    if (Array.isArray(out[k])) out[k] = out[k].slice();
  }
  if ("__purePath" in src) __carryClassProperties(src, out);
  return out;
}
// A metadata-backed element exposes its properties through reads, not own keys,
// so a spread keeps only {__purePath, path}: `^$fn()` lost name, package,
// expressionSequence, ... Carry every property the element's class declares
// (Pure copy semantics) as a LAZY getter — copying a package must not eagerly
// read its whole subtree. The first read (or a write, e.g. a copy override)
// replaces the getter with a plain data property.
function __carryClassProperties(src, out) {
  const cgt = out.classifierGenericType;
  const t = cgt && cgt.type;
  const classPath = t && (t.__purePath !== void 0 ? t.__purePath : t.path);
  if (typeof classPath !== "string") return;
  const settle = (name, v) => Object.defineProperty(out, name, { value: v, writable: true, enumerable: true, configurable: true });
  for (const name of __classPropertyNames(classPath)) {
    if (name in out) continue;
    Object.defineProperty(out, name, {
      enumerable: true, configurable: true,
      get() { const v = src[name]; settle(name, Array.isArray(v) ? v.slice() : v); return out[name]; },
      set(v) { settle(name, v); },
    });
  }
}
// Every property name declared on `classPath` or inherited through its
// generalizations (including association-contributed ones), memoized per class.
const __classPropertyNamesCache = new Map();
function __classPropertyNames(classPath) {
  const cached = __classPropertyNamesCache.get(classPath);
  if (cached !== undefined) return cached;
  const names = new Set();
  const seen = new Set();
  const queue = [classPath];
  while (queue.length) {
    const path = queue.shift();
    if (typeof path !== "string" || seen.has(path)) continue;
    seen.add(path);
    const cls = __pureResolve(path);
    for (const p of [...__asArr(cls.properties), ...__asArr(cls.propertiesFromAssociations)]) {
      if (p && typeof p.name === "string") names.add(p.name);
    }
    for (const g of __asArr(cls.generalizations)) {
      const gt = g && g.general && g.general.type;
      queue.push(gt && (gt.__purePath !== void 0 ? gt.__purePath : gt.path));
    }
  }
  __classPropertyNamesCache.set(classPath, names);
  return names;
}
function __checkCopyAssocImmutability(base, className, propName) {
  if (__epochDepth !== 1) return;
  if (base === null || typeof base !== "object") return;
  const v = base[propName];
  if (v === void 0 || v === null) return;
  const check = (x) => {
    if (x === null || typeof x !== "object") return;
    if (x.__epoch !== void 0 && x.__epoch !== __currentEpoch) {
      __throwAssocImmutability(className, propName);
    }
  };
  if (Array.isArray(v)) for (const o of v) check(o);
  else check(v);
}
function __bindAssoc(thisObj, otherValue, reverseName, toMany, className, propName) {
  if (otherValue === void 0 || otherValue === null) return;
  if (Array.isArray(otherValue)) {
    for (const o of otherValue) __bindAssoc(thisObj, o, reverseName, toMany, className, propName);
    return;
  }
  if (typeof otherValue !== "object") return;
  if (className && propName && otherValue.__epoch !== void 0 && otherValue.__epoch !== __currentEpoch) {
    __throwAssocImmutability(className, propName);
  }
  if (toMany) {
    const cur = otherValue[reverseName];
    const arr = Array.isArray(cur) ? cur : cur === void 0 || cur === null ? [] : [cur];
    if (arr.indexOf(thisObj) < 0) arr.push(thisObj);
    otherValue[reverseName] = arr;
  } else {
    otherValue[reverseName] = thisObj;
  }
}
function __copy(base, overrides) {
  const self = __spreadEager(base);
  if (self === null || typeof self !== "object") return self;
  return Object.assign(self, overrides);
}
function __copyCtx(src, fill) {
  const self = __spreadEager(src);
  __ctorStack.push(self);
  try {
    fill(self);
  } finally {
    __ctorStack.pop();
  }
  return self;
}
function __genericType(v) {
  if (v === void 0 || v === null) return void 0;
  // A COLLECTION's generic type is the least common supertype of its
  // elements' types (`[1,2,3]->type()` -> Integer; mixed Integer/String ->
  // Any; CO_Address+CO_Location -> their shared CO_GeographicEntity).
  // Candidate starts at the first element's type and climbs one
  // generalization step (through the metadata globals) until every element's
  // type is a subtype of it.
  if (Array.isArray(v)) {
    if (v.length === 0) return { type: __pureResolve("meta::pure::metamodel::type::Nil") };
    if (v.length === 1) return __genericType(v[0]);
    const pathOf = (x) => {
      const g = __genericType(x);
      const t = g && g.type;
      return t && (t.__purePath !== void 0 ? t.__purePath : t.path);
    };
    const paths = v.map(pathOf);
    const ANY = "meta::pure::metamodel::type::Any";
    const covers = (c) => paths.every((p) => p && (p === c || __metadataSubtypeOf(p, c)));
    let cand = paths[0];
    while (cand && cand !== ANY && !covers(cand)) {
      const gens = __asArr(__pureResolve(cand).generalizations);
      const st = gens[0] && gens[0].general && gens[0].general.type;
      cand = st ? (st.__purePath !== void 0 ? st.__purePath : st.path) : ANY;
    }
    return { type: __pureResolve(cand && covers(cand) ? cand : ANY) };
  }
  if (typeof v === "bigint") {
    return { type: __pureResolve("meta::pure::metamodel::type::primitives::Integer") };
  }
  if (v instanceof Big) {
    return { type: __pureResolve("meta::pure::metamodel::type::primitives::Decimal") };
  }
  if (typeof v === "number") {
    return { type: __pureResolve("meta::pure::metamodel::type::primitives::Float") };
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
const __reservedProxyProps = /* @__PURE__ */ new Set([
  "then",
  "toJSON",
  "toString",
  "valueOf",
  "asymmetricMatch",
  "constructor",
  "Symbol.toPrimitive",
  "Symbol.iterator",
  "@@toPrimitive",
  "@@iterator",
  "@@toStringTag"
]);
function __pdate(lit) {
  const m = /^(\d{1,6})(?:-(\d{1,2}))?(?:-(\d{1,2}))?(?:T(\d{1,2})(?::(\d{1,2})(?::(\d{1,2})(?:\.(\d+))?)?)?)?([zZ]|[+-]\d{2}:?\d{2})?$/.exec(lit);
  let parse = lit;
  if (m !== null) {
    const Y = parseInt(m[1], 10) > 9999 ? "+" + m[1].padStart(6, "0") : m[1].padStart(4, "0");
    const Mo = (m[2] || "01").padStart(2, "0"), Da = (m[3] || "01").padStart(2, "0");
    const H = (m[4] || "00").padStart(2, "0"), Mi = (m[5] || "00").padStart(2, "0"), S = (m[6] || "00").padStart(2, "0");
    const ms = m[7] ? (m[7] + "000").slice(0, 3) : "000";
    parse = Y + "-" + Mo + "-" + Da + "T" + H + ":" + Mi + ":" + S + "." + ms + (m[8] || "Z");
  }
  const d = new Date(parse);
  d.__lit = lit;
  // Out of JS Date range (year beyond ±275760, e.g. adjust-by-big-number
  // expecteds like 33803336-12-17): the getters all read NaN, so canonicalize
  // __fmt from the literal's own fields instead — the same parse+render pair
  // __adjust uses, so both sides of an equality land on identical strings.
  d.__fmt = isNaN(d.getTime())
    ? __renderLit(__parseLitToFields(lit), __parseLitToFields(lit).gran)
    : __formatPureDate(d);
  return d;
}
function __formatPureDate(d) {
  const lit = d.__lit;
  const p2 = (n) => String(n).padStart(2, "0");
  if (lit === void 0) {
    let s = d.toISOString();
    if (s.endsWith("Z")) s = s.slice(0, -1);
    if (s.endsWith(".000")) s = s.slice(0, -4);
    return s;
  }
  // An invalid-Date CARRIER (__adjust's result) holds its value only in the
  // literal, rendered canonical and in UTC; its getters are all NaN.
  if (Number.isNaN(d.getTime())) return lit.replace(/([zZ]|[+-]\d{2}:?\d{2})$/, "");
  const Y = String(d.getUTCFullYear()).padStart(4, "0");
  const Mo = p2(d.getUTCMonth() + 1), Da = p2(d.getUTCDate());
  const tIdx = lit.indexOf("T");
  if (tIdx < 0) {
    const parts = lit.split("-").length;
    return parts === 1 ? Y : parts === 2 ? Y + "-" + Mo : Y + "-" + Mo + "-" + Da;
  }
  const H = p2(d.getUTCHours()), Mi = p2(d.getUTCMinutes()), S = p2(d.getUTCSeconds());
  const timeNoTz = lit.slice(tIdx + 1).replace(/([zZ]|[+-]\d{2}:?\d{2})$/, "");
  const colons = (timeNoTz.match(/:/g) || []).length;
  const dot = /\.(\d+)/.exec(timeNoTz);
  let out = Y + "-" + Mo + "-" + Da + "T" + H;
  if (colons >= 1) out += ":" + Mi;
  if (colons >= 2) out += ":" + S;
  if (dot !== null) out += "." + dot[1];
  return out;
}
// ===== Variant (meta::pure::metamodel::variant::Variant) =====
// A Variant wraps an immutable JSON tree:
//   null | boolean | bigint (JSON integer) | number (JSON decimal) |
//   string | Array | Map (JSON object, insertion-ordered).
// bigint-vs-number keeps the JSON integer/decimal token distinction that
// to(@Integer)/to(@Float) dispatch on — JSON.parse would collapse both to
// number, so parsing is hand-rolled. Coercion rules and error messages
// mirror the Java backends (Jackson node-type names: NULL, BOOLEAN, NUMBER,
// STRING, ARRAY, OBJECT) — they are part of the cross-platform PCT contract.
const __VARIANT_PATH = "meta::pure::metamodel::variant::Variant";
const __VARIANT_P = "meta::pure::metamodel::type::primitives::";
function __mkVariant(tree) {
  // classifierGenericType makes __matchType/cast/instanceOf see a Variant
  return { __variantJson: tree, classifierGenericType: { type: { path: __VARIANT_PATH } } };
}
function __vScalar(v) {
  return Array.isArray(v) ? (v.length === 0 ? null : v[0]) : v === void 0 ? null : v;
}
function __fromJson(s) {
  return __mkVariant(__variantParse(String(__vScalar(s))));
}
function __variantToJson(v) {
  return __variantJsonStr(__vScalar(v).__variantJson);
}
function __variantParse(text) {
  let pos = 0;
  const ws = () => {
    while (pos < text.length && " \t\n\r".includes(text[pos])) pos++;
  };
  const err = (m) => {
    throw new Error("Invalid JSON (at offset " + pos + "): " + m);
  };
  const str = () => {
    if (text[pos] !== '"') err("Expected string");
    let out = "";
    pos++;
    for (;;) {
      if (pos >= text.length) err("Unterminated string");
      const c = text[pos++];
      if (c === '"') return out;
      if (c !== "\\") {
        out += c;
        continue;
      }
      const e = text[pos++];
      if (e === '"') out += '"';
      else if (e === "\\") out += "\\";
      else if (e === "/") out += "/";
      else if (e === "b") out += "\b";
      else if (e === "f") out += "\f";
      else if (e === "n") out += "\n";
      else if (e === "r") out += "\r";
      else if (e === "t") out += "\t";
      else if (e === "u") {
        out += String.fromCharCode(parseInt(text.slice(pos, pos + 4), 16));
        pos += 4;
      } else err("Invalid escape character '\\" + e + "'");
    }
  };
  const num = () => {
    const start = pos;
    if (text[pos] === "-") pos++;
    while (pos < text.length && text[pos] >= "0" && text[pos] <= "9") pos++;
    let integral = true;
    if (text[pos] === ".") {
      integral = false;
      pos++;
      while (pos < text.length && text[pos] >= "0" && text[pos] <= "9") pos++;
    }
    if (text[pos] === "e" || text[pos] === "E") {
      integral = false;
      pos++;
      if (text[pos] === "+" || text[pos] === "-") pos++;
      while (pos < text.length && text[pos] >= "0" && text[pos] <= "9") pos++;
    }
    const lit = text.slice(start, pos);
    if (lit === "" || lit === "-") err("Unexpected token");
    return integral ? BigInt(lit) : Number(lit);
  };
  const obj = () => {
    pos++;
    const m = new Map();
    ws();
    if (text[pos] === "}") {
      pos++;
      return m;
    }
    for (;;) {
      ws();
      const k = str();
      ws();
      if (text[pos] !== ":") err("Expected ':'");
      pos++;
      ws();
      m.set(k, value());
      ws();
      if (text[pos] === ",") {
        pos++;
        continue;
      }
      if (text[pos] === "}") {
        pos++;
        return m;
      }
      err("Expected ',' or '}'");
    }
  };
  const arr = () => {
    pos++;
    const a = [];
    ws();
    if (text[pos] === "]") {
      pos++;
      return a;
    }
    for (;;) {
      ws();
      a.push(value());
      ws();
      if (text[pos] === ",") {
        pos++;
        continue;
      }
      if (text[pos] === "]") {
        pos++;
        return a;
      }
      err("Expected ',' or ']'");
    }
  };
  const value = () => {
    const c = text[pos];
    if (c === "{") return obj();
    if (c === "[") return arr();
    if (c === '"') return str();
    if (text.startsWith("true", pos)) {
      pos += 4;
      return true;
    }
    if (text.startsWith("false", pos)) {
      pos += 5;
      return false;
    }
    if (text.startsWith("null", pos)) {
      pos += 4;
      return null;
    }
    return num();
  };
  ws();
  const v = value();
  ws();
  if (pos < text.length) err("Unexpected trailing content");
  return v;
}
function __variantJsonStr(t) {
  if (t === null) return "null";
  if (typeof t === "boolean" || typeof t === "bigint") return String(t);
  // Whole decimals keep a .0 suffix, matching Java's Double.toString
  if (typeof t === "number") return Number.isInteger(t) ? t.toFixed(1) : String(t);
  if (typeof t === "string") return JSON.stringify(t);
  if (Array.isArray(t)) return "[" + t.map(__variantJsonStr).join(",") + "]";
  let out = "{";
  let first = true;
  for (const [k, v] of t) {
    if (!first) out += ",";
    first = false;
    out += JSON.stringify(k) + ":" + __variantJsonStr(v);
  }
  return out + "}";
}
function __variantNodeType(t) {
  if (t === null) return "NULL";
  if (typeof t === "boolean") return "BOOLEAN";
  if (typeof t === "bigint" || typeof t === "number") return "NUMBER";
  if (typeof t === "string") return "STRING";
  return Array.isArray(t) ? "ARRAY" : "OBJECT";
}
function __toVariant(vals) {
  if (Array.isArray(vals)) {
    if (vals.length === 0) return __mkVariant(null);
    if (vals.length === 1) return __mkVariant(__variantValueTree(vals[0]));
    return __mkVariant(vals.map(__variantValueTree));
  }
  return __mkVariant(__variantValueTree(vals));
}
function __variantValueTree(v) {
  if (v === void 0 || v === null) return null;
  if (Array.isArray(v)) return v.map(__variantValueTree);
  if (typeof v === "bigint" || typeof v === "number" || typeof v === "boolean" || typeof v === "string") return v;
  if (v instanceof Big) return Number(v.toString());
  if (v instanceof Date) return __formatPureDate(v);
  if (typeof v === "object") {
    if (v.__variantJson !== void 0) return v.__variantJson;
    if (__isMap(v)) {
      // Object keys are rendered in sorted order for deterministic output
      const entries = v.__mapEntries.map(([k, val]) => {
        if (typeof k !== "string") throw new Error("Only maps with String keys can be converted to Variant, got key: " + __toString(k));
        return [k, __variantValueTree(val)];
      });
      entries.sort((x, y) => (x[0] < y[0] ? -1 : x[0] > y[0] ? 1 : 0));
      return new Map(entries);
    }
    if (v.values !== void 0) return __asArr(v.values).map(__variantValueTree); // List instance
  }
  throw new Error(__toString(v) + " - not supported!");
}
function __vDescName(d) {
  const simple = (path) =>
    path && (path.startsWith("meta::pure::metamodel::") || path.startsWith("meta::pure::functions::"))
      ? path.split("::").pop()
      : path || "?";
  return simple(d.p) + (d.a.length ? "<" + d.a.map(__vDescName).join(", ") + ">" : "");
}
function __variantTo(v, d) {
  // d: {p: '<type path>', a: [...]} — target-type descriptor built by the
  // translator from the @T holder at translation time
  const x = __vScalar(v);
  if (x === null) return [];
  const r = __variantCoerce(x.__variantJson, d);
  return r === null ? [] : r;
}
function __variantToMany(v, d) {
  const x = __vScalar(v);
  if (x === null) return [];
  const t = x.__variantJson;
  if (!Array.isArray(t)) throw new Error("Expect variant that contains an 'ARRAY', but got '" + __variantNodeType(t) + "'");
  return t.map((e) => __variantCoerce(e, d)).filter((e) => e !== null);
}
function __variantGet(v, k) {
  // get(Variant[0..1], String[1]) / get(Variant[0..1], Integer[1]) — mirrors
  // the pure-level definition (to(@Map<String, Variant>) / toMany(@Variant)->at)
  const x = __vScalar(v);
  if (x === null) return [];
  const t = x.__variantJson;
  if (typeof k === "bigint") {
    if (!Array.isArray(t)) throw new Error("Expect variant that contains an 'ARRAY', but got '" + __variantNodeType(t) + "'");
    const i = Number(k);
    if (i < 0 || i >= t.length) throw new Error("The system is trying to get an element at offset " + i + " where the collection is of size " + t.length);
    return [__mkVariant(t[i])];
  }
  if (t === null) return [];
  if (!(t instanceof Map)) throw new Error("Variant of type '" + __variantNodeType(t) + "' cannot be converted to Map<String, Variant>");
  return t.has(k) ? [__mkVariant(t.get(k))] : [];
}
function __variantCoerce(t, d) {
  if (d.p === __VARIANT_PATH) return __mkVariant(t);
  if (t === null) return null;
  let wrong = false;
  if (d.p === "meta::pure::functions::collection::List") {
    if (Array.isArray(t)) {
      const ed = d.a[0] || { p: void 0, a: [] };
      return {
        values: t.map((e) => __variantCoerce(e, ed)).filter((e) => e !== null),
        // self-describing tag so the JS->Truffle marshaller lifts to a List PDO
        classifierGenericType: { type: { path: "meta::pure::functions::collection::List" } }
      };
    }
    wrong = true;
  } else if (d.p === "meta::pure::functions::collection::Map" && d.a[0] && d.a[0].p === __VARIANT_P + "String") {
    if (t instanceof Map) {
      const vd = d.a[1] || { p: void 0, a: [] };
      const entries = [];
      for (const [k, v] of t) {
        const c = __variantCoerce(v, vd);
        entries.push([k, c === null ? [] : c]);
      }
      return { __mapEntries: entries };
    }
    wrong = true;
  } else if (d.p === __VARIANT_P + "Integer") {
    if (typeof t === "bigint") return t;
    if (typeof t === "string") {
      if (!/^[+-]?\d+$/.test(t.trim())) throw new Error('For input string: "' + t + '"');
      return BigInt(t.trim());
    }
    wrong = true;
  } else if (d.p === __VARIANT_P + "Float") {
    if (typeof t === "bigint") return Number(t);
    if (typeof t === "number") return t;
    if (typeof t === "string") {
      const n = Number(t);
      if (Number.isNaN(n)) throw new Error('For input string: "' + t + '"');
      return n;
    }
    wrong = true;
  } else if (d.p === __VARIANT_P + "StrictDate") {
    if (typeof t === "string") {
      if (!/^\d{4}-\d{1,2}-\d{1,2}$/.test(t)) throw new Error("StrictDate must be a calendar day, got: " + t);
      return __pdate(t);
    }
    wrong = true;
  } else if (d.p === __VARIANT_P + "DateTime") {
    if (typeof t === "string") {
      if (!t.includes("T")) throw new Error("DateTime must include time information, got: " + t);
      return __pdate(t);
    }
    wrong = true;
  } else if (d.p === __VARIANT_P + "String") {
    if (typeof t === "string") return t;
    if (typeof t === "bigint") return String(t);
    if (typeof t === "number") return Number.isInteger(t) ? t.toFixed(1) : String(t);
    if (typeof t === "boolean") return String(t);
    wrong = true;
  } else if (d.p === __VARIANT_P + "Boolean") {
    if (typeof t === "boolean") return t;
    if (typeof t === "string") {
      if (t === "true") return true;
      if (t === "false") return false;
      throw new Error("Invalid Pure Boolean: '" + t + "'");
    }
    wrong = true;
  }
  if (wrong) throw new Error("Variant of type '" + __variantNodeType(t) + "' cannot be converted to " + __vDescName(d));
  throw new Error(__vDescName(d) + " is not managed yet!");
}
function __toString(v) {
  if (v === void 0 || v === null) return "";
  if (v instanceof Big) return v.toString();
  if (v instanceof Date) return __formatPureDate(v);
  if (Array.isArray(v)) return "[" + v.map(__toString).join(", ") + "]";
  if (typeof v === "object") {
    if (v.__variantJson !== void 0) return __variantJsonStr(v.__variantJson);
    if (typeof v.__purePath === "string") {
      const nm = Array.isArray(v.name) ? v.name[0] : v.name;
      if (typeof nm === "string" && nm.length) return nm;
      return v.__purePath.split("::").pop();
    }
    if (v.first !== void 0 && v.second !== void 0)
      return "<" + __toString(v.first) + ", " + __toString(v.second) + ">";
    if (Array.isArray(v.values))
      return "[" + v.values.map(__toString).join(", ") + "]";
    if (typeof v.name === "string") return v.name;
    return __json(v);
  }
  return String(v);
}
function __pureFormat(v, depth) {
  if (v === void 0 || v === null) return "";
  if (Array.isArray(v)) {
    if (v.length === 0) return "";
    return v.map((e) => __pureFormat(e, depth)).join("\n");
  }
  if (typeof v === "string") return depth === 0 ? v : "'" + v + "'";
  if (typeof v === "bigint") return v.toString();
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  if (v instanceof Big) return v.toString();
  if (v instanceof Date) return __formatPureDate(v);
  if (typeof v === "object") {
    if (v.__variantJson !== void 0) return __variantJsonStr(v.__variantJson);
    const cgt = v.classifierGenericType;
    if (cgt && cgt.type) {
      const typePath = cgt.type.__purePath || cgt.type.path || "";
      const className = typePath.split("::").pop() || "Object";
      const childIndent = "  ".repeat(depth + 1);
      const keys = Object.keys(v).filter((k) => !k.startsWith("_") && k !== "classifierGenericType" && v[k] !== void 0);
      let out = className;
      for (const k of keys) {
        const valFmt = __pureFormat(v[k], depth + 1);
        out += "\n" + childIndent + k + ": " + valFmt;
      }
      return out;
    }
    return __toString(v);
  }
  return String(v);
}
// meta::pure::functions::io::withSilencedPrint — suppress the WRITE only. The
// native's contract is that `println(x)` still RETURNS 'x\n'; just nothing
// reaches stdout. Nesting is counted so an inner silence cannot un-silence an
// outer one, and the counter is restored on throw.
let __printSilenceDepth = 0;
function __withSilencedPrint(body) {
  __printSilenceDepth++;
  try { return typeof body === "function" ? body() : body; }
  finally { __printSilenceDepth--; }
}
function __print(v) {
  const s = __pureFormat(v, 0);
  if (__printSilenceDepth === 0) console.log(s);
  return s;
}
function __println(v) {
  const s = __pureFormat(v, 0) + "\n";
  if (__printSilenceDepth === 0) console.log(s);
  return s;
}
