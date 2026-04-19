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

package org.finos.legend.pure.truffle.codegen;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.function.property.Property;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that Class properties survive the full pipeline:
 * Pure source → compile → PDB (FlatBuffer) → read back.
 */
class PdbPropertyTest
{
    private static PureModel compiledModel;
    private static LocalModule localModule;
    private static PDBModule pdbModule;
    private static PureModel pdbModel;

    @BeforeAll
    static void setup() throws Exception
    {
        Path m3Ttl = Path.of("../../specification/m3.ttl");

        // Step 1: Compile from source
        localModule = new LocalModule("specification", "(meta::pure)(::.*)?",
                List.of(Path.of("../../specification/functions")), List.of("m3"));
        MutableList<Module> modules = Lists.mutable.with(new BootstrapModule(m3Ttl), localModule);
        compiledModel = PureModel.withModules(modules)
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        compiledModel.compile();

        // Step 2: Read back from PDB
        Path corePdb = Path.of("../../build/core.pdb");
        if (corePdb.toFile().exists())
        {
            pdbModule = new PDBModule(corePdb, PDBModule.Mode.EXECUTION,
                    "core", "*", Lists.mutable.empty());
            MutableList<Module> pdbModules = Lists.mutable.with(pdbModule);
            pdbModel = PureModel.withModules(pdbModules)
                    .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                    .build();
            pdbModel.compile();
        }
    }

    // =========================================================================
    // In-memory compiled model tests (before PDB serialization)
    // =========================================================================

    @Test
    void pairHasPropertiesInCompiledModel()
    {
        assertClassProperties("meta::pure::functions::collection::Pair",
                localModule, List.of("first", "second"));
    }

    @Test
    void listHasPropertiesInCompiledModel()
    {
        assertClassProperties("meta::pure::functions::collection::List",
                localModule, List.of("values"));
    }

    @Test
    void keyExpressionHasPropertiesInCompiledModel()
    {
        assertClassProperties("meta::pure::functions::lang::KeyExpression",
                localModule, List.of("name", "expression", "add"));
    }

    @Test
    void laPersonHasPropertiesInCompiledModel()
    {
        assertClassProperties("meta::pure::functions::lang::tests::model::LA_Person",
                localModule, List.of("firstName", "lastName"));
    }

    @Test
    void foPersonHasPropertiesInCompiledModel()
    {
        assertClassProperties("meta::pure::functions::collection::tests::fold::FO_Person",
                localModule, List.of("firstName", "lastName", "otherNames"));
    }

    // =========================================================================
    // PDB round-trip tests (after serialization + deserialization)
    // =========================================================================

    @Test
    void pairHasPropertiesInPdb()
    {
        assumePdbAvailable();
        assertClassProperties("meta::pure::functions::collection::Pair",
                pdbModule, List.of("first", "second"));
    }

    @Test
    void listHasPropertiesInPdb()
    {
        assumePdbAvailable();
        assertClassProperties("meta::pure::functions::collection::List",
                pdbModule, List.of("values"));
    }

    @Test
    void keyExpressionHasPropertiesInPdb()
    {
        assumePdbAvailable();
        assertClassProperties("meta::pure::functions::lang::KeyExpression",
                pdbModule, List.of("name", "expression", "add"));
    }

    @Test
    void laPersonHasPropertiesInPdb()
    {
        assumePdbAvailable();
        assertClassProperties("meta::pure::functions::lang::tests::model::LA_Person",
                pdbModule, List.of("firstName", "lastName"));
    }

    @Test
    void foPersonHasPropertiesInPdb()
    {
        assumePdbAvailable();
        assertClassProperties("meta::pure::functions::collection::tests::fold::FO_Person",
                pdbModule, List.of("firstName", "lastName", "otherNames"));
    }

    @Test
    void laPersonHasAssociationPropertiesInPdb()
    {
        assumePdbAvailable();
        PackageableElement elem = pdbModule.getElement(
                "meta::pure::functions::lang::tests::model::LA_Person");
        assertNotNull(elem, "LA_Person should exist in PDB");
        assertInstanceOf(meta.pure.metamodel.type.Class.class, elem);
        meta.pure.metamodel.type.Class cls = (meta.pure.metamodel.type.Class) elem;

        MutableList<Property> assocProps = cls._propertiesFromAssociations();
        assertNotNull(assocProps, "LA_Person should have association properties");
        List<String> assocPropNames = assocProps.collect(Property::_name).toList();
        System.out.println("LA_Person association props: " + assocPropNames);
        assertTrue(assocPropNames.contains("firm"),
                "LA_Person should have 'firm' association property, got: " + assocPropNames);
    }

