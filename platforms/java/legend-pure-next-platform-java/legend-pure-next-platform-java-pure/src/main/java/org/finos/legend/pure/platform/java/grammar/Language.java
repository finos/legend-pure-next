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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MapIterable;

/**
 * One language this platform can run, as its manifest declares it.
 *
 * <p>READ, not written here: {@link #fromResources} parses {@code language_*.json} — the language's
 * own file, staged as a classpath resource by whichever module owns it. Before this, a host spelled
 * out the Pure element path of every section parser and named {@code M3Parser} in a literal table, so
 * the host was where those facts lived.</p>
 *
 * <p>The Top grammar is the one thing no manifest declares, and it is not a language: it splits a
 * file into {@code ###Section} blocks before any language is consulted — {@code ###Diagram} passes
 * through it too — so it stays {@link TopGrammarExtension}.</p>
 *
 * @param name              for diagnostics, and to find one language among many.
 * @param module            the Pure module carrying this language's code, or null when it needs none.
 *                          What its halves are GUARDED on: an extension whose module is not registered
 *                          contributes nothing instead of throwing, which lets a module arrive late.
 * @param bundled           the section parser ships with the compiler rather than in a registered
 *                          module, so there is no element to guard against. True for {@code ###Pure}
 *                          alone: it is not optional and must not be guarded into silence.
 * @param pdbs              the Pure modules this language needs, in load order, repo-relative — what
 *                          `###Pure` MEANS on any host, as opposed to what a platform brings to make it
 *                          executable (see PlatformModules). Empty for a language that needs none.
 * @param sectionParsers    the Pure element returning this language's section parsers, or null.
 * @param compilerExtension the Pure element returning its compiler passes, or null when the built-in
 *                          passes already compile it (as for {@code ###Pure}).
 * @param grammar           its ANTLR adapter, or null when the language adds no syntax.
 */
public record Language(
        String name,
        String module,
        boolean bundled,
        ImmutableList<String> pdbs,
        String sectionParsers,
        String compilerExtension,
        GrammarExtension grammar)
{
    /**
     * The languages declared by {@code resources}, in the order given — which is registration order,
     * and {@code ###Pure} must come before anything is parsed.
     *
     * <p>{@code javaPackage} is where the caller's module put the ANTLR classes its manifests name. It
     * is the host's fact, not the language's, which is why a manifest carries simple names only.</p>
     */
    public static ImmutableList<Language> fromResources(ListIterable<String> resources, String javaPackage)
    {
        MutableList<Language> out = Lists.mutable.withInitialCapacity(resources.size());
        for (String resource : resources)
        {
            out.add(fromResource(resource, javaPackage));
        }
        return out.toImmutable();
    }

    private static Language fromResource(String resource, String javaPackage)
    {
        ClassLoader loader = Language.class.getClassLoader();
        String text;
        try (InputStream in = loader.getResourceAsStream(resource))
        {
            if (in == null)
            {
                throw new IllegalStateException("no language manifest on the class path at '" + resource
                        + "' — the module that owns it did not stage it (see its pom)");
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("cannot read the language manifest '" + resource + "'", e);
        }
        return fromJson(text, resource, javaPackage);
    }

    private static Language fromJson(String text, String source, String javaPackage)
    {
        MapIterable<String, Object> json = Json.parseObject(text);
        MapIterable<String, Object> pure = Json.object(json, "pure");
        MapIterable<String, Object> grammar = Json.object(json, "grammar");
        String name = required(Json.string(json, "name"), "name", source);
        // `section`/`sections` is deliberately unread: a section is claimed by the Pure function named
        // in `sectionParsers`, so restating its name here would be a second place to keep right.
        return new Language(
                name,
                Json.string(json, "module"),
                Json.bool(json, "bundled"),
                Json.strings(json, "pdbs"),
                Json.string(pure, "sectionParsers"),
                Json.string(pure, "compilerExtension"),
                grammar.isEmpty() ? null : Grammars.of(
                        javaPackage,
                        required(Json.string(grammar, "lexer"), "grammar.lexer", source),
                        required(Json.string(grammar, "parser"), "grammar.parser", source),
                        required(Json.string(grammar, "entryRule"), "grammar.entryRule", source),
                        name));
    }

    private static String required(String value, String field, String source)
    {
        if (value == null || value.isEmpty())
        {
            throw new IllegalStateException("the language manifest '" + source + "' is missing '" + field + "'");
        }
        return value;
    }
}
