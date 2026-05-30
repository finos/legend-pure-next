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

// ---- Vendored big.js v6.2.2 (MIT — github.com/MikeMcl/big.js) ----
// Pure Decimal is an arbitrary-precision fixed-point number; big.js provides it.
// Vendored inline (the UMD export wrapper replaced by `return Big`) because the
// GraalJS transpile context has no module resolution. Used by the __dec* helpers.
const Big: any = (function () {
  'use strict';
  var Big,


/************************************** EDITABLE DEFAULTS *****************************************/


    // The default values below must be integers within the stated ranges.

    /*
     * The maximum number of decimal places (DP) of the results of operations involving division:
     * div and sqrt, and pow with negative exponents.
     */
    DP = 20,            // 0 to MAX_DP

    /*
     * The rounding mode (RM) used when rounding to the above decimal places.
     *
     *  0  Towards zero (i.e. truncate, no rounding).       (ROUND_DOWN)
     *  1  To nearest neighbour. If equidistant, round up.  (ROUND_HALF_UP)
     *  2  To nearest neighbour. If equidistant, to even.   (ROUND_HALF_EVEN)
     *  3  Away from zero.                                  (ROUND_UP)
     */
    RM = 1,             // 0, 1, 2 or 3

    // The maximum value of DP and Big.DP.
    MAX_DP = 1E6,       // 0 to 1000000

    // The maximum magnitude of the exponent argument to the pow method.
    MAX_POWER = 1E6,    // 1 to 1000000

    /*
     * The negative exponent (NE) at and beneath which toString returns exponential notation.
     * (JavaScript numbers: -7)
     * -1000000 is the minimum recommended exponent value of a Big.
     */
    NE = -7,            // 0 to -1000000

    /*
     * The positive exponent (PE) at and above which toString returns exponential notation.
     * (JavaScript numbers: 21)
     * 1000000 is the maximum recommended exponent value of a Big, but this limit is not enforced.
     */
    PE = 21,            // 0 to 1000000

    /*
     * When true, an error will be thrown if a primitive number is passed to the Big constructor,
     * or if valueOf is called, or if toNumber is called on a Big which cannot be converted to a
     * primitive number without a loss of precision.
     */
    STRICT = false,     // true or false


/**************************************************************************************************/


    // Error messages.
    NAME = '[big.js] ',
    INVALID = NAME + 'Invalid ',
    INVALID_DP = INVALID + 'decimal places',
    INVALID_RM = INVALID + 'rounding mode',
    DIV_BY_ZERO = NAME + 'Division by zero',

    // The shared prototype object.
    P = {},
    UNDEFINED = void 0,
    NUMERIC = /^-?(\d+(\.\d*)?|\.\d+)(e[+-]?\d+)?$/i;


  /*
   * Create and return a Big constructor.
   */
  function _Big_() {

    /*
     * The Big constructor and exported function.
     * Create and return a new instance of a Big number object.
     *
     * n {number|string|Big} A numeric value.
     */
    function Big(n) {
      var x = this;

      // Enable constructor usage without new.
      if (!(x instanceof Big)) return n === UNDEFINED ? _Big_() : new Big(n);

      // Duplicate.
      if (n instanceof Big) {
        x.s = n.s;
        x.e = n.e;
        x.c = n.c.slice();
      } else {
        if (typeof n !== 'string') {
          if (Big.strict === true && typeof n !== 'bigint') {
            throw TypeError(INVALID + 'value');
          }

          // Minus zero?
          n = n === 0 && 1 / n < 0 ? '-0' : String(n);
        }

        parse(x, n);
      }

      // Retain a reference to this Big constructor.
      // Shadow Big.prototype.constructor which points to Object.
      x.constructor = Big;
    }

    Big.prototype = P;
    Big.DP = DP;
    Big.RM = RM;
    Big.NE = NE;
    Big.PE = PE;
    Big.strict = STRICT;
    Big.roundDown = 0;
    Big.roundHalfUp = 1;
    Big.roundHalfEven = 2;
    Big.roundUp = 3;

    return Big;
  }


  /*
   * Parse the number or string value passed to a Big constructor.
   *
   * x {Big} A Big number instance.
   * n {number|string} A numeric value.
   */
  function parse(x, n) {
    var e, i, nl;

    if (!NUMERIC.test(n)) {
      throw Error(INVALID + 'number');
    }

    // Determine sign.
    x.s = n.charAt(0) == '-' ? (n = n.slice(1), -1) : 1;

    // Decimal point?
    if ((e = n.indexOf('.')) > -1) n = n.replace('.', '');

    // Exponential form?
    if ((i = n.search(/e/i)) > 0) {

      // Determine exponent.
      if (e < 0) e = i;
      e += +n.slice(i + 1);
      n = n.substring(0, i);
    } else if (e < 0) {

      // Integer.
      e = n.length;
    }

    nl = n.length;

    // Determine leading zeros.
    for (i = 0; i < nl && n.charAt(i) == '0';) ++i;

    if (i == nl) {

      // Zero.
      x.c = [x.e = 0];
    } else {

      // Determine trailing zeros.
      for (; nl > 0 && n.charAt(--nl) == '0';);
      x.e = e - i - 1;
      x.c = [];

      // Convert string to array of digits without leading/trailing zeros.
      for (e = 0; i <= nl;) x.c[e++] = +n.charAt(i++);
    }

    return x;
  }


  /*
   * Round Big x to a maximum of sd significant digits using rounding mode rm.
   *
   * x {Big} The Big to round.
   * sd {number} Significant digits: integer, 0 to MAX_DP inclusive.
   * rm {number} Rounding mode: 0 (down), 1 (half-up), 2 (half-even) or 3 (up).
   * [more] {boolean} Whether the result of division was truncated.
   */
  function round(x, sd, rm, more) {
    var xc = x.c;

    if (rm === UNDEFINED) rm = x.constructor.RM;
    if (rm !== 0 && rm !== 1 && rm !== 2 && rm !== 3) {
      throw Error(INVALID_RM);
    }

    if (sd < 1) {
      more =
        rm === 3 && (more || !!xc[0]) || sd === 0 && (
        rm === 1 && xc[0] >= 5 ||
        rm === 2 && (xc[0] > 5 || xc[0] === 5 && (more || xc[1] !== UNDEFINED))
      );

      xc.length = 1;

      if (more) {

        // 1, 0.1, 0.01, 0.001, 0.0001 etc.
        x.e = x.e - sd + 1;
        xc[0] = 1;
      } else {

        // Zero.
        xc[0] = x.e = 0;
      }
    } else if (sd < xc.length) {

      // xc[sd] is the digit after the digit that may be rounded up.
      more =
        rm === 1 && xc[sd] >= 5 ||
        rm === 2 && (xc[sd] > 5 || xc[sd] === 5 &&
          (more || xc[sd + 1] !== UNDEFINED || xc[sd - 1] & 1)) ||
        rm === 3 && (more || !!xc[0]);

      // Remove any digits after the required precision.
      xc.length = sd;

      // Round up?
      if (more) {

        // Rounding up may mean the previous digit has to be rounded up.
        for (; ++xc[--sd] > 9;) {
          xc[sd] = 0;
          if (sd === 0) {
            ++x.e;
            xc.unshift(1);
            break;
          }
        }
      }

      // Remove trailing zeros.
      for (sd = xc.length; !xc[--sd];) xc.pop();
    }

    return x;
  }


  /*
   * Return a string representing the value of Big x in normal or exponential notation.
   * Handles P.toExponential, P.toFixed, P.toJSON, P.toPrecision, P.toString and P.valueOf.
   */
  function stringify(x, doExponential, isNonzero) {
    var e = x.e,
      s = x.c.join(''),
      n = s.length;

    // Exponential notation?
    if (doExponential) {
      s = s.charAt(0) + (n > 1 ? '.' + s.slice(1) : '') + (e < 0 ? 'e' : 'e+') + e;

    // Normal notation.
    } else if (e < 0) {
      for (; ++e;) s = '0' + s;
      s = '0.' + s;
    } else if (e > 0) {
      if (++e > n) {
        for (e -= n; e--;) s += '0';
      } else if (e < n) {
        s = s.slice(0, e) + '.' + s.slice(e);
      }
    } else if (n > 1) {
      s = s.charAt(0) + '.' + s.slice(1);
    }

    return x.s < 0 && isNonzero ? '-' + s : s;
  }


  // Prototype/instance methods


  /*
   * Return a new Big whose value is the absolute value of this Big.
   */
  P.abs = function () {
    var x = new this.constructor(this);
    x.s = 1;
    return x;
  };


  /*
   * Return 1 if the value of this Big is greater than the value of Big y,
   *       -1 if the value of this Big is less than the value of Big y, or
   *        0 if they have the same value.
   */
  P.cmp = function (y) {
    var isneg,
      x = this,
      xc = x.c,
      yc = (y = new x.constructor(y)).c,
      i = x.s,
      j = y.s,
      k = x.e,
      l = y.e;

    // Either zero?
    if (!xc[0] || !yc[0]) return !xc[0] ? !yc[0] ? 0 : -j : i;

    // Signs differ?
    if (i != j) return i;

    isneg = i < 0;

    // Compare exponents.
    if (k != l) return k > l ^ isneg ? 1 : -1;

    j = (k = xc.length) < (l = yc.length) ? k : l;

    // Compare digit by digit.
    for (i = -1; ++i < j;) {
      if (xc[i] != yc[i]) return xc[i] > yc[i] ^ isneg ? 1 : -1;
    }

    // Compare lengths.
    return k == l ? 0 : k > l ^ isneg ? 1 : -1;
  };


  /*
   * Return a new Big whose value is the value of this Big divided by the value of Big y, rounded,
   * if necessary, to a maximum of Big.DP decimal places using rounding mode Big.RM.
   */
  P.div = function (y) {
    var x = this,
      Big = x.constructor,
      a = x.c,                  // dividend
      b = (y = new Big(y)).c,   // divisor
      k = x.s == y.s ? 1 : -1,
      dp = Big.DP;

    if (dp !== ~~dp || dp < 0 || dp > MAX_DP) {
      throw Error(INVALID_DP);
    }

    // Divisor is zero?
    if (!b[0]) {
      throw Error(DIV_BY_ZERO);
    }

    // Dividend is 0? Return +-0.
    if (!a[0]) {
      y.s = k;
      y.c = [y.e = 0];
      return y;
    }

    var bl, bt, n, cmp, ri,
      bz = b.slice(),
      ai = bl = b.length,
      al = a.length,
      r = a.slice(0, bl),   // remainder
      rl = r.length,
      q = y,                // quotient
      qc = q.c = [],
      qi = 0,
      p = dp + (q.e = x.e - y.e) + 1;    // precision of the result

    q.s = k;
    k = p < 0 ? 0 : p;

    // Create version of divisor with leading zero.
    bz.unshift(0);

    // Add zeros to make remainder as long as divisor.
    for (; rl++ < bl;) r.push(0);

    do {

      // n is how many times the divisor goes into current remainder.
      for (n = 0; n < 10; n++) {

        // Compare divisor and remainder.
        if (bl != (rl = r.length)) {
          cmp = bl > rl ? 1 : -1;
        } else {
          for (ri = -1, cmp = 0; ++ri < bl;) {
            if (b[ri] != r[ri]) {
              cmp = b[ri] > r[ri] ? 1 : -1;
              break;
            }
          }
        }

        // If divisor < remainder, subtract divisor from remainder.
        if (cmp < 0) {

          // Remainder can't be more than 1 digit longer than divisor.
          // Equalise lengths using divisor with extra leading zero?
          for (bt = rl == bl ? b : bz; rl;) {
            if (r[--rl] < bt[rl]) {
              ri = rl;
              for (; ri && !r[--ri];) r[ri] = 9;
              --r[ri];
              r[rl] += 10;
            }
            r[rl] -= bt[rl];
          }

          for (; !r[0];) r.shift();
        } else {
          break;
        }
      }

      // Add the digit n to the result array.
      qc[qi++] = cmp ? n : ++n;

      // Update the remainder.
      if (r[0] && cmp) r[rl] = a[ai] || 0;
      else r = [a[ai]];

    } while ((ai++ < al || r[0] !== UNDEFINED) && k--);

    // Leading zero? Do not remove if result is simply zero (qi == 1).
    if (!qc[0] && qi != 1) {

      // There can't be more than one zero.
      qc.shift();
      q.e--;
      p--;
    }

    // Round?
    if (qi > p) round(q, p, Big.RM, r[0] !== UNDEFINED);

    return q;
  };


  /*
   * Return true if the value of this Big is equal to the value of Big y, otherwise return false.
   */
  P.eq = function (y) {
    return this.cmp(y) === 0;
  };


  /*
   * Return true if the value of this Big is greater than the value of Big y, otherwise return
   * false.
   */
  P.gt = function (y) {
    return this.cmp(y) > 0;
  };


  /*
   * Return true if the value of this Big is greater than or equal to the value of Big y, otherwise
   * return false.
   */
  P.gte = function (y) {
    return this.cmp(y) > -1;
  };


  /*
   * Return true if the value of this Big is less than the value of Big y, otherwise return false.
   */
  P.lt = function (y) {
    return this.cmp(y) < 0;
  };


  /*
   * Return true if the value of this Big is less than or equal to the value of Big y, otherwise
   * return false.
   */
  P.lte = function (y) {
    return this.cmp(y) < 1;
  };


  /*
   * Return a new Big whose value is the value of this Big minus the value of Big y.
   */
  P.minus = P.sub = function (y) {
    var i, j, t, xlty,
      x = this,
      Big = x.constructor,
      a = x.s,
      b = (y = new Big(y)).s;

    // Signs differ?
    if (a != b) {
      y.s = -b;
      return x.plus(y);
    }

    var xc = x.c.slice(),
      xe = x.e,
      yc = y.c,
      ye = y.e;

    // Either zero?
    if (!xc[0] || !yc[0]) {
      if (yc[0]) {
        y.s = -b;
      } else if (xc[0]) {
        y = new Big(x);
      } else {
        y.s = 1;
      }
      return y;
    }

    // Determine which is the bigger number. Prepend zeros to equalise exponents.
    if (a = xe - ye) {

      if (xlty = a < 0) {
        a = -a;
        t = xc;
      } else {
        ye = xe;
        t = yc;
      }

      t.reverse();
      for (b = a; b--;) t.push(0);
      t.reverse();
    } else {

      // Exponents equal. Check digit by digit.
      j = ((xlty = xc.length < yc.length) ? xc : yc).length;

      for (a = b = 0; b < j; b++) {
        if (xc[b] != yc[b]) {
          xlty = xc[b] < yc[b];
          break;
        }
      }
    }

    // x < y? Point xc to the array of the bigger number.
    if (xlty) {
      t = xc;
      xc = yc;
      yc = t;
      y.s = -y.s;
    }

    /*
     * Append zeros to xc if shorter. No need to add zeros to yc if shorter as subtraction only
     * needs to start at yc.length.
     */
    if ((b = (j = yc.length) - (i = xc.length)) > 0) for (; b--;) xc[i++] = 0;

    // Subtract yc from xc.
    for (b = i; j > a;) {
      if (xc[--j] < yc[j]) {
        for (i = j; i && !xc[--i];) xc[i] = 9;
        --xc[i];
        xc[j] += 10;
      }

      xc[j] -= yc[j];
    }

    // Remove trailing zeros.
    for (; xc[--b] === 0;) xc.pop();

    // Remove leading zeros and adjust exponent accordingly.
    for (; xc[0] === 0;) {
      xc.shift();
      --ye;
    }

    if (!xc[0]) {

      // n - n = +0
      y.s = 1;

      // Result must be zero.
      xc = [ye = 0];
    }

    y.c = xc;
    y.e = ye;

    return y;
  };


  /*
   * Return a new Big whose value is the value of this Big modulo the value of Big y.
   */
  P.mod = function (y) {
    var ygtx,
      x = this,
      Big = x.constructor,
      a = x.s,
      b = (y = new Big(y)).s;

    if (!y.c[0]) {
      throw Error(DIV_BY_ZERO);
    }

    x.s = y.s = 1;
    ygtx = y.cmp(x) == 1;
    x.s = a;
    y.s = b;

    if (ygtx) return new Big(x);

    a = Big.DP;
    b = Big.RM;
    Big.DP = Big.RM = 0;
    x = x.div(y);
    Big.DP = a;
    Big.RM = b;

    return this.minus(x.times(y));
  };
  
  
  /*
   * Return a new Big whose value is the value of this Big negated.
   */
  P.neg = function () {
    var x = new this.constructor(this);
    x.s = -x.s;
    return x;
  };


  /*
   * Return a new Big whose value is the value of this Big plus the value of Big y.
   */
  P.plus = P.add = function (y) {
    var e, k, t,
      x = this,
      Big = x.constructor;

    y = new Big(y);

    // Signs differ?
    if (x.s != y.s) {
      y.s = -y.s;
      return x.minus(y);
    }

    var xe = x.e,
      xc = x.c,
      ye = y.e,
      yc = y.c;

    // Either zero?
    if (!xc[0] || !yc[0]) {
      if (!yc[0]) {
        if (xc[0]) {
          y = new Big(x);
        } else {
          y.s = x.s;
        }
      }
      return y;
    }

    xc = xc.slice();

    // Prepend zeros to equalise exponents.
    // Note: reverse faster than unshifts.
    if (e = xe - ye) {
      if (e > 0) {
        ye = xe;
        t = yc;
      } else {
        e = -e;
        t = xc;
      }

      t.reverse();
      for (; e--;) t.push(0);
      t.reverse();
    }

    // Point xc to the longer array.
    if (xc.length - yc.length < 0) {
      t = yc;
      yc = xc;
      xc = t;
    }

    e = yc.length;

    // Only start adding at yc.length - 1 as the further digits of xc can be left as they are.
    for (k = 0; e; xc[e] %= 10) k = (xc[--e] = xc[e] + yc[e] + k) / 10 | 0;

    // No need to check for zero, as +x + +y != 0 && -x + -y != 0

    if (k) {
      xc.unshift(k);
      ++ye;
    }

    // Remove trailing zeros.
    for (e = xc.length; xc[--e] === 0;) xc.pop();

    y.c = xc;
    y.e = ye;

    return y;
  };


  /*
   * Return a Big whose value is the value of this Big raised to the power n.
   * If n is negative, round to a maximum of Big.DP decimal places using rounding
   * mode Big.RM.
   *
   * n {number} Integer, -MAX_POWER to MAX_POWER inclusive.
   */
  P.pow = function (n) {
    var x = this,
      one = new x.constructor('1'),
      y = one,
      isneg = n < 0;

    if (n !== ~~n || n < -MAX_POWER || n > MAX_POWER) {
      throw Error(INVALID + 'exponent');
    }

    if (isneg) n = -n;

    for (;;) {
      if (n & 1) y = y.times(x);
      n >>= 1;
      if (!n) break;
      x = x.times(x);
    }

    return isneg ? one.div(y) : y;
  };


  /*
   * Return a new Big whose value is the value of this Big rounded to a maximum precision of sd
   * significant digits using rounding mode rm, or Big.RM if rm is not specified.
   *
   * sd {number} Significant digits: integer, 1 to MAX_DP inclusive.
   * rm? {number} Rounding mode: 0 (down), 1 (half-up), 2 (half-even) or 3 (up).
   */
  P.prec = function (sd, rm) {
    if (sd !== ~~sd || sd < 1 || sd > MAX_DP) {
      throw Error(INVALID + 'precision');
    }
    return round(new this.constructor(this), sd, rm);
  };


  /*
   * Return a new Big whose value is the value of this Big rounded to a maximum of dp decimal places
   * using rounding mode rm, or Big.RM if rm is not specified.
   * If dp is negative, round to an integer which is a multiple of 10**-dp.
   * If dp is not specified, round to 0 decimal places.
   *
   * dp? {number} Integer, -MAX_DP to MAX_DP inclusive.
   * rm? {number} Rounding mode: 0 (down), 1 (half-up), 2 (half-even) or 3 (up).
   */
  P.round = function (dp, rm) {
    if (dp === UNDEFINED) dp = 0;
    else if (dp !== ~~dp || dp < -MAX_DP || dp > MAX_DP) {
      throw Error(INVALID_DP);
    }
    return round(new this.constructor(this), dp + this.e + 1, rm);
  };


  /*
   * Return a new Big whose value is the square root of the value of this Big, rounded, if
   * necessary, to a maximum of Big.DP decimal places using rounding mode Big.RM.
   */
  P.sqrt = function () {
    var r, c, t,
      x = this,
      Big = x.constructor,
      s = x.s,
      e = x.e,
      half = new Big('0.5');

    // Zero?
    if (!x.c[0]) return new Big(x);

    // Negative?
    if (s < 0) {
      throw Error(NAME + 'No square root');
    }

    // Estimate.
    s = Math.sqrt(+stringify(x, true, true));

    // Math.sqrt underflow/overflow?
    // Re-estimate: pass x coefficient to Math.sqrt as integer, then adjust the result exponent.
    if (s === 0 || s === 1 / 0) {
      c = x.c.join('');
      if (!(c.length + e & 1)) c += '0';
      s = Math.sqrt(c);
      e = ((e + 1) / 2 | 0) - (e < 0 || e & 1);
      r = new Big((s == 1 / 0 ? '5e' : (s = s.toExponential()).slice(0, s.indexOf('e') + 1)) + e);
    } else {
      r = new Big(s + '');
    }

    e = r.e + (Big.DP += 4);

    // Newton-Raphson iteration.
    do {
      t = r;
      r = half.times(t.plus(x.div(t)));
    } while (t.c.slice(0, e).join('') !== r.c.slice(0, e).join(''));

    return round(r, (Big.DP -= 4) + r.e + 1, Big.RM);
  };


  /*
   * Return a new Big whose value is the value of this Big times the value of Big y.
   */
  P.times = P.mul = function (y) {
    var c,
      x = this,
      Big = x.constructor,
      xc = x.c,
      yc = (y = new Big(y)).c,
      a = xc.length,
      b = yc.length,
      i = x.e,
      j = y.e;

    // Determine sign of result.
    y.s = x.s == y.s ? 1 : -1;

    // Return signed 0 if either 0.
    if (!xc[0] || !yc[0]) {
      y.c = [y.e = 0];
      return y;
    }

    // Initialise exponent of result as x.e + y.e.
    y.e = i + j;

    // If array xc has fewer digits than yc, swap xc and yc, and lengths.
    if (a < b) {
      c = xc;
      xc = yc;
      yc = c;
      j = a;
      a = b;
      b = j;
    }

    // Initialise coefficient array of result with zeros.
    for (c = new Array(j = a + b); j--;) c[j] = 0;

    // Multiply.

    // i is initially xc.length.
    for (i = b; i--;) {
      b = 0;

      // a is yc.length.
      for (j = a + i; j > i;) {

        // Current sum of products at this digit position, plus carry.
        b = c[j] + yc[i] * xc[j - i - 1] + b;
        c[j--] = b % 10;

        // carry
        b = b / 10 | 0;
      }

      c[j] = b;
    }

    // Increment result exponent if there is a final carry, otherwise remove leading zero.
    if (b) ++y.e;
    else c.shift();

    // Remove trailing zeros.
    for (i = c.length; !c[--i];) c.pop();
    y.c = c;

    return y;
  };


  /*
   * Return a string representing the value of this Big in exponential notation rounded to dp fixed
   * decimal places using rounding mode rm, or Big.RM if rm is not specified.
   *
   * dp? {number} Decimal places: integer, 0 to MAX_DP inclusive.
   * rm? {number} Rounding mode: 0 (down), 1 (half-up), 2 (half-even) or 3 (up).
   */
  P.toExponential = function (dp, rm) {
    var x = this,
      n = x.c[0];

    if (dp !== UNDEFINED) {
      if (dp !== ~~dp || dp < 0 || dp > MAX_DP) {
        throw Error(INVALID_DP);
      }
      x = round(new x.constructor(x), ++dp, rm);
      for (; x.c.length < dp;) x.c.push(0);
    }

    return stringify(x, true, !!n);
  };


  /*
   * Return a string representing the value of this Big in normal notation rounded to dp fixed
   * decimal places using rounding mode rm, or Big.RM if rm is not specified.
   *
   * dp? {number} Decimal places: integer, 0 to MAX_DP inclusive.
   * rm? {number} Rounding mode: 0 (down), 1 (half-up), 2 (half-even) or 3 (up).
   *
   * (-0).toFixed(0) is '0', but (-0.1).toFixed(0) is '-0'.
   * (-0).toFixed(1) is '0.0', but (-0.01).toFixed(1) is '-0.0'.
   */
  P.toFixed = function (dp, rm) {
    var x = this,
      n = x.c[0];

    if (dp !== UNDEFINED) {
      if (dp !== ~~dp || dp < 0 || dp > MAX_DP) {
        throw Error(INVALID_DP);
      }
      x = round(new x.constructor(x), dp + x.e + 1, rm);

      // x.e may have changed if the value is rounded up.
      for (dp = dp + x.e + 1; x.c.length < dp;) x.c.push(0);
    }

    return stringify(x, false, !!n);
  };


  /*
   * Return a string representing the value of this Big.
   * Return exponential notation if this Big has a positive exponent equal to or greater than
   * Big.PE, or a negative exponent equal to or less than Big.NE.
   * Omit the sign for negative zero.
   */
  P.toJSON = P.toString = function () {
    var x = this,
      Big = x.constructor;
    return stringify(x, x.e <= Big.NE || x.e >= Big.PE, !!x.c[0]);
  };


  /*
   * Return the value of this Big as a primitve number.
   */
  P.toNumber = function () {
    var n = +stringify(this, true, true);
    if (this.constructor.strict === true && !this.eq(n.toString())) {
      throw Error(NAME + 'Imprecise conversion');
    }
    return n;
  };


  /*
   * Return a string representing the value of this Big rounded to sd significant digits using
   * rounding mode rm, or Big.RM if rm is not specified.
   * Use exponential notation if sd is less than the number of digits necessary to represent
   * the integer part of the value in normal notation.
   *
   * sd {number} Significant digits: integer, 1 to MAX_DP inclusive.
   * rm? {number} Rounding mode: 0 (down), 1 (half-up), 2 (half-even) or 3 (up).
   */
  P.toPrecision = function (sd, rm) {
    var x = this,
      Big = x.constructor,
      n = x.c[0];

    if (sd !== UNDEFINED) {
      if (sd !== ~~sd || sd < 1 || sd > MAX_DP) {
        throw Error(INVALID + 'precision');
      }
      x = round(new Big(x), sd, rm);
      for (; x.c.length < sd;) x.c.push(0);
    }

    return stringify(x, sd <= x.e || x.e <= Big.NE || x.e >= Big.PE, !!n);
  };


  /*
   * Return a string representing the value of this Big.
   * Return exponential notation if this Big has a positive exponent equal to or greater than
   * Big.PE, or a negative exponent equal to or less than Big.NE.
   * Include the sign for negative zero.
   */
  P.valueOf = function () {
    var x = this,
      Big = x.constructor;
    if (Big.strict === true) {
      throw Error(NAME + 'valueOf disallowed');
    }
    return stringify(x, x.e <= Big.NE || x.e >= Big.PE, true);
  };


  // Export


  Big = _Big_();

  Big['default'] = Big.Big = Big;
  return Big;
})();
// Brand every Big instance so the Java lift can recognise a Pure Decimal (the
// brand is inherited by all instances, including arithmetic results).
Big.prototype.__isDec = true;
Big.DP = 40; // division precision (decimal places) before an explicit round
Big.RM = 2;  // ROUND_HALF_EVEN — Pure's rounding mode (round(16.5)=16, round(-17.5)=-18)
// A Pure Decimal is a big.js Big. __dec coerces any numeric operand to a Big
// (bigint -> exact via its string; number/string -> Big). __isDec tests it.
function __isDec(x: any): boolean { return x instanceof Big; }
function __dec(x: any): any {
  if (x instanceof Big) return x;
  if (typeof x === "bigint") return new Big(x.toString());
  return new Big(x);
}
// parseDecimal is lenient where big.js is strict: it accepts a trailing Decimal
// suffix ('3.14d'), a leading '+', and leading zeros (big.js rejects the first two).
function __parseDec(x: any): any {
  return new Big(String(x).trim().replace(/[dD]$/, "").replace(/^\+/, ""));
}

