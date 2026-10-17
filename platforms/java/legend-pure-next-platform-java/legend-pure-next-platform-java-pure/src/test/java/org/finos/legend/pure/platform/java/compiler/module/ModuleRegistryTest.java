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

package org.finos.legend.pure.platform.java.compiler.module;

import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.platform.java.compiler.module.inMemoryModule.InMemoryModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Registration returns the registry, so a host can build one inline: {@code withRegistry(new ModuleRegistry().registerAll(...))}. */
class ModuleRegistryTest
{
    @Test
    void registerAllKeepsTheOrderGivenAndReturnsTheRegistry()
    {
        ModuleRegistry registry = new ModuleRegistry();
        ModuleRegistry returned = registry.registerAll(Lists.immutable.of(
                new InMemoryModule("a", List.of()), new InMemoryModule("b", List.of("a"))));
        assertSame(registry, returned);
        assertEquals(Lists.mutable.of("a", "b"), Lists.mutable.withAll(registry.modules()).collect(Module::name));
    }

    @Test
    void registerReturnsTheRegistry()
    {
        ModuleRegistry registry = new ModuleRegistry();
        assertSame(registry, registry.register(new InMemoryModule("a", List.of())));
    }

    @Test
    void unregisterReturnsTheRemovedModuleOrNull()
    {
        InMemoryModule a = new InMemoryModule("a", List.of());
        ModuleRegistry registry = new ModuleRegistry().register(a);
        assertSame(a, registry.unregister("a"));
        assertNull(registry.unregister("a"));
    }

    @Test
    void validateReturnsTheRegistry()
    {
        ModuleRegistry registry = new ModuleRegistry().register(new InMemoryModule("a", List.of()));
        assertSame(registry, registry.validate());
    }
}
