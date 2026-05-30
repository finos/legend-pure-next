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

import java.util.Arrays;

/**
 * Unboxed {@code double[]} storage for Pure {@code Float[*]} sequences.
 */
public final class DoubleSequence extends PureSequence
{
    private final double[] values;

    public DoubleSequence(double[] values)
    {
        this.values = values;
    }

    @Override
    public int size()
    {
        return values.length;
    }

    @Override
    public boolean isEmpty()
    {
        return values.length == 0;
    }

    public double getDouble(int index)
    {
        return values[index];
    }

    @Override
    public Object getBoxed(int index)
    {
        return values[index];
    }

    @Override
    public Object[] toBoxedArray()
    {
        Double[] boxed = new Double[values.length];
        for (int i = 0; i < values.length; i++)
        {
            boxed[i] = values[i];
        }
        return boxed;
    }

    @Override
    public String toString()
    {
        return "DoubleSequence" + Arrays.toString(values);
    }
}