    // =========================================================================
    // Full pipeline test: compile → write PDB → read back
    // =========================================================================

    @Test
    void pairPropertiesSurviveFullPipeline() throws Exception
    {
        Path m3Ttl = Path.of("../../specification/m3.ttl");
        LocalModule lm = new LocalModule("specification", "(meta::pure)(::.*)?",
                List.of(Path.of("../../specification/functions")), List.of("m3"));
        MutableList<Module> modules = Lists.mutable.with(new BootstrapModule(m3Ttl), lm);
        PureModel model = PureModel.withModules(modules)
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        // Step 1: Check in-memory
        PackageableElement pair = null;
        for (Module m : modules)
        {
            pair = m.getElement("meta::pure::functions::collection::Pair");
            if (pair != null)
            {
                break;
            }
        }
        assertNotNull(pair);
        meta.pure.metamodel.type.Class cls = (meta.pure.metamodel.type.Class) pair;
        int inMemoryProps = cls._properties().size();
        System.out.println("In-memory Pair props: " + inMemoryProps);
        assertEquals(2, inMemoryProps, "Pair should have 2 props in memory");

        // Step 2: Write PDB
        Path tempPdb = java.nio.file.Files.createTempFile("test-", ".pdb");
        try
        {
            var writer = new org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveWriter();

            // Collect elements same as SpecificationBinaryBuilder
            java.util.LinkedHashMap<String, PackageableElement> elementsByPath = new java.util.LinkedHashMap<>();
            for (Module module : modules)
            {
                for (String path : module.elementPaths())
                {
                    if (!elementsByPath.containsKey(path))
                    {
                        PackageableElement element = module.getElement(path);
                        if (element != null)
                        {
                            elementsByPath.put(path, element);
                        }
                    }
                }
            }
            java.util.List<PackageableElement> elements = new java.util.ArrayList<>(elementsByPath.values());
            System.out.println("Writing " + elements.size() + " elements to PDB");

            writer.write(elements, List.of(new PureLanguageExtension()), lm, tempPdb);

            // Step 3: Read back
            PDBModule readBack = new PDBModule(tempPdb, PDBModule.Mode.EXECUTION,
                    "test", "*", Lists.mutable.empty());
            MutableList<Module> readModules = Lists.mutable.with(readBack);
            PureModel readModel = PureModel.withModules(readModules)
                    .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                    .build();
            readModel.compile();

            PackageableElement pairBack = readBack.getElement("meta::pure::functions::collection::Pair");
            assertNotNull(pairBack, "Pair should exist in PDB");
            meta.pure.metamodel.type.Class clsBack = (meta.pure.metamodel.type.Class) pairBack;
            int pdbProps = clsBack._properties().size();
            System.out.println("PDB Pair props: " + pdbProps);

            // List property names
            if (pdbProps > 0)
            {
                for (Property p : clsBack._properties())
                {
                    System.out.println("  " + p._name());
                }
            }

            assertEquals(2, pdbProps, "Pair should have 2 props after PDB round-trip");
        }
        finally
        {
            java.nio.file.Files.deleteIfExists(tempPdb);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void assumePdbAvailable()
    {
        assertNotNull(pdbModule, "core.pdb not found — run 'just build-core-pdb' first");
    }

    private void assertClassProperties(String classPath, Module module, List<String> expectedProps)
    {
        PackageableElement elem = null;
        // Try finding in multiple modules
        if (module != null)
        {
            elem = module.getElement(classPath);
        }
        if (elem == null)
        {
            // Search compiled model's modules
            for (Module m : Lists.mutable.with(localModule))
            {
                elem = m.getElement(classPath);
                if (elem != null)
                {
                    break;
                }
            }
        }
        assertNotNull(elem, classPath + " should exist");
        assertInstanceOf(meta.pure.metamodel.type.Class.class, elem,
                classPath + " should be a Class, got: " + elem.getClass().getSimpleName());

        meta.pure.metamodel.type.Class cls = (meta.pure.metamodel.type.Class) elem;
        MutableList<Property> props = cls._properties();
        assertNotNull(props, classPath + "._properties() should not be null");

        List<String> propNames = props.collect(Property::_name).toList();
        System.out.println(classPath + " props: " + propNames);

        for (String expected : expectedProps)
        {
            assertTrue(propNames.contains(expected),
                    classPath + " should have property '" + expected + "', got: " + propNames);
        }
    }
}
