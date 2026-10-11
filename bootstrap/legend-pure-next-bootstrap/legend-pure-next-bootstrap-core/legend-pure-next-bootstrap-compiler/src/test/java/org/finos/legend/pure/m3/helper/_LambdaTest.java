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

import platform.pure.metamodel.function.FunctionDefinition;
import platform.pure.metamodel.function.LambdaFunction;
import platform.pure.metamodel.type.FunctionType;
import platform.pure.metamodel.type.generics.GenericType;
import platform.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.JavaCompiler;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.sourceModule.SourceModule;
import org.finos.legend.pure.m3.module.sourceModule.PureContent;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Lambda;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class _LambdaTest
{
    private static JavaCompiler model;
    
    @BeforeAll
    public static void setUp()
    {
        String source = 
            "function test::testFn():Any[*] { {a:String[1]| $a}; }";

        model = JavaCompiler.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new SourceModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent(source, "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());
    }

    private LambdaFunction getLambda()
    {
        FunctionDefinition fd = (FunctionDefinition) model.registry().module("test").getElement("test::testFn__Any_MANY_");
        platform.pure.metamodel.valuespecification.AtomicValue letExpr = (platform.pure.metamodel.valuespecification.AtomicValue) fd._expressionSequence().getFirst();
        return (LambdaFunction) letExpr._value();
    }

    @Test
    public void testBuildFunctionType()
    {
        LambdaFunction lambda = getLambda();
        FunctionType ft = _Lambda.buildFunctionType(lambda, new ScopedMetadataAccess(model.registry().module("test"), model.registry()));
        
        assertNotNull(ft);
        assertEquals(1, ft._parameters().size());
        assertEquals("a", ft._parameters().getFirst()._name());
        assertEquals("String", ((platform.pure.metamodel.PackageableElement) _GenericType.type(ft._parameters().getFirst()._genericType()))._name());
        
        ValueSpecification lastExpr = lambda._expressionSequence().getLast();
        assertEquals(lastExpr._genericType(), ft._returnType());
        Assertions.assertEquals(1L, _Multiplicity.lowerBound(ft._returnMultiplicity()));
    }

    @Test
    public void testGetLambdaClassifierGenericType()
    {
        LambdaFunction lambda = getLambda();
        GenericType gto = _Lambda.getLambdaClassifierGenericType(new ScopedMetadataAccess(model.registry().module("test"), model.registry()), lambda);
        
        assertEquals("LambdaFunction", ((platform.pure.metamodel.PackageableElement) _GenericType.type(gto))._name());
        assertEquals(1, _GenericType.typeArguments(gto).size());
        assertTrue(_GenericType.type(_GenericType.typeArguments(gto).getFirst()) instanceof FunctionType);
    }
}
