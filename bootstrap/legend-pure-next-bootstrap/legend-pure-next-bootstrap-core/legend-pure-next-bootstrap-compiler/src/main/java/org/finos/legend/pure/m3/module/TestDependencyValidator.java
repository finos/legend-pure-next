// ©2026 JP Morgan Chase & Co. All rights reserved.
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

import meta.pure.metamodel.SourceInformation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Enforces the {@code testDependencies} contract ACROSS modules: a module that declares a
 * dependency as test-only must not reference it from its non-test half.
 *
 * <p>This is what makes a narrowed lean manifest safe rather than merely smaller. A
 * {@code --tests split} build writes {@code testDependencies} into the {@code -tests}
 * archive's dependencies and leaves them out of the lean archive, so a consumer may load the
 * lean PDB alone. If a non-test element referenced one of those modules, that consumer would
 * get a dangling reference at run time — far from the edit that caused it, and only on the
 * hosts that load the lean PDB by itself. Caught here instead, at the build that introduces
 * it.</p>
 *
 * <p>The sibling {@link LeanReferencesValidator} enforces the same boundary WITHIN a module
 * (non-test must not reference test-only). This is its cross-module twin: same reverse index,
 * same {@link TestElementFilter} classification of the caller, but the target is classified
 * by which MODULE owns it rather than by its stereotypes.</p>
 */
public final class TestDependencyValidator
{
    private TestDependencyValidator()
    {
    }

    /**
     * What the validator needs to know about a caller: whether it is test code, and where it
     * is. Deliberately not a {@code PackageableElement} — keeping the metamodel out of here
     * is what lets the rule be unit-tested with plain values.
     *
     * @param isTestElement whether the caller is test-only ({@code TestElementFilter})
     * @param sourceInformation where to point the error; may be null
     */
    public record Caller(boolean isTestElement, SourceInformation sourceInformation)
    {
    }

    /**
     * Validate the reverse index against the module's declared test-only dependencies.
     *
     * @param referencedBy reverse reference index: target path → set of caller paths
     * @param testDependencies module names the manifest declares as test-only; empty
     *        disables the check entirely
     * @param moduleOfTarget maps a target element path to the name of the module that owns
     *        it, or {@code null} when no loaded module claims it (a local or built-in path)
     * @param callerOf describes a caller path, or returns {@code null} when the path is not
     *        an element of the module being built (a native, or another module's element) —
     *        the boundary can only be enforced on code we are compiling
     * @return one error per non-test → test-only-module reference, in deterministic order
     */
    public static List<CompilationError> validate(
            Map<String, Set<String>> referencedBy,
            Collection<String> testDependencies,
            Function<String, String> moduleOfTarget,
            Function<String, Caller> callerOf)
    {
        List<CompilationError> errors = new ArrayList<>();
        if (referencedBy == null || referencedBy.isEmpty()
                || testDependencies == null || testDependencies.isEmpty())
        {
            return errors;
        }

        // Sorted (caller, target) so the output is deterministic regardless of the
        // recording order the index happened to be built in — the same reason
        // LeanReferencesValidator sorts.
        List<String[]> pairs = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : referencedBy.entrySet())
        {
            String targetPath = entry.getKey();
            if (targetPath == null || entry.getValue() == null)
            {
                continue;
            }
            for (String callerPath : entry.getValue())
            {
                if (callerPath != null)
                {
                    pairs.add(new String[]{callerPath, targetPath});
                }
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

            String owner = moduleOfTarget.apply(stripSubPath(targetPath));
            if (owner == null || !testDependencies.contains(owner))
            {
                continue;
            }
            Caller caller = callerOf.apply(stripSubPath(callerPath));
            // A caller we cannot classify is a native or a path from another module; the
            // boundary can only be enforced on elements of the module being built.
            if (caller == null || caller.isTestElement())
            {
                continue;
            }
            String message = "Non-test element '" + callerPath + "' references '" + targetPath
                    + "' from module '" + owner + "', which is declared as a test-only"
                    + " dependency (testDependencies). Either mark the caller"
                    + " <<test.TestDependency>>, or move '" + owner + "' into 'dependencies'.";
            errors.add(new CompilationError(message, caller.sourceInformation()));
        }
        return errors;
    }

    /**
     * Strip a sub-element suffix ({@code Foo.bar}, {@code MyEnum.VAL}) — only the parent
     * element exists to be resolved or owned.
     */
    private static String stripSubPath(String path)
    {
        if (path == null || path.isEmpty())
        {
            return path;
        }
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(0, dot) : path;
    }
}