function __assert(c: any, m: any): boolean { if (!c) { throw new Error(String(m)); } return true; }

// Pure-style structural equality on the translated value graph. Both `==`
// (assertEquals) and array comparisons use this. Primitives fall back to ===;
// arrays and plain objects walk structurally. Skips translator-internal
// metadata keys (`_kind`, `_type`) — these are tagged by the newCoder for
// classifier round-trip, not user data.
// Pure `compare(a, b)`: like JS `<`/`>` for same-typed values, but values of
// DIFFERENT primitive types order by type (Pure never coerces — `compare(5,'5')`
// is non-zero), and the ordering is total/consistent (`compare(a,b) = -compare(b,a)`).
function __compare(a: any, b: any): number {
  const rank = (x: any): number => {
    if (x === null || x === undefined) return 0;
    if (typeof x === "boolean") return 1;
    if (typeof x === "number" || typeof x === "bigint" || x instanceof Big) return 2;
    if (x instanceof Date) return 3;
    if (typeof x === "string") return 4;
    return 5;
  };
  const ra = rank(a), rb = rank(b);
  if (ra !== rb) return ra < rb ? -1 : 1;
  // Decimal (Big) — and any numeric mix involving one — compares via big.js cmp
  // (`a < b` on a Big would coerce to a lexicographic string compare).
  if (a instanceof Big || b instanceof Big) return __dec(a).cmp(__dec(b));
  if (a instanceof Date && b instanceof Date) {
    const ta = a.getTime(), tb = b.getTime();
    if (ta !== tb) return ta < tb ? -1 : 1;
    // Same instant at ms resolution — break ties on the canonical literal,
    // which preserves sub-millisecond digits JS Date can't represent
    // (`...19.14231` vs `...19.14231555`).
    const fa = (a as any).__fmt, fb = (b as any).__fmt;
    if (fa !== undefined && fb !== undefined && fa !== fb) return fa < fb ? -1 : 1;
    return 0;
  }
  return a < b ? -1 : a > b ? 1 : 0;
}

