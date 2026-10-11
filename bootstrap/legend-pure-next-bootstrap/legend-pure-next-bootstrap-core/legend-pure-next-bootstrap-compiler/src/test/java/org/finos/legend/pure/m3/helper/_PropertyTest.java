// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.m3.helper;

import platform.pure.metamodel.function.property.Property;
import platform.pure.metamodel.type.Class;
import platform.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.JavaCompiler;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.sourceModule.SourceModule;
import org.finos.legend.pure.m3.module.sourceModule.PureContent;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Property;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class _PropertyTest
{
    private static JavaCompiler model;
    private static Class testClass;
    private static Class myClass;

    @BeforeAll
    public static void setUp()
    {
        String source = 
            "Class test::MyClass<T, U|m>\n" +
            "{\n" +
            "}\n" +
            "Class test::TestClass\n" +
            "{\n" +
            "    prop: test::MyClass<String, Integer | 1..*>[1];\n" +
            "}\n";

        model = JavaCompiler.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new SourceModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent(source, "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());

        testClass = (Class) model.registry().module("test").getElement("test::TestClass");
        myClass = (Class) model.registry().module("test").getElement("test::MyClass");
    }

    @Test
    public void testResolveProperty()
    {
        Property prop = testClass._properties().detect(p -> "prop".equals(p._name()));
        GenericType receiverType = prop._genericType();
        
        ScopedMetadataAccess metadataAccess = new ScopedMetadataAccess(model.registry().module("test"), model.registry());
        
        Property sourceProp = new platform.pure.metamodel.function.property.PropertyImpl()
                ._name("mockProp")
                ._owner(myClass)
                ._genericType(_GenericType.buildUserDefinedGenericType(testClass, metadataAccess))
                ._multiplicity((platform.pure.metamodel.multiplicity.Multiplicity) metadataAccess.getElement("platform::pure::metamodel::multiplicity::PureOne"))
                ._classifierGenericType(_GenericType.buildUserDefinedGenericType((platform.pure.metamodel.type.Type) metadataAccess.getElement("platform::pure::metamodel::function::property::Property"), metadataAccess));
        
        Property resolvedProp = _Property.resolveProperty(sourceProp, receiverType, metadataAccess);
        assertEquals("mockProp", resolvedProp._name());
    }
}
