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

package org.finos.legend.pure.truffle.types;

/**
 * Singleton sentinel for Pure's empty multiplicity ({@code [0..0]}) and
 * missing {@code [0..1]} values.
 *
 * <p>Using a singleton rather than {@code null} lets a frame slot stay
 * primitive ({@code long} / {@code double} / {@code boolean}) when the
 * payload is absent — we return {@code PureNull.INSTANCE} (an {@code Object})
 * instead of forcing the slot to be boxed. Graal can constant-fold null
 * checks to {@code == PureNull.INSTANCE}.</p>
 */
public final class PureNull
{
    public static final PureNull INSTANCE = new PureNull();

    private PureNull()
    {
    }

    @Override
    public String toString()
    {
        return "<null>";
    }
}
