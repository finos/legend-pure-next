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
import org.eclipse.collections.api.list.MutableList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * {@link PersistentVector} is immutable: whatever is done to any version, every version keeps reading exactly
 * as it did when it was made.
 */
public class PersistentVectorTest
{
    @Test
    public void appendThenGetAcrossTrieGrowth()
    {
        // 32 fills the tail, 1056 fills a two-level trie, 33824 a three-level one.
        int n = 40_000;
        PersistentVector v = PersistentVector.EMPTY;
        for (int i = 0; i < n; i++)
        {
            v = v.append(i);
        }
        Assertions.assertEquals(n, v.size());
        for (int i = 0; i < n; i++)
        {
            Assertions.assertEquals(i, v.get(i));
        }
        MutableList<Object> seen = Lists.mutable.empty();
        v.forEach(seen::add);
        Assertions.assertEquals(n, seen.size());
        for (int i = 0; i < n; i++)
        {
            Assertions.assertEquals(i, seen.get(i));
        }
    }

    @Test
    public void everyVersionKeepsItsElements()
    {
        int n = 3_000;
        MutableList<PersistentVector> versions = Lists.mutable.with(PersistentVector.EMPTY);
        for (int i = 0; i < n; i++)
        {
            versions.add(versions.getLast().append("e" + i));
        }
        // Change old versions after the newer ones exist: none of the others may move.
        PersistentVector changed = versions.get(1_500).set(0, "x").set(1_499, "y");
        for (int k = 0; k <= n; k++)
        {
            PersistentVector v = versions.get(k);
            Assertions.assertEquals(k, v.size());
            for (int i = 0; i < k; i++)
            {
                Assertions.assertEquals("e" + i, v.get(i));
            }
        }
        Assertions.assertEquals("x", changed.get(0));
        Assertions.assertEquals("y", changed.get(1_499));
        Assertions.assertEquals("e1", changed.get(1));
    }

    @Test
    public void randomUpdatesOfRandomVersionsMatchCopiedLists()
    {
        Random random = new Random(42);
        MutableList<PersistentVector> versions = Lists.mutable.with(PersistentVector.EMPTY);
        MutableList<MutableList<Object>> models = Lists.mutable.with(Lists.mutable.empty());
        int head = 0;
        // Mostly from the head of a main line, so the vector grows past a two-level trie, and otherwise from any older one.
        for (int step = 0; step < 3_000; step++)
        {
            int from = random.nextInt(4) > 0 ? head : random.nextInt(versions.size());
            PersistentVector v = versions.get(from);
            MutableList<Object> model = Lists.mutable.withAll(models.get(from));
            if (v.size() == 0 || random.nextInt(3) > 0)
            {
                v = v.append(step);
                model.add(step);
            }
            else
            {
                int i = random.nextInt(v.size());
                v = v.set(i, -step);
                model.set(i, -step);
            }
            versions.add(v);
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
        int largest = versions.collectInt(PersistentVector::size).max();
        Assertions.assertTrue(largest > 1_056, "grew to " + largest);
    }

    @Test
    public void outOfRangeIndexFails()
    {
        PersistentVector v = PersistentVector.EMPTY.append(1);
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> v.get(1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> v.set(-1, 0));
    }

    private static void assertSame(MutableList<Object> model, PersistentVector v)
    {
        Assertions.assertEquals(model.size(), v.size());
        for (int i = 0; i < model.size(); i++)
        {
            Assertions.assertEquals(model.get(i), v.get(i));
        }
        MutableList<Object> seen = Lists.mutable.empty();
        v.forEach(seen::add);
        Assertions.assertEquals(model, seen);
    }
}
