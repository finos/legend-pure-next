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

package org.finos.legend.pure.m3.module.pdbModule;

import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Utf8;
import com.google.flatbuffers.Utf8Safe;
import org.finos.legend.pure.m3.module.pdbModule.fbs.StringValueDef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * PDB strings must not be decoded by FlatBuffers' own UTF-8 decoder.
 *
 * <p>Why this exists: HotSpot's C2, under tiered compilation, miscompiles
 * {@code Utf8Safe.decodeUtf8Array} and makes it throw
 * {@code IllegalArgumentException("Invalid UTF-8")} on a perfectly valid
 * two-byte sequence (e.g. {@code U+00E9}, {@code C3 A9}). It reproduces on
 * stock OpenJDK/Temurin 25 and not on GraalVM, whose JIT is unaffected — so
 * it surfaced only on CI, as a single failing PCT case per run (the throw
 * deoptimises the method, so everything after it passes).</p>
 *
 * <p>The fix is to decode PDB strings with {@code new String(bytes, UTF_8)}
 * instead, installed once as the FlatBuffers default. These tests pin that
 * contract: the first asserts we are not on FlatBuffers' decoder at all, the
 * second is a guard that whatever decoder we do install reads a two-byte
 * character back correctly.</p>
 */
class PdbUtf8DecodingTest
{
    /** Force the class whose static initializer installs the decoder. */
    private static void initialisePdbDecoding() throws ClassNotFoundException
    {
        Class.forName("org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension");
    }

    @Test
    void pdbStringDecodingDoesNotUseFlatBuffersUtf8Safe() throws Exception
    {
        initialisePdbDecoding();

        assertNotEquals(
                Utf8Safe.class,
                Utf8.getDefault().getClass(),
                "PDB string decoding must not run through FlatBuffers' Utf8Safe: HotSpot C2 miscompiles "
                        + "decodeUtf8Array and throws \"Invalid UTF-8\" on valid two-byte sequences.");
    }

    @Test
    void twoByteUtf8StringRoundTripsThroughFlatBufferTable() throws Exception
    {
        initialisePdbDecoding();

        assertEquals("é", readBackStringValue("é"), "two-byte character (U+00E9)");
        assertEquals("€", readBackStringValue("€"), "three-byte character (U+20AC)");
        assertEquals("a::b", readBackStringValue("a::b"), "ascii");
        assertEquals("", readBackStringValue(""), "empty");
        assertEquals("meta::pure – é€", readBackStringValue("meta::pure – é€"), "mixed");
    }

    /** Write {@code value} into a real FlatBuffer table and read it back out. */
    private static String readBackStringValue(String value)
    {
        FlatBufferBuilder builder = new FlatBufferBuilder(64);
        int valOffset = builder.createString(value);
        StringValueDef.startStringValueDef(builder);
        StringValueDef.addVal(builder, valOffset);
        builder.finish(StringValueDef.endStringValueDef(builder));

        return StringValueDef.getRootAsStringValueDef(builder.dataBuffer()).val();
    }
}
