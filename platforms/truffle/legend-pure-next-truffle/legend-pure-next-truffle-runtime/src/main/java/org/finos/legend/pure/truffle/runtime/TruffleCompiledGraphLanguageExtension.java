// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.runtime;

import org.finos.legend.pure.truffle.parser.topLevel.TruffleParserExtension;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObjBuilder;

import java.util.List;

/**
 * Truffle-native parser extension for {@code ###CompiledGraph} test sections.
 * Produces a PDO at {@code meta::pure::compiler::test::CompiledGraph}.
 */
public final class TruffleCompiledGraphLanguageExtension implements TruffleParserExtension
{
    @Override
    public String sectionName()
    {
        return "CompiledGraph";
    }

    @Override
    public List<Object> parseSection(String content, String sourceId, int lineOffset, TruffleMetadataAccess resolver)
    {
        Object pkg = PureObjBuilder.of("meta::pure::protocol::grammar::Package_Pointer", resolver)
                .put("value", sourceId)
                .build();
        Object cg = PureObjBuilder.of("meta::pure::compiler::test::CompiledGraph", resolver)
                .put("name", "CompiledGraph")
                .put("value", content)
                .put("package", pkg)
                .build();
        return List.of(cg);
    }
}
