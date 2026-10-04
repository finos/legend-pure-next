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

import org.finos.legend.pure.m3.module.TestDependencyValidator.Caller;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that makes a narrowed lean manifest safe: nothing in a module's non-test half may
 * reach into a module declared in {@code testDependencies}.
 *
 * <p>Without this, a lean PDB could be published declaring dependencies it does not actually
 * satisfy — and the breakage would surface only on a host that loads the lean PDB alone,
 * long after the edit. A rule with no failing test is decoration, so the violating case is
 * first.</p>
 */
public class TestDependencyValidatorTest
{
    private static final List<String> TEST_DEPS = List.of("core-tests");

    /** `core-tests` owns anything under the shared test corpus; this module owns the rest. */
    private static final Function<String, String> OWNER = path ->
            path.startsWith("meta::pure::test") || path.contains("::tests::") ? "core-tests" : null;

    private static Function<String, Caller> callers(Map<String, Boolean> isTest)
    {
        return path ->
        {
            Boolean test = isTest.get(path);
            return test == null ? null : new Caller(test, null);
        };
    }

    @Test
    public void failsWhenNonTestCodeReachesIntoATestOnlyDependency()
    {
        List<CompilationError> errors = TestDependencyValidator.validate(
                Map.of("meta::pure::test::resolveChild_X_1__Y_1_", Set.of("m::translatePackage_String_1__String_1_")),
                TEST_DEPS, OWNER,
                callers(Map.of("m::translatePackage_String_1__String_1_", false)));
        assertEquals(1, errors.size());
        String message = errors.get(0).message();
        assertTrue(message.contains("m::translatePackage_String_1__String_1_"), message);
        assertTrue(message.contains("core-tests"), message);
        // The message has to say what to do, not just what is wrong.
        assertTrue(message.contains("test.TestDependency"), message);
        assertTrue(message.contains("dependencies"), message);
    }

    @Test
    public void allowsTestCodeToReachIntoATestOnlyDependency()
    {
        // This is the whole point of the field — the test half may use it freely.
        assertEquals(List.of(), TestDependencyValidator.validate(
                Map.of("meta::pure::test::resolveChild_X_1__Y_1_", Set.of("m::renderGallery_String_1__String_1_")),
                TEST_DEPS, OWNER,
                callers(Map.of("m::renderGallery_String_1__String_1_", true))));
    }

    @Test
    public void ignoresReferencesIntoModulesThatAreOrdinaryDependencies()
    {
        assertEquals(List.of(), TestDependencyValidator.validate(
                Map.of("meta::pure::functions::collection::map_T_m__Function_1__V_m_", Set.of("m::f__String_1_")),
                TEST_DEPS, OWNER,
                callers(Map.of("m::f__String_1_", false))));
    }

    @Test
    public void ignoresCallersItCannotClassify()
    {
        // A native, or an element of another module: not ours to police.
        assertEquals(List.of(), TestDependencyValidator.validate(
                Map.of("meta::pure::test::resolveChild_X_1__Y_1_", Set.of("somewhere::else_X_1_")),
                TEST_DEPS, OWNER, callers(Map.of())));
    }

    @Test
    public void doesNothingWhenNoTestOnlyDependenciesAreDeclared()
    {
        // Every module without the field must behave exactly as before.
        assertEquals(List.of(), TestDependencyValidator.validate(
                Map.of("meta::pure::test::resolveChild_X_1__Y_1_", Set.of("m::f__String_1_")),
                List.of(), OWNER,
                callers(Map.of("m::f__String_1_", false))));
    }

    @Test
    public void attributesASubElementReferenceToItsParent()
    {
        // `Foo.bar` does not resolve on its own; the owner and the caller are both the parent.
        List<CompilationError> errors = TestDependencyValidator.validate(
                Map.of("meta::pure::functions::collection::tests::model::CO_Person.firstName",
                       Set.of("m::render_String_1_.inner")),
                TEST_DEPS, OWNER,
                callers(Map.of("m::render_String_1_", false)));
        assertEquals(1, errors.size());
    }

    @Test
    public void reportsViolationsInDeterministicOrder()
    {
        // The reverse index is built in whatever order references were recorded; the output
        // must not be, or the error list churns between compilers.
        List<CompilationError> errors = TestDependencyValidator.validate(
                Map.of("meta::pure::test::a_X_1_", Set.of("m::zeta__String_1_", "m::alpha__String_1_")),
                TEST_DEPS, OWNER,
                callers(Map.of("m::zeta__String_1_", false, "m::alpha__String_1_", false)));
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).message().contains("m::alpha"), errors.get(0).message());
        assertTrue(errors.get(1).message().contains("m::zeta"), errors.get(1).message());
    }

    @Test
    public void handlesAnEmptyOrNullIndex()
    {
        assertEquals(List.of(), TestDependencyValidator.validate(Map.of(), TEST_DEPS, OWNER, callers(Map.of())));
        assertEquals(List.of(), TestDependencyValidator.validate(null, TEST_DEPS, OWNER, callers(Map.of())));
    }
}
