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
import java.nio.file.Files;
import java.nio.file.Path;

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
 * @param pdbs              the Pure modules this language needs, in load order, repo-relative — what
 *                          `###Pure` MEANS on any host, as opposed to what a platform brings to make it
 *                          executable (see PlatformModules). Empty for a language that needs none.
 * @param sectionParsers    the Pure element returning this language's section parsers, or null.
 * @param compilerExtension the Pure element returning its compiler passes, or null when it has
 *                          none. {@code ###Pure} names pureExtension(), which carries m3's PDB writer.
 * @param grammar           its ANTLR adapter, or null when the language adds no syntax.
 * @param fbsText           the language's PDB schema text — m3.fbs for {@code ###Pure}, the fragment
 *                          generated from its metamodel (pdb/fbsgen) for an extension — or null when its
 *                          compiled elements have no PDB form. Shipped next to the manifest (`fbs` names
 *                          it); the host's schema is every language's, the always-active ones first.
 * @param types             the language's table -> implementation factory (generated, like
 *                          {@code PdbTypes}), or null. Not in the manifest: the module that owns the
 *                          generated classes supplies it, through {@link #withTypes}.
 */
public record Language(
        String name,
        String module,
        ImmutableList<String> pdbs,
        String sectionParsers,
        String compilerExtension,
        GrammarExtension grammar,
        String fbsText,
        java.util.function.Function<String, org.finos.legend.pure.platform.java.compiler.module.pdbModule.LazyObject> types)
{
    /**
     * Always active: the language has no module to wait for, so its halves are never guarded into
     * silence — true for {@code ###Pure} alone, whose manifest is the one with no {@code module}.
     */
    public boolean bundled()
    {
        return module == null;
    }

    /** This language with its generated table -> implementation factory. */
    public Language withTypes(java.util.function.Function<String, org.finos.legend.pure.platform.java.compiler.module.pdbModule.LazyObject> types)
    {
        return new Language(name, module, pdbs, sectionParsers, compilerExtension, grammar, fbsText, types);
    }

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
        return fromJson(text, resource, javaPackage, loader);
    }

    private static Language fromJson(String text, String source, String javaPackage, ClassLoader loader)
    {
        MapIterable<String, Object> json = Json.parseObject(text);
        MapIterable<String, Object> pure = Json.object(json, "pure");
        MapIterable<String, Object> grammar = Json.object(json, "grammar");
        String name = required(Json.string(json, "name"), "name", source);
        // `sections` is deliberately unread: a section is claimed by the Pure function named
        // in `sectionParsers`, so restating its name here would be a second place to keep right.
        return new Language(
                name,
                Json.string(json, "module"),
                Json.strings(json, "pdbs"),
                Json.string(pure, "sectionParsers"),
                Json.string(pure, "compilerExtension"),
                grammar.isEmpty() ? null : Grammars.of(
                        javaPackage,
                        required(Json.string(grammar, "lexer"), "grammar.lexer", source),
                        required(Json.string(grammar, "parser"), "grammar.parser", source),
                        required(Json.string(grammar, "entryRule"), "grammar.entryRule", source),
                        name),
                fbsText(Json.string(json, "fbs"), source, name, loader),
                null);
    }

    /**
     * The `fbs` file a manifest names, read from the class path BESIDE the manifest: the manifest
     * gives the repo path the extension's build writes it to, and the module that stages the
     * manifest stages the file into the same directory.
     */
    private static String fbsText(String fbs, String source, String name, ClassLoader loader)
    {
        if (fbs == null || fbs.isEmpty())
        {
            return null;
        }
        String dir = source.contains("/") ? source.substring(0, source.lastIndexOf('/') + 1) : "";
        String resource = dir + fbs.substring(fbs.lastIndexOf('/') + 1);
        try (InputStream in = loader.getResourceAsStream(resource))
        {
            if (in == null)
            {
                throw new IllegalStateException("language " + name + " declares fbs " + fbs + " but " + resource
                        + " is not on the class path — the module that owns it did not stage it (see its pom)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("cannot read the schema '" + resource + "'", e);
        }
    }

    /**
     * The PDB schema of {@code languages}: the always-active languages' {@code fbs} first — m3's, which
     * every other language's tables refer to — then each other language's in order. {@code override},
     * when given, replaces the always-active part (a caller decoding with a schema of its own).
     */
    public static String schemaText(ListIterable<Language> languages, Path override) throws IOException
    {
        String first = override != null
                ? Files.readString(override)
                : languages.select(l -> l.bundled() && l.fbsText() != null).collect(Language::fbsText).makeString("\n");
        if (first.isEmpty())
        {
            throw new IllegalStateException("no always-active language declares a schema — install PureLanguage.all() first");
        }
        ListIterable<String> rest = languages.reject(Language::bundled).collect(Language::fbsText).select(t -> t != null);
        return rest.isEmpty() ? first : first + "\n" + rest.makeString("\n");
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
