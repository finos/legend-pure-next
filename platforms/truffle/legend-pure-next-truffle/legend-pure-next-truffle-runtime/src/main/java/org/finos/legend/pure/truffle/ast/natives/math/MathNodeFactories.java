// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.truffle.ast.natives.math;

import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;

/**
 * Registers specialized Truffle nodes for math natives.
 *
 * <p>Covers all arithmetic, comparison, rounding, trigonometric,
 * exponential and logarithmic signatures for Integer, Float, Decimal
 * and generic Number types.</p>
 */
public final class MathNodeFactories
{
    private MathNodeFactories()
    {
    }

    public static void registerAll(NativeNodeRegistry registry)
    {
        // ── Integer arithmetic ──────────────────────────────────────────
        registry.register("plus_Integer_1__Integer_1__Integer_1_",
                (args, gt, mul, fe) -> new PlusIntegerNode(args[0], args[1]));
        registry.register("minus_Integer_1__Integer_1__Integer_1_",
                (args, gt, mul, fe) -> new MinusIntegerNode(args[0], args[1]));
        registry.register("times_Integer_1__Integer_1__Integer_1_",
                (args, gt, mul, fe) -> new TimesIntegerNode(args[0], args[1]));

        // ── Float arithmetic ───────────────────────────────────────────
        registry.register("plus_Float_1__Float_1__Float_1_",
                (args, gt, mul, fe) -> new PlusFloatNode(args[0], args[1]));
        registry.register("minus_Float_1__Float_1__Float_1_",
                (args, gt, mul, fe) -> new BinaryNumberNode(args[0], args[1],
                        "minus_Float_1__Float_1__Float_1_", (a, b) -> a - b));
        registry.register("times_Float_1__Float_1__Float_1_",
                (args, gt, mul, fe) -> new TimesFloatNode(args[0], args[1]));

        // ── Generic Number arithmetic ──────────────────────────────────
        registry.register("plus_Number_1__Number_1__Number_1_",
                (args, gt, mul, fe) -> new BinaryNumberNode(args[0], args[1],
                        "plus_Number_1__Number_1__Number_1_", Double::sum));
        registry.register("minus_Number_1__Number_1__Number_1_",
                (args, gt, mul, fe) -> new BinaryNumberNode(args[0], args[1],
                        "minus_Number_1__Number_1__Number_1_", (a, b) -> a - b));
        registry.register("times_Number_1__Number_1__Number_1_",
                (args, gt, mul, fe) -> new BinaryNumberNode(args[0], args[1],
                        "times_Number_1__Number_1__Number_1_", (a, b) -> a * b));

        // ── Decimal arithmetic ────────────────────────────────────────
        registry.register("plus_Decimal_1__Decimal_1__Decimal_1_",
                (args, gt, mul, fe) -> new DecimalBinaryNode(args[0], args[1],
                        "plus_Decimal_1__Decimal_1__Decimal_1_", java.math.BigDecimal::add));
        registry.register("minus_Decimal_1__Decimal_1__Decimal_1_",
                (args, gt, mul, fe) -> new DecimalBinaryNode(args[0], args[1],
                        "minus_Decimal_1__Decimal_1__Decimal_1_", java.math.BigDecimal::subtract));
        registry.register("times_Decimal_1__Decimal_1__Decimal_1_",
                (args, gt, mul, fe) -> new DecimalBinaryNode(args[0], args[1],
                        "times_Decimal_1__Decimal_1__Decimal_1_", java.math.BigDecimal::multiply));
        registry.register("times_Decimal_1__Integer_1__Decimal_1_",
                (args, gt, mul, fe) -> new DecimalBinaryNode(args[0], args[1],
                        "times_Decimal_1__Integer_1__Decimal_1_", java.math.BigDecimal::multiply));
        registry.register("times_Integer_1__Decimal_1__Decimal_1_",
                (args, gt, mul, fe) -> new DecimalBinaryNode(args[0], args[1],
                        "times_Integer_1__Decimal_1__Decimal_1_", java.math.BigDecimal::multiply));
        registry.register("divide_Decimal_1__Decimal_1__Integer_1__Decimal_1_",
                (args, gt, mul, fe) -> new DivideDecimalNode(args[0], args[1], args[2]));
        registry.register("minus_Decimal_1__Decimal_1_",
                (args, gt, mul, fe) -> new MinusNegateDecimalNode(args[0]));
        registry.register("abs_Decimal_1__Decimal_1_",
                (args, gt, mul, fe) -> new AbsDecimalNode(args[0]));
        registry.register("round_Decimal_1__Integer_1__Decimal_1_",
                (args, gt, mul, fe) -> new RoundDecimalNode(args[0], args[1]));

        // ── Division ───────────────────────────────────────────────────
        registry.register("divide_Number_1__Number_1__Float_1_",
                (args, gt, mul, fe) -> new DivideNumberNode(args[0], args[1]));

        // ── Single-arg negate ──────────────────────────────────────────
        registry.register("minus_Number_1__Number_1_",
                (args, gt, mul, fe) -> new MinusNegateNumberNode(args[0]));
        registry.register("minus_Integer_1__Integer_1_",
                (args, gt, mul, fe) -> new MinusNegateNumberNode(args[0]));
        registry.register("minus_Float_1__Float_1_",
                (args, gt, mul, fe) -> new MinusNegateNumberNode(args[0]));

        // ── Comparisons ────────────────────────────────────────────────
        registry.register("lessThan_Number_1__Number_1__Boolean_1_",
                (args, gt, mul, fe) -> new LessThanNumberNode(args[0], args[1]));
        registry.register("greaterThan_Number_1__Number_1__Boolean_1_",
                (args, gt, mul, fe) -> new GreaterThanNumberNode(args[0], args[1]));
        registry.register("lessThanEqual_Number_1__Number_1__Boolean_1_",
                (args, gt, mul, fe) -> new LessThanEqualNumberNode(args[0], args[1]));
        registry.register("greaterThanEqual_Number_1__Number_1__Boolean_1_",
                (args, gt, mul, fe) -> new GreaterThanEqualNumberNode(args[0], args[1]));

        // ── Absolute value ─────────────────────────────────────────────
        registry.register("abs_Integer_1__Integer_1_",
                (args, gt, mul, fe) -> new AbsIntegerNode(args[0]));
        registry.register("abs_Float_1__Float_1_",
                (args, gt, mul, fe) -> new AbsFloatNode(args[0]));
        // abs_Decimal registered above in Decimal section
        registry.register("abs_Number_1__Number_1_",
                (args, gt, mul, fe) -> new AbsNumberNode(args[0]));

        // ── Modulo / remainder ─────────────────────────────────────────
        registry.register("mod_Integer_1__Integer_1__Integer_1_",
                (args, gt, mul, fe) -> new ModIntegerNode(args[0], args[1]));
        registry.register("rem_Number_1__Number_1__Number_1_",
                (args, gt, mul, fe) -> new RemNumberNode(args[0], args[1]));

        // ── Sign ───────────────────────────────────────────────────────
        registry.register("sign_Number_1__Integer_1_",
                (args, gt, mul, fe) -> new SignNumberNode(args[0]));

        // ── Power ──────────────────────────────────────────────────────
        registry.register("pow_Number_1__Number_1__Number_1_",
                (args, gt, mul, fe) -> new PowNumberNode(args[0], args[1]));

        // ── Rounding ───────────────────────────────────────────────────
        registry.register("ceiling_Number_1__Integer_1_",
                (args, gt, mul, fe) -> new CeilingNumberNode(args[0]));
        registry.register("floor_Number_1__Integer_1_",
                (args, gt, mul, fe) -> new FloorNumberNode(args[0]));
        registry.register("round_Number_1__Integer_1_",
                (args, gt, mul, fe) -> new RoundNumberNode(args[0]));
        registry.register("round_Float_1__Integer_1__Float_1_",
                (args, gt, mul, fe) -> new RoundFloatNode(args[0], args[1]));
        // round_Decimal registered above in Decimal section

        // ── Trigonometric (unary Number[1] -> Float[1]) ────────────────
        registry.register("sin_Number_1__Float_1_",
                (args, gt, mul, fe) -> new UnaryDoubleOpNode(args[0],
                        "sin_Number_1__Float_1_", Math::sin));
        registry.register("cos_Number_1__Float_1_",
                (args, gt, mul, fe) -> new UnaryDoubleOpNode(args[0],
                        "cos_Number_1__Float_1_", Math::cos));
        registry.register("tan_Number_1__Float_1_",
                (args, gt, mul, fe) -> new UnaryDoubleOpNode(args[0],
                        "tan_Number_1__Float_1_", Math::tan));
        registry.register("cot_Number_1__Float_1_",
                (args, gt, mul, fe) -> new UnaryDoubleOpNode(args[0],
                        "cot_Number_1__Float_1_", v -> 1.0 / Math.tan(v)));
        registry.register("asin_Number_1__Float_1_",
                (args, gt, mul, fe) -> new UnaryDoubleOpNode(args[0],
                        "asin_Number_1__Float_1_", Math::asin));
        registry.register("acos_Number_1__Float_1_",
                (args, gt, mul, fe) -> new UnaryDoubleOpNode(args[0],
                        "acos_Number_1__Float_1_", Math::acos));
        registry.register("atan_Number_1__Float_1_",
                (args, gt, mul, fe) -> new UnaryDoubleOpNode(args[0],
                        "atan_Number_1__Float_1_", Math::atan));

        // ── atan2 (two-arg) ────────────────────────────────────────────
        registry.register("atan2_Number_1__Number_1__Float_1_",
                (args, gt, mul, fe) -> new Atan2NumberNode(args[0], args[1]));

        // ── Exponential / logarithmic (unary Number[1] -> Float[1]) ───
        registry.register("sqrt_Number_1__Float_1_",
                (args, gt, mul, fe) -> new UnaryDoubleOpNode(args[0],
                        "sqrt_Number_1__Float_1_", Math::sqrt));
        registry.register("cbrt_Number_1__Float_1_",
                (args, gt, mul, fe) -> new UnaryDoubleOpNode(args[0],
                        "cbrt_Number_1__Float_1_", Math::cbrt));
        registry.register("exp_Number_1__Float_1_",
                (args, gt, mul, fe) -> new UnaryDoubleOpNode(args[0],
                        "exp_Number_1__Float_1_", Math::exp));
        registry.register("log_Number_1__Float_1_",
                (args, gt, mul, fe) -> new UnaryDoubleOpNode(args[0],
                        "log_Number_1__Float_1_", Math::log));
        registry.register("log10_Number_1__Float_1_",
                (args, gt, mul, fe) -> new UnaryDoubleOpNode(args[0],
                        "log10_Number_1__Float_1_", Math::log10));
    }
}
