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

package org.finos.legend.pure.execution;

import java.util.LinkedHashMap;
import meta.pure.metamodel.valuespecification.ValueSpecification;

/**
 * Runtime representation of a Pure {@code Map<K,V>} value.
 *
 * <p>Pure maps use structural equality for key lookup (via
 * {@link NativeRepository#pureEquals}), so we can't use a plain
 * Java {@link java.util.HashMap}. Instead, we back the map with a
 * {@link LinkedHashMap} and do equality-based key lookup manually
 * in the native handlers.</p>
 */
public class PureMap
{
    private final LinkedHashMap<ValueSpecification, ValueSpecification> map;

    public PureMap(LinkedHashMap<ValueSpecification, ValueSpecification> map)
    {
        this.map = map;
    }

    public LinkedHashMap<ValueSpecification, ValueSpecification> getMap()
    {
        return map;
    }

    @Override
    public String toString()
    {
        return "PureMap" + map;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (!(obj instanceof PureMap other))
        {
            return false;
        }
        if (map.size() != other.map.size())
        {
            return false;
        }
        // Entry-wise structural equality
        for (var entry : map.entrySet())
        {
            boolean found = false;
            for (var otherEntry : other.map.entrySet())
            {
                if (NativeRepository.pureEquals(entry.getKey(), otherEntry.getKey())
                        && NativeRepository.pureEquals(entry.getValue(), otherEntry.getValue()))
                {
                    found = true;
                    break;
                }
            }
            if (!found)
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode()
    {
        return map.size();
    }
}
