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

package org.finos.legend.pure.m3;

import org.finos.legend.pure.m3.module.MetadataAccessExtension;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilerExtension;
import org.finos.legend.pure.m3.module.pdbModule.archive.PDBExtension;
import org.finos.legend.pure.next.parser.ParserExtension;

/**
 * Extension point that bundles grammar parsing and compilation for a custom
 * section type (e.g. {@code ###CompiledGraph}, {@code ###Error}).
 *
 * <p>Extends both {@link ParserExtension} (for parsing custom section content)
 * and {@link CompilerExtension} (for compiling the resulting grammar elements),
 * so a single object handles the full lifecycle of a custom section.</p>
 */
public interface LanguageExtension extends ParserExtension, CompilerExtension, PDBExtension
{
    default MetadataAccessExtension buildMetadataExtensionForModule(Module module)
    {
        return null;
    }
}
