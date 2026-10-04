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

// generate-languages.mjs — the JavaScript platform ADAPTING the languages it can run.
//
// HERE, not under pure/, because everything it writes is this platform's: generated/languages.js and
// the descriptors beside it exist so these hosts can register a language without naming one. The
// manifests it reads are the language's (pure/specification/language_*.json and
// pure/extensions/*/language_<name>.json); the output is the platform's.
//
// A platform that is not JavaScript reads the same manifests and generates whatever it needs. Two generated modules, both derived from the
// `pure/extensions/*/language_<name>.json` manifests, so an extension is registered by existing on disk
// rather than by a host edit:
//
//   extension-grammars.js   the ANTLR lexer/parser per grammar name — merged into
//                           `antlr-natives.js`'s grammar table, which held
//                           `{ TopParser, M3Parser }` as a literal before this.
//   extensions/<name>/      what the HOST needs to register an extension: the Pure module that
//                           carries its code, where that module's pdb is built, and the two Pure
//                           entry points for its section parser and its compiler passes. Consumed
//                           by src/core/runtime/LanguageExtensions.js, which used to spell those
//                           two Pure paths out by hand.
//
// The two land in DIFFERENT places, because they have different consumers and the trees must not
// reach into each other:
//
// Both land under the platform's own generated/extensions area, one directory per extension beside
// that extension's generated parsers and translated Pure:
//
//   --grammars   extensions/grammars.js   the lexer/parser classes per grammar name, imported from
//                                         each extension's directory. Bundled into antlr-bundle.js.
//   --languages  extensions/<name>/language_<name>.js plus extensions/extensions.js — what a HOST needs to
//                                         register the extension: its Pure module, where that
//                                         module's pdb is built, and the Pure entry points for its
//                                         section parser and compiler passes.
//
// They are separate files on purpose: a host that only needs a descriptor must not drag the ANTLR
// parsers into its module graph, and the browser must not fetch them twice (the bundle has them).
//
// A browser cannot list a directory, so the SET of extensions has to be baked either way — but each
// extension's own data is re-exported from its manifest verbatim, never restated.
//
// Usage: generate-languages.mjs <repoRoot> <genDir>

