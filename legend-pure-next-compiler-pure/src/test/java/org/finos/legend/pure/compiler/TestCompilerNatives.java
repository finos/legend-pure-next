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

import org.finos.legend.pure.m3.pureLanguage.metadata.PureLanguageMetadata;
import org.finos.legend.pure.m3.pureLanguage.metadata.CompositePureLanguageMetadata;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry;

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

        // meta::pure::compiler::findFunctionsByNameAndArity(name:String[1], arity:Integer[1]):PackageableFunction<Any>[*]
        natives.put("findFunctionsByNameAndArity_String_1__Integer_1__PackageableFunction_MANY_", (args, eval, genericType, multiplicity) ->
        {
            String name = (String) _E_ValueSpecification.unwrap(args.get(0));
            long arity = (Long) _E_ValueSpecification.unwrap(args.get(1));
            CompositePureLanguageMetadata metadata = new CompositePureLanguageMetadata(resolver.getMetadataAccessExtension(PureLanguageMetadata.class), resolver);
            org.eclipse.collections.api.list.MutableList<FunctionIndexEntry> entries = metadata.findFunctionHeadersByNameAndArity(name, (int) arity);
            List<meta.pure.metamodel.valuespecification.ValueSpecification> wrapped = new java.util.ArrayList<>();
            for (FunctionIndexEntry entry : entries)
            {
                wrapped.add((meta.pure.metamodel.valuespecification.ValueSpecification) _E_ValueSpecification.wrap(entry, null, null, resolver));
            }
            return org.finos.legend.pure.execution.natives.collection.CollectionNatives.makeCollection(wrapped, resolver);
        });

        // meta::pure::compiler::primitiveType(value:Any[1]):String[0..1]
        // Returns the Pure primitive type name for a raw Java value using PrimitiveJavaTypeMapping reverse lookup
        natives.put("primitiveType_Any_1__String_$0_1$_", (args, eval, genericType, multiplicity) ->
        {
            // Recursively unwrap to handle double-wrapping from property access + function call
            Object value = _E_ValueSpecification.unwrap(args.get(0));
            while (value instanceof meta.pure.metamodel.valuespecification.ValueSpecification vs)
            {
                value = _E_ValueSpecification.unwrap(vs);
            }
            // If value is a DynamicInstance (e.g., protocol AtomicValue), try to extract the raw value
            if (value instanceof org.finos.legend.pure.execution.DynamicInstance di)
            {
                Object diVal = di.get("value");
                if (diVal != null)
                {
                    // DynamicInstance stores unwrapped values, so this is the raw Java value
                    if (diVal instanceof java.util.List<?> lst && !lst.isEmpty())
                    {
                        value = lst.get(0);
                    }
                    else
                    {
                        value = diVal;
                    }
                }
            }
            String pureName = null;
            if (value instanceof String)         pureName = "String";
            else if (value instanceof Long)      pureName = "Integer";
            else if (value instanceof Integer)   pureName = "Integer";
            else if (value instanceof Double)    pureName = "Float";
            else if (value instanceof Float)     pureName = "Float";
            else if (value instanceof Boolean)   pureName = "Boolean";
            else if (value instanceof java.math.BigDecimal)  pureName = "Decimal";
            else if (value instanceof java.time.ZonedDateTime) pureName = "DateTime";
            else if (value instanceof java.time.LocalDate)   pureName = "StrictDate";
            else if (value instanceof java.time.LocalTime)   pureName = "StrictTime";
            else if (value instanceof Number)    pureName = "Number";
            return _E_ValueSpecification.wrap(pureName, genericType, multiplicity, resolver);
        });
    }
}
