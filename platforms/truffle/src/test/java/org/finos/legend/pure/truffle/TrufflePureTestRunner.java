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

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.extension.Stereotype;
import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.PureAssertionError;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;
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
    private static PDBModule coreModule;
    private static PDBModule compilerModule;
    private static FunctionDefinition pctAdapter;

    private static void ensureSetup() throws java.io.IOException
    {
        if (evaluator != null)
        {
            return;
        }
        Path corePdb = Path.of("../../build/core.pdb");
        Path compilerPdb = Path.of("../../build/compiler.pdb");

        coreModule = new PDBModule(corePdb, PDBModule.Mode.EXECUTION, "core", "*", Lists.mutable.empty());
        compilerModule = new PDBModule(compilerPdb, PDBModule.Mode.EXECUTION, "compiler", "*", Lists.mutable.with("core"));

        PureModel model = PureModel.withModules(Lists.mutable.with(coreModule, compilerModule))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        ScopedMetadataAccess resolver = new ScopedMetadataAccess(compilerModule, model);
        evaluator = new StandaloneEvaluator(resolver, null, NativeNodeRegistry.createDefault(), null);

        // Find PCT adapter
        for (String path : coreModule.elementPaths())
        {
            PackageableElement element = coreModule.getElement(path);
            if (element instanceof FunctionDefinition fd && isPCTAdapter(element))
            {
                pctAdapter = fd;
                break;
            }
        }
    }

    @TestFactory
    Collection<DynamicTest> functionTests() throws java.io.IOException
    {
        ensureSetup();
        List<DynamicTest> tests = new ArrayList<>();

        for (String path : coreModule.elementPaths())
        {
            PackageableElement element = coreModule.getElement(path);
            if (element instanceof FunctionDefinition fd && isTestStereotype(element) && isZeroArg(fd))
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

        for (String path : coreModule.elementPaths())
        {
            PackageableElement element = coreModule.getElement(path);
            if (element instanceof FunctionDefinition fd && isPCTTest(element))
            {
                tests.add(createTest(path, fd, pctAdapter));
            }
        }

        assertFalse(tests.isEmpty(), "Should discover at least one <<PCT.test>> function");
        return tests;
    }

    private DynamicTest createTest(String path, FunctionDefinition fd, FunctionDefinition adapter)
    {
        return DynamicTest.dynamicTest(path, () ->
        {
            StandaloneEvaluatorHolder.set(evaluator);
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
            catch (PureAssertionError e)
            {
                throw new org.opentest4j.AssertionFailedError(e.getMessage(), e);
            }
            catch (RuntimeException e)
            {
                // Unwrap to get a cleaner error message
                Throwable cause = e;
                while (cause.getCause() != null && cause.getCause() != cause)
                {
                    cause = cause.getCause();
                }
                throw new org.opentest4j.AssertionFailedError(cause.getMessage(), e);
            }
            finally
            {
                StandaloneEvaluatorHolder.clear();
            }
        });
    }

    private static boolean isTestStereotype(PackageableElement element)
    {
        if (element instanceof meta.pure.metamodel.extension.ElementWithStereotypes ews)
        {
            for (Stereotype s : ews._stereotypes())
            {
                if (s != null && "Test".equals(s._value()) && "test".equals(s._profile()._name()))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isPCTTest(PackageableElement element)
    {
        if (element instanceof meta.pure.metamodel.extension.ElementWithStereotypes ews)
        {
            for (Stereotype s : ews._stereotypes())
            {
                if (s != null && "test".equals(s._value()) && "PCT".equals(s._profile()._name()))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isPCTAdapter(PackageableElement element)
    {
        if (element instanceof meta.pure.metamodel.extension.ElementWithStereotypes ews)
        {
            for (Stereotype s : ews._stereotypes())
            {
                if (s != null && "adapter".equals(s._value()) && "PCT".equals(s._profile()._name()))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isZeroArg(FunctionDefinition fd)
    {
        var params = fd._parameters();
        return params == null || params.isEmpty();
    }
}
