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

package org.finos.legend.pure.truffle.ast.natives.string;

import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.regex.Pattern;

/**
 * Translate Pure-level {@code RegexpParameter} enum values into JDK
 * {@link Pattern} flag bits and compile patterns.
 *
 * <p>Mapping mirrors legend-engine's PosixToJavaRegexConverter table:
 * <ul>
 *   <li>{@code CASE_SENSITIVE}        — no flag (clears CASE_INSENSITIVE).</li>
 *   <li>{@code CASE_INSENSITIVE}      — {@link Pattern#CASE_INSENSITIVE}.</li>
 *   <li>{@code MULTILINE}             — {@link Pattern#MULTILINE}.</li>
 *   <li>{@code NON_NEWLINE_SENSITIVE} — {@link Pattern#DOTALL}.</li>
 * </ul>
 *
 * <p>Pure callers pass these as an {@code RegexpParameter[1..*]} list, which
 * arrives here as a {@link PureSequence} of enum PDOs. Read each value's
 * {@code name} slot (the enum's simple value name) and OR the corresponding
 * flag.</p>
 */
public final class RegexpHelper
{
    private RegexpHelper() {}

    /** Read JDK Pattern flag bits from a Pure-side {@code RegexpParameter[*]}. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static int flagsFor(Object regexpParams)
    {
        if (regexpParams == null) return 0;
        if (regexpParams instanceof PureSequence seq)
        {
            int flags = 0;
            for (int i = 0, n = seq.size(); i < n; i++)
            {
                flags |= flagFor(seq.getBoxed(i));
            }
            return flags;
        }
        return flagFor(regexpParams);
    }

    private static int flagFor(Object enumValue)
    {
        if (enumValue == null) return 0;
        Object name = PureObj.read(enumValue, "name");
        if (!(name instanceof String s)) return 0;
        return switch (s)
        {
            case "CASE_INSENSITIVE"      -> Pattern.CASE_INSENSITIVE;
            case "MULTILINE"             -> Pattern.MULTILINE;
            case "NON_NEWLINE_SENSITIVE" -> Pattern.DOTALL;
            default                      -> 0; // CASE_SENSITIVE is the default
        };
    }

    /** Compile {@code regex} with the given JDK Pattern flag mask. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Pattern compile(String regex, int flags)
    {
        return Pattern.compile(regex, flags);
    }
}
