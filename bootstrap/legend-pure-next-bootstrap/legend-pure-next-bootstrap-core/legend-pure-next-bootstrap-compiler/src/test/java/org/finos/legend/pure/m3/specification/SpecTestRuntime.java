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

package org.finos.legend.pure.m3.specification;

import meta.pure.protocol.PureFile;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.extensions.compiledgraph.CompiledGraphLanguageExtension;
import org.finos.legend.pure.m3.extensions.compilerstats.CompilerStatsLanguageExtension;
import org.finos.legend.pure.m3.extensions.error.ErrorLanguageExtension;
import org.finos.legend.pure.m3.extensions.reverseindex.ReverseIndexLanguageExtension;
import org.finos.legend.pure.m3.extensions.testfile.TestFileLanguageExtension;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.PureContent;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.next.parser.PureParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared scaffolding for the compiler-spec test classes
 * ({@link CompilerCompiledGraphTest}, {@link CompilerErrorTest},
 * {@link CompilerCompiledGraphPdbRoundTripTest}).
 *
 * <p>Holds the extension list, parser, and core PDB once so each test's
 * {@code @Test} body shrinks to "load resource → {@link #compileSpec} →
 * assert". Mirrors the {@code compilerTest.pure} flow on the Pure side:
 * one parse + one compile per test, with section-driven assertions.</p>
 */
public final class SpecTestRuntime
{
    private final List<LanguageExtension> extensions;
    private final PureParser parser;
    private final PDBModule coreModule;

    public SpecTestRuntime()
    {
        this.extensions = Lists.mutable.<LanguageExtension>with(
                new CompiledGraphLanguageExtension(),
                new CompilerStatsLanguageExtension(),
                new TestFileLanguageExtension(),
                new ReverseIndexLanguageExtension(),
                new ErrorLanguageExtension(),
                new PureLanguageExtension());
        this.parser = PureParser.builder().withExtensions(Lists.mutable.withAll(this.extensions)).build();
        try
        {
            this.coreModule = new PDBModule(BootstrapModule.locateCorePdb(), PDBModule.Mode.COMPILATION);
        }
        catch (java.io.IOException e)
        {
            throw new RuntimeException("Failed to locate core.pdb", e);
        }
    }

    /**
     * Parse, split (when ###File sections are present), compile, and
     * collect every parsed file so callers can iterate grammar elements in
     * declared order.
     */
    public CompiledSpec compileSpec(String content, String testName)
    {
        PureFile primaryFile = parser.parse(testName, content);
        List<PureContent> sources = primaryFile._sections().anySatisfy(s -> "File".equals(s._parserName()))
                ? TestFileLanguageExtension.splitTestFiles(content, testName, primaryFile)
                : List.of(new PureContent(content, testName));
        PureModel model = PureModel.withModules(
                        Lists.mutable.with(
                                new LocalModule("test", "*",
                                        Lists.mutable.with(coreModule.getName()),
                                        Lists.mutable.withAll(sources)),
                                coreModule))
                .withExtensions(Lists.mutable.withAll(extensions))
                .build();

        List<PureFile> allParsedFiles = new ArrayList<>();
        allParsedFiles.add(primaryFile);
        sources.stream()
                .filter(s -> !s.sourceId().equals(testName))
                .forEach(s -> allParsedFiles.add(parser.parse(s.sourceId(), s.content())));

        return new CompiledSpec(primaryFile, allParsedFiles, model, model.compile());
    }

    public PureParser parser() { return parser; }
    public PDBModule coreModule() { return coreModule; }
    public List<LanguageExtension> extensions() { return extensions; }
}
