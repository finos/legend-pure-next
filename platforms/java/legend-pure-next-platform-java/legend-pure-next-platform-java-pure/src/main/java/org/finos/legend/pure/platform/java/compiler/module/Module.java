// Copyright 2026 Goldman Sachs
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

import java.util.List;

/**
 * A unit of compiled Pure the runtime loads: a PDB archive on disk
 * ({@link PdbModule}) or code held in memory ({@link InMemoryModule}).
 *
 * <p>Identity is the name plus the dependencies, as on the other hosts — a
 * module names the modules whose elements its own may refer to, and
 * {@link ModuleRegistry#validate()} checks every one of them was registered.</p>
 */
public interface Module
{
    /** The module's name, as its manifest declares it. */
    String name();

    /** The names of the modules this one depends on. */
    List<String> dependencies();
}
