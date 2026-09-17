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

import com.google.flatbuffers.Utf8;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Decodes FlatBuffer strings with {@code new String(bytes, UTF_8)} instead of
 * FlatBuffers' own {@code Utf8Safe} decoder.
 *
 * <p>Why: HotSpot's C2, under tiered compilation, miscompiles
 * {@code Utf8Safe.decodeUtf8Array} so that it throws
 * {@code IllegalArgumentException("Invalid UTF-8")} on a valid two-byte
 * sequence such as {@code U+00E9} ({@code C3 A9}). It reproduces on stock
 * OpenJDK/Temurin 25 and not on GraalVM (whose JIT is unaffected), which is
 * why it showed up only on CI — as a single failing PCT case per run, since
 * the throw deoptimises the method and every later decode succeeds. Neither
 * the PDB bytes nor our own code are at fault: the same archives decode
 * correctly under {@code -Xint}, under C1, and on GraalVM.</p>
 *
 * <p>{@link String}'s UTF-8 constructor is intrinsified, so this also removes
 * the char-at-a-time decode loop that JFR measured at ~13% of bootstrap
 * self-host CPU (see {@code RdfFbsJavaGenerator}, which caches decoded
 * {@code String} fields for the same reason).</p>
 *
 * <p>Only decoding changes. {@code encodedLength} and {@code encodeUtf8} are
 * delegated untouched to the decoder this one replaced, so written PDBs stay
 * byte-for-byte identical and the golden archives keep matching.</p>
 *
 * <p>One behavioural difference: on malformed input FlatBuffers throws, while
 * {@code new String(..., UTF_8)} substitutes {@code U+FFFD}. PDBs are written
 * by our own writer and are always valid UTF-8, and a replacement character is
 * a gentler failure than an exception mid-load.</p>
 *
 * <p>{@code Table} and {@code StringVector} each capture
 * {@code Utf8.getDefault()} into a field when they are constructed, so
 * {@link #install()} has to run before the first table is built. It is called
 * from the static initializers of the classes that construct them.</p>
 */
public final class PdbUtf8 extends Utf8
{
    /** The decoder this one replaced; still used for the encode side. */
    private final Utf8 delegate;

    private PdbUtf8(Utf8 delegate)
    {
        this.delegate = delegate;
    }

    /**
     * Make this the default FlatBuffers UTF-8 processor. Idempotent, and safe
     * to call from several static initializers.
     */
    public static synchronized void install()
    {
        Utf8 current = Utf8.getDefault();
        if (!(current instanceof PdbUtf8))
        {
            Utf8.setDefault(new PdbUtf8(current));
        }
    }

    @Override
    public String decodeUtf8(ByteBuffer buffer, int offset, int length)
    {
        if (buffer.hasArray())
        {
            return new String(buffer.array(), buffer.arrayOffset() + offset, length, StandardCharsets.UTF_8);
        }
        // Read-only or direct buffer: copy out with absolute gets, which leave
        // the buffer's own position alone (it is shared across wrappers).
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++)
        {
            bytes[i] = buffer.get(offset + i);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public int encodedLength(CharSequence sequence)
    {
        return this.delegate.encodedLength(sequence);
    }

    @Override
    public void encodeUtf8(CharSequence in, ByteBuffer out)
    {
        this.delegate.encodeUtf8(in, out);
    }
}
