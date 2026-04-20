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

package org.finos.legend.pure.truffle;



import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.PureAssertionError;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * JUnit 5 test factory that discovers and runs Pure tests via the
 * standalone Truffle evaluator. Each {@code <<test.Test>>} and
 * {@code <<PCT.test>>} function becomes an individual dynamic test
 * with independent pass/fail — no fail-fast.
 *
 * <p>Run with: {@code mvn test -Dtest=TrufflePureTestRunner}</p>
 */
class TrufflePureTestRunner
{
    private static StandaloneEvaluator evaluator;
    private static org.finos.legend.pure.truffle.runtime.TrufflePdbLoader coreLoader;
    private static org.finos.legend.pure.truffle.runtime.TrufflePdbLoader compilerLoader;
    private static PDBModule coreModule;
    private static PDBModule compilerModule;
    private static Object pctAdapter;

    private static void ensureSetup() throws java.io.IOException
    {
        if (evaluator != null)
        {
            return;
        }
        Path corePdb = Path.of("../../../build/core.pdb");
        Path compilerPdb = Path.of("../../../build/compiler.pdb");

        // Use truffle PDB loader — reads FlatBuffer directly into truffle-namespaced wrappers
        coreLoader = new org.finos.legend.pure.truffle.runtime.TrufflePdbLoader(corePdb);
        compilerLoader = new org.finos.legend.pure.truffle.runtime.TrufflePdbLoader(compilerPdb);

        // Composite resolver: compiler first, then core
        org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver = new org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess()
        {
            @Override
            public Object getElement(String path)
            {
                Object e = compilerLoader.getElement(path);
                return e != null ? e : coreLoader.getElement(path);
            }
            @Override
            public boolean hasElement(String path)
            {
                return compilerLoader.hasElement(path) || coreLoader.hasElement(path);
            }
            @Override
            public java.util.Set<String> elementPaths()
            {
                java.util.Set<String> all = new java.util.LinkedHashSet<>(coreLoader.elementPaths());
                all.addAll(compilerLoader.elementPaths());
                return all;
            }
        };

        evaluator = new StandaloneEvaluator(resolver, null, NativeNodeRegistry.createDefault(), null);
        StandaloneEvaluator.INSTANCE = evaluator;

        // Keep bootstrap PDB modules for element path discovery
        coreModule = new PDBModule(corePdb, PDBModule.Mode.EXECUTION, "core", "*", Lists.mutable.empty());
        compilerModule = new PDBModule(compilerPdb, PDBModule.Mode.EXECUTION, "compiler", "*", Lists.mutable.with("core"));
        PureModel model = PureModel.withModules(Lists.mutable.with(coreModule, compilerModule))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        // Configure the PureParser for the parse native
        java.util.List<org.finos.legend.pure.next.parser.ParserExtension> parserExts = new java.util.ArrayList<>();
        parserExts.add(new org.finos.legend.pure.next.parser.m3.PureLanguageParser());
        evaluator.setPureParser(org.finos.legend.pure.next.parser.PureParser.builder()
                .withExtensions(parserExts)
                .build());

        // Find PCT adapter via truffle loader
        for (String path : coreLoader.elementPaths())
        {
            Object element = coreLoader.getElement(path);
            if (element instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition && isPCTAdapter(element))
            {
                pctAdapter = element;
                break;
            }
        }
    }

    @TestFactory
    Collection<DynamicTest> functionTests() throws java.io.IOException
    {
        ensureSetup();
        List<DynamicTest> tests = new ArrayList<>();

        for (String path : coreLoader.elementPaths())
        {
            Object element = coreLoader.getElement(path);
            if (element instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition fd && isTestStereotype(element) && isZeroArg(fd))
            {
                tests.add(createTest(path, fd, null));
            }
        }

        assertFalse(tests.isEmpty(), "Should discover at least one <<test.Test>> function");
        return tests;
    }

    @TestFactory
    Collection<DynamicTest> pctTests() throws java.io.IOException
    {
        ensureSetup();
        List<DynamicTest> tests = new ArrayList<>();

        for (String path : coreLoader.elementPaths())
        {
            Object element = coreLoader.getElement(path);
            if (element instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition fd && isPCTTest(element))
            {
                tests.add(createTest(path, fd, pctAdapter));
            }
        }

        assertFalse(tests.isEmpty(), "Should discover at least one <<PCT.test>> function");
        return tests;
    }

