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
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.module.pdbModule.archive.PDBArchiveSection;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageSerialization;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdbRoundTripTest
{
    @Test
    void pairPropertiesSurviveRoundTrip() throws Exception
    {
        Path m3Ttl = Path.of("../../specification/m3.ttl");
        LocalModule localModule = new LocalModule("specification", "(meta::pure)(::.*)?",
                List.of(Path.of("../../specification/functions")), List.of("m3"));
        var modules = Lists.mutable.<Module>with(new BootstrapModule(m3Ttl), localModule);
        var model = PureModel.withModules(modules)
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        // Get Pair before serialization
        PackageableElement pair = null;
        for (Module m : modules)
        {
            pair = m.getElement("meta::pure::functions::collection::Pair");
            if (pair != null)
            {
                break;
            }
        }
        assertNotNull(pair, "Pair should exist in compiled model");
        meta.pure.metamodel.type.Class cls = (meta.pure.metamodel.type.Class) pair;

        // Before: should have 2 properties
        System.out.println("Before serialize - props: " + cls._properties().size());
        assertEquals(2, cls._properties().size(), "Pair should have 2 properties before serialization");
        for (Property p : cls._properties())
        {
            System.out.println("  " + p._name());
        }

        // Serialize
        PureLanguageSerialization serialization = new PureLanguageSerialization();
        PDBArchiveSection section = serialization.serialize(pair);
        assertNotNull(section, "Serialization should produce data");
        System.out.println("Serialized type=" + section.name() + " size=" + section.data().length);

        // Deserialize
        var resolver = new ScopedMetadataAccess(localModule, model);
        PackageableElement deserialized = serialization.deserialize(section.name(), section.data(), resolver);
        assertNotNull(deserialized, "Deserialization should produce an element");
        assertInstanceOf(meta.pure.metamodel.type.Class.class, deserialized);

        meta.pure.metamodel.type.Class cls2 = (meta.pure.metamodel.type.Class) deserialized;
        System.out.println("After deserialize - props: " + cls2._properties().size());
        for (Property p : cls2._properties())
        {
            System.out.println("  " + p._name());
        }

        // After: should STILL have 2 properties
        assertEquals(2, cls2._properties().size(),
                "Pair should have 2 properties after deserialization — properties are lost in FlatBuffer round-trip!");
    }

    @Test
    void pairPropertiesSurviveFullPdbPipeline() throws Exception
    {
        Path m3Ttl = Path.of("../../specification/m3.ttl");
        LocalModule lm = new LocalModule("specification", "(meta::pure)(::.*)?",
                java.util.List.of(Path.of("../../specification/functions")), java.util.List.of("m3"));
        var modules = Lists.mutable.<Module>with(new BootstrapModule(m3Ttl), lm);
        var model = PureModel.withModules(modules)
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        // In-memory: Pair has 2 properties
        PackageableElement pair = null;
        for (Module m : modules) { pair = m.getElement("meta::pure::functions::collection::Pair"); if (pair != null) break; }
        assertNotNull(pair);
        assertEquals(2, ((meta.pure.metamodel.type.Class) pair)._properties().size(), "In-memory Pair should have 2 props");

        // Write full PDB (same as compile-spec)
        Path tempPdb = java.nio.file.Files.createTempFile("test-", ".pdb");
        try
        {
            var writer = new org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveWriter();
            java.util.LinkedHashMap<String, PackageableElement> elementsByPath = new java.util.LinkedHashMap<>();
            for (Module module : modules)
            {
                for (String path : module.elementPaths())
                {
                    if (!elementsByPath.containsKey(path))
                    {
                        PackageableElement element = module.getElement(path);
                        if (element != null) { elementsByPath.put(path, element); }
                    }
                }
            }
            writer.write(new java.util.ArrayList<>(elementsByPath.values()),
                    java.util.List.of(new PureLanguageExtension()), lm, tempPdb);

            // Read back
            var readBack = new org.finos.legend.pure.m3.module.pdbModule.PDBModule(
                    tempPdb, org.finos.legend.pure.m3.module.pdbModule.PDBModule.Mode.EXECUTION,
                    "test", "*", Lists.mutable.empty());
            var readModel = PureModel.withModules(Lists.mutable.<Module>with(readBack))
                    .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                    .build();
            readModel.compile();

            PackageableElement pairBack = readBack.getElement("meta::pure::functions::collection::Pair");
            assertNotNull(pairBack, "Pair should exist in PDB");
            var clsBack = (meta.pure.metamodel.type.Class) pairBack;
            System.out.println("PDB Pair props: " + clsBack._properties().size());
            for (var p : clsBack._properties()) { System.out.println("  " + p._name()); }
            assertEquals(2, clsBack._properties().size(), "Pair should have 2 props after full PDB pipeline");
        }
        finally
        {
            java.nio.file.Files.deleteIfExists(tempPdb);
        }
    }

    @Test
    void pairHasQualifiedProperties() throws Exception
    {
        Path m3Ttl = Path.of("../../specification/m3.ttl");
        LocalModule localModule = new LocalModule("specification", "(meta::pure)(::.*)?",
                java.util.List.of(Path.of("../../specification/functions")), java.util.List.of("m3"));
        var modules = Lists.mutable.<Module>with(new BootstrapModule(m3Ttl), localModule);
        var model = PureModel.withModules(modules)
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        // Serialize and deserialize
        PureLanguageSerialization serialization = new PureLanguageSerialization();
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
        PDBArchiveSection section = serialization.serialize(pair);
        var resolver = new ScopedMetadataAccess(localModule, model);
        PackageableElement deserialized = serialization.deserialize(section.name(), section.data(), resolver);

        meta.pure.metamodel.type.Class cls = (meta.pure.metamodel.type.Class) deserialized;
        System.out.println("QPs: " + (cls._qualifiedProperties() != null ? cls._qualifiedProperties().size() : "null"));
        if (cls._qualifiedProperties() != null)
        {
            for (var qp : cls._qualifiedProperties())
            {
                System.out.println("  " + qp._name());
            }
        }
        assertNotNull(cls._qualifiedProperties());
        assertTrue(cls._qualifiedProperties().size() > 0, "Pair should have toString() QP");
    }
}
