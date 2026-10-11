// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.m3;

import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.sourceModule.SourceModule;
import org.finos.legend.pure.m3.module.sourceModule.PureContent;
import org.finos.legend.pure.m3.module.pdbModule.PdbModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests demonstrating JavaCompiler usage: building, compiling,
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
        JavaCompiler model = JavaCompiler.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new SourceModule("test", "*", Lists.mutable.with("m3"), Lists.mutable.with()))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();

        assertTrue(result.errors().isEmpty(), "Empty model should compile without errors");
        assertNotNull(model.root(), "Root package should exist");
    }

    @Test
    void compileClassAndResolveFromModule()
    {
        JavaCompiler model = JavaCompiler.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new SourceModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Class platform::pure::MyClass { name : String[1]; }", "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(),
                "Compilation errors: " + result.errors());

        // Resolve the compiled class from the test module
        Module testModule = model.registry().module("test");
        assertNotNull(testModule, "test module should exist");
        assertNotNull(testModule.getElement("platform::pure::MyClass"),
                "MyClass should be resolvable from the test module");
        assertEquals("MyClass", testModule.getElement("platform::pure::MyClass")._name());
    }

    @Test
    void compileEnumerationAndResolve()
    {
        JavaCompiler model = JavaCompiler.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new SourceModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Enum platform::pure::MyColor { RED, GREEN, BLUE }", "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(),
                "Compilation errors: " + result.errors());

        Module testModule = model.registry().module("test");
        assertNotNull(testModule.getElement("platform::pure::MyColor"));
        assertInstanceOf(platform.pure.metamodel.type.Enumeration.class,
                testModule.getElement("platform::pure::MyColor"));
    }

    // -----------------------------------------------------------------------
    // Module scoping
    // -----------------------------------------------------------------------

    @Test
    void bootstrapTypesVisibleThroughDependency()
    {
        JavaCompiler model = JavaCompiler.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new SourceModule("test", "*", Lists.mutable.with("m3"), Lists.mutable.with()))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();
        model.compile();

        // The bootstrap module provides core types
        Module bootstrap = model.registry().module("m3");
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

        // Verify PackageableGenericType anchors are bootstrapped. The anchor name
        // now encodes the type's full Pure path (`::` -> `_`) so collisions
        // between user modules and platform types can't pick the wrong anchor.
        String siAnchor = "platform::pure::metamodel::type::generics::optimization::GenericType_platform_pure_metamodel_SourceInformation";
        assertTrue(bootstrap.elementPaths().stream().anyMatch(p -> p.equals(siAnchor)),
                "Bootstrap should contain " + siAnchor);
        assertNotNull(bootstrap.getElement(siAnchor),
                siAnchor + " should be resolvable by full path");
        assertInstanceOf(platform.pure.metamodel.type.generics.PackageableGenericType.class,
                bootstrap.getElement(siAnchor),
                siAnchor + " should be a PackageableGenericType");
        platform.pure.metamodel.type.generics.PackageableGenericType sourceInfoGT =
                (platform.pure.metamodel.type.generics.PackageableGenericType) bootstrap.getElement(siAnchor);
        assertNotNull(sourceInfoGT._type(), siAnchor + " should have its type wired");
        assertEquals("SourceInformation", ((platform.pure.metamodel.PackageableElement) sourceInfoGT._type())._name(), siAnchor + " should point to SourceInformation type");
    }

    @Test
    void moduleCanAccessItsOwnElements()
    {
        JavaCompiler model = JavaCompiler.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new SourceModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Class platform::pure::A { name : String[1]; }", "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();
        model.compile();

        Module testModule = model.registry().module("test");
        assertTrue(testModule.hasElement("platform::pure::A"));
        assertTrue(testModule.elementPaths().contains("platform::pure::A"));
    }

    @Test
    void moduleCannotSeeElementsFromNonDependency()
    {
        JavaCompiler model = JavaCompiler.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new SourceModule("moduleA", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Class platform::pure::A {}", "a.pure"))),
                        new SourceModule("moduleB", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Class platform::pure::B {}", "b.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();
        model.compile();

        Module modA = model.registry().module("moduleA");
        Module modB = model.registry().module("moduleB");

        // Each module sees its own elements
        assertTrue(modA.hasElement("platform::pure::A"));
        assertTrue(modB.hasElement("platform::pure::B"));

        // But not the other module's elements (no dependency declared)
        assertFalse(modA.hasElement("platform::pure::B"));
        assertFalse(modB.hasElement("platform::pure::A"));
    }


    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    @Test
    void compilationErrorForUnknownType()
    {
        JavaCompiler model = JavaCompiler.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new SourceModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Class platform::pure::Bad { value : UnknownType[1]; }", "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertFalse(result.errors().isEmpty(),
                "Should have compilation errors for unknown type");
    }

    @Test
    void packagePatternValidation()
    {
        JavaCompiler model = JavaCompiler.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new SourceModule("test", "(platform::pure)(::.*)?", Lists.mutable.with("m3"),
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
        JavaCompiler model = JavaCompiler.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new SourceModule("moduleA", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent("Class platform::pure::Base {}", "a.pure"))),
                        new SourceModule("moduleB", "*", Lists.mutable.with("m3", "moduleA"),
                                Lists.mutable.with(new PureContent("Class platform::pure::Child extends platform::pure::Base {}", "b.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(),
                "Cross-module class inheritance should compile: " + result.errors());

        // moduleB can see Base from moduleA
        Module modB = model.registry().module("moduleB");
        assertNotNull(modB);
    }

    // -----------------------------------------------------------------------
    // PDB module (core.pdb)
    // -----------------------------------------------------------------------

    @Test
    void compileAgainstPdbModule() throws IOException
    {
        PdbModule specModule = new PdbModule(
                BootstrapModule.locateCorePdb(),
                PdbModule.Mode.COMPILATION);

        // A local module that depends on the PDB-backed specification
        // and compiles a function calling filter (provided by the spec)
        String source = "function platform::pure::test::keepPositive(numbers: Integer[*]): Integer[*]\n"
                + "{\n"
                + "  $numbers->filter(n | $n > 0);\n"
                + "}\n";

        JavaCompiler model = JavaCompiler.withModules(
                Lists.mutable.with(
                        new SourceModule("test", "*",
                                Lists.mutable.with(specModule.name()),
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
        Module testModule = model.registry().module("test");
        assertNotNull(testModule);
        assertTrue(testModule.hasElement("platform::pure::test::keepPositive_Integer_MANY__Integer_MANY_"),
                "Compiled function should be resolvable from the test module");
    }
}

