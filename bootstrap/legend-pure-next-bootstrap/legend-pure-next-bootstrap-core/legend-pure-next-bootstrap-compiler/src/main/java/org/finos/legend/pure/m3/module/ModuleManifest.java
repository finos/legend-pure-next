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

package org.finos.legend.pure.m3.module;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The on-disk, in-archive identity of a Pure module: its name, the
 * package-path pattern its elements must satisfy, and the names of
 * the other modules it depends on.
 *
 * <p>Stored alongside source as {@code module.json} and embedded in
 * every {@code .pdb} archive as the {@code manifest} section so that
 * loaders can reconstruct the module's identity without relying on
 * filename heuristics or constructor arguments.</p>
 *
 * <p><b>{@code testDependencies} is a source-only field.</b> It records modules that only
 * the module's TEST half needs — its {@code <<test.TestDependency>>} functions and the
 * suites under {@code code/tests/}. A {@code --tests split} build folds them into the
 * {@code -tests} archive's {@code dependencies} (see
 * {@link TestElementFilter#testsManifest}) and leaves them out of the lean archive, so a
 * consumer that loads only the lean PDB is not made to carry test corpora it never reads.
 * {@code javascript-translation} declaring {@code core-tests} cost the browser IDE 5.35 MB
 * of exactly that.</p>
 *
 * <p>Because of this, {@link #toJson} emits only the three archive fields and NEVER
 * {@code testDependencies} — the archive format is unchanged, so every other reader of a
 * {@code manifest} section (the JavaScript and Truffle hosts, and the self-hosted Pure
 * writer in {@code meta::pure::compiler::pdb::archive::manifestJson}) is untouched and the
 * PDB goldens cannot move. {@link #parse} accepts the field so {@code module.json} can
 * carry it; a manifest round-tripped through {@code toJson} therefore loses it, which is
 * correct for an archive and is pinned by {@code ModuleManifestTest}.</p>
 */
public record ModuleManifest(String name, String packagePattern, List<String> dependencies,
                             List<String> testDependencies)
{
    public static final String ARCHIVE_SECTION = "manifest";

    public ModuleManifest
    {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(packagePattern, "packagePattern");
        dependencies = List.copyOf(dependencies);
        testDependencies = testDependencies == null ? List.of() : List.copyOf(testDependencies);
    }

    /** A manifest with no test-only dependencies — the shape read back from an archive. */
    public ModuleManifest(String name, String packagePattern, List<String> dependencies)
    {
        this(name, packagePattern, dependencies, List.of());
    }

    /**
     * The manifest for an archive that holds BOTH halves (a non-split build): its test
     * elements are present, so their dependencies must be declared as ordinary ones.
     */
    public ModuleManifest withAllDependencies()
    {
        return testDependencies.isEmpty()
                ? this
                : new ModuleManifest(name, packagePattern, allDependencies(), List.of());
    }

    /** Everything the module needs to compile: both halves' dependencies. */
    public List<String> allDependencies()
    {
        if (testDependencies.isEmpty())
        {
            return dependencies;
        }
        List<String> all = new ArrayList<>(dependencies);
        for (String dep : testDependencies)
        {
            if (!all.contains(dep))
            {
                all.add(dep);
            }
        }
        return List.copyOf(all);
    }

    /**
     * The archive {@code manifest} section: three fields, never {@code testDependencies}.
     * See the class note — widening this changes the on-disk format for four independent
     * readers and moves every PDB golden.
     */
    public String toJson()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"name\": ").append(quote(name)).append(",\n");
        sb.append("  \"packagePattern\": ").append(quote(packagePattern)).append(",\n");
        sb.append("  \"dependencies\": [");
        for (int i = 0; i < dependencies.size(); i++)
        {
            if (i > 0) sb.append(", ");
            sb.append(quote(dependencies.get(i)));
        }
        sb.append("]\n");
        sb.append("}\n");
        return sb.toString();
    }

    public byte[] toBytes()
    {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    public void write(Path path) throws IOException
    {
        Files.writeString(path, toJson(), StandardCharsets.UTF_8);
    }

    public static ModuleManifest read(Path path) throws IOException
    {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    /**
     * Conventional manifest file name.
     */
    public static final String FILE_NAME = "module.json";

    /**
     * Walk up from {@code start} looking for a {@code module.json} at any
     * ancestor directory. The manifest always lives at the module's root,
     * so any source folder under that root resolves to the same manifest.
     */
    public static ModuleManifest locate(Path start) throws IOException
    {
        Path cur = start.toAbsolutePath();
        while (cur != null)
        {
            Path candidate = cur.resolve(FILE_NAME);
            if (Files.isRegularFile(candidate))
            {
                return read(candidate);
            }
            cur = cur.getParent();
        }
        throw new IOException("No " + FILE_NAME + " found walking up from " + start.toAbsolutePath());
    }

    public static ModuleManifest parse(byte[] bytes)
    {
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Parse a {@code ModuleManifest} from JSON.
     *
     * <p>Tolerant of whitespace and key ordering. Accepts only the three
     * known keys; rejects extras to surface typos.</p>
     */
    public static ModuleManifest parse(String json)
    {
        Cursor c = new Cursor(json);
        c.skipWs();
        c.expect('{');
        String name = null;
        String packagePattern = null;
        List<String> dependencies = null;
        List<String> testDependencies = List.of();
        boolean first = true;
        while (true)
        {
            c.skipWs();
            if (c.peek() == '}') { c.next(); break; }
            if (!first)
            {
                c.expect(',');
                c.skipWs();
            }
            first = false;
            String key = c.readString();
            c.skipWs();
            c.expect(':');
            c.skipWs();
            switch (key)
            {
                case "name" -> name = c.readString();
                case "packagePattern" -> packagePattern = c.readString();
                case "dependencies" -> dependencies = c.readStringArray();
                case "testDependencies" -> testDependencies = c.readStringArray();
                default -> throw new IllegalArgumentException("Unknown manifest key: '" + key + "'");
            }
        }
        c.skipWs();
        if (c.hasMore())
        {
            throw new IllegalArgumentException("Trailing content after manifest object at offset " + c.pos);
        }
        if (name == null) throw new IllegalArgumentException("manifest is missing required 'name' field");
        if (packagePattern == null) throw new IllegalArgumentException("manifest is missing required 'packagePattern' field");
        if (dependencies == null) throw new IllegalArgumentException("manifest is missing required 'dependencies' field");
        return new ModuleManifest(name, packagePattern, dependencies, testDependencies);
    }

    private static String quote(String s)
    {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++)
        {
            char ch = s.charAt(i);
            switch (ch)
            {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default ->
                {
                    if (ch < 0x20)
                    {
                        sb.append(String.format("\\u%04x", (int) ch));
                    }
                    else
                    {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static final class Cursor
    {
        final String s;
        int pos;

        Cursor(String s) { this.s = s; }

        boolean hasMore() { return pos < s.length(); }
        char peek() { return s.charAt(pos); }
        char next() { return s.charAt(pos++); }

        void skipWs()
        {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        void expect(char ch)
        {
            if (pos >= s.length() || s.charAt(pos) != ch)
            {
                throw new IllegalArgumentException("Expected '" + ch + "' at offset " + pos
                        + " but got " + (pos >= s.length() ? "EOF" : "'" + s.charAt(pos) + "'"));
            }
            pos++;
        }

        String readString()
        {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length())
            {
                char ch = s.charAt(pos++);
                if (ch == '"') return sb.toString();
                if (ch == '\\')
                {
                    if (pos >= s.length()) throw new IllegalArgumentException("Unterminated escape");
                    char esc = s.charAt(pos++);
                    switch (esc)
                    {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' ->
                        {
                            if (pos + 4 > s.length()) throw new IllegalArgumentException("Truncated \\u escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Bad escape '\\" + esc + "' at offset " + (pos - 1));
                    }
                }
                else
                {
                    sb.append(ch);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        List<String> readStringArray()
        {
            expect('[');
            List<String> out = new ArrayList<>();
            skipWs();
            if (peek() == ']') { next(); return out; }
            while (true)
            {
                skipWs();
                out.add(readString());
                skipWs();
                if (peek() == ']') { next(); return out; }
                expect(',');
            }
        }
    }
}
