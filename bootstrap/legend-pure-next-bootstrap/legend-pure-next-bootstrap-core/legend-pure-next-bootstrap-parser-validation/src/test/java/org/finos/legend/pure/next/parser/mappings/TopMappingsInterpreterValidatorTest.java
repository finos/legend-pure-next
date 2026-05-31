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

package org.finos.legend.pure.next.parser.mappings;

import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.protocol.PureFile;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.next.parser.ParserExtension;
import org.finos.legend.pure.next.parser.pureLanguage.PureLanguageParser;
import org.finos.legend.pure.next.parser.topLevel.TopLevelParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Auto-discover validator: for every .pure fixture across both corpora,
 * parses the full document (sections + imports + elements) via two
 * pipelines and asserts the resulting {@link PureFile} agrees on every
 * protocol field.
 *
 * <p>The two pipelines are:</p>
 * <ul>
 *   <li><b>Reference Java parser</b>: {@link TopLevelParser#parse} drives
 *       the bootstrap {@code TopLevelProtocolBuilder} + {@link PureLanguageParser}.</li>
 *   <li><b>Pure interpreter</b>: loads {@code shared/parser-mappings.pdb}
 *       and invokes the Pure-side {@code parseDocument} (defined in
 *       {@code mappings-pure/mapping_top.pure}). Sections are dispatched
 *       via a Pure-side {@code Pair<name, parserLambda>[*]} registry: the
 *       built-in {@code pureSectionParser()} for ###Pure plus a
 *       {@code noOpSectionParser(name)} for every other section name the
 *       fixture corpus uses. A section name missing from the registry is a
 *       hard error.</li>
 * </ul>
 *
 * <p>The comparison is strict (no slot skips beyond Java/Pure runtime
 * artifacts — see {@link ProtocolPrinter}). A failure means the
 * Pure-side mappings drifted from the reference parser's shape.</p>
 */
class TopMappingsInterpreterValidatorTest
{
    private static final List<String> STUB_SECTIONS = List.of(
            "CompiledGraph", "Error", "File", "CompilerStats", "ReverseIndex", "TestFile");

    private static PureExecution exec;
    private static FunctionDefinition parseDocumentFn;
    private static Object pureRegistry;

    @BeforeAll
    static void setUpInterpreter() throws IOException
    {
        PDBModule coreModule = new PDBModule(BootstrapModule.locateCorePdb(), PDBModule.Mode.COMPILATION);
        PDBModule mappingsModule = new PDBModule(BootstrapModule.locateParserMappingsPdb(), PDBModule.Mode.COMPILATION);

        PureModel model = PureModel.withModules(Lists.mutable.with(coreModule, mappingsModule))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        exec = PureExecution.builder()
                .withResolver(new ScopedMetadataAccess(mappingsModule, model))
                .build();

        parseDocumentFn = (FunctionDefinition) mappingsModule.getElement(
                "meta::pure::parser::mappings::interpreter::parseDocument_AntlrContext_1__String_1__Boolean_1__Pair_MANY__PureFile_1_");
        assertNotNull(parseDocumentFn, "parseDocument missing from parser-mappings.pdb");

        pureRegistry = buildPureRegistry(mappingsModule);
    }

    /**
     * Build the Pure-side section registry: {@code pureSectionParser()} for
     * ###Pure plus {@code noOpSectionParser(name)} for every stub section name.
     */
    private static Object buildPureRegistry(PDBModule mappingsModule)
    {
        FunctionDefinition pureSectionParserFn = (FunctionDefinition) mappingsModule.getElement(
                "meta::pure::parser::mappings::interpreter::pureSectionParser__Pair_1_");
        assertNotNull(pureSectionParserFn, "pureSectionParser missing from parser-mappings.pdb");
        FunctionDefinition noOpSectionParserFn = (FunctionDefinition) mappingsModule.getElement(
                "meta::pure::parser::mappings::interpreter::noOpSectionParser_String_1__Pair_1_");
        assertNotNull(noOpSectionParserFn, "noOpSectionParser missing from parser-mappings.pdb");

        List<Object> registry = new ArrayList<>();
        registry.add(exec.execute(pureSectionParserFn));
        for (String name : STUB_SECTIONS)
        {
            registry.add(exec.execute(noOpSectionParserFn, name));
        }
        return registry;
    }

    /**
     * Auto-discovered fixtures across both corpora — same set the M3
     * mapping validator uses. Adding a fixture there automatically
     * exercises the top-level parser here too (the top parser fronts
     * EVERY .pure source).
     */
    static Collection<Arguments> coveredFixtures() throws IOException
    {
        Path grammarCorpus = locateGrammarTestsRoot();
        Path compilerCorpus = locateCompilerTestsRoot();
        List<Arguments> tests = new ArrayList<>();
        try (Stream<Path> grammar = Files.walk(grammarCorpus))
        {
            grammar.filter(p -> p.getFileName().toString().equals("grammar.pure"))
                    .sorted()
                    .forEach(p ->
                    {
                        String rel = grammarCorpus.relativize(p.getParent()).toString().replace('\\', '/');
                        tests.add(Arguments.of("grammar:" + rel, p));
                    });
        }
        try (Stream<Path> compiler = Files.walk(compilerCorpus))
        {
            compiler.filter(p -> p.toString().endsWith(".pure"))
                    .sorted()
                    .forEach(p ->
                    {
                        String rel = compilerCorpus.relativize(p).toString().replace('\\', '/');
                        tests.add(Arguments.of("compiler:" + rel, p));
                    });
        }
        return tests;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coveredFixtures")
    void interpreterMatchesProduction(String name, Path sourcePath) throws Exception
    {
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);

        // Reference Java parser is fed PureLanguageParser + a VerbatimSection
        // per stub name; the Pure interpreter is fed pureSectionParser() +
        // noOpSectionParser(name) for the same names. Both sides therefore
        // produce empty `elements` for every stub section.
        PureFile expected = TopLevelParser.parse(source, name, buildReferenceExtensions());

        // Pure interpreter path: TopParser → Pure parseDocument(ctx, sourceId, syntheticHeader, registry).
        boolean syntheticHeader = !source.startsWith("###");
        String effectiveSource = syntheticHeader ? "###Pure\n" + source : source;
        org.finos.legend.pure.next.parser.TopLexer lexer = new org.finos.legend.pure.next.parser.TopLexer(
                org.antlr.v4.runtime.CharStreams.fromString(effectiveSource));
        org.antlr.v4.runtime.CommonTokenStream tokens = new org.antlr.v4.runtime.CommonTokenStream(lexer);
        org.finos.legend.pure.next.parser.TopParser parser = new org.finos.legend.pure.next.parser.TopParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new org.antlr.v4.runtime.BaseErrorListener()
        {
            @Override
            public void syntaxError(org.antlr.v4.runtime.Recognizer<?, ?> r, Object off,
                                    int line, int col, String msg, org.antlr.v4.runtime.RecognitionException e)
            {
                throw new RuntimeException("Top-level parse error in " + name
                        + " at line " + line + ":" + col + " - " + msg);
            }
        });
        org.finos.legend.pure.next.parser.TopParser.DocumentContext document = parser.document();

        Object actual = exec.execute(parseDocumentFn, document, name, syntheticHeader, pureRegistry);

        String expectedPrint = ProtocolPrinter.print(expected);
        String actualPrint   = ProtocolPrinter.print(actual);
        assertEquals(expectedPrint, actualPrint, "[" + name + "] PureFile mismatch");
    }

    /**
     * Section-parser extensions for the reference Java pipeline. Mirrors the
     * Pure-side registry built in {@link #buildPureRegistry}: ###Pure goes
     * through {@link PureLanguageParser}; every other section gets a
     * no-op {@link VerbatimSection} so the protocol shape stays comparable.
     */
    private static List<ParserExtension> buildReferenceExtensions()
    {
        List<ParserExtension> out = new ArrayList<>();
        out.add(new PureLanguageParser());
        for (String sectionName : STUB_SECTIONS)
        {
            out.add(new VerbatimSection(sectionName));
        }
        return out;
    }

    /** A no-op section parser: matches by name, ignores content (returns empty element list). */
    private static final class VerbatimSection implements ParserExtension
    {
        private final String sectionName;
        VerbatimSection(String sectionName) { this.sectionName = sectionName; }
        @Override public String sectionName() { return sectionName; }
        @Override public List<meta.pure.protocol.grammar.PackageableElement> parseSection(
                String content, String sourceId, int lineOffset) { return List.of(); }
    }

    private static Path locateGrammarTestsRoot()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("pure").resolve("specification").resolve("grammar").resolve("tests");
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate pure/specification/grammar/tests from " + Path.of("").toAbsolutePath());
    }

    private static Path locateCompilerTestsRoot()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("pure").resolve("specification").resolve("compiler").resolve("tests");
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate pure/specification/compiler/tests from " + Path.of("").toAbsolutePath());
    }

}
