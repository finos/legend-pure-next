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

package org.finos.legend.pure.execution.natives.meta;

import meta.pure.protocol.PureFile;
import org.finos.legend.pure.execution.NativeExtension;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution.ProtocolToDynamicInstance;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.metadata.CompositePureLanguageMetadata;
import org.finos.legend.pure.m3.pureLanguage.metadata.PureLanguageMetadata;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry;
import org.finos.legend.pure.next.parser.ParserExtension;
import org.finos.legend.pure.next.parser.PureParser;
import org.finos.legend.pure.next.parser.m3.PureLanguageParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CompilerNatives implements NativeExtension
{
    private final List<ParserExtension> extraExtensions;

    public CompilerNatives()
    {
        this(List.of());
    }

    public CompilerNatives(List<? extends ParserExtension> extraExtensions)
    {
        this.extraExtensions = List.copyOf(extraExtensions);
    }

    @Override
    public void register(Map<String, NativeImpl> natives,
                         Map<String, LazyNativeImpl> lazyNatives,
                         MetadataAccess resolver)
    {
        // meta::pure::functions::meta::parse(sourceId:String[1], content:String[1]):PureFile[1]
        List<ParserExtension> extensions = new ArrayList<>();
        extensions.add(new PureLanguageParser());
        extensions.addAll(this.extraExtensions);
        PureParser parser = PureParser.builder()
                .withExtensions(extensions)
                .build();

        natives.put("parse_String_1__String_1__PureFile_1_", (args, eval, genericType, multiplicity) ->
        {
            String sourceId = (String) _E_ValueSpecification.unwrap(args.get(0));
            String content = (String) _E_ValueSpecification.unwrap(args.get(1));

            PureFile pureFile = parser.parse(sourceId, content);

            ProtocolToDynamicInstance translator = new ProtocolToDynamicInstance(resolver, obj ->
            {
                for (ParserExtension ext : extensions)
                {
                    String resolved = ext.resolveType(obj);
                    if (resolved != null)
                    {
                        return resolved;
                    }
                }
                return ProtocolToDynamicInstance.defaultTypeResolutionStrategy(obj);
            });
            Object resultValue = translator.convert(pureFile);

            return _E_ValueSpecification.wrap(resultValue, genericType, multiplicity, resolver);
        });

        // meta::pure::compiler::findFunctionsByNameAndArity(name:String[1], arity:Integer[1]):PackageableFunction<Any>[*]
        natives.put("findFunctionsByNameAndArity_String_1__Integer_1__PackageableFunction_MANY_", (args, eval, genericType, multiplicity) ->
        {
            String name = (String) _E_ValueSpecification.unwrap(args.get(0));
            long arity = (Long) _E_ValueSpecification.unwrap(args.get(1));
            CompositePureLanguageMetadata metadata = new CompositePureLanguageMetadata(resolver.getMetadataAccessExtension(PureLanguageMetadata.class), resolver);
            org.eclipse.collections.api.list.MutableList<FunctionIndexEntry> entries = metadata.findFunctionHeadersByNameAndArity(name, (int) arity);
            List<meta.pure.metamodel.valuespecification.ValueSpecification> wrapped = new ArrayList<>();
            for (FunctionIndexEntry entry : entries)
            {
                wrapped.add(_E_ValueSpecification.wrap(entry, null, null, resolver));
            }
            return org.finos.legend.pure.execution.natives.collection.CollectionNatives.makeCollection(wrapped, resolver);
        });
    }
}
