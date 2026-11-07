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

package org.finos.legend.pure.truffle.execution.types;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * A Pure map never changes: the update operations return new maps, and the {@link java.util.Map} view of a map
 * cannot be written.
 */
public class MapImplTest
{
    @Test
    public void updatesReturnNewMapsAndLeaveTheSourceUnchanged()
    {
        MapImpl source = new MapImpl();
        source.put("a", 1);
        source.put("b", 2);
        // What the put and putAll natives do: a fresh map takes the source whole, then is filled.
        MapImpl added = new MapImpl();
        added.putAll(source);
        added.put("z", 26);
        MapImpl merged = new MapImpl();
        merged.putAll(source);
        merged.putAll(added);
        MapImpl removed = source.without("cgt", "a");

        Assertions.assertEquals(Lists.mutable.with("a", "b"), source.keys());
        Assertions.assertEquals(Lists.mutable.with(1, 2), source.values());
        Assertions.assertEquals(Lists.mutable.with("a", "b", "z"), added.keys());
        Assertions.assertEquals(Lists.mutable.with("a", "b", "z"), merged.keys());
        Assertions.assertEquals(Lists.mutable.with("b"), removed.keys());
        Assertions.assertEquals("cgt", removed.readProperty("classifierGenericType"));
    }

    @Test
    public void copyAndViewShareTheEntriesButCannotChangeThem()
    {
        MapImpl source = new MapImpl();
        source.put("a", 1);
        MapImpl copy = source._copy();
        java.util.Map<Object, Object> view = source.getMap();

        Assertions.assertEquals(1, copy.get("a"));
        Assertions.assertEquals(1, view.get("a"));
        Assertions.assertEquals(1, view.size());
        Assertions.assertThrows(UnsupportedOperationException.class, () -> view.put("b", 2));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> view.remove("a"));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> view.entrySet().iterator().remove());
        Assertions.assertEquals(1, source.size());
    }
}