// ---- Numeric helpers: Pure Integer = JS bigint, Float/Decimal = JS number ----
// Pure Integer is arbitrary-precision, so it maps to bigint; Float/Decimal stay
// JS number. Mixed arithmetic: both-bigint stays exact bigint; if either side is
// a JS number (a Float), coerce both to number — the Pure result type is Float.
// `bigint <op> number` would otherwise throw a TypeError at runtime.
function __num(x: any): any { return x instanceof Big ? Number(x.toString()) : (typeof x === "bigint" ? Number(x) : x); }
// JSON.stringify with a bigint-safe replacer (raw JSON.stringify throws on a BigInt).
function __json(v: any): string { return JSON.stringify(v, (_k: string, val: any) => typeof val === "bigint" ? val.toString() : (val instanceof Big ? val.toString() : val)); }
function __bi(x: any): any { return typeof x === "bigint" ? x : BigInt(Math.trunc(Number(x))); }
// Arithmetic: a Decimal (Big) operand wins -> exact big.js math (Pure plus/minus/
// times with any Decimal operand returns Decimal). Else both-bigint stays exact
// Integer; else Float (Number). `__add` also does Pure string `+`.
function __add(a: any, b: any): any {
  if (a instanceof Big || b instanceof Big) return __dec(a).plus(__dec(b));
  if (typeof a === "bigint" && typeof b === "bigint") return a + b;
  if (typeof a === "string" || typeof b === "string") return a + b; // Pure string `+`
  return __num(a) + __num(b);
}
function __sub(a: any, b: any): any { return (a instanceof Big || b instanceof Big) ? __dec(a).minus(__dec(b)) : ((typeof a === "bigint" && typeof b === "bigint") ? a - b : __num(a) - __num(b)); }
function __mul(a: any, b: any): any { return (a instanceof Big || b instanceof Big) ? __dec(a).times(__dec(b)) : ((typeof a === "bigint" && typeof b === "bigint") ? a * b : __num(a) * __num(b)); }
function __rem(a: any, b: any): any { return (a instanceof Big || b instanceof Big) ? __dec(a).mod(__dec(b)) : ((typeof a === "bigint" && typeof b === "bigint") ? a % b : __num(a) % __num(b)); }
// Pure `mod` is floored modulo (result takes the sign of the divisor):
// `mod(-12,5)=3`, not JS `%`'s remainder (-2, sign of dividend — that's `rem`).
function __mod(a: any, b: any): any {
  if (a instanceof Big || b instanceof Big) { const A = __dec(a), B = __dec(b); return A.mod(B).plus(B).mod(B); }
  if (typeof a === "bigint" && typeof b === "bigint") return ((a % b) + b) % b;
  const na = __num(a), nb = __num(b); return ((na % nb) + nb) % nb;
}
function __div(a: any, b: any): any { return __num(a) / __num(b); } // 2-arg Pure divide -> Float
function __divScale(a: any, b: any, scale: any): any { return __dec(a).div(__dec(b)).round(Number(scale)); } // divide(a,b,scale) -> Decimal
function __abs(x: any): any { return x instanceof Big ? x.abs() : (typeof x === "bigint" ? (x < 0n ? -x : x) : Math.abs(x)); }
function __sign(x: any): any { return x > 0 ? 1n : (x < 0 ? -1n : 0n); } // Pure sign -> Integer
function __pow(a: any, b: any): any { return Math.pow(__num(a), __num(b)); } // Pure pow -> Float
function __sqrtN(x: any): any { return Math.sqrt(__num(x)); }
// round(x) -> Integer (bigint), HALF_EVEN (Pure's mode) — via big.js, so 16.5->16,
// -17.5->-18 (Math.round is half-up and wrong here). round(x, scale) -> rounded to
// scale, preserving domain: Decimal -> Decimal, Float -> Float (number).
function __round1(x: any): any { return typeof x === "bigint" ? x : BigInt(__dec(x).round(0).toString()); }
function __round2(x: any, scale: any): any { return x instanceof Big ? x.round(Number(scale)) : Number(__dec(x).round(Number(scale)).toString()); }
function __floorI(x: any): any { return typeof x === "bigint" ? x : BigInt(Math.floor(__num(x))); }
function __ceilI(x: any): any { return typeof x === "bigint" ? x : BigInt(Math.ceil(__num(x))); }
function __min2(a: any, b: any): any { return a <= b ? a : b; }
function __max2(a: any, b: any): any { return a >= b ? a : b; }
function __minColl(c: any): any { return __asArr(c).reduce((x: any, y: any) => (x <= y ? x : y)); }
function __maxColl(c: any): any { return __asArr(c).reduce((x: any, y: any) => (x >= y ? x : y)); }
function __sumColl(c: any): any { const a = __asArr(c); return a.reduce((x: any, y: any) => __add(x, y), a.some((v: any) => typeof v === "bigint") ? 0n : 0); }
function __avgColl(c: any): any { const a = __asArr(c); return a.reduce((x: any, y: any) => Number(x) + Number(y), 0) / a.length; }

