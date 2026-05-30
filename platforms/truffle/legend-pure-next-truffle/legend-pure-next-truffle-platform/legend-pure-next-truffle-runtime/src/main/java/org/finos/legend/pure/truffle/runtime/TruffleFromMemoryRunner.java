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

import org.finos.legend.pure.truffle.PureTruffleRuntime;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;
import org.finos.legend.pure.truffle.runtime.helper.PointerGraphResolver;
import org.finos.legend.pure.truffle.runtime.helper._PackageableElement;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Compiles a Pure source corpus through Pure-on-Truffle and registers the
 * filtered result as an in-memory {@link TruffleInMemoryModule} on top of an
 * already-loaded registry — no PDB on disk.
 *
 * <p>Used by the {@code pure-truffle execute-from-memory} CLI subcommand to
 * drive {@code runTests} / {@code runPCTTests} against freshly-compiled test
 * sources, exercising the same path a regular {@code test-pure-runtime} run
 * would use except the test elements come from source instead of from
 * {@code core-tests.pdb}. Also useful to any future test harness that wants
 * to inject hand-built test elements without staging a PDB.</p>
 *
 * <p>Filter rationale: bootstrap puts library (non-test) elements in
 * {@code core.pdb} and test elements in {@code core-tests.pdb}; the inmem
 * module must NOT re-declare the library elements or the registry's
 * uniqueness rule rejects them. So we keep only test-stereotyped functions
 * plus every {@code Package} PDO. Package path collisions ARE allowed and
 * folded by {@link TruffleModuleRegistry#foldChildrenInto foldChildrenInto},
 * which is what threads the namespace structure so {@code Package.children}
 * traversal (used by both runners) reaches the test functions.</p>
 */
public final class TruffleFromMemoryRunner
{
    private static final String PARSE_DIRS_FN_PATH =
            "meta::pure::compiler::parseDirs_String_MANY__PureFile_MANY_";
    // 3-arg silent variant — callers pull errors/stats structurally off the
    // CompilationResult; the live progress bar + stats summary would clutter
    // CLI output where the only thing that matters is the runner verdict.
    private static final String COMPILE_FN_PATH =
            "meta::pure::compiler::compile_PureFile_MANY__Boolean_1__Boolean_1__CompilationResult_1_";

    /** {@code <<test.X>>} for any X — Test / TestDependency / BeforePackage / AfterPackage / ToFix. */
    private static final String TEST_PROFILE_PATH = "meta::pure::profiles::test";
    /** {@code <<PCT.test>>} and {@code <<PCT.adapter>>}. */
    private static final String PCT_PROFILE_PATH = "meta::pure::test::pct::PCT";
    private static final Set<String> PCT_TEST_STEREOTYPES = Set.of("test", "adapter");

    private TruffleFromMemoryRunner() {}

    /**
     * Compile {@code sourceRoots} via Pure-on-Truffle, filter to
     * test-stereotyped elements + Package PDOs, wrap as a
     * {@link TruffleInMemoryModule} named {@code moduleName} with every
     * already-registered module as a dependency, and register it on
     * {@code registry}. Throws if the compile produces errors or yields no
     * test elements after filtering.
     *
     * @param registry      must already have at least {@code core} and
     *                      {@code compiler} (the latter provides
     *                      {@code parseDirs} / {@code compile} natives)
     * @param runtime       Truffle runtime wired to {@code registry}
     * @param sourceRoots   directory paths whose .pure files form the corpus
     * @param moduleName    name to register the inmem module under
     *                      (e.g. {@code "inmem-runtime-tests"})
     */
    public static void compileAndRegister(
            TruffleModuleRegistry registry,
            PureTruffleRuntime runtime,
            List<String> sourceRoots,
            String moduleName)
    {
        Object parseDirsFn = registry.getElement(PARSE_DIRS_FN_PATH);
        Object compileFn = registry.getElement(COMPILE_FN_PATH);
        if (parseDirsFn == null || compileFn == null
                || !PureObj.isType(parseDirsFn, "meta::pure::metamodel::function::FunctionDefinition", registry)
                || !PureObj.isType(compileFn, "meta::pure::metamodel::function::FunctionDefinition", registry))
        {
            throw new IllegalStateException("parseDirs/compile FunctionDefinition not resolvable through registry — "
                    + "make sure compiler.pdb is loaded before calling compileAndRegister");
        }

        Object result;
        try
        {
            Object parsedFiles = runtime.execute(parseDirsFn, sourceRoots);
            // debug=false, silent=true — caller will read errors/elements off the result.
            result = runtime.execute(compileFn, parsedFiles, Boolean.FALSE, Boolean.TRUE);
        }
        catch (Throwable t)
        {
            // Surface the Pure-level stack so the CLI user can see WHICH
            // atomic value / source location compile-pure choked on. The JVM
            // stack ends at the native node and doesn't include Pure frames.
            String pureStack = PureStackFormatter.format(t);
            if (!pureStack.isEmpty())
            {
                System.err.println(pureStack);
            }
            throw t instanceof RuntimeException rt ? rt : new RuntimeException(t);
        }
        if (result == null)
        {
            throw new RuntimeException("compile returned null");
        }
        Object errorsObj = PureObj.read(result, "errors");
        if (errorsObj instanceof PureSequence errSeq && errSeq.size() > 0)
        {
            StringBuilder sb = new StringBuilder("compile produced errors:\n");
            for (int i = 0; i < errSeq.size(); i++)
            {
                sb.append("  ").append(errSeq.getBoxed(i)).append('\n');
            }
            throw new RuntimeException(sb.toString());
        }

        Object elementsObj = PureObj.read(result, "elements");
        if (!(elementsObj instanceof PureSequence elementsSeq) || elementsSeq.size() == 0)
        {
            throw new IllegalStateException("compile.elements is empty");
        }

        PureSequence testElements = filterTestStereotyped(elementsSeq, registry);
        if (testElements.size() == 0)
        {
            throw new IllegalStateException("no test-tagged elements after filtering compile result for module '"
                    + moduleName + "'");
        }

        // Inmem module declares every already-registered module as a
        // dependency so its filtered elements can reference library / helper
        // PDOs (Classes, Functions, etc.) in core via the resolver.
        List<String> deps = new ArrayList<>();
        for (TruffleModule existing : registry.modules())
        {
            deps.add(existing.name());
        }
        registry.register(new TruffleInMemoryModule(
                moduleName, deps, testElements, registry,
                PointerGraphResolver.ResolveProgress.NOOP));
    }

    private static PureSequence filterTestStereotyped(PureSequence elements, TruffleModuleRegistry registry)
    {
        List<Object> kept = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++)
        {
            Object el = elements.getBoxed(i);
            if (el == null) continue;
            if (PureObj.pureTypeIs(el, "meta::pure::metamodel::Package") || isTestElement(el, registry))
            {
                kept.add(el);
            }
        }
        return new ObjectSequence(kept.toArray());
    }

    private static boolean isTestElement(Object element, TruffleModuleRegistry registry)
    {
        Object stereos = PureObj.read(element, "stereotypes");
        if (!(stereos instanceof PureSequence seq)) return false;
        for (int i = 0; i < seq.size(); i++)
        {
            Object s = seq.getBoxed(i);
            if (s == null) continue;
            String profilePath;
            String stereoName;
            String ptrPath = _PackageableElement.pointerPath(s);
            if (ptrPath != null)
            {
                profilePath = ptrPath;
                Object name = PureObj.read(s, "element");
                stereoName = name instanceof String str ? str : null;
            }
            else
            {
                Object profile = PureObj.read(s, "profile");
                profilePath = profile != null
                        ? _PackageableElement.path(profile, registry)
                        : null;
                Object value = PureObj.read(s, "value");
                stereoName = value instanceof String str ? str : null;
            }
            if (TEST_PROFILE_PATH.equals(profilePath))
            {
                return true;
            }
            if (PCT_PROFILE_PATH.equals(profilePath) && PCT_TEST_STEREOTYPES.contains(stereoName))
            {
                return true;
            }
        }
        return false;
    }
}
