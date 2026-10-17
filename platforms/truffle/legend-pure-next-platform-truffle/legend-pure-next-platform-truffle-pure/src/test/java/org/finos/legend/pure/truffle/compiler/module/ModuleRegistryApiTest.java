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

package org.finos.legend.pure.truffle.compiler.module;

import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.truffle.compiler.module.inMemoryModule.InMemoryModule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The registry API every host shares: register and registerAll return the registry (so a host can build
 * one inline), unregister returns the removed module or null, validate returns the registry.
 */
public class ModuleRegistryApiTest
{
    @Test
    public void registerAllKeepsTheOrderGivenAndReturnsTheRegistry()
    {
        ModuleRegistry registry = new ModuleRegistry();
        ModuleRegistry returned = registry.registerAll(Lists.immutable.of(
                new InMemoryModule("a", List.of()), new InMemoryModule("b", List.of("a"))));
        Assertions.assertSame(registry, returned);
        Assertions.assertEquals(Lists.mutable.of("a", "b"), Lists.mutable.withAll(registry.modules()).collect(Module::name));
    }

    @Test
    public void registerReturnsTheRegistry()
    {
        ModuleRegistry registry = new ModuleRegistry();
        Assertions.assertSame(registry, registry.register(new InMemoryModule("a", List.of())));
    }

    @Test
    public void unregisterReturnsTheRemovedModuleOrNull()
    {
        InMemoryModule a = new InMemoryModule("a", List.of());
        ModuleRegistry registry = new ModuleRegistry().register(a);
        Assertions.assertSame(a, registry.unregister("a"));
        Assertions.assertNull(registry.unregister("a"));
    }

    @Test
    public void validateReturnsTheRegistry()
    {
        ModuleRegistry registry = new ModuleRegistry().register(new InMemoryModule("a", List.of()));
        Assertions.assertSame(registry, registry.validate());
    }
}