import { mkdirSync, readdirSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { join } from "node:path";

const args = process.argv.slice(2);
const [repoRoot, genDir] = args.filter((a) => !a.startsWith("--"));
if (!repoRoot || !genDir) {
    console.error("usage: generate-languages.mjs <repoRoot> <genDir>");
    process.exit(1);
}

const extensionsDir = join(repoRoot, "pure", "extensions");
const extensions = [];
if (existsSync(extensionsDir)) {
    for (const entry of readdirSync(extensionsDir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
        if (!entry.isDirectory()) continue;
        // `language_<name>.json`, not `language.json`: three manifests open in an editor should be
        // tellable apart by their tab, and the name says which language the file declares.
        const manifest = join(extensionsDir, entry.name, `language_${entry.name}.json`);
        if (!existsSync(manifest)) continue;
        const parsed = JSON.parse(readFileSync(manifest, "utf8"));
        // `dir` is how the pdb path is derived; the extension itself never spells a path out.
        extensions.push({ ...parsed, dir: entry.name });
    }
}

// --- the CORE languages' descriptors -----------------------------------------
// Every `language_*.json` under pure/specification declares a language that is not an extension, and
// the file IS the language, exactly as an extension's manifest is one extension:
//
//   pure/specification/language_pure.json  `###Pure` itself
//   pure/specification/language_test.json  compiler-pure's test sections (implemented in
//                                          compiler/compiler-pure/test/testSectionParsers.pure)
//
// They exist so no host spells these facts out — LanguageExtensions.js used to hard-code the section
// parser's element path and the pdb list, which made the host the place those facts lived. The only
// thing separating a core language from an extension is that it ships with the language rather than
// being found on disk, so both go through the same descriptor shape and the same host code.
//
// Two fields are worth knowing, since JSON cannot explain itself:
//
//   bundled   the section parser arrives through the parser bundle rather than a registered module,
//             so there is no element to guard it against. `###Pure` is not optional and must not be
//             guarded into silence; an extension has a `module` instead and stays quietly inert until
//             that module is registered.
//   pdbs      the modules the language needs, in load order, repo-relative — bootstrap writes into
//             shared/ and each module into its own build/, and a host that fetches rather than opens
//             files (the browser IDE) uses the same list as URLs. Pure's last three are THIS
//             platform's translator, which is what makes `###Pure` executable here rather than merely
//             compilable; a platform with a different translator reads the first two and brings its
//             own.
// Collected here so the single aggregate below can name each core language's module.
const coreLanguages = [];
{
// `language_*.json` directly under pure/specification — the core languages live side by side, so
// there is nothing to walk. `language_pure.json` sorts before `language_test.json`, which is also the
// order they must be registered in: `###Pure` before anything can be parsed.
const coreDir = join(repoRoot, "pure", "specification");
const manifests = readdirSync(coreDir)
    .filter((name) => name.startsWith("language_") && name.endsWith(".json"))
    .sort()
    .map((name) => join(coreDir, name));
// ONE FILE PER LANGUAGE, and one discovery module over them — the same shape the extensions have
// (extensions/<dir>/language_<dir>.js + extensions/extensions.js). `//` is commentary and `pdbs` is
// the host's load list, so neither belongs in the descriptor a host reads.
// Straight into `pure/`, beside the rest of the Pure language's generated code (pure/grammar,
// pure/compiler, pure/runtime): these descriptors describe the language itself, not the platform's
// extension area, and `language_*.js` already says what each one is without a folder to repeat it.
const dir = join(genDir, "pure");
mkdirSync(dir, { recursive: true });
const core = manifests.map((file) => {
    const { "//": _comment, pdbs, ...language } = JSON.parse(readFileSync(file, "utf8"));
    // `language_pure.json` -> `pure`, which is both the file's suffix and its import alias.
    const slug = file.split("/").pop().replace(/^language_/, "").replace(/\.json$/, "")
        .replace(/[^A-Za-z0-9_]/g, "_");
    writeFileSync(join(dir, `language_${slug}.js`), [
        "// GENERATED by platforms/javascript/bin/generate-languages.mjs — do not edit.",
        "//",
        `// The '${language.name}' language, from pure/specification/language_${slug}.json verbatim. A host`,
        "// registers it the same way it registers an extension.",
        "",
        `export const language = ${JSON.stringify(language, null, 4)};`,
        ...(pdbs ? ["",
            "/** The modules this language needs, in load order. */",
            `export const pdbs = ${JSON.stringify(pdbs, null, 4)};`] : []),
        "",
    ].join("\n"));
    return { slug, name: language.name, hasPdbs: Boolean(pdbs) };
});
coreLanguages.push(...core);

console.log(`  core languages: ${core.map((c) => c.name).join(", ")} -> ${core.map((c) => `pure/language_${c.slug}.js`).join(", ")}`);
}

// --- the BUNDLE's parser-module list -----------------------------------------
// Every extension's ANTLR classes, with literal imports so esbuild inlines them into
// antlr-bundle.js.
//
// WHY THIS FILE EXISTS, so it is not re-litigated: all ANTLR objects must come from ONE antlr4
// runtime. The native bridge (antlr-natives.js) identifies contexts by class —
// `c instanceof ParserRuleContext`, `r instanceof TerminalNode` — and those classes are the bundle's,
// which esbuild inlines from antlr4's BROWSER build. A parser loaded any other way brings its own
// antlr4 (Node's build, through node_modules), so every one of those checks fails and the bridge sees
// an empty tree. Measured: moving these imports into each extension's descriptor and registering the
// classes from the host parsed a ###Diagram section into zero elements.
//
// The bare `import antlr4 from 'antlr4'` in a generated parser is a second reason — a browser cannot
// resolve it without an import map — but the runtime identity is the one that cannot be worked around.
//
// So: IMPORTED BY THE BUNDLE ONLY. It carries nothing but the classes; a grammar's name, entry rule
// and section come from the extension's own descriptor, which is the single authority on them.
{
const lines = [
    "// GENERATED by platforms/javascript/bin/generate-languages.mjs — do not edit.",
    "//",
    "// Every extension's ANTLR classes, for antlr-bundle.js ONLY. All ANTLR objects must come from one",
    "// antlr4 runtime: the native bridge identifies contexts with `instanceof ParserRuleContext`, and",
    "// those classes are the bundle's. A parser loaded outside it brings a second antlr4 and the bridge",
    "// then sees an empty tree. The grammar's name, entry rule and section live in its descriptor.",
    "",
];
const withGrammar = extensions.filter((e) => e.grammar);   // an extension may add no syntax
for (const { grammar, dir } of withGrammar) {
    lines.push(`import ${grammar.lexer} from "./${dir}/grammar/antlr/${grammar.lexer}.js";`);
    lines.push(`import ${grammar.parser} from "./${dir}/grammar/antlr/${grammar.parser}.js";`);
}
lines.push("");
lines.push("/** `{ [extensionDir]: { lexer, parser } }` — classes only. */");
lines.push("export const EXTENSION_GRAMMAR_CLASSES = {");
for (const ext of withGrammar) {
    lines.push(`    ${JSON.stringify(ext.dir)}: { lexer: ${ext.grammar.lexer}, parser: ${ext.grammar.parser} },`);
}
lines.push("};");
lines.push("");
mkdirSync(join(genDir, "extensions"), { recursive: true });
const out = join(genDir, "extensions", "antlr-modules.js");
writeFileSync(out, lines.join("\n"));
console.log(`  extension parsers: ${withGrammar.length ? withGrammar.map((e) => e.name).join(", ") : "(none)"} -> ${out}`);
}

// --- the platform's extension area: one directory per extension, plus a discovery module -------
const extensionsDirOut = join(genDir, "extensions");
mkdirSync(extensionsDirOut, { recursive: true });

for (const ext of extensions) {
    const { dir, ...manifest } = ext;
    const own = join(extensionsDirOut, dir);
    mkdirSync(own, { recursive: true });
    // One translated file per HALF the manifest declares, named for the Pure package that holds it —
    // `parser.js` for the grammar mapping rules, `compiler.js` for the compiler passes. They were one
    // `code.js`, which said nothing about what was in it. Each half's package is the element path
    // minus the function name, so the manifest stays the only place anything is declared.
    //
    // The classes the two halves share (TypeView, Diagram, ParsedDiagram, …) are in neither file:
    // they live in the extension's root package, and `__classRef` falls back to `__pureResolve`,
    // which reads them from the registered pdb.
    // Where each of the extension's Pure packages lands in its generated directory, grouped by
    // SUBJECT rather than by kind:
    //
    //   grammar/   text -> tree -> protocol: the ANTLR parser (grammar/antlr), the rules that walk it,
    //              and the protocol those rules build — the protocol IS the grammar's output, so it
    //              sits with the grammar rather than in a directory of its own.
    //   compiler/  protocol -> graph: the compiler passes, and the metamodel they build.
    //
    // Keyed on the package's last segment, so it applies to a declared half and a declared package
    // alike. A package not listed here lands at the extension root.
    const SUBDIR = { parser: "grammar", protocol: "grammar", compiler: "compiler", metamodel: "compiler" };
    const fileFor = (pkg) => {
        const leaf = pkg.split("::").pop();
        return SUBDIR[leaf] ? `${SUBDIR[leaf]}/${leaf}.js` : `${leaf}.js`;
    };
    const halves = Object.entries(manifest.pure ?? {})
        .filter(([, elementPath]) => elementPath)
        .map(([half, elementPath]) => {
            const pkg = elementPath.split("::").slice(0, -1).join("::");
            return { half, pkg, file: fileFor(pkg) };
        });
    // Packages the manifest lists outright: the extension's classes, which have no entry point the
    // host calls and so cannot be derived from an element path the way a half can.
    const extra = (manifest.packages ?? []).map((pkg) => ({ half: null, pkg, file: fileFor(pkg) }));
    // A package may be named twice (two halves sharing one, or a half's package also listed);
    // translate it once, keeping the first spelling.
    // Classes first, so a half never loads before the types it builds — order is not required for
    // correctness (these files only declare functions) but depending on that would be needless.
    const translate = [...new Map([...extra, ...halves].map((h) => [h.pkg, h])).values()];

    // The manifest is re-exported VERBATIM rather than field by field, so a new key in
    // language_<name>.json reaches the host with no change to this generator. Only what the host cannot
    // read off the manifest is added: where the extension's pdb is built, and which translated files
    // this platform writes.
    writeFileSync(join(own, `language_${dir}.js`), [
        "// GENERATED by platforms/javascript/bin/generate-languages.mjs — do not edit.",
        "//",
        `// The '${manifest.name}' language extension, as this platform sees it. The fields are`,
        `// pure/extensions/${dir}/language_${dir}.json verbatim; the paths are derived.`,
        "//",
        "// It does NOT import its ANTLR classes — antlr-modules.js does, for the bundle. Every ANTLR",
        "// object has to come from ONE antlr4 runtime, because the native bridge identifies contexts",
        "// with `instanceof ParserRuleContext`; a parser imported here would get its own antlr4 and",
        "// every such check would fail (silently: the bridge would see no children at all).",
        "",
        "export const extension = {",
        `    ...${JSON.stringify(manifest)},`,
        `    dir: ${JSON.stringify(dir)},`,
        `    pdbPath: ${JSON.stringify(`pure/extensions/${dir}/build/${manifest.module}.pdb`)},`,
        `    jsPaths: ${JSON.stringify(translate.map((t) => `extensions/${dir}/${t.file}`))},`,
        `    translate: ${JSON.stringify(translate.map((t) => ({ package: t.pkg, out: `extensions/${dir}/${t.file}` })))},`,
        "};",
        "",
    ].join("\n"));
}

// --- the ONE baked list of languages -----------------------------------------
// Every language this platform knows, core and extension alike, in registration order: `###Pure`
// first, because nothing can be parsed before it.
//
// There is a single list because there is a single kind of thing. A core language ships with the
// compiler and an extension is found on disk, but both reach the host as the same descriptor and go
// through the same languageExtensionFor — so two baked lists would have differed only in which
// languages they happened to name. What distinguishes an extension downstream is that it has a `dir`
// (and the paths derived from it); a consumer that wants only those filters on it.
//
// The list has to be baked at all because a browser cannot list a directory.
const aggregate = [
    "// GENERATED by platforms/javascript/bin/generate-languages.mjs — do not edit.",
    "//",
    "// Every language this platform knows, in registration order: the ones that ship with the compiler",
    "// (pure/specification/language_*.json) and then the ones found on disk (pure/extensions/*). Both",
    "// kinds are the same descriptor; only an extension has a `dir` and the paths derived from it.",
    "",
    ...coreLanguages.map(({ slug, hasPdbs }) => `import { language as ${slug}`
        + (hasPdbs ? `, pdbs as ${slug}Pdbs` : "") + ` } from "./pure/language_${slug}.js";`),
    ...extensions.map(({ dir }) => `import { extension as ${dir.replace(/[^A-Za-z0-9_]/g, "_")} } `
        + `from "./extensions/${dir}/language_${dir}.js";`),
    "",
    "/** `{ name, section|sections, pure, … }[]` — each language's manifest, plus derived paths. */",
    `export const LANGUAGES = [${[...coreLanguages.map((c) => c.slug),
        ...extensions.map((e) => e.dir.replace(/[^A-Za-z0-9_]/g, "_"))].join(", ")}];`,
    "",
    "/** The modules the Pure language needs, in load order, from whichever language declares them. */",
    `export const PURE_LANGUAGE_PDBS = [${coreLanguages.filter((c) => c.hasPdbs)
        .map((c) => `...${c.slug}Pdbs`).join(", ")}];`,
    "",
];
const langOut = join(genDir, "languages.js");
writeFileSync(langOut, aggregate.join("\n"));
console.log(`  languages: ${[...coreLanguages.map((c) => c.name), ...extensions.map((e) => e.name)].join(", ")} -> ${langOut}`);
