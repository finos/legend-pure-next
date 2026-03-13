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

package org.finos.legend.pure.m3;

import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.PureContent;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests demonstrating PureModel usage: building, compiling,
 * module-scoped element resolution, and error handling.
 */
class TestPureModel
{
    // -----------------------------------------------------------------------
    // Basic compilation
    // -----------------------------------------------------------------------

    @Test
    void emptyModelCompilesSuccessfully()
    {
        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("test", "*", Lists.mutable.with("m3"), Lists.mutable.with()))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();

        assertTrue(result.errors().isEmpty(), "Empty model should compile without errors");
        assertNotNull(model._root(), "Root package should exist");
    }

    @Test
    void compileClassAndResolveFromModule()
    {
        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Class meta::pure::MyClass { name : String[1]; }", "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(),
                "Compilation errors: " + result.errors());

        // Resolve the compiled class from the test module
        Module testModule = model.getModule("test");
        assertNotNull(testModule, "test module should exist");
        assertNotNull(testModule.getElement("meta::pure::MyClass"),
                "MyClass should be resolvable from the test module");
        assertEquals("MyClass", testModule.getElement("meta::pure::MyClass")._name());
    }

    @Test
    void compileEnumerationAndResolve()
    {
        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Enum meta::pure::MyColor { RED, GREEN, BLUE }", "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(),
                "Compilation errors: " + result.errors());

        Module testModule = model.getModule("test");
        assertNotNull(testModule.getElement("meta::pure::MyColor"));
        assertInstanceOf(meta.pure.metamodel.type.Enumeration.class,
                testModule.getElement("meta::pure::MyColor"));
    }

    // -----------------------------------------------------------------------
    // Module scoping
    // -----------------------------------------------------------------------

    @Test
    void bootstrapTypesVisibleThroughDependency()
    {
        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("test", "*", Lists.mutable.with("m3"), Lists.mutable.with()))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();
        model.compile();

        // The bootstrap module provides core types
        Module bootstrap = model.getModule("m3");
        assertNotNull(bootstrap);
        assertFalse(bootstrap.elementPaths().isEmpty(),
                "Bootstrap should contain elements");
        // Verify some core types exist by short suffix match
        assertTrue(bootstrap.elementPaths().stream().anyMatch(p -> p.endsWith("::String")),
                "Bootstrap should contain a String type");
        assertTrue(bootstrap.elementPaths().stream().anyMatch(p -> p.endsWith("::Integer")),
                "Bootstrap should contain an Integer type");
        assertTrue(bootstrap.elementPaths().stream().anyMatch(p -> p.endsWith("::Any")),
                "Bootstrap should contain an Any type");
    }

    @Test
    void moduleCanAccessItsOwnElements()
    {
        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Class meta::pure::A { name : String[1]; }", "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();
        model.compile();

        Module testModule = model.getModule("test");
        assertTrue(testModule.hasElement("meta::pure::A"));
        assertTrue(testModule.elementPaths().contains("meta::pure::A"));
    }

    @Test
    void moduleCannotSeeElementsFromNonDependency()
    {
        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("moduleA", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Class meta::pure::A {}", "a.pure"))),
                        new LocalModule("moduleB", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Class meta::pure::B {}", "b.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();
        model.compile();

        Module modA = model.getModule("moduleA");
        Module modB = model.getModule("moduleB");

        // Each module sees its own elements
        assertTrue(modA.hasElement("meta::pure::A"));
        assertTrue(modB.hasElement("meta::pure::B"));

        // But not the other module's elements (no dependency declared)
        assertFalse(modA.hasElement("meta::pure::B"));
        assertFalse(modB.hasElement("meta::pure::A"));
    }


    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    @Test
    void compilationErrorForUnknownType()
    {
        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Class meta::pure::Bad { value : UnknownType[1]; }", "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertFalse(result.errors().isEmpty(),
                "Should have compilation errors for unknown type");
    }

    @Test
    void packagePatternValidation()
    {
        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("test", "(meta::pure)(::.*)?", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Class wrong::path::MyClass {}", "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertFalse(result.errors().isEmpty(),
                "Should reject elements outside the module's package pattern");
        assertTrue(result.errors().get(0).message().contains("does not match module package pattern"));
    }

    // -----------------------------------------------------------------------
    // Topological ordering
    // -----------------------------------------------------------------------

    @Test
    void modulesCompiledInDependencyOrder()
    {
        // moduleB depends on moduleA
        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("moduleA", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Class meta::pure::Base {}", "a.pure"))),
                        new LocalModule("moduleB", "*", Lists.mutable.with("m3", "moduleA"),
                                Lists.mutable.with(new PureContent("Class meta::pure::Child extends meta::pure::Base {}", "b.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(),
                "Cross-module class inheritance should compile: " + result.errors());

        // moduleB can see Base from moduleA
        Module modB = model.getModule("moduleB");
        assertNotNull(modB);
    }

    // -----------------------------------------------------------------------
    // PDB module (core.pdb)
    // -----------------------------------------------------------------------

    @Test
    void compileAgainstPdbModule() throws IOException
    {
        PDBModule specModule = new PDBModule(
                Path.of("target/core.pdb"),
                PDBModule.Mode.COMPILATION);

        // A local module that depends on the PDB-backed specification
        // and compiles a function calling filter (provided by the spec)
        String source = "function meta::pure::test::keepPositive(numbers: Integer[*]): Integer[*]\n"
                + "{\n"
                + "  $numbers->filter(n | $n > 0);\n"
                + "}\n";

        PureModel model = PureModel.withModules(
                Lists.mutable.with(
                        new LocalModule("test", "*",
                                Lists.mutable.with(specModule.getName()),
                                Lists.mutable.with(new PureContent(source, "test.pure"))),
                        specModule))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(),
                "Compilation against PDB module should succeed: " + result.errors());

        // The PDB module should expose its element index
        assertFalse(specModule.elementPaths().isEmpty(),
                "PDB module should contain elements");

        // The locally compiled function should be resolvable from the test module
        Module testModule = model.getModule("test");
        assertNotNull(testModule);
        assertTrue(testModule.hasElement("meta::pure::test::keepPositive_Integer_MANY__Integer_MANY_"),
                "Compiled function should be resolvable from the test module");
    }
}

