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

package org.finos.legend.pure.platform.java.grammar;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.TokenStream;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Builds a {@link GrammarExtension} from the lexer, parser and entry rule a manifest NAMES.
 *
 * <p>A manifest carries simple names ({@code DiagramLexer}, {@code DiagramParser}, {@code section})
 * because it belongs to the language and must say nothing about any host's packages. The package is
 * the host's own fact and is supplied by whichever module staged the manifest, so the two together
 * resolve to classes here.</p>
 *
 * <p>REFLECTION IS RESOLVED EAGERLY — the constructors and the rule method are looked up when the
 * manifest is read, not when a source is first parsed. A manifest naming a class or rule that does
 * not exist therefore fails at startup with a message saying which, rather than on the one code path
 * that happens to parse that language.</p>
 *
 * <p>The listener REPLACES ANTLR's default, which only logs to stderr and lets the parser recover
 * silently: a quietly broken tree is worse than a failure. Errors carry {@code sourceId} and
 * (line + lineOffset) so coordinates stay file-relative even inside a section body.</p>
 */
final class Grammars
{
    private Grammars()
    {
    }

    /**
     * An adapter for the grammar named {@code parserName}, whose generated classes live in
     * {@code javaPackage}.
     */
    static GrammarExtension of(String javaPackage, String lexerName, String parserName, String entryRule, String language)
    {
        Class<?> lexerClass = type(javaPackage, lexerName, language);
        Class<?> parserClass = type(javaPackage, parserName, language);
        Constructor<?> lexerCtor = constructor(lexerClass, CharStream.class, language);
        Constructor<?> parserCtor = constructor(parserClass, TokenStream.class, language);
        Method rule = rule(parserClass, entryRule, language);
        return new GrammarExtension()
        {
            @Override
            public String grammarName()
            {
                return parserName;
            }

            @Override
            public ParserRuleContext parse(String source, String sourceId, int lineOffset)
            {
                try
                {
                    Lexer lexer = (Lexer) lexerCtor.newInstance(CharStreams.fromString(source));
                    Parser parser = (Parser) parserCtor.newInstance(new CommonTokenStream(lexer));
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
                    return (ParserRuleContext) rule.invoke(parser);
                }
                catch (InvocationTargetException e)
                {
                    // The error listener above throws from INSIDE the rule method, so its exception
                    // arrives wrapped. Unwrapped here, or every parse error would reach Pure as a
                    // reflection failure with the real message buried.
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException)
                    {
                        throw (RuntimeException) cause;
                    }
                    throw new RuntimeException("Parsing " + sourceId + " with " + parserName + " failed", cause);
                }
                catch (ReflectiveOperationException e)
                {
                    throw new RuntimeException("Cannot run " + parserName + "." + entryRule + " on " + sourceId, e);
                }
            }
        };
    }

    private static Class<?> type(String javaPackage, String simpleName, String language)
    {
        String name = javaPackage + "." + simpleName;
        try
        {
            return Class.forName(name);
        }
        catch (ClassNotFoundException e)
        {
            throw new IllegalStateException("language '" + language + "' names the grammar class '"
                    + simpleName + "', but " + name + " is not on the class path — the generated parser "
                    + "for this language was not built, or its manifest names it wrongly", e);
        }
    }

    private static Constructor<?> constructor(Class<?> type, Class<?> parameter, String language)
    {
        try
        {
            return type.getConstructor(parameter);
        }
        catch (NoSuchMethodException e)
        {
            throw new IllegalStateException("language '" + language + "': " + type.getName()
                    + " has no (" + parameter.getSimpleName() + ") constructor, so it is not an "
                    + "ANTLR-generated lexer or parser", e);
        }
    }

    private static Method rule(Class<?> parserClass, String entryRule, String language)
    {
        try
        {
            return parserClass.getMethod(entryRule);
        }
        catch (NoSuchMethodException e)
        {
            throw new IllegalStateException("language '" + language + "' names the entry rule '"
                    + entryRule + "', which " + parserClass.getName() + " does not have", e);
        }
    }
}
