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

package org.finos.legend.pure.execution;

import java.util.Objects;

/**
 * Runtime representation of a Pure {@code meta::pure::metamodel::variant::Variant}
 * value: an immutable JSON tree.
 *
 * <p>{@code toString()} is the compact JSON text — this is part of the PCT
 * contract ({@code fromJson('...')->toString()} round-trips the JSON), so
 * the standard {@code toString} native needs no Variant-specific handling.
 * Equality is structural over the JSON tree, which makes
 * {@code pureEquals} work unmodified.</p>
 */
public class PureVariant
{
    public static final String TYPE_PATH = "meta::pure::metamodel::variant::Variant";

    private final JsonValue value;

    public PureVariant(JsonValue value)
    {
        this.value = Objects.requireNonNull(value);
    }

    public JsonValue getValue()
    {
        return value;
    }

    @Override
    public String toString()
    {
        return value.toJson();
    }

    @Override
    public boolean equals(Object obj)
    {
        return obj instanceof PureVariant other && other.value.equals(value);
    }

    @Override
    public int hashCode()
    {
        return value.hashCode();
    }
}
