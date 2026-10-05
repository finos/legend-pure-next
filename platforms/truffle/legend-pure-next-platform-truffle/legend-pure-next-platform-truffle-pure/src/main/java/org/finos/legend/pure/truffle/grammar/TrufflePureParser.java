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

package org.finos.legend.pure.truffle.grammar;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.finos.legend.pure.truffle.grammar.TopLexer;
import org.finos.legend.pure.truffle.grammar.TopParser;
import org.finos.legend.pure.truffle.execution.PureContext;
import org.finos.legend.pure.truffle.execution.PureLanguage;
import org.finos.legend.pure.truffle.compiler.module.MetadataAccess;

import java.util.List;

/**
 * Pure-source parser that returns a {@code PureDynamicObject} directly,
 * bypassing the {@link org.finos.legend.pure.truffle.compiler.helper._Any}
 * protocol-Impl → PDO copy.
 *
 * <p>Drives ANTLR ({@link TopLexer}/{@link TopParser}) and delegates the
 * entire parse to the Pure-side interpreter loaded from
 * {@code shared/parser-mappings.pdb}: {@code parseDocument} walks the top
 * tree, builds {@code PureFile} + {@code Section}, and dispatches each
 * section body through the Pure-side registry
 * ({@code pureSectionParser()} for {@code ###Pure} + optionally
 * {@code testSectionParsers()} for the compiler-test sections). A section
 * name without a matching registry entry is a hard error.</p>
 * <p>The .dsl pipeline that previously generated {@code TruffleTopLevelProtocolBuilder}
 * and {@code TrufflePureLanguageProtocolBuilder} has been retired entirely.</p>
 */
public final class TrufflePureParser
{
    private final MetadataAccess resolver;
    private final Object parseDocumentFn;
    /**
     * Pure-side registry of built-in section parsers. Eagerly resolved at
     * construction so the per-parse path is one executeFunction call. ###Pure
     * comes from parser-mappings via {@code pureSectionParser()}. Test section
     * parsers (CompiledGraph / CompilerStats / …) are appended via
     * {@code testSectionParsers()} when compiler.pdb is loaded into the
     * resolver — the call returns the empty list when the function isn't
     * present so the optional dependency stays optional.
     */
    private final List<Object> pureSectionRegistry;

    private TrufflePureParser(MetadataAccess resolver,
                              org.eclipse.collections.api.list.ListIterable<Language> languages)
    {
        this.resolver = resolver;
        this.parseDocumentFn = resolver == null ? null : resolver.getElement(
                "meta::pure::parser::mappings::interpreter::parseDocument_AntlrContext_1__String_1__Boolean_1__Pair_MANY__PureFile_1_");
        this.pureSectionRegistry = resolver == null
                ? new java.util.ArrayList<>()
                : buildPureSectionRegistry(resolver, languages);
    }

    /**
     * Compose the Pure-side section-parser registry by invoking the relevant
     * aggregators. {@code pureSectionParser()} (parser-mappings) is required;
     * {@code testSectionParsers()} (compiler-pure) is optional. Returns the
     * concatenated list in registry order. The live {@link PureContext}
     * (looked up via {@link PureLanguage#get}) is what drives the Pure-side
     * evaluation — no PureRuntime dependency.
     */
    private static List<Object> buildPureSectionRegistry(MetadataAccess resolver,
                                                        org.eclipse.collections.api.list.ListIterable<Language> languages)
    {
        PureContext ctx = PureLanguage.get(null);
        List<Object> registry = new java.util.ArrayList<>();
        // The languages' own, when a host named them: each declares the element that parses its section in
        // its manifest, so `###Diagram` is registered by the extension existing rather than by an edit here.
        // Absent that, the two the language and compiler-pure have always provided — every host that does not
        // yet name its languages (the CLI, the IDE) still works.
        org.eclipse.collections.api.list.ListIterable<String> paths = languages.notEmpty()
                ? languages.collect(Language::sectionParsers).select((path) -> path != null)
                : org.eclipse.collections.api.factory.Lists.immutable.of(
                        "meta::pure::parser::mappings::interpreter::pureSectionParser__Pair_1_",
                        "meta::pure::compiler::test::testSectionParsers__Pair_MANY_");
        paths.forEach((path) ->
        {
            // GUARDED on the element being present: a language whose Pure module is not registered
            // contributes nothing rather than failing, which lets a module arrive late.
            Object fn = resolver.getElement(path);
            if (fn == null)
            {
                return;
            }
            Object value = ctx.executeFunction(fn, new Object[0]);
            if (value instanceof org.finos.legend.pure.truffle.execution.types.PureSequence ps)
            {
                for (int i = 0; i < ps.size(); i++)
                {
                    registry.add(ps.getBoxed(i));
                }
            }
            else if (value instanceof List<?> l)
            {
                registry.addAll((List<Object>) l);
            }
            else if (value != null)
            {
                registry.add(value);
            }
        });
        return registry;
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public Object parse(String sourceId, String content)
    {
        // If the source has no ### header, treat it as implicit ###Pure (matches bootstrap TopLevelParser).
        boolean syntheticHeader = !content.startsWith("###");
        String effectiveSource = syntheticHeader ? "###Pure\n" + content : content;

        TopLexer lexer = new TopLexer(CharStreams.fromString(effectiveSource));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        TopParser parser = new TopParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener()
        {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg, RecognitionException e)
            {
                throw new RuntimeException("Top-level parse error in file " + sourceId + " at line "
                        + line + ":" + charPositionInLine + " - " + msg);
            }
        });

        TopParser.DocumentContext document = parser.document();
        // Whole parse is Pure-driven: parseDocument walks the top tree,
        // dispatches each section through the Pure registry (pureSectionParser
        // for ###Pure, optionally testSectionParsers for compiler-test
        // sections). A section name not in the registry is a hard error.
        if (parseDocumentFn == null)
        {
            throw new RuntimeException(
                    "TrufflePureParser requires parser-mappings.pdb loaded into the resolver. "
                            + "Register a PdbModule for shared/parser-mappings.pdb.");
        }
        return PureLanguage.get(null).executeFunction(parseDocumentFn,
                new Object[] {document, sourceId, syntheticHeader, pureSectionRegistry});
    }

    public static final class Builder
    {
        private MetadataAccess resolver;
        private org.eclipse.collections.api.list.ListIterable<Language> languages =
                org.eclipse.collections.api.factory.Lists.immutable.empty();

        private Builder() {}

        public Builder resolver(MetadataAccess resolver)
        {
            this.resolver = resolver;
            return this;
        }

        /**
         * The languages whose sections this parser should recognise. Left unset, the Pure language's own
         * parser and compiler-pure's test sections are used, which is what every host did before languages
         * were descriptors.
         */
        public Builder languages(org.eclipse.collections.api.list.ListIterable<Language> languages)
        {
            this.languages = languages;
            return this;
        }

        public TrufflePureParser build()
        {
            return new TrufflePureParser(resolver, languages);
        }
    }
}
