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

package org.finos.legend.pure.m3.module;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.extension.ElementWithStereotypes;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;

import java.nio.file.Path;
import java.util.List;

/**
 * Selects which compiled elements end up in which output PDB based on whether
 * they carry a stereotype from {@code meta::pure::profiles::test}.
 *
 * <p>The four modes drive both element selection and output filename(s):</p>
 * <ul>
 *   <li>{@link Mode#NONE}: strip test-annotated elements; emit a single lean
 *       PDB at the given output path. The {@code test} profile itself is kept
 *       so dependent PDBs can still reference it.</li>
 *   <li>{@link Mode#WITH}: keep everything; emit one PDB whose filename has
 *       {@code -with-tests} inserted before {@code .pdb}.</li>
 *   <li>{@link Mode#ONLY}: keep only test-annotated elements; emit one PDB
 *       whose filename has {@code -tests} inserted before {@code .pdb}.</li>
 *   <li>{@link Mode#SPLIT}: emit two PDBs — the lean output at the given path
 *       and the test-only companion at the {@code -tests} variant.</li>
 * </ul>
 *
 * <p>"Test-annotated" means the element implements {@link ElementWithStereotypes}
 * and at least one of its stereotypes resolves to profile
 * {@code meta::pure::profiles::test}. The {@code test} profile element itself
 * is always treated as non-test so the lean PDB carries it.</p>
 */
public final class TestElementFilter
{
    /**
     * Stereotypes from this profile are <em>all</em> test-related — every
     * element annotated by any stereotype here is filtered from lean.
     * (Members: Test, TestDependency, BeforePackage, AfterPackage, ToFix.)
     */
    public static final String TEST_PROFILE_PATH = "meta::pure::profiles::test";

    /**
     * PCT profile path. Only a <em>subset</em> of its stereotypes is
     * test-only — see {@link #PCT_TEST_STEREOTYPES}.
     */
    public static final String PCT_PROFILE_PATH = "meta::pure::test::pct::PCT";

    /**
     * Stereotypes on {@link #PCT_PROFILE_PATH} that mark test infrastructure
     * (test functions and adapters). Other PCT stereotypes — {@code function},
     * {@code platformOnly}, {@code testQualifierProfile} — annotate real
     * platform code (e.g. {@code map}, {@code filter}, {@code sort}) and
     * must stay in lean PDB.
     */
    public static final java.util.Set<String> PCT_TEST_STEREOTYPES =
            java.util.Set.of("test", "adapter");

    public enum Mode
    {
        /** Strip tests; emit lean PDB at the given output path. */
        NONE,
        /** Keep everything; emit one PDB with {@code -with-tests} suffix. */
        WITH,
        /** Keep only tests; emit one PDB with {@code -tests} suffix. */
        ONLY,
        /** Emit both lean PDB and tests-only PDB. */
        SPLIT;

        public static Mode parse(String s)
        {
            return switch (s)
            {
                case "none" -> NONE;
                case "with" -> WITH;
                case "only" -> ONLY;
                case "split" -> SPLIT;
                default -> throw new IllegalArgumentException(
                        "Invalid --tests value '" + s + "' (expected: none, with, only, split)");
            };
        }
    }

    private TestElementFilter()
    {
    }

    /**
     * @return {@code true} when {@code element} carries any test-related
     *         stereotype: anything on {@link #TEST_PROFILE_PATH}, or one of
     *         {@link #PCT_TEST_STEREOTYPES} on {@link #PCT_PROFILE_PATH}.
     *         Other PCT stereotypes (e.g. {@code <<PCT.function>>} on
     *         {@code map}/{@code filter}) annotate real platform code and
     *         do <em>not</em> mark an element as test-related.
     */
    public static boolean isTestElement(PackageableElement element)
    {
        if (!(element instanceof ElementWithStereotypes annotated))
        {
            return false;
        }
        if (annotated._stereotypes() == null || annotated._stereotypes().isEmpty())
        {
            return false;
        }
        return annotated._stereotypes().anySatisfy(TestElementFilter::isTestStereotype);
    }

    /**
     * @return {@code true} when {@code s} is a test-marking stereotype:
     *         any stereotype on the test profile, or {@code test}/{@code adapter}
     *         on the PCT profile.
     */
    private static boolean isTestStereotype(meta.pure.metamodel.extension.Stereotype s)
    {
        if (s == null || s._profile() == null) return false;
        String profilePath = _PackageableElement.path(s._profile());
        if (TEST_PROFILE_PATH.equals(profilePath))
        {
            return true;
        }
        if (PCT_PROFILE_PATH.equals(profilePath) && s._value() != null
                && PCT_TEST_STEREOTYPES.contains(s._value()))
        {
            return true;
        }
        return false;
    }

    /**
     * Derive the {@code -with-tests} variant of the given output path.
     * Example: {@code shared/core.pdb} → {@code shared/core-with-tests.pdb}.
     */
    public static Path withTestsPath(Path outputPath)
    {
        return suffixed(outputPath, "-with-tests");
    }

    /**
     * Derive the {@code -tests} variant of the given output path.
     * Example: {@code shared/core.pdb} → {@code shared/core-tests.pdb}.
     */
    public static Path testsOnlyPath(Path outputPath)
    {
        return suffixed(outputPath, "-tests");
    }

    private static Path suffixed(Path outputPath, String suffix)
    {
        String fileName = outputPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot < 0 ? fileName : fileName.substring(0, dot);
        String ext = dot < 0 ? "" : fileName.substring(dot);
        Path parent = outputPath.getParent();
        String decorated = stem + suffix + ext;
        return parent == null ? Path.of(decorated) : parent.resolve(decorated);
    }

    /**
     * Build the manifest for the test-only companion PDB: name is
     * {@code <originalName>-tests}, and the original module becomes a
     * dependency (since test elements reference non-test ones).
     */
    public static org.finos.legend.pure.m3.module.ModuleManifest testsManifest(
            org.finos.legend.pure.m3.module.ModuleManifest base)
    {
        List<String> deps = new java.util.ArrayList<>(base.dependencies());
        if (!deps.contains(base.name()))
        {
            deps.add(base.name());
        }
        return new org.finos.legend.pure.m3.module.ModuleManifest(
                base.name() + "-tests", base.packagePattern(), deps);
    }
}
