package org.finos.legend.pure.compiler;

import meta.pure.metamodel.function.FunctionDefinition;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.PureAssertionError;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
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
                PDBModule.Mode.COMPILATION);

        LocalModule compilerModule = new LocalModule("compiler", "*",
                Lists.mutable.with(coreModule.getName()),
                Path.of("src/main/resources"));

        PureModel model = PureModel.withModules(Lists.mutable.with(coreModule, compilerModule))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        resolver = new ScopedMetadataAccess(compilerModule, model);
        
        execution = PureExecution.builder()
                .withResolver(resolver)
                .withNativeExtensions(Lists.mutable.with(new TestCompilerNatives()))
                .build();

        assertCompiledGraph = (FunctionDefinition) compilerModule.getElement("meta::pure::compiler::test::assertCompiledGraph_String_1__Boolean_1_");
        if (assertCompiledGraph == null) {
            throw new RuntimeException("Could not find meta::pure::compiler::test::assertCompiledGraph_String_1__Boolean_1_ in compiler execution.");
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
                          if (content.contains("###CompiledGraph"))
                          {
                              String testName = finalStart.relativize(p).toString();
                              
                              if (!testName.contains("class/property/simple.pure")) {
                                  return;
                              }
                              
                              tests.add(DynamicTest.dynamicTest(testName, () -> 
                              {
                                  try
                                  {
                                      execution.execute(assertCompiledGraph, content);
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
