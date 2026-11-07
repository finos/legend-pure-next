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
import org.eclipse.collections.api.list.MutableList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * {@link PersistentOrderedMap} behaves as a {@link LinkedHashMap} copied before every update, and is immutable:
 * whatever is done to any version, every version keeps reading exactly as it did when it was made.
 */
public class PersistentOrderedMapTest
{
    // Equal by id, but with only a few hash codes: many keys collide on the whole hash.
    private static final class Key
    {
        final int id;

        Key(int id)
        {
            this.id = id;
        }

        @Override
        public boolean equals(Object o)
        {
            return o instanceof Key k && k.id == id;
        }

        @Override
        public int hashCode()
        {
            return id % 7;
        }

        @Override
        public String toString()
        {
            return "K" + id;
        }
    }

    @Test
    public void randomUpdatesOfRandomVersionsMatchCopiedLinkedHashMaps()
    {
        Random random = new Random(7);
        MutableList<PersistentOrderedMap> versions = Lists.mutable.with(PersistentOrderedMap.EMPTY);
        MutableList<LinkedHashMap<Object, Object>> models = Lists.mutable.with(new LinkedHashMap<>());
        int head = 0;
        for (int step = 0; step < 4_000; step++)
        {
            // Mostly from the head of a main line, so maps grow large, and otherwise from any older one.
            int from = random.nextInt(4) > 0 ? head : random.nextInt(versions.size());
            PersistentOrderedMap map = versions.get(from);
            LinkedHashMap<Object, Object> model = new LinkedHashMap<>(models.get(from));
            Object key = randomKey(random);
            int op = random.nextInt(10);
            if (op < 6)
            {
                map = map.with(key, step);
                model.put(key, step);
            }
            else if (op < 9)
            {
                map = map.without(key);
                model.remove(key);
            }
            else
            {
                int other = random.nextInt(versions.size());
                map = map.withAll(versions.get(other));
                model.putAll(models.get(other));
            }
            versions.add(map);
            models.add(model);
            if (from == head)
            {
                head = versions.size() - 1;
            }
        }
        for (int k = 0; k < versions.size(); k++)
        {
            assertSame(models.get(k), versions.get(k));
        }
    }

    @Test
    public void largeMapGrownAndShrunkInALoop()
    {
        PersistentOrderedMap map = PersistentOrderedMap.EMPTY;
        LinkedHashMap<Object, Object> model = new LinkedHashMap<>();
        for (int i = 0; i < 50_000; i++)
        {
            String key = "k" + i;
            map = map.with(key, i);
            model.put(key, i);
        }
        PersistentOrderedMap full = map;
        // Removing most entries compacts the order vector; the order of the rest must survive it.
        for (int i = 0; i < 50_000; i += 3)
        {
            map = map.without("k" + i).without("k" + (i + 1));
            model.remove("k" + i);
            model.remove("k" + (i + 1));
        }
        assertSame(model, map);
        Assertions.assertEquals(50_000, full.size());
        Assertions.assertEquals(49_999, full.get("k49999"));
    }

    @Test
    public void puttingAnEqualKeyKeepsItsPlaceAndFirstKeyObject()
    {
        Key first = new Key(1);
        Key equal = new Key(1);
        PersistentOrderedMap map = PersistentOrderedMap.EMPTY.with(first, "a").with(new Key(2), "b").with(equal, "c");
        MutableList<Object> keys = Lists.mutable.empty();
        MutableList<Object> values = Lists.mutable.empty();
        map.forEachEntry((k, v) ->
        {
            keys.add(k);
            values.add(v);
        });
        Assertions.assertSame(first, keys.get(0));
        Assertions.assertEquals(Lists.mutable.with("c", "b"), values);
    }

    @Test
    public void nullKeyAndNullValue()
    {
        PersistentOrderedMap map = PersistentOrderedMap.EMPTY.with(null, "n").with("v", null);
        Assertions.assertEquals("n", map.get(null));
        Assertions.assertTrue(map.containsKey("v"));
        Assertions.assertNull(map.get("v"));
        Assertions.assertFalse(map.without(null).containsKey(null));
        Assertions.assertTrue(map.containsKey(null));
    }

    private static Object randomKey(Random random)
    {
        int kind = random.nextInt(10);
        if (kind == 0)
        {
            return null;
        }
        int id = random.nextInt(300);
        // A fresh Key object each time: equal keys are usually distinct objects.
        return kind < 6 ? new Key(id) : "s" + id;
    }

    private static void assertSame(LinkedHashMap<Object, Object> model, PersistentOrderedMap map)
    {
        Assertions.assertEquals(model.size(), map.size());
        MutableList<Object> keys = Lists.mutable.empty();
        MutableList<Object> values = Lists.mutable.empty();
        map.forEachEntry((k, v) ->
        {
            keys.add(k);
            values.add(v);
        });
        MutableList<Object> modelKeys = Lists.mutable.empty();
        MutableList<Object> modelValues = Lists.mutable.empty();
        for (Map.Entry<Object, Object> e : model.entrySet())
        {
            modelKeys.add(e.getKey());
            modelValues.add(e.getValue());
            Assertions.assertTrue(map.containsKey(e.getKey()));
            Assertions.assertEquals(e.getValue(), map.get(e.getKey()));
        }
        Assertions.assertEquals(modelValues, values);
        // Same key objects, not just equal ones: LinkedHashMap keeps the first key put.
        Assertions.assertEquals(modelKeys.size(), keys.size());
        for (int i = 0; i < keys.size(); i++)
        {
            Assertions.assertSame(modelKeys.get(i), keys.get(i));
        }
    }
}
