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

package org.finos.legend.pure.truffle.ast.natives.string;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.math.IntegerHelper;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * {@code parseDecimal(String[1]) : Decimal[1]} -- parses a string to a double.
 * Also handles the three-arg variant with precision and scale.
 */
@NodeInfo(shortName = "parseDec")
public final class ParseDecimalNode extends PureNode
{
    private static final String SIG = "parseDecimal_String_1__Decimal_1_";

    @Child
    private PureNode strArg;

    @Child
    private PureNode precisionArg;

    @Child
    private PureNode scaleArg;

    public ParseDecimalNode(PureNode strArg, PureNode precisionArg, PureNode scaleArg)
    {
        this.strArg = strArg;
        this.precisionArg = precisionArg;
        this.scaleArg = scaleArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String s = StringHelper.asString(strArg.executeGeneric(frame), SIG);
        if (scaleArg != null)
        {
            long scale = IntegerHelper.asLong(scaleArg.executeGeneric(frame), SIG);
            return parseAndScale(s, (int) scale);
        }
        return parsePlain(s);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static double parseAndScale(String s, int scale)
    {
        // BigDecimal.setScale walks into BigInteger arithmetic where
        // squareKaratsuba recursion would blow Graal's inline budget
        // (~490 levels). String parsing also reaches into JDK reflection
        // (Class.getGenericInfo → SignatureParser.parseClassSignature) on
        // some hot paths. Boundary keeps both off the inlining target list.
        double d = new BigDecimal(s.trim())
                .setScale(scale, RoundingMode.HALF_UP)
                .doubleValue();
        return d == 0.0 ? 0.0 : d;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static double parsePlain(String s)
    {
        // Double.parseDouble has its own deep call chain (NumberFormatException
        // construction, Locale lookups, signature parsing in failure paths) —
        // running it across a TruffleBoundary keeps PE-time inlining bounded.
        double d = Double.parseDouble(s.trim());
        return d == 0.0 ? 0.0 : d;
    }
}
