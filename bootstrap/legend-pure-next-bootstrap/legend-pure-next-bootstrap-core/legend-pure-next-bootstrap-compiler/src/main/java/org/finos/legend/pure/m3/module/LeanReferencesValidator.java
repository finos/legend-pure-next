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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Validates the reverse reference index against the lean/tests boundary.
 * For each (target, caller) pair recorded in {@code referencedBy}, fails when a
 * non-test caller references a test-only target. Mirrors the contract codified
 * by {@code leanReferencesTest_E_nonTestReferencesTestElement.pure}.
 *
 * <p>Classification uses {@link TestElementFilter#isTestElement} — anything
 * stereotyped under {@code meta::pure::profiles::test} or the test-only PCT
 * stereotypes is test-only.</p>
 *
 * <p>Source-info attribution uses the <em>caller element's</em>
 * {@code _sourceInformation} (function/class definition line). The reverse
 * index doesn't track per-reference call-site locations; tightening this
 * would require enriching the index with per-reference {@code SourceInformation}.</p>
 */
public final class LeanReferencesValidator
{
    private LeanReferencesValidator()
    {
    }

    /**
     * Validate the reverse index and return a list of violations.
     *
     * @param referencedBy reverse reference index: target_path → set of caller paths
     * @param resolveElement maps an element path to its compiled
     *        {@link PackageableElement}; returns {@code null} when the path
     *        is unknown (e.g. it's a stale reference or built-in)
     * @return list of compilation errors, one per non-test → test reference
     */
    public static List<CompilationError> validate(
            Map<String, Set<String>> referencedBy,
            Function<String, PackageableElement> resolveElement)
    {
        List<CompilationError> errors = new ArrayList<>();
        if (referencedBy == null || referencedBy.isEmpty())
        {
            return errors;
        }
        // Iterate in sorted (caller, target) order so output is deterministic
        // across Java/Pure compilers and resilient to recording-order
        // differences (HashMap/LinkedHashSet vs Pure Map<K, List<V>>).
        java.util.List<String[]> pairs = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : referencedBy.entrySet())
        {
            String targetPath = entry.getKey();
            for (String callerPath : entry.getValue())
            {
                pairs.add(new String[]{callerPath, targetPath});
            }
        }
        pairs.sort((a, b) ->
        {
            int byCaller = a[0].compareTo(b[0]);
            return byCaller != 0 ? byCaller : a[1].compareTo(b[1]);
        });
        for (String[] pair : pairs)
        {
            String callerPath = pair[0];
            String targetPath = pair[1];
            PackageableElement targetElement = resolveElementWithSubPath(targetPath, resolveElement);
            if (targetElement == null || !TestElementFilter.isTestElement(targetElement))
            {
                continue;
            }
            PackageableElement callerElement = resolveElementWithSubPath(callerPath, resolveElement);
            if (callerElement == null)
            {
                // Caller path refers to nothing in the current module's
                // element map — happens for built-in/native callers that
                // get recorded but don't live as PackageableElements here.
                // Skip rather than flag — we can only enforce the lean
                // boundary on elements we can classify.
                continue;
            }
            if (TestElementFilter.isTestElement(callerElement))
            {
                continue;
            }
            String message = "Non-test element '" + callerPath
                    + "' references test-only element '" + targetPath + "'";
            errors.add(new CompilationError(message, callerElement._sourceInformation()));
        }
        return errors;
    }

    /**
     * Strip sub-element suffix (after the last {@code .}) and look up the
     * parent element. Sub-element paths like {@code Foo.bar} or
     * {@code MyEnum.VAL} don't resolve directly — their parent
     * {@code Foo}/{@code MyEnum} carries the stereotypes that classify them.
     */
    private static PackageableElement resolveElementWithSubPath(String path,
            Function<String, PackageableElement> resolveElement)
    {
        if (path == null || path.isEmpty())
        {
            return null;
        }
        int dot = path.lastIndexOf('.');
        String parentPath = dot >= 0 ? path.substring(0, dot) : path;
        return resolveElement.apply(parentPath);
    }
}
