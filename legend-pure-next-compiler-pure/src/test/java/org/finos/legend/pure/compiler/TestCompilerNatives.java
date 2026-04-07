package org.finos.legend.pure.compiler;

import meta.pure.protocol.PureFile;
import org.finos.legend.pure.compiler.pure.natives.ProtocolToDynamicInstance;
import org.finos.legend.pure.execution.NativeExtension;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.localModule.compiledgraph.CompiledGraphLanguageExtension;
import org.finos.legend.pure.m3.localModule.error.ErrorLanguageExtension;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.next.parser.PureParser;
import org.finos.legend.pure.next.parser.m3.PureLanguageParser;

import java.util.List;
import java.util.Map;

public class TestCompilerNatives implements NativeExtension
{
    @Override
    public void register(Map<String, NativeImpl> natives,
                         Map<String, LazyNativeImpl> lazyNatives,
                         MetadataAccess resolver)
    {
        // meta::pure::compiler::parse(sourceId:String[1], content:String[1]):PureFile[1]
        // Override the default parse to include the CompiledGraphLanguageExtension for tests
        natives.put("parse_String_1__String_1__PureFile_1_", (args, eval, genericType, multiplicity) ->
        {
            String sourceId = (String) _E_ValueSpecification.unwrap(args.get(0));
            String content = (String) _E_ValueSpecification.unwrap(args.get(1));

            PureParser parser = PureParser.builder()
                .withExtensions(List.of(new PureLanguageParser(), new CompiledGraphLanguageExtension(), new ErrorLanguageExtension()))
                .build();
            PureFile pureFile = parser.parse(sourceId, content);

            ProtocolToDynamicInstance translator = new ProtocolToDynamicInstance(resolver, javaPOJO -> {
                if (javaPOJO instanceof org.finos.legend.pure.m3.localModule.compiledgraph.CompiledGraph) {
                    return "meta::pure::compiler::test::CompiledGraph";
                }
                return ProtocolToDynamicInstance.defaultTypeResolutionStrategy(javaPOJO);
            });
            Object resultValue = translator.convert(pureFile);

            return _E_ValueSpecification.wrap(resultValue, genericType, multiplicity, resolver);
        });

        // meta::pure::compiler::getPackageableElement(path:String[1]):meta::pure::metamodel::PackageableElement[0..1]
        natives.put("getPackageableElement_String_1__PackageableElement_$0_1$_", (args, eval, genericType, multiplicity) ->
        {
            String path = (String) _E_ValueSpecification.unwrap(args.get(0));
            meta.pure.metamodel.PackageableElement el = (meta.pure.metamodel.PackageableElement) resolver.getElement(path);
            return _E_ValueSpecification.wrap(el, genericType, multiplicity, resolver);
        });



        // meta::pure::compiler::test::getSpecificationFilePaths():String[*]
        natives.put("getSpecificationFilePaths__String_MANY_", (args, eval, genericType, multiplicity) ->
        {
             try
             {
                 java.nio.file.Path start = java.nio.file.Path.of("../../legend-pure-next-specification/src/main/resources/specification/compiler");
                 if (!java.nio.file.Files.exists(start)) {
                     start = java.nio.file.Path.of("../legend-pure-next-specification/src/main/resources/specification/compiler");
                 }
                 List<meta.pure.metamodel.valuespecification.ValueSpecification> wrappedPaths = new java.util.ArrayList<>();
                 try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(start))
                 {
                     stream.filter(java.nio.file.Files::isRegularFile)
                           .filter(p -> p.toString().endsWith(".pure"))
                           .forEach(p -> {
                               Object wrapped = _E_ValueSpecification.wrap(p.toAbsolutePath().toString(), null, null, resolver);
                               wrappedPaths.add((meta.pure.metamodel.valuespecification.ValueSpecification) wrapped);
                           });
                 }
                 return org.finos.legend.pure.execution.natives.collection.CollectionNatives.makeCollection(wrappedPaths, resolver);
             }
             catch (java.io.IOException e)
             {
                 throw new RuntimeException("Failed to read specification files", e);
             }
        });

        // meta::pure::compiler::test::readSpecificationFile(path:String[1]):String[1]
        natives.put("readSpecificationFile_String_1__String_1_", (args, eval, genericType, multiplicity) ->
        {
             String path = (String) _E_ValueSpecification.unwrap(args.get(0));
             try
             {
                 String content = java.nio.file.Files.readString(java.nio.file.Path.of(path));
                 return _E_ValueSpecification.wrap(content, genericType, multiplicity, resolver);
             }
             catch (java.io.IOException e)
             {
                 throw new RuntimeException("Failed to read specification file: " + path, e);
             }
        });
    }
}
