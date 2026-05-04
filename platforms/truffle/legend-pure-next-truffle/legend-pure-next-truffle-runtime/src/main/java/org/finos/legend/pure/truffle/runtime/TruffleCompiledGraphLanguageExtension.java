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

import meta.pure.protocol.grammar.Package_PointerImpl;
import meta.pure.protocol.grammar.PackageableElement;
import org.finos.legend.pure.next.parser.ParserExtension;

import java.util.List;

/**
 * Truffle-native parser extension for {@code ###CompiledGraph} test sections.
 *
 * <p>Creates grammar elements in the {@code meta.pure.compiler.test} namespace
 * so that {@link ProtocolTranslator} can translate them to the PDB-generated
 * {@code org.finos.legend.pure.truffle.pdb.meta.pure.compiler.test.CompiledGraphImpl}.
 * This replaces the M3
 * {@code org.finos.legend.pure.m3.extensions.compiledgraph.CompiledGraphLanguageExtension}
 * for Truffle-based evaluation.</p>
 */
public final class TruffleCompiledGraphLanguageExtension implements ParserExtension
{
    @Override
    public String sectionName()
    {
        return "CompiledGraph";
    }

    @Override
    public List<PackageableElement> parseSection(String content, String sourceId, int lineOffset)
    {
        meta.pure.compiler.test.CompiledGraphImpl cg = new meta.pure.compiler.test.CompiledGraphImpl();
        cg._name("CompiledGraph");
        cg._value(content);
        cg._package(new Package_PointerImpl()._value(sourceId));
        return List.of(cg);
    }
}
