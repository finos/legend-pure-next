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

package org.finos.legend.pure.platform.java.runtime;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Pure map never changes: {@link PureMap}'s updates return new maps, also from a map built elsewhere, and a
 * map is a read-only {@link Map} equal to any map with the same entries.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class PureMapTest
{
    @Test
    public void updatesReturnNewMapsAndLeaveTheSourceUnchanged()
    {
        Map source = PureMap.put(PureMap.put(PureMap.newMap(null), "a", 1), "b", 2);
        Map added = PureMap.put(source, "z", 26);
        Map removed = PureMap.remove(source, "a");
        Map merged = PureMap.putAll(source, PureMap.put(PureMap.newMap(null), "c", 3));

        Assertions.assertEquals(Lists.mutable.with("a", "b"), PureMap.keys(source));
        Assertions.assertEquals(Lists.mutable.with(1, 2), PureMap.values(source));
        Assertions.assertEquals(Lists.mutable.with("a", "b", "z"), PureMap.keys(added));
        Assertions.assertEquals(Lists.mutable.with("b"), PureMap.keys(removed));
        Assertions.assertEquals(Lists.mutable.with("a", "b", "c"), PureMap.keys(merged));
    }

    @Test
    public void aMapFromElsewhereIsConvertedOnItsFirstChange()
    {
        LinkedHashMap<Object, Object> plain = new LinkedHashMap<>();
        plain.put("x", 1);
        plain.put("y", 2);
        Map changed = PureMap.put(plain, "x", 10);

        Assertions.assertEquals(Lists.mutable.with("x", "y"), PureMap.keys(changed));
        Assertions.assertEquals(10, PureMap.get(changed, "x"));
        Assertions.assertEquals(1, plain.get("x"));
    }

    @Test
    public void equalToAnyMapWithTheSameEntriesAndReadOnly()
    {
        Map map = PureMap.put(PureMap.put(PureMap.newMap(null), "a", 1), "b", 2);
        LinkedHashMap<Object, Object> same = new LinkedHashMap<>();
        same.put("b", 2);
        same.put("a", 1);

        Assertions.assertEquals(same, map);
        Assertions.assertEquals(map, same);
        Assertions.assertEquals(same.hashCode(), map.hashCode());
        Assertions.assertThrows(UnsupportedOperationException.class, () -> map.put("c", 3));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> map.remove("a"));
        Assertions.assertEquals(2, map.size());
    }
}