function __eq(a: any, b: any): boolean {
  if (a === b) return true;
  // Empty-collection equivalence: an unset Pure `[*]`/`[0..1]` is `undefined`,
  // a literal empty list is `[]` — both are the empty collection and compare
  // equal to each other (but not to a non-empty value).
  const aEmpty = a === undefined || a === null || (Array.isArray(a) && a.length === 0);
  const bEmpty = b === undefined || b === null || (Array.isArray(b) && b.length === 0);
  if (aEmpty || bEmpty) return aEmpty && bEmpty;
  // Date equality is GRANULARITY-aware: `%2014` (year) ≠ `%2014-01-01` (day)
  // even though they denote the same instant. `__pdate` stashes `__fmt` (the
  // canonical Pure date string at the literal's granularity); compare that.
  if (a instanceof Date && b instanceof Date) {
    if ((a as any).__fmt !== undefined && (b as any).__fmt !== undefined) return (a as any).__fmt === (b as any).__fmt;
    return a.getTime() === b.getTime();
  }
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
  // Numeric equality across Decimal (Big), Integer (bigint) and Float (number).
  // `1n === 1` is false in JS; a Big never `===` anything. Compare by value.
  const aNum = a instanceof Big || typeof a === "number" || typeof a === "bigint";
  const bNum = b instanceof Big || typeof b === "number" || typeof b === "bigint";
  if (aNum && bNum) {
    if (a instanceof Big || b instanceof Big) return __dec(a).eq(__dec(b)); // Decimal value-equality
    if (typeof a === "bigint" && typeof b === "bigint") return a === b;     // exact (no Number() precision loss)
    return Number(a) === Number(b);                                         // mixed Integer/Float
  }
  if (aNum || bNum) return false; // numeric vs non-numeric
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
  // Element / pointer references — a `__pureResolve(path)` proxy or any value
  // carrying a Pure `__purePath` (e.g. a ClassPointer stub) — are equal iff
  // they denote the SAME element: compare by path. A pointer's `.properties`
  // is unresolved, so never fall through to structural / equality-key compare.
  const ppA = (a as any).__purePath, ppB = (b as any).__purePath;
  if (typeof ppA === "string" || typeof ppB === "string") return ppA === ppB;
  // Equality-key semantics (Pure's <<equality.Key>>): when a side carries a
  // `classifierGenericType.__equalityKeys` array, compare by those keys only — an
  // EMPTY list means the class has no keys, so equality is by identity (two
  // distinct instances are NOT equal). Both `^X(...)` literals and captured PDOs
  // stamp it (same class → same keys), so taking whichever side has it is safe;
  // a path mismatch short-circuits to false. Metamodel classes are never
  // stamped, so they fall through to the structural path (unchanged behaviour).
  const cgtA = (a as any).classifierGenericType, cgtB = (b as any).classifierGenericType;
  const eqKeysA = cgtA && cgtA.__equalityKeys, eqKeysB = cgtB && cgtB.__equalityKeys;
  const eqKeys = Array.isArray(eqKeysA) ? eqKeysA : (Array.isArray(eqKeysB) ? eqKeysB : undefined);
  if (eqKeys !== undefined) {
    const pa = cgtA && cgtA.type && ((cgtA.type as any).__purePath ?? (cgtA.type as any).path);
    const pb = cgtB && cgtB.type && ((cgtB.type as any).__purePath ?? (cgtB.type as any).path);
    if (pa != null && pb != null && pa !== pb) return false;
    if (eqKeys.length === 0) return a === b;
    for (const k of eqKeys) { if (!__eq((a as any)[k], (b as any)[k])) return false; }
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
        // Integer is a bigint; format directly to preserve precision (Number(v)
        // would round large integers). `n` carries the sign, `s` the digits.
        const neg = typeof v === "bigint" ? v < 0n : Math.trunc(Number(v)) < 0;
        let s = typeof v === "bigint"
          ? (v < 0n ? -v : v).toString()
          : Math.abs(Math.trunc(Number(v))).toString();
        if (width && width.startsWith("0")) {
          // Pure's `%0Nd` pads the DIGITS to N; the sign is extra (`%05d` of -3
          // is "-00003", unlike Java's "-0003").
          const w = parseInt(width, 10);
          while (s.length < w) s = "0" + s;
        }
        return (neg ? "-" : "") + s;
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
  if (v instanceof Big) return v.toString() + "D"; // Pure Decimal representation suffix
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

function __chunk(coll: any[], n: any): any[][] {
  const N = Number(n);
  const out: any[][] = [];
  for (let i = 0; i < coll.length; i += N) out.push(coll.slice(i, i + N));
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
function __slice(coll: any, low: any, high: any): any[] {
  const a = __asArr(coll);
  const lo = Math.max(0, Math.min(Number(low), a.length));
  const hi = Math.max(lo, Math.min(Number(high), a.length));
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

// Runtime match dispatch over a list of lambdas whose arm types aren't known
// statically (e.g. `match($capturedLambdas)`). For each lambda, reflect its
// first parameter's type + multiplicity via its `__purePath` (every translated
// arm is `__lambda(arrow, path)`), then reuse `__matchType` to pick the arm.
function __lambdaParamType(lam: any): any {
  const path = lam && (lam as any).__purePath;
  if (!path) return null;
  const pdo = __pureResolve(path);
  const params = __asArr(pdo.parameters);
  if (params.length === 0) return null;
  const p = params[0];
  const t = p.genericType && p.genericType.type;
  const typePath = (t && ((t as any).__purePath ?? (t as any).path)) || "meta::pure::metamodel::type::Any";
  let lower = 0;
  let upper: number | undefined;
  const m = p.multiplicity;
  if (m) {
    const lb = m.lowerBound;
    if (lb != null && lb.value != null) lower = lb.value;
    const ub = m.upperBound;
    if (ub != null && ub.value != null) upper = ub.value;
  }
  return { typePath, lower, upper };
}
function __matchDynamic(value: any, lambdas: any, withArg?: any): any {
  for (const lam of __asArr(lambdas)) {
    const pt = __lambdaParamType(lam);
    if (pt && __matchType(value, pt.typePath, pt.lower, pt.upper)) {
      return withArg !== undefined ? (lam as any)(value, withArg) : (lam as any)(value);
    }
  }
  throw new Error("match: no arm matched value " + __toRepresentation(value));
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
        return typeof e === "number" || typeof e === "bigint"; // Integer is a bigint
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
function __at(coll: any, i: any): any {
  const arr = __asArr(coll);
  const idx = Number(i);
  if (idx < 0 || idx >= arr.length) {
    throw new Error("The system is trying to get an element at offset " + idx + " where the collection is of size " + arr.length);
  }
  return arr[idx];
}
function __size(coll: any): any { return BigInt(__asArr(coll).length); }
// Pure take/drop clamp the count (no JS negative-index wraparound): take(n<=0)
// is empty, drop(n<=0) is the whole collection.
function __take(coll: any, n: any): any[] { const N = Number(n); return N <= 0 ? [] : __asArr(coll).slice(0, N); }
function __drop(coll: any, n: any): any[] { const N = Number(n); return __asArr(coll).slice(N < 0 ? 0 : N); }
// Pure natural ordering: numbers compare numerically (JS `.sort()` defaults to
// lexicographic, so `[1,171,2]` would mis-sort); everything else by toString.
function __defaultCompare(a: any, b: any): number {
  if ((typeof a === "number" || typeof a === "bigint") && (typeof b === "number" || typeof b === "bigint")) return a < b ? -1 : (a > b ? 1 : 0);
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
function __adjust(d: Date, n: any, units: any): Date {
  const dt = new Date(d.getTime());
  const N = Number(n); // n is a Pure Integer (bigint); coerce for Date math
  const u = String(units).split(".").pop();
  switch (u) {
    case "DAYS":    dt.setUTCDate(dt.getUTCDate() + N); break;
    case "HOURS":   dt.setUTCHours(dt.getUTCHours() + N); break;
    case "MINUTES": dt.setUTCMinutes(dt.getUTCMinutes() + N); break;
    case "SECONDS": dt.setUTCSeconds(dt.getUTCSeconds() + N); break;
    case "WEEKS":   dt.setUTCDate(dt.getUTCDate() + 7 * N); break;
    case "MONTHS":  dt.setUTCMonth(dt.getUTCMonth() + N); break;
    case "YEARS":   dt.setUTCFullYear(dt.getUTCFullYear() + N); break;
  }
  return dt;
}
function __dateDiff(a: Date, b: Date, units: any): any {
  const ms = b.getTime() - a.getTime();
  const u = String(units).split(".").pop();
  let n = 0;
  switch (u) {
    case "DAYS":    n = Math.floor(ms / 86400000); break;
    case "HOURS":   n = Math.floor(ms / 3600000); break;
    case "MINUTES": n = Math.floor(ms / 60000); break;
    case "SECONDS": n = Math.floor(ms / 1000); break;
    case "WEEKS":   n = Math.floor(ms / (7 * 86400000)); break;
    case "MONTHS":  n = (b.getUTCFullYear() - a.getUTCFullYear()) * 12 + (b.getUTCMonth() - a.getUTCMonth()); break;
    case "YEARS":   n = b.getUTCFullYear() - a.getUTCFullYear(); break;
  }
  return BigInt(n); // Pure dateDiff -> Integer
}
function __datePart(d: Date): Date {
  // Return a Date at midnight UTC for the calendar day part. Tests assert by
  // toString, so the resulting Date renders as 'YYYY-MM-DD' through __toString.
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
}

// Date field getters — UTC throughout. monthNumber is 1-indexed (matches
// Pure semantics) while JS getUTCMonth is 0-indexed.
// Pure date getters return Integer (bigint).
function __year(d: Date): any        { return BigInt(d.getUTCFullYear()); }
function __monthNumber(d: Date): any { return BigInt(d.getUTCMonth() + 1); }
function __dayOfMonth(d: Date): any  { return BigInt(d.getUTCDate()); }
function __hour(d: Date): any        { return BigInt(d.getUTCHours()); }
function __minute(d: Date): any      { return BigInt(d.getUTCMinutes()); }
function __second(d: Date): any      { return BigInt(d.getUTCSeconds()); }
function __millis(d: Date): any      { return BigInt(d.getUTCMilliseconds()); }

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
  if (typeof v === "bigint") {
    // Pure Integer (bigint) is-a Integer / Number / Any. Not Float, not Decimal.
    return tPath === "meta::pure::metamodel::type::primitives::Integer"
        || tPath === "meta::pure::metamodel::type::primitives::Number";
  }
  if (v instanceof Big) {
    // Pure Decimal is-a Decimal / Number / Any.
    return tPath === "meta::pure::metamodel::type::primitives::Decimal"
        || tPath === "meta::pure::metamodel::type::primitives::Number";
  }
  if (typeof v === "number") {
    // A JS number is always a Pure Float: Integer is a bigint and Decimal is a
    // Big, so a whole-valued number like `1.0` is still a Float (not Integer).
    return tPath === "meta::pure::metamodel::type::primitives::Float"
        || tPath === "meta::pure::metamodel::type::primitives::Number";
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
// Inverse of __pathToElement: unwrap a PE reference back to its canonical
// string path. Strings pass through (so AtomicValue class-name literals
// emitted by genericType()/type() chains keep working); a __pureResolve
// proxy / any object carrying `.path` yields that slot; everything else
// pass-through unchanged so the caller's downstream code sees the raw value.
function __elementToPath(v: any): any {
  if (typeof v === "string") return v;
  if (v && typeof v === "object" && typeof v.path === "string") return v.path;
  return v;
}

function __pathToElement(p: string, sep?: string): any {
  const norm = sep && sep !== "::" ? p.split(sep).join("::") : p;
  // Return a __pureResolve proxy so downstream slot reads
  // (`classifierGenericType`, `properties`, `generalizations`, …) route
  // through the host resolver instead of hitting `undefined` on a plain
  // `{name, path}` literal. Both `name` and `path` are still readable —
  // `path` directly on the proxy target, `name` via the get-trap. Same
  // pattern as `pathToElement` produces in Pure: a live PE reference.
  return __pureResolve(norm);
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

// Pure `cast(value, @T)` — runtime type-check against a CONCRETE target type.
// Succeeds when the value's dynamic type is `T` or a subtype (upcast/identity)
// OR `T` is a subtype of the value's type (a runtime-checked downcast, e.g.
// Integer -> P8 where P8 extends Integer). Throws "Cast exception: <Actual>
// cannot be cast to <Target>" otherwise. Type-parameter / metamodel targets
// never reach here (the coder passes those through unchanged).
const __P = "meta::pure::metamodel::type::primitives::";
// The Pure type PATH of any runtime value — the TS-primitive -> Pure-Type map
// (mirrors Truffle): a JS primitive HAS a Pure type even without a
// classifierGenericType. Class instances carry their type in `classifierGenericType`.
function __pureTypeOf(v: any): string | undefined {
  if (v === undefined || v === null) return undefined;
  if (typeof v === "bigint") return __P + "Integer";
  if (v instanceof Big) return __P + "Decimal";
  if (typeof v === "number") return __P + "Float";
  if (typeof v === "string") return __P + "String";
  if (typeof v === "boolean") return __P + "Boolean";
  if (v instanceof Date) return __P + ((v as any).__fmt && String((v as any).__fmt).indexOf("T") >= 0 ? "DateTime" : "StrictDate");
  if (typeof v === "object") {
    const cgt = (v as any).classifierGenericType;
    if (cgt && cgt.type) return (cgt.type.__purePath ?? cgt.type.path);
    if (typeof (v as any).__purePath === "string") return (v as any).__purePath;
  }
  return undefined;
}
// Immediate supertype paths of a type (walks the metamodel via the resolver proxy).
function __directSupertypes(path: string): string[] {
  const out: string[] = [];
  const cls = __pureResolve(path);
  for (const g of __asArr(cls && (cls as any).generalizations)) {
    const gt = g && g.general && g.general.type;
    const p = gt && ((gt as any).__purePath ?? (gt as any).path);
    if (p) out.push(p);
  }
  return out;
}
// Nearest common supertype of two type paths (the meet in the hierarchy).
function __commonAncestor(a: string, b: string): string {
  if (a === b) return a;
  if (__metadataSubtypeOf(a, b)) return b;
  if (__metadataSubtypeOf(b, a)) return a;
  let frontier = [a]; const seen = new Set<string>();
  while (frontier.length) {
    const next: string[] = [];
    for (const t of frontier) {
      if (seen.has(t)) continue; seen.add(t);
      if (__metadataSubtypeOf(b, t)) return t;       // b is-a t  =>  t is a common ancestor
      for (const s of __directSupertypes(t)) next.push(s);
    }
    frontier = next;
  }
  return "meta::pure::metamodel::type::Any";
}
// The Pure type a `T[*]` witness binds T to: the common supertype of its elements.
function __commonType(coll: any): string {
  const arr = __asArr(coll);
  if (arr.length === 0) return "meta::pure::metamodel::type::Any";
  let t = __pureTypeOf(arr[0]) ?? "meta::pure::metamodel::type::Any";
  for (let i = 1; i < arr.length; i++) t = __commonAncestor(t, __pureTypeOf(arr[i]) ?? "meta::pure::metamodel::type::Any");
  return t;
}
// Pure's constraint-violation throw. `ok` is the boolean from the constraint's
// predicate (already evaluated); `name` is the constraint's id (or the
// stringified index for anonymous `[expr]` constraints); `owner` is the short
// name of the class/primitive declaring the constraint. `msgFn` (optional) is
// lazy so the message expression — itself a Pure lambda body — is only
// evaluated on violation. Error format mirrors the Java runtime
// (CastNode.validateConstraints): `Constraint :[<id>] violated in the Class
// <Owner>[, Message: <msg>]`.
function __checkConstraint(ok: boolean, name: string, owner: string, msgFn?: () => any): void {
  if (ok) return;
  const m = msgFn ? msgFn() : undefined;
  throw new Error("Constraint :[" + name + "] violated in the Class " + owner
                  + (m !== undefined ? ", Message: " + m : ""));
}

function __castLeaf(p: string | undefined): string { return p ? (String(p).split("::").pop() as string) : "?"; }
function __cast(value: any, targetPath: string): any {
  if (value === undefined || value === null) return value;
  if (Array.isArray(value)) { for (const e of value) __cast(e, targetPath); return value; }
  if (__instanceOf(value, { path: targetPath })) return value;           // upcast / identity
  const vtPath = __pureTypeOf(value);
  if (vtPath && __metadataSubtypeOf(targetPath, vtPath)) return value;   // runtime-checked downcast
  throw new Error("Cast exception: " + __castLeaf(vtPath) + " cannot be cast to " + __castLeaf(targetPath));
}

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
// Spread `src` into a plain object, eagerly materialising the
// __pureResolve-proxy slots that an object spread would otherwise drop.
// A PDO proxy carries only `{__purePath, path}` as OWN enumerable keys;
// `classifierGenericType` and friends are lazy via the get-trap, so `{...proxy}`
// loses them — downstream `$copy->genericType()` then hits
// `Cannot read property 'type' of undefined`. Pull `classifierGenericType` into
// the snapshot so the copy keeps a self-describing type tag. Non-proxy bases
// pass through with a plain spread.
function __spreadEager(src: any): any {
  if (src === null || typeof src !== "object" || Array.isArray(src)) return src;
  if ("__purePath" in src) {
    return { ...src, classifierGenericType: (src as any).classifierGenericType };
  }
  return { ...src };
}

// Bidirectional association binding. When `^Person(firm=F)` runs, Pure wires
// the paired `employees` side on the firm — so `F.employees` reaches back to
// the new Person. The translator detects association props at codegen time
// and emits one `__bindAssoc` call per association key set; here we mutate
// the partner side (the OTHER instance's reverse slot) to include `this`.
//
// `toMany`: true if the partner side is `[*]`/`[1..*]`/`[0..*]` (Pure's
// `isToManyMult`) — accumulate into an array, deduping by identity. false
// → scalar replace, matching the metamodel's `[0..1]`/`[1]` side. Skips
// undefined/null/primitive partners silently — Pure already requires
// association partners to be instantiated within the new/copy expression;
// an uninstantiated key produces an empty value, so there's nothing to bind.
function __bindAssoc(thisObj: any, otherValue: any, reverseName: string, toMany: boolean): void {
  if (otherValue === undefined || otherValue === null) return;
  if (Array.isArray(otherValue)) {
    for (const o of otherValue) __bindAssoc(thisObj, o, reverseName, toMany);
    return;
  }
  if (typeof otherValue !== "object") return;
  if (toMany) {
    const cur = (otherValue as any)[reverseName];
    const arr = Array.isArray(cur) ? cur : (cur === undefined || cur === null ? [] : [cur]);
    if (arr.indexOf(thisObj) < 0) arr.push(thisObj);
    (otherValue as any)[reverseName] = arr;
  } else {
    (otherValue as any)[reverseName] = thisObj;
  }
}

// `^$x(k = v, ...)` — copy of `src` with the supplied key overrides applied.
// Goes through __spreadEager so a proxy base keeps its classifier tag, then
// merges the overrides. Used by copyCoder for the no-parent-ref form; the
// `~`-referencing form goes through __copyCtx below.
function __copy(base: any, overrides: any): any {
  const self = __spreadEager(base);
  if (self === null || typeof self !== "object") return self;
  return Object.assign(self, overrides);
}

// `^$x(k = ~...)` — copy `src`, then fill with the copy on the stack so its
// key expressions can reference `~` (the copy itself / enclosing instances).
function __copyCtx(src: any, fill: (self: any) => void): any {
  const self = __spreadEager(src);
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
  if (typeof v === "bigint") {
    return { type: __pureResolve("meta::pure::metamodel::type::primitives::Integer") };
  }
  if (v instanceof Big) {
    return { type: __pureResolve("meta::pure::metamodel::type::primitives::Decimal") };
  }
  if (typeof v === "number") {
    // A JS number is always a Pure Float (Integer is bigint, Decimal is Big).
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
  const m = /^(\d{1,6})(?:-(\d{1,2}))?(?:-(\d{1,2}))?(?:T(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?(?:\.(\d+))?)?([zZ]|[+-]\d{2}:?\d{2})?$/.exec(lit);
  let parse = lit;
  if (m !== null) {
    // Years > 9999 need ISO-8601 extended format (`+NNNNNN`); JS Date rejects a
    // bare 5+/6-digit year.
    const Y = parseInt(m[1], 10) > 9999 ? "+" + m[1].padStart(6, "0") : m[1].padStart(4, "0");
    const Mo = (m[2] || "01").padStart(2, "0"), Da = (m[3] || "01").padStart(2, "0");
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
  if (v instanceof Big) return v.toString(); // Decimal — before the object branch
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
    return JSON.stringify(v, (_k, val) => typeof val === "bigint" ? val.toString() : val);
  }
  return String(v);
}
