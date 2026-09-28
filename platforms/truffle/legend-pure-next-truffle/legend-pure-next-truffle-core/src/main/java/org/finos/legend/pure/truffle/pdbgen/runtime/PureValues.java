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


package org.finos.legend.pure.truffle.pdbgen.runtime;

/**
 * Value normalisation as the Pure language sees values, for the generated PDB
 * reader.
 *
 * <p>The translator generates a class of this name for each host. Truffle's is
 * written out rather than generated for ONE reason: the generated `captures`
 * wraps each captured value in `meta::pure::functions::collection::ListImpl`,
 * which would drag the collection metamodel into Truffle's generated set for no
 * benefit — Truffle's {@link org.finos.legend.pure.truffle.pdbgen.pdb.PureLambda}
 * discards captures, because every lambda in the reader is consumed by the
 * translated code itself. Every other method is a faithful copy; keep them in
 * step with `pureValuesHelperClass` in translation.pure.</p>
 */
public class PureValues
{
    public static Object norm(Object v)
    {
        if (v == null) { return java.util.Collections.emptyList(); }
        return v instanceof java.util.List && ((java.util.List<?>) v).size() == 1 ? ((java.util.List<?>) v).get(0) : v;
    }

    public static boolean equal(Object a, Object b)
    {
        Object x = norm(a), y = norm(b);
        if (x instanceof Double && y instanceof Double && ((Double) x) == 0.0 && ((Double) y) == 0.0) { return true; }
        if (same(x, y)) { return true; }
        if (x instanceof byte[] && y instanceof byte[]) { return java.util.Arrays.equals((byte[]) x, (byte[]) y); }
        return java.util.Objects.equals(x, y);
    }

    /** Captured values by name. Unwrapped — see the class note. */
    public static java.util.Map captures(java.util.List namesAndValues)
    {
        java.util.LinkedHashMap<Object, Object> captures = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.size(); i += 2)
        {
            captures.put(namesAndValues.get(i), list(namesAndValues.get(i + 1)));
        }
        return captures;
    }

    public static java.util.List list(Object v)
    {
        if (v == null) { return java.util.Collections.emptyList(); }
        return v instanceof java.util.List ? (java.util.List) v : java.util.Collections.singletonList(v);
    }

    public static Object one(Object v)
    {
        if (!(v instanceof java.util.List)) { return v; }
        java.util.List<?> l = (java.util.List<?>) v;
        return l.isEmpty() ? null : l.get(0);
    }

    public static boolean same(Object a, Object b)
    {
        Object x = norm(a), y = norm(b);
        Object ea = org.finos.legend.pure.truffle.pdbgen.pdb.PureLambda.elementOf(x);
        Object eb = org.finos.legend.pure.truffle.pdbgen.pdb.PureLambda.elementOf(y);
        return (ea == null ? x : ea) == (eb == null ? y : eb);
    }

    public static Object negate(Object v)
    {
        if (v instanceof Long) { return -((Long) v); }
        if (v instanceof Double) { return -((Double) v); }
        if (v instanceof java.math.BigDecimal) { return ((java.math.BigDecimal) v).negate(); }
        if (v instanceof Number) { return -((Number) v).doubleValue(); }
        throw new RuntimeException("Cannot negate " + (v == null ? "[]" : v.getClass().getName()));
    }
}
