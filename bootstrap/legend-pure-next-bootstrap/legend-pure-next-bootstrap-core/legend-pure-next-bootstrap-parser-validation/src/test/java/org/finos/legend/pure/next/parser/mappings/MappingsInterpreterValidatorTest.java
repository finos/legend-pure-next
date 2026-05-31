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
import meta.pure.protocol.grammar.PackageableElement;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.next.parser.m3.M3Lexer;
import org.finos.legend.pure.next.parser.m3.M3Parser;
import org.finos.legend.pure.next.parser.pureLanguage.PureLanguageParser;
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
 * Parameterized validator: for every test fixture currently covered by the
 * ported `mapping_*.pure` rule set, parses the fixture's grammar.pure via
 * both pipelines (the existing .dsl-derived production parser, and the
 * interpreter consuming shared/parser-mappings.pdb) and asserts the
 * resulting PackageableElement lists agree on the checked fields.
 *
 * <p>Today: enumDefinition only. Coverage expands as new mapping_*.pure
 * files land.</p>
 *
 * <p>Comparison is intentionally shallow at the field level (name, class
 * path, package). Full JSON-level equality is the next gate, once
 * genericType/multiplicity/aggregation defaults match production.</p>
 */
class MappingsInterpreterValidatorTest
{
    private static PureExecution exec;
    private static FunctionDefinition parseElementsFn;
    private static Object allRules;
    private static PDBModule mappingsModule;

    @BeforeAll
    static void setUpInterpreter() throws IOException
    {
        PDBModule coreModule = new PDBModule(BootstrapModule.locateCorePdb(), PDBModule.Mode.COMPILATION);
        mappingsModule = new PDBModule(BootstrapModule.locateParserMappingsPdb(), PDBModule.Mode.COMPILATION);

        PureModel model = PureModel.withModules(Lists.mutable.with(coreModule, mappingsModule))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        exec = PureExecution.builder()
                .withResolver(new ScopedMetadataAccess(mappingsModule, model))

                .build();

        FunctionDefinition allRulesFn = (FunctionDefinition) mappingsModule.getElement(
                "meta::pure::parser::mappings::allRules__Rule_MANY_");
        parseElementsFn = (FunctionDefinition) mappingsModule.getElement(
                "meta::pure::parser::mappings::interpreter::parseElements_AntlrContext_1__Rule_MANY__Integer_1__PackageableElement_MANY_");
        assertNotNull(allRulesFn);
        assertNotNull(parseElementsFn);

        allRules = exec.execute(allRulesFn);
    }

    /**
     * Auto-discovered fixtures across both corpora:
     * <ul>
     *   <li>{@code pure/specification/grammar/tests/**}{@code /grammar.pure}
     *       — one fixture per directory containing a {@code grammar.pure} file.</li>
     *   <li>{@code pure/specification/compiler/tests/**}{@code /*.pure} — one
     *       fixture per {@code .pure} file (richer real-world syntax with
     *       {@code ###Pure} + {@code ###CompiledGraph} sections; the validator
     *       only consumes the {@code ###Pure} body).</li>
     * </ul>
     *
     * <p>Names are prefixed with {@code grammar:} / {@code compiler:} so the
     * corpus is obvious in JUnit output and so {@code EXPECTED_FAILURES} keys
     * stay unambiguous.</p>
     *
     * <p>Auto-discovery (instead of a hand-curated allowlist) prevents the
     * coverage drift the validator used to suffer: every fixture that exists
     * on disk is now exercised, and any new failure must be either fixed in
     * the mapping or explicitly recorded in {@link #EXPECTED_FAILURES}.</p>
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
                        Path dir = p.getParent();
                        String rel = grammarCorpus.relativize(dir).toString().replace('\\', '/');
                        tests.add(Arguments.of("grammar:" + rel, dir));
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
    void interpreterMatchesProduction(String name, Path fixturePath) throws Exception
    {
        // grammar-tests fixtures are directories containing grammar.pure;
        // compiler-tests fixtures are direct .pure files. Resolve accordingly
        // based on the `grammar:` / `compiler:` prefix in the test name.
        Path sourcePath = name.startsWith("compiler:") ? fixturePath : fixturePath.resolve("grammar.pure");
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
        runValidation(name, source);
    }

    private static void runValidation(String name, String source) throws Exception
    {
        // A .pure file may have multiple `###Pure` sections — production's
        // TopLevelParser handles that routing. The M3 parser only knows the
        // body of a single section (no `###Pure` token), so we split on the
        // marker and concatenate results from both pipelines per section.
        List<String> sections = splitSections(source);

        List<PackageableElement> expected = new java.util.ArrayList<>();
        List<Object> actual = new java.util.ArrayList<>();
        for (String sectionContent : sections)
        {
            expected.addAll(new PureLanguageParser().parseSection(sectionContent, name, 0));

            M3Parser parser = new M3Parser(new CommonTokenStream(new M3Lexer(CharStreams.fromString(sectionContent))));
            M3Parser.DefinitionContext root = parser.definition();
            @SuppressWarnings("unchecked")
            List<Object> sectionActual = (List<Object>) exec.execute(parseElementsFn, root, allRules, 0L);
            actual.addAll(sectionActual);
        }

        assertEquals(expected.size(), actual.size(),
                "[" + name + "] element-list size mismatch");

        for (int i = 0; i < expected.size(); i++)
        {
            String expectedPrint = ProtocolPrinter.print(expected.get(i));
            String actualPrint   = ProtocolPrinter.print(actual.get(i));
            assertEquals(expectedPrint, actualPrint,
                    "[" + name + "] structural mismatch at element " + i);
        }
    }

    /**
     * Split a .pure file into per-section bodies (one entry per `###Pure`
     * block) with section headers + `import` lines removed. M3's `definition`
     * rule doesn't know about either — they're TopLevelParser's job.
     */
    private static List<String> splitSections(String source)
    {
        List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^###Pure\\s*$").matcher(source);
        int[] starts = new int[16];
        int n = 0;
        while (m.find())
        {
            if (n == starts.length) starts = java.util.Arrays.copyOf(starts, n * 2);
            starts[n++] = m.end() + 1;  // skip past the trailing newline
        }
        if (n == 0) { out.add(stripImports(source)); return out; }
        // For each ###Pure section, the body ends at the next ###<anything>
        // marker (e.g. ###CompiledGraph, ###Pure, ###Error) — compiler-tests
        // fixtures interleave Pure source with CompiledGraph dumps and the M3
        // parser must only see the Pure body.
        java.util.regex.Pattern nextSection = java.util.regex.Pattern.compile("(?m)^###[A-Za-z][A-Za-z0-9_]*\\s*$");
        for (int i = 0; i < n; i++)
        {
            java.util.regex.Matcher nm = nextSection.matcher(source);
            int end = source.length();
            if (nm.find(starts[i]))
            {
                end = nm.start();
            }
            out.add(stripImports(source.substring(starts[i], end)));
        }
        return out;
    }

    private static String stripImports(String body)
    {
        return body.replaceAll("(?m)^\\s*import [^;]+;\\s*\\n", "");
    }

    private static Path locateGrammarTestsRoot()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("pure")
                    .resolve("specification").resolve("grammar").resolve("tests");
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
            Path candidate = current.resolve("pure")
                    .resolve("specification").resolve("compiler").resolve("tests");
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate pure/specification/compiler/tests from " + Path.of("").toAbsolutePath());
    }

}
