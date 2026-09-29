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
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import org.finos.legend.pure.truffle.grammar.m3.M3Lexer;
import org.finos.legend.pure.truffle.grammar.m3.M3Parser;

/**
 * TRUFFLE'S OWN COPY — platforms do not depend on one another. The GRAMMAR is
 * shared (pure/specification/grammar/antlr); the generated parser and this
 * wiring are Truffle's, as the JavaScript platform generates its own.
 *
 * {@link GrammarExtension} for the M3 grammar. Registered name {@code "M3Parser"};
 * parses a Pure-source body and returns the {@code DefinitionContext} root.
 */
public final class M3GrammarExtension implements GrammarExtension
{
    @Override
    public String grammarName() { return "M3Parser"; }

    @Override
    public ParserRuleContext parse(String source, String sourceId, int lineOffset)
    {
        M3Parser parser = new M3Parser(new CommonTokenStream(new M3Lexer(CharStreams.fromString(source))));
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener()
        {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object offending,
                                    int line, int charPositionInLine,
                                    String msg, RecognitionException e)
            {
                throw new RuntimeException("Parse error in " + sourceId
                        + " at line " + (line + lineOffset) + ":" + charPositionInLine + " - " + msg);
            }
        });
        return parser.definition();
    }
}
