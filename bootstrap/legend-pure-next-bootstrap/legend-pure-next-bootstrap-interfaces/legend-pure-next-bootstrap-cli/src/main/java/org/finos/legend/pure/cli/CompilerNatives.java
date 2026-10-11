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

package org.finos.legend.pure.cli;

import org.finos.legend.pure.execution.natives.NativeRegistry;

import org.finos.legend.pure.execution.natives.NativesExtension;
import org.finos.legend.pure.execution.natives.NativeRegistry.LazyNativeImpl;
import org.finos.legend.pure.execution.natives.NativeRegistry.NativeImpl;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.metadata.CompositePureLanguageMetadata;
import org.finos.legend.pure.m3.pureLanguage.metadata.PureLanguageMetadata;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry;

import java.util.List;
import java.util.Map;

public class CompilerNatives implements NativesExtension
{
    @Override
    public void registerAll(NativeRegistry registry)
    {
        MetadataAccess resolver = registry.resolver();
        // platform::pure::compiler::findFunctionsByNameAndArity(name:String[1], arity:Integer[1]):PackageableFunction<Any>[*]
        registry.register("findFunctionsByNameAndArity_String_1__Integer_1__PackageableFunction_MANY_", (args, eval, genericType, multiplicity) ->
        {
            String name = (String) _E_ValueSpecification.unwrap(args.get(0));
            long arity = (Long) _E_ValueSpecification.unwrap(args.get(1));
            CompositePureLanguageMetadata metadata = new CompositePureLanguageMetadata(resolver.getMetadataAccessExtension(PureLanguageMetadata.class), resolver);
            org.eclipse.collections.api.list.MutableList<FunctionIndexEntry> entries = metadata.findFunctionHeadersByNameAndArity(name, (int) arity);
            List<platform.pure.metamodel.valuespecification.ValueSpecification> wrapped = new java.util.ArrayList<>();
            for (FunctionIndexEntry entry : entries)
            {
                wrapped.add(_E_ValueSpecification.wrap(entry, null, null, resolver));
            }
            return org.finos.legend.pure.execution.natives.collection.CollectionNatives.makeCollection(wrapped, resolver);
        });
    }
}
