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
 * Boxed {@code Object[]} fallback storage for heterogeneous or
 * non-primitive Pure sequences. Used when a storage transition forces a
 * {@code LongSequence} or {@code DoubleSequence} to accommodate mixed
 * types.
 */
public final class ObjectSequence extends PureSequence
{
    private final Object[] values;

    public ObjectSequence(Object[] values)
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

    @Override
    public Object getBoxed(int index)
    {
        return values[index];
    }

    @Override
    public Object[] toBoxedArray()
    {
        return values.clone();
    }

    @Override
    public String toString()
    {
        return "ObjectSequence" + Arrays.toString(values);
    }
}
