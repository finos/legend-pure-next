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
        // meta::pure::compiler::findFunctionsByNameAndArity(name:String[1], arity:Integer[1]):PackageableFunction<Any>[*]
        registry.register("findFunctionsByNameAndArity_String_1__Integer_1__PackageableFunction_MANY_", (args, eval, genericType, multiplicity) ->
        {
            String name = (String) _E_ValueSpecification.unwrap(args.get(0));
            long arity = (Long) _E_ValueSpecification.unwrap(args.get(1));
            CompositePureLanguageMetadata metadata = new CompositePureLanguageMetadata(resolver.getMetadataAccessExtension(PureLanguageMetadata.class), resolver);
            org.eclipse.collections.api.list.MutableList<FunctionIndexEntry> entries = metadata.findFunctionHeadersByNameAndArity(name, (int) arity);
            List<meta.pure.metamodel.valuespecification.ValueSpecification> wrapped = new java.util.ArrayList<>();
            for (FunctionIndexEntry entry : entries)
            {
                wrapped.add(_E_ValueSpecification.wrap(entry, null, null, resolver));
            }
            return org.finos.legend.pure.execution.natives.collection.CollectionNatives.makeCollection(wrapped, resolver);
        });
    }
}
