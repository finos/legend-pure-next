package org.finos.legend.pure.compiler;

import meta.pure.metamodel.function.FunctionDefinition;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.PureAssertionError;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Validates the compiler by evaluating each compiled graph specification file
 * within the Pure test functions.
 */
public class TestCompilerPureCompiledGraph
{
    private static PureExecution execution;
    private static ScopedMetadataAccess resolver;
    private static FunctionDefinition assertCompiledGraph;

    @BeforeAll
    public static void setup() throws Exception
    {
        PDBModule coreModule = new PDBModule(
                Path.of("../legend-pure-next-java/legend-pure-next-compiler/target/core.pdb"),
                PDBModule.Mode.EXECUTION,
                "core",
                "*",
                Lists.mutable.with());

        PDBModule compilerModule = new PDBModule(
                Path.of("target/compiler.pdb"),
                PDBModule.Mode.EXECUTION,
                "compiler",
                "*",
                Lists.mutable.with("core"));

        PureModel model = PureModel.withModules(Lists.mutable.with(coreModule, compilerModule))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        resolver = new ScopedMetadataAccess(compilerModule, model);

        execution = PureExecution.builder()
                .withResolver(resolver)
                .withNativeExtensions(Lists.mutable.with(new CompilerNatives()))
                .withParserExtensions(List.of(new org.finos.legend.pure.m3.localModule.compiledgraph.CompiledGraphLanguageExtension()))
                .build();

        assertCompiledGraph = (FunctionDefinition) compilerModule.getElement("meta::pure::compiler::test::assertCompiledGraph_String_1__String_1__Boolean_1_");
        if (assertCompiledGraph == null) {
            throw new RuntimeException("Could not find meta::pure::compiler::test::assertCompiledGraph_String_1__String_1__Boolean_1_ in compiler execution.");
        }
    }

    @TestFactory
    public Collection<DynamicTest> testCompiledGraphs() throws IOException
    {
        Path start = Path.of("../../legend-pure-next-specification/src/main/resources/specification/compiler");
        if (!Files.exists(start)) {
            start = Path.of("../legend-pure-next-specification/src/main/resources/specification/compiler");
        }

        final Path finalStart = start;
        List<DynamicTest> tests = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(finalStart))
        {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".pure"))
                  .forEach(p ->
                  {
                      try
                      {
                          String content = Files.readString(p);
                          if (content.contains("###CompiledGraph") || content.contains("###Error"))
                          {
                              String testName = finalStart.relativize(p).toString();
                              String sourceId = testName.replace(".pure", "");

                              if (!testName.contains("association/property/simple.pure") &&
                                  !testName.contains("association/property/simple_E_unknownTypeInProperty.pure") &&
                                  !testName.contains("association/property/simple_E_multipleUnknownTypes.pure") &&
                                  !testName.contains("association/property/simple_E_ambiguousType.pure") &&
                                  !testName.contains("association/property/typeArguments.pure") &&
                                  !testName.contains("association/property/typeArguments_E_Unknown.pure") &&
                                  !testName.contains("class/inheritance/simple.pure") &&
                                  !testName.contains("class/inheritance/simple_E_unknownTypeInGeneralization.pure") &&
                                  !testName.contains("class/inheritance/simple_E_multipleUnknownTypes.pure") &&
                                  !testName.contains("class/inheritance/typeArguments.pure") &&
                                  !testName.contains("class/inheritance/typeArguments_E_Unknown.pure") &&
                                  !testName.contains("class/profile/profile.pure") &&
                                  !testName.contains("class/profile/profile_E_unknownProfile.pure") &&
                                  !testName.contains("class/profile/profile_E_unknownStereotype.pure") &&
                                  !testName.contains("class/profile/profile_E_unknownTag.pure") &&
                                  !testName.contains("class/property/simple.pure") &&
                                  !testName.contains("class/property/simple_E_unknownTypeInProperty.pure") &&
                                  !testName.contains("class/property/simple_E_multipleUnknownTypes.pure") &&
                                  !testName.contains("class/property/typeArguments.pure") &&
                                  !testName.contains("class/property/typeArguments_E_Unknown.pure") &&
                                  !testName.contains("class/typeAndMulParam/typeAndMulParam.pure") &&
                                  !testName.contains("enumeration/simple.pure") &&
                                  !testName.contains("function/lambda/openVariables.pure") &&
                                  !testName.contains("function/lambda/lambda_E_dupParamName.pure") &&
                                  !testName.contains("function/lambda/lambda_E_NoInference.pure") &&
                                  !testName.contains("function/native/simple.pure") &&
                                  !testName.contains("function/native/simple_E_dupParamName.pure") &&
                                  !testName.contains("function/native/simple_E_unknownParameterType.pure") &&
                                  !testName.contains("function/native/simple_E_unknownReturnType.pure") &&
                                  !testName.contains("function/native/simple_E_unknownTypeParam.pure") &&
                                  !testName.contains("function/userDefined/simple.pure") &&
                                  !testName.contains("function/userDefined/simple_E_dupParamName.pure") &&
                                  !testName.contains("function/userDefined/simple_E_unknownVariable.pure") &&
                                  !testName.contains("primitive/simple.pure") &&
                                  !testName.contains("primitive/typeVariable.pure"))
                              {
                                  return;
                              }

                              tests.add(DynamicTest.dynamicTest(testName, () ->
                              {
                                  try
                                  {
                                      execution.execute(assertCompiledGraph, content, sourceId);
                                  }
                                  catch (PureAssertionError e)
                                  {
                                      throw new org.opentest4j.AssertionFailedError(e.getMessage(), e);
                                  }
                              }));
                          }
                      }
                      catch (IOException e)
                      {
                          throw new RuntimeException(e);
                      }
                  });
        }

        assertFalse(tests.isEmpty(), "Should discover at least one specification file");

        return tests;
    }
}