    private DynamicTest createTest(String path, org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition fd, Object adapter)
    {
        return DynamicTest.dynamicTest(path, () ->
        {
            StandaloneEvaluator.INSTANCE = evaluator;
            try
            {
                if (adapter != null)
                {
                    evaluator.executeFunction(fd, new Object[]{adapter});
                }
                else
                {
                    evaluator.executeFunction(fd, new Object[0]);
                }
            }
            catch (org.finos.legend.pure.truffle.ast.PureException.AssertionError e)
            {
                throw new org.opentest4j.AssertionFailedError("[" + path + "] " + e.getMessage() + formatPureStack(e), e);
            }
            catch (org.finos.legend.pure.truffle.ast.PureException e)
            {
                throw new org.opentest4j.AssertionFailedError("[" + path + "] " + e.getMessage() + formatPureStack(e), e);
            }
            catch (PureAssertionError e)
            {
                // Legacy bootstrap assertion — should be removed once all asserts use PureException
                throw new org.opentest4j.AssertionFailedError("[" + path + "] " + e.getMessage() + formatPureStack(e), e);
            }
            catch (RuntimeException e)
            {
                Throwable cause = e;
                while (cause.getCause() != null && cause.getCause() != cause)
                {
                    cause = cause.getCause();
                }
                throw new org.opentest4j.AssertionFailedError("[" + path + "] " + cause.getMessage() + formatPureStack(e), e);
            }
        });
    }

    private static boolean isTestStereotype(Object element)
    {
        return hasStereotype(element, "Test", "test");
    }

    private static boolean isPCTTest(Object element)
    {
        return hasStereotype(element, "test", "PCT");
    }

    private static boolean isPCTAdapter(Object element)
    {
        return hasStereotype(element, "adapter", "PCT");
    }

    private static boolean hasStereotype(Object element, String value, String profileName)
    {
        if (!(element instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.extension.ElementWithStereotypes ews))
        {
            return false;
        }
        var st = ews._stereotypes();
        if (st == null)
        {
            return false;
        }
        // Stereotypes are stored as [string] in PDB — format: "profile::path.StereotypeName"
        // The wrapper resolves via resolver but stereotypes aren't top-level elements.
        // Read the raw strings from the FBS directly.
        if (!(element instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition))
        {
            return false;
        }
        // Access the raw FBS stereotype strings via the wrapper's underlying data
        // For now, iterate the resolved values — they may be null (unresolved) or Stereotype objects
        for (Object s : st.toBoxedArray())
        {
            if (s == null) continue;
            if (s instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.extension.Stereotype stereotype)
            {
                String stValue = stereotype._value();
                if (value.equals(stValue))
                {
                    Object profile = stereotype._profile();
                    if (profile instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.extension.Profile p)
                    {
                        if (profileName.equals(p._name()))
                        {
                            return true;
                        }
                    }
                    // Profile might not resolve — match by value alone as fallback
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isZeroArg(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition fd)
    {
        var params = fd._parameters();
        return params == null || params.isEmpty();
    }

    /**
     * Extract the Pure call stack from a Truffle exception.
     * Uses TruffleStackTrace to get guest language frames with source locations.
     */
    private static String formatPureStack(Throwable e)
    {
        Throwable inner = e;
        while (inner.getCause() != null && inner.getCause() != inner)
        {
            inner = inner.getCause();
        }

        try
        {
            var frames = com.oracle.truffle.api.TruffleStackTrace.getStackTrace(inner);
            if (frames == null || frames.isEmpty())
            {
                return "";
            }
            StringBuilder sb = new StringBuilder("\nPure stack:");
            for (var frame : frames)
            {
                var target = frame.getTarget();
                var rootNode = target.getRootNode();
                String name = rootNode != null ? rootNode.getName() : "?";

                // Find source from the location node (the call site or expression node)
                com.oracle.truffle.api.source.SourceSection sourceSection = null;
                var location = frame.getLocation();
                if (location != null)
                {
                    sourceSection = location.getSourceSection();
                    // Walk up the node tree to find the nearest source section
                    var node = location;
                    while (sourceSection == null && node != null)
                    {
                        sourceSection = node.getSourceSection();
                        node = node.getParent();
                    }
                }
                if (sourceSection == null && rootNode != null)
                {
                    sourceSection = rootNode.getSourceSection();
                }

                sb.append("\n  at ").append(name != null ? name : "?");
                if (sourceSection != null && sourceSection.getSource() != null)
                {
                    sb.append(" (").append(sourceSection.getSource().getName())
                            .append(":").append(sourceSection.getStartLine())
                            .append(":").append(sourceSection.getStartColumn()).append(")");
                }
            }
            return sb.toString();
        }
        catch (Exception ignored)
        {
            return "";
        }
    }
}
