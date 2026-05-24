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

import meta.pure.metamodel.PackageableElement;
import meta.pure.protocol.PureFile;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.extensions.compiledgraph.CompiledGraph;
import org.finos.legend.pure.m3.extensions.compilerstats.CompilerStats;
import org.finos.legend.pure.m3.extensions.error.Error;
import org.finos.legend.pure.m3.extensions.reverseindex.ReverseIndex;
import org.finos.legend.pure.m3.extensions.testfile.TestFile;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.Module;

import java.util.ArrayList;
import java.util.List;

/**
 * Output of {@link SpecTestRuntime#compileSpec}: the primary parsed file
 * (for section walks), every parsed file (primary + ###File chunks, in
 * declared order, used to keep printed graph deterministic), the built
 * model, and the compile result.
 */
public record CompiledSpec(
        PureFile primary,
        List<PureFile> allParsedFiles,
        PureModel model,
        CompilationResult result)
{
    public Module testModule()
    {
        return model.getModule("test");
    }

    /**
     * Walks every parsed file's grammar elements in declared order,
     * resolves each through {@link #testModule()}, and skips test-fixture
     * elements (###CompiledGraph, ###CompilerStats, ###Error,
     * ###ReverseIndex, ###File markers).
     */
    public List<PackageableElement> compiledElementsInDeclarationOrder()
    {
        Module module = testModule();
        List<PackageableElement> elements = new ArrayList<>();
        for (PureFile pf : allParsedFiles)
        {
            pf._sections().forEach(section ->
                    section._elements().forEach(grammar ->
                    {
                        if (isAssertionElement(grammar))
                        {
                            return;
                        }
                        PackageableElement resolved = module.getElement(pathOf(grammar));
                        if (resolved != null)
                        {
                            elements.add(resolved);
                        }
                    }));
        }
        return elements;
    }

    private static boolean isAssertionElement(meta.pure.protocol.grammar.PackageableElement e)
    {
        return e instanceof CompiledGraph
                || e instanceof CompilerStats
                || e instanceof Error
                || e instanceof ReverseIndex
                || e instanceof TestFile;
    }

    private static String pathOf(meta.pure.protocol.grammar.PackageableElement e)
    {
        String name = e._name();
        String pkg = e._package() != null ? e._package()._value() : null;
        return pkg != null ? pkg + "::" + name : name;
    }
}
