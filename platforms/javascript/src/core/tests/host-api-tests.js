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

// Host platform API conformance for the JavaScript host: the names, members and
// start-up sequence fixed by docs/superpowers/specs/2026-09-13-unified-host-api-design.md.
//
// Run: node --stack-size=4000 src/core/tests/host-api-tests.js [--report <path>]

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { openZip } from "../compiler/module/pdbModule/zip/zip.js";
import { PdbModule } from "../compiler/module/pdbModule/PdbModule.js";
import { metadataHost } from "../compiler/module/pdbModule/marshal.js";
import { InMemoryModule } from "../compiler/module/inMemoryModule/InMemoryModule.js";
import { ModuleRegistry } from "../compiler/module/ModuleRegistry.js";
import { loadRuntime } from "../runtime/load-runtime.js";
import { NativeRegistry } from "../execution/natives/NativeRegistry.js";
import { PureRuntime, pairExtensionModules } from "../runtime/PureRuntime.js";
import { EXTENSION_DESCRIPTORS, coreLanguages, extensionLanguages, extensionNamed, languageNamed, languageSchema } from "../runtime/LanguageExtensions.js";
import { LANGUAGES } from "../../../generated/languages.js";
import * as SIG from "../execution/natives/native-signatures.js";
import { TestReport } from "./test-report.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const SHARED = join(HERE, "../../../../../shared");
const REPO = join(HERE, "../../../../..");
const ANY = "platform::pure::metamodel::type::Any";

const results = [];
const report = new TestReport("host-api");
async function checkAsync(name, fn) {
    const started = Date.now();
    try {
        await fn();
        results.push({ name });
        report.pass(name, Date.now() - started);
    } catch (error) {
        results.push({ name, error });
        report.fail(name, Date.now() - started, error);
    }
}
function check(name, fn) {
    const started = Date.now();
    try {
        fn();
        results.push({ name });
        report.pass(name, Date.now() - started);
    } catch (error) {
        results.push({ name, error });
        report.fail(name, Date.now() - started, error);
    }
}

check("PdbModule.open reads identity from the manifest", () => {
    const core = PdbModule.open(join(SHARED, "core.pdb"));
    assert.equal(core.name, "core");
    assert.deepEqual([...core.dependencies], []);
    assert.equal(core.packagePattern, "(platform::pure)(::.*)?");
    assert.equal(core.kind, "pdb");
});

check("new PdbModule(archive) reads the same identity", () => {
    const compiler = new PdbModule(openZip(readFileSync(join(SHARED, "compiler.pdb"))));
    assert.equal(compiler.name, "compiler");
    assert.deepEqual([...compiler.dependencies], ["core"]);
});

check("PdbModule is MetadataAccess over its elements", () => {
    const core = PdbModule.open(join(SHARED, "core.pdb"));
    assert.equal(core.hasElement(ANY), true);
    assert.equal(core.hasElement("no::such::Element"), false);
    assert.ok([...core.elementPaths()].includes(ANY));
});

check("PdbModule rejects an archive without a manifest", () => {
    const archive = { names: () => [], has: () => false, read: () => { throw new Error("unreachable"); } };
    assert.throws(() => new PdbModule(archive), /has no module manifest section/);
});

check("InMemoryModule identity and elements", () => {
    const module = new InMemoryModule("runtime");
    assert.equal(module.name, "runtime");
    assert.deepEqual([...module.dependencies], []);
    assert.equal(module.packagePattern, null);
    assert.equal(module.kind, "memory");
    const changed = [];
    module.onPathChange = (path) => changed.push(path);
    const element = { name: "B" };
    module.addElement("a::B", element);
    assert.equal(module.hasElement("a::B"), true);
    assert.equal(module.getElement("a::B"), element);
    assert.deepEqual([...module.elementPaths()], ["a::B"]);
    module.removeElement("a::B");
    assert.equal(module.hasElement("a::B"), false);
    assert.equal(module.getElement("a::B"), null);
    assert.deepEqual(changed, ["a::B", "a::B"]);
});

// A registry decodes with the schema of the languages it is given: ###Pure's (and the compiler test
// sections'), plus the diagram extension's where a check reads diagram elements.
const CORE = coreLanguages.map((l) => l.descriptor);
const WITH_DIAGRAM = [...CORE, extensionNamed("diagram").descriptor];
// Pure values cross as a value OR a one-element array; a [*] slot may be either or absent.
const asArray = (v) => (v === undefined || v === null ? [] : Array.isArray(v) ? v : [v]);
const one = (v) => (Array.isArray(v) ? v[0] : v);

check("ModuleRegistry registers modules in order", () => {
    const registry = new ModuleRegistry().useLanguages(CORE);
    const core = PdbModule.open(join(SHARED, "core.pdb"));
    registry.register(core);
    registry.register(PdbModule.open(join(SHARED, "compiler.pdb")));
    assert.equal(core.name, "core");
    assert.deepEqual(registry.modules.map((m) => m.name), ["core", "compiler"]);
    assert.deepEqual([...registry.module("compiler").dependencies], ["core"]);
    assert.equal(registry.module("missing"), null);
});

check("ModuleRegistry.validate accepts satisfied dependencies", () => {
    const registry = new ModuleRegistry().useLanguages(CORE);
    registry.register(PdbModule.open(join(SHARED, "core.pdb")));
    registry.register(PdbModule.open(join(SHARED, "compiler.pdb")));
    assert.equal(registry.validate(), registry);
});

check("ModuleRegistry.validate names a missing dependency", () => {
    const registry = new ModuleRegistry().useLanguages(CORE);
    registry.register(PdbModule.open(join(SHARED, "compiler.pdb")));
    assert.throws(() => registry.validate(), {
        message: "Module 'compiler' declares dependency 'core' but it was not loaded (loaded: [compiler])",
    });
});

check("ModuleRegistry is MetadataAccess over every module", () => {
    const registry = new ModuleRegistry().useLanguages(CORE);
    registry.register(PdbModule.open(join(SHARED, "core.pdb")));
    const runtime = new InMemoryModule("runtime");
    registry.register(runtime);
    runtime.addElement("a::B", { name: "B" });
    assert.equal(registry.hasElement(ANY), true);
    assert.equal(registry.hasElement("a::B"), true);
    const paths = [...registry.elementPaths()];
    assert.ok(paths.includes(ANY));
    assert.ok(paths.includes("a::B"));
});

check("ModuleRegistry.unregister removes a module by name", () => {
    const registry = new ModuleRegistry().useLanguages(CORE);
    registry.register(PdbModule.open(join(SHARED, "core.pdb")));
    const runtime = new InMemoryModule("runtime");
    registry.register(runtime);
    assert.equal(registry.unregister("runtime"), runtime);
    assert.equal(registry.module("runtime"), null);
    assert.equal(registry.unregister("runtime"), null);
});

// The execution host, booted the canonical way (loads generated/ JS).
const { registry: host, runtime } = await loadRuntime();

check("NativeRegistry.createDefault registers the host natives", () => {
    assert.deepEqual(
        NativeRegistry.createDefault().signatures.sort(),
        [SIG.COMPILE_SOURCE, SIG.EVALUATE, SIG.JS_COMPILE_MODULE, SIG.JS_DRAIN_COMPILED_SOURCES, SIG.JS_EXECUTE,
         // archiveExtension is a DEFAULT extension (a browser can read a .pdb).
         SIG.ENTRY_NAMES, SIG.ENTRY_BYTES,
         ...SIG.ANTLR_NATIVES.map(([, signature]) => signature)].sort());
});

check("NativeRegistry rejects a signature with no runtime-lib hook", () => {
    assert.throws(() => new NativeRegistry().register("nope_String_1__String_1_", () => () => ""), /no runtime-lib hook/);
});

check("every host native signature is a native declared in the loaded PDBs", () => {
    const declaredIn = {
        [SIG.COMPILE_SOURCE]: "platform::pure::functions::meta",
        [SIG.EVALUATE]: "platform::pure::functions::lang",
        [SIG.JS_COMPILE_MODULE]: "platform::extensions::language::javascript",
        [SIG.JS_EXECUTE]: "platform::extensions::language::javascript",
        [SIG.JS_DRAIN_COMPILED_SOURCES]: "platform::extensions::language::javascript",
        [SIG.READ_FILE_BYTES]: "platform::pure::functions::io",
        [SIG.DIRECTORY_TREE]: "platform::pure::functions::io",
        [SIG.WRITE_FILE]: "platform::pure::functions::io",
        [SIG.ENTRY_NAMES]: "platform::pure::compiler::pdb::archive",
        [SIG.ENTRY_BYTES]: "platform::pure::compiler::pdb::archive",
        ...Object.fromEntries(SIG.ANTLR_NATIVES.map(([, signature]) => [signature, "platform::pure::functions::meta::antlr"])),
    };
    for (const [signature, pkg] of Object.entries(declaredIn)) {
        assert.ok(host.hasElement(`${pkg}::${signature}`), `${pkg}::${signature} is not declared`);
    }
});

check("PureRuntime installs each native on its host object", () => {
    assert.equal(globalThis.__pureHost, runtime.host);
    for (const key of ["hostCompileSource", "hostJsCompile", "hostJsExecute", "hostJsDrainCompiledSources", "hostEvaluateFunctionDefinition", "hostReadFileBytes", "hostDirectoryTree", "hostWriteFile", "hostSourceText", "pureParseTop", "metadataRead", "metadataInvoke", "findAllTypes", ...SIG.ANTLR_NATIVES.map(([name]) => name)]) {
        assert.equal(typeof runtime.host[key], "function", key);
    }
});

check("host services live on one global: no __host*, __metadata* or ANTLR globals are installed", () => {
    const leaked = Object.keys(globalThis).filter((k) => /^__(host|metadata)[A-Z]/.test(k) && typeof globalThis[k] !== "function");
    assert.deepEqual(leaked, []);
    // runtime-lib's forwarders reach the host object.
    assert.equal(__metadataPathToElement("platform::pure::metamodel::type::Any", "::"), "platform::pure::metamodel::type::Any");
});

check("PureRuntime has the languages its host named, in order", () => {
    assert.deepEqual(runtime.languages.map((e) => e.name), [...coreLanguages, ...extensionLanguages].map((e) => e.name));
});

check("PureRuntime.parse parses a ###Pure section, and runtime-lib's __pureParseTop reaches it", () => {
    const file = runtime.parse("probe", "Class a::B { name: String[1]; }");
    assert.equal(file.sections.length, 1);
    assert.equal(file.sections[0].parserName, "Pure");
    assert.equal(globalThis.__pureParseTop("probe", "Class a::B { }").sections.length, 1);
});

check("compiler test sections are contributed only when compiler-tests is registered", () => {
    const withTests = new ModuleRegistry().useLanguages(CORE);
    for (const pdb of ["core.pdb", "compiler.pdb", "compiler-tests.pdb"]) withTests.register(PdbModule.open(join(SHARED, pdb)));
    const withoutTests = new ModuleRegistry().useLanguages(CORE);
    withoutTests.register(PdbModule.open(join(SHARED, "core.pdb")));
    // Reached by NAME now: the test sections are a core language from pure/specification/language_test.json,
    // built through the same languageExtensionFor as any extension.
    const testSections = languageNamed("compiler test sections");
    assert.ok(testSections, "the compiler test sections language is not registered");
    assert.ok(testSections.sectionParsers({ registry: withTests }).length > 0);
    assert.equal(testSections.sectionParsers({ registry: withoutTests }).length, 0);
});

check("the extension descriptors come from the extensions' own manifests", () => {
    // No host names a language: every descriptor is generated from pure/extensions/*/language_<name>.json,
    // so adding an extension is a directory on disk rather than an edit to LanguageExtensions.js.
    const diagram = EXTENSION_DESCRIPTORS.find((d) => d.name === "diagram");
    assert.ok(diagram, `no diagram descriptor in [${EXTENSION_DESCRIPTORS.map((d) => d.name).join(", ")}]`);
    assert.deepEqual(diagram.sections, ["Diagram"]);
    assert.equal(diagram.module, "diagram");
    assert.deepEqual(diagram.pdbs, ["pure/extensions/diagram/build/diagram.pdb"]);
    // One translated file per half the manifest declares — parser.js and compiler.js — rather than one
    // opaque code.js. Derived: the package is the element path minus the function name.
    // One file per Pure package: the classes first, then each half. The grammar half sits under
    // grammar/, beside the ANTLR parser it drives (grammar/antlr/).
    // Grouped by subject: grammar/ is text -> model (ANTLR, the mapping rules, the protocol form they
    // produce); compiler/ is model -> graph (the passes, the metamodel they build).
    // pdb/ is graph -> archive: the PDB writer generated from the metamodel.
    assert.deepEqual(diagram.jsPaths, [
        "extensions/diagram/compiler/metamodel.js",
        "extensions/diagram/grammar/protocol.js",
        "extensions/diagram/pdb/gen.js",
        "extensions/diagram/grammar/parser.js",
        "extensions/diagram/compiler/compiler.js",
    ]);
    // The fixture runners are TEST code: translated (so the CLI's suites can run them) but not in jsPaths,
    // which every host loads — the browser IDE included, with the lean PDBs those runners cannot use.
    assert.deepEqual(diagram.testJsPaths, ["extensions/diagram/tests.js"]);
    assert.deepEqual(diagram.translate.map((t) => t.package), [
        "platform::extensions::pure::diagram::metamodel",
        "platform::extensions::pure::diagram::protocol",
        "platform::extensions::pure::diagram::pdb::gen",
        "platform::extensions::pure::diagram::parser",
        "platform::extensions::pure::diagram::compiler",
        "platform::extensions::pure::diagram::tests",
    ]);
    // `pure` is the manifest's own block, re-exported verbatim — the generator copies no field, so a
    // key added to language_<name>.json arrives here untouched.
    assert.ok(diagram.pure.sectionParsers.startsWith("platform::extensions::pure::diagram::"));
    assert.ok(diagram.pure.compilerExtension.startsWith("platform::extensions::pure::diagram::"));
});

check("the Pure language declares its grammar like any other, and Top is not a language", () => {
    // M3 used to be a literal in antlr-natives.js's grammar table beside Top. It is the Pure
    // language's grammar, so it belongs in the Pure language's manifest, and the host builds its
    // entry from here. Dropped from the manifest, the table loses M3Parser and nothing parses — a
    // failure a long way from its cause, which is what this pins.
    const pure = languageNamed("Pure");
    assert.deepEqual(pure.descriptor.grammar, { lexer: "M3Lexer", parser: "M3Parser", entryRule: "definition" });
    // Top frames a document into `###Section` blocks before any language is consulted (`###Diagram`
    // goes through it too), so no descriptor may claim it.
    const claimsTop = (l) => l.descriptor.grammar && l.descriptor.grammar.parser === "TopParser";
    assert.ok(![...coreLanguages, ...extensionLanguages].some(claimsTop), "no language may declare the Top grammar");
});

check("both halves of the diagram language are contributed only when its module is registered", () => {
    const diagram = extensionNamed("diagram");
    const withDiagram = new ModuleRegistry().useLanguages(WITH_DIAGRAM);
    withDiagram.register(PdbModule.open(join(SHARED, "core.pdb")));
    withDiagram.registerExtension(diagram.descriptor, PdbModule.open(join(REPO, diagram.descriptor.pdbs.find((p) => p.endsWith(`/${diagram.descriptor.module}.pdb`)))));
    const withoutDiagram = new ModuleRegistry().useLanguages(CORE);
    withoutDiagram.register(PdbModule.open(join(SHARED, "core.pdb")));
    assert.ok(diagram.sectionParsers({ registry: withDiagram }).length > 0);
    assert.equal(diagram.sectionParsers({ registry: withoutDiagram }).length, 0);
    // The compiler half is what makes `###Diagram` a language rather than carried text.
    assert.ok(diagram.compilerExtensions({ registry: withDiagram }).length > 0);
    assert.equal(diagram.compilerExtensions({ registry: withoutDiagram }).length, 0);
});

check("registerExtension pairs an extension with its module, and validate() enforces it", () => {
    const diagram = extensionNamed("diagram").descriptor;
    // Forgetting the module used to leave the extension silently inert; now it is refused outright.
    const registry = new ModuleRegistry().useLanguages(CORE);
    assert.throws(() => registry.registerExtension(diagram, null), /needs its module 'diagram'/);
    assert.throws(() => registry.registerExtension(diagram, PdbModule.open(join(SHARED, "core.pdb"))),
                  /declares module 'diagram' but was given 'core'/);

    // The extension's module brings dependencies of its own, and they have to be there too. The
    // message blames the extension, because the caller asked for an extension and not for 'compiler'.
    const partial = new ModuleRegistry().useLanguages(CORE);
    partial.register(PdbModule.open(join(SHARED, "core.pdb")));
    partial.registerExtension(diagram, PdbModule.open(join(REPO, diagram.pdbs.find((p) => p.endsWith(`/${diagram.module}.pdb`)))));
    assert.throws(() => partial.validate(), /declares dependency 'compiler' but it was not loaded/);

    // Complete, and valid — and validate() may be called as often as you like.
    const paired = new ModuleRegistry().useLanguages(WITH_DIAGRAM);
    paired.register(PdbModule.open(join(SHARED, "core.pdb")));
    paired.register(PdbModule.open(join(SHARED, "compiler.pdb")));
    paired.registerExtension(diagram, PdbModule.open(join(REPO, diagram.pdbs.find((p) => p.endsWith(`/${diagram.module}.pdb`)))));
    paired.validate().validate();
    assert.equal(paired.extensions.length, 1);

    // A module that goes away after registration is caught by the next validate() — the asymmetric
    // case, since adding can only ever satisfy more dependencies.
    paired.unregister("diagram");
    assert.throws(() => paired.validate(), /Language extension 'diagram' needs module 'diagram'/);
});

check("every language manifest names its sections as a list and none says bundled", () => {
    LANGUAGES.forEach((language) => {
        assert.ok(Array.isArray(language.sections), `${language.name}: sections is a list`);
        assert.equal(language.section, undefined, `${language.name}: no singular section`);
        assert.equal(language.bundled, undefined, `${language.name}: always-active is "no module", not a flag`);
    });
});

check("the Pure language's schema comes from its manifest", () => {
    // ###Pure's own; CORE also holds the test language, whose fragment (its island values) follows m3's.
    assert.equal(languageSchema([languageNamed("Pure").descriptor]), readFileSync(join(SHARED, "specification/m3.fbs"), "utf8"));
    assert.ok(languageSchema(CORE).startsWith(readFileSync(join(SHARED, "specification/m3.fbs"), "utf8")), "m3 first");
    assert.equal(LANGUAGES.find((l) => l.name === "Pure").fbs, "shared/specification/m3.fbs");
});

check("a Pure language without its generated schema fails naming the file", () => {
    const bare = LANGUAGES.map((l) => (l.module ? l : { ...l, fbsText: null }));
    assert.throws(() => languageSchema(bare), /shared\/specification\/m3\.fbs/);
});

check("extension descriptors carry their manifest's pdbs, not a derived pdbPath", () => {
    const diagram = EXTENSION_DESCRIPTORS.find((e) => e.name === "diagram");
    assert.equal(diagram.pdbPath, undefined, "no path is derived from `module`; `pdbs` is read");
    assert.ok(diagram.pdbs.some((p) => p.endsWith("/diagram.pdb")));
});

check("the module paired with an extension is the one its manifest names, wherever it is in pdbs", () => {
    const descriptors = [
        { name: "one", module: "one", pdbs: ["x/one.pdb"] },
        { name: "two", module: "two", pdbs: ["x/helper.pdb", "x/two.pdb"] },
    ];
    const loaded = [{ name: "core" }, { name: "one" }, { name: "helper" }, { name: "two" }];
    const pairs = pairExtensionModules(descriptors, loaded, 1);
    assert.deepEqual(pairs.map((p) => [p.extension.name, p.own?.name, p.others.map((m) => m.name)]),
        [["one", "one", []], ["two", "two", ["helper"]]]);
});

check("a runtime naming only an extension gets ###Pure first", () => {
    const scratch = PureRuntime.builder().withRegistry(new ModuleRegistry()).withNatives(new NativeRegistry())
        .withLanguages([extensionNamed("diagram")]).build();
    try {
        assert.deepEqual(scratch.languages.map((l) => l.name), [...coreLanguages, extensionNamed("diagram")].map((l) => l.name));
    } finally {
        scratch.close();
        globalThis.__pureHost = runtime.host;   // the scratch runtime replaced the suite's host
    }
});

check("a language extension need not have a compiler half", () => {
    // `compilerExtensions` is optional on the contract, so an extension that only parses must
    // not make PureRuntime.compile throw.
    assert.deepEqual(runtime.compilerExtensions !== undefined, true);
    // compiler-pure's test sections declare no `compilerExtension`, so that half contributes nothing.
    // Asserted as BEHAVIOUR rather than as a missing key: every language now comes from a descriptor,
    // so the key is always present and what matters is that it yields nothing.
    assert.deepEqual(languageNamed("compiler test sections").compilerExtensions(runtime), []);
    // `###Pure` does declare one — pureExtension(), which carries m3's PDB writer to the archive
    // writer through the same hook an extension uses — and it is the only one it contributes.
    assert.deepEqual(languageNamed("Pure").compilerExtensions(runtime).map((e) => e.name), ["Pure"]);
});

check("PureRuntime.parse parses a ###Diagram section into diagram elements", () => {
    // The section is a LANGUAGE, not carried text: its parser runs the platform-generated
    // DiagramParser and returns one protocol element per diagram, addressed by its own path.
    // Without the parser this throws "No parser registered for section: ###Diagram", which is
    // what made a source containing a diagram impossible to compile at all.
    const body = "Diagram m::D\n{\n   TypeView tv(type=a::B, position=(1.0, 2.0), width=3.0, height=4.0)\n}";
    const file = runtime.parse("probe", `###Pure\nClass a::B { name: String[1]; }\n\n###Diagram\n${body}\n`);
    assert.deepEqual(file.sections.map((s) => s.parserName), ["Pure", "Diagram"]);
    const [element] = file.sections[1].elements;
    assert.equal(one(element.name), "D");
    assert.equal(one(one(element.package).value), "m");
    const [view] = asArray(element.typeViews);
    assert.equal(one(view.id), "tv");
    assert.equal(Number(one(one(view.position).x)), 1);
    // The parsed form is the GENERATED protocol (from code/metamodel.pure's <<ProtocolInfo.pointer>>
    // slot), so the reference is a Type_Pointer carrying the path as written — not a resolved type.
    // That is what lets a diagram naming a class that no longer exists still parse.
    assert.equal(one(one(view.type).value), "a::B");
});

check("a ###Diagram section compiles to a Diagram element with its types resolved", () => {
    // Both halves of the extension, through the host API: the section parses, and the compiler
    // passes put a Diagram in the graph with each view's `type=` resolved to the class the same
    // compile produced. While `###Diagram` was carried text the element did not exist at all.
    // The source goes into the MODULE that holds it, as the browser IDE and both usage examples do.
    host.module("runtime").setSource("diagram.pure", [
        "###Pure",
        "Class probe::Person { name : String[1]; }",
        "",
        "###Diagram",
        "Diagram probe::D",
        "{",
        "   TypeView tv(type=probe::Person, position=(100.0, 20.0), width=200.0, height=80.0)",
        "}",
        "",
    ].join("\n"));
    const { errors } = runtime.compile();
    assert.deepEqual(errors, [], errors.join("; "));

    const diagram = runtime.registry.getElement("probe::D");
    assert.ok(diagram, "probe::D is not in the graph");
    const view = asArray(diagram.typeViews)[0];
    assert.equal(one(view.id), "tv");
    assert.equal(Number(one(one(view.position).x)), 100);
    // One slot, both sides: the protocol held a path, the metamodel holds the resolved element.
    const resolved = asArray(view.type);
    assert.equal(resolved.length, 1, "pass 2 did not resolve probe::Person");
    assert.equal(one(resolved[0].__purePath ?? one(resolved[0].name)), "Person");
    host.module("runtime").removeSource("diagram.pure");
});

// A golden beside the diagram's compiler fixtures (pure/extensions/diagram/tests/compiler-pdb-serialization,
// regenerated with `just extensions::diagram::regen-pdb-goldens`): every view kind, with an association-
// owned property pointer, read back with THIS host's decoder.
const DIAGRAM_GOLDEN = join(REPO, "pure/extensions/diagram/tests/compiler-pdb-serialization/diagram/propertyAndAssociation.pdb");

check("a PDB holding ###Diagram elements reads back once the diagram language is registered", () => {
    // The host registered the diagram extension, and with it the schema fragment generated from its
    // metamodel — so DiagramDef, TypeViewDef, PropertyViewDef, … decode like any m3 table.
    host.register(PdbModule.open(DIAGRAM_GOLDEN));
    try {
        const d = host.getElement("m::D");
        // No dimension clause: an absent optional reads back EMPTY, not 0.0.
        assert.equal(asArray(d.width).length, 0);
        const [a, b] = asArray(d.typeViews);
        assert.equal(one(a.id), "tv_a");
        assert.equal(one(one(a.type).name), "Person", "a type PointerRef resolves to the class itself");
        assert.equal(Number(one(one(b.position).x)), 100);
        const pv = asArray(d.propertyViews)[0];
        assert.equal(one(one(pv.property).name), "firm");
        assert.equal(one(one(one(pv.property).owner).name), "Employment", "the property pointer resolves to the association's property");
        const av = asArray(d.associationViews)[0];
        assert.equal(one(one(av.association).name), "Employment");
        assert.equal(Number(one(asArray(av.points)[0].x)), 50);
    } finally {
        host.unregister("test");
    }
});

const DIAGRAM_GOLDENS = join(REPO, "pure/extensions/diagram/tests/compiler-pdb-serialization/diagram");

check("a ###Diagram golden reads back every edge styling field and multi-point path", () => {
    host.register(PdbModule.open(join(DIAGRAM_GOLDENS, "complexGeometry.pdb")));
    try {
        const d = host.getElement("m::D");
        assert.equal(Number(one(d.width)), 1920.5);
        assert.equal(Number(one(d.height)), 1080, "an integer in the source reads back as the number");
        const firm = asArray(d.typeViews)[1];
        assert.equal(one(firm.color), "#ffcc00", "a colour keeps the case it was written in");
        assert.equal(one(firm.attributesVisible), false);
        assert.equal(Number(one(one(firm.position).y)), -20);
        const [manager, toFirm] = asArray(d.propertyViews);
        assert.equal(one(manager.source), "tv_person");
        assert.equal(one(manager.target), "tv_person", "a self-loop keeps both ends");
        assert.deepEqual(asArray(manager.points).map(p => [Number(one(p.x)), Number(one(p.y))]),
            [[-70.25, 50], [-40, 50], [-40, 10], [-160.5, 10]]);
        assert.equal(one(manager.color), "#FF0000");
        assert.equal(Number(one(manager.lineWidth)), 2.5);
        assert.equal(one(manager.label), "manager");
        assert.equal(asArray(toFirm.color).length, 0, "an absent colour reads back EMPTY");
        assert.equal(asArray(toFirm.label).length, 0);
        const [left, right] = asArray(d.associationViews);
        assert.equal(one(left.label), "employment");
        assert.equal(one(right.source), "tv_person_copy");
        assert.equal(Number(one(one(asArray(d.generalizationViews)[0]).lineWidth)), 0.5);
    } finally {
        host.unregister("test");
    }
});

check("a ###Diagram golden reads back pointers to class-typed, generic and inherited properties", () => {
    host.register(PdbModule.open(join(DIAGRAM_GOLDENS, "complexTypeProperties.pdb")));
    try {
        const views = asArray(host.getElement("m::D").propertyViews);
        const owned = views.map(v => one(one(one(v.property).owner).name) + "." + one(one(v.property).name));
        assert.deepEqual(owned, ["Person.home", "Person.mailbox", "Person.previous", "Person.archive", "Box.items", "Person.home"],
            "m::Employee.home resolved to the property m::Person declares");
    } finally {
        host.unregister("test");
    }
});

// An island value (a language extension's value specification, which m3's unions hold as an
// ExtensionValueDef) reads back as an object of its own class, its pointers resolved like any other. The
// archive is the specification's island golden for `#REF{m::Person}#`; the schema fragment comes with the
// test language (language_test.json).
const ISLAND_GOLDEN = join(REPO, "pure/specification/compiler/tests-pdb-serialization/valueSpecification/island/ref.pdb");

check("an island value reads back as an object of its own class", () => {
    host.register(PdbModule.open(ISLAND_GOLDEN));
    try {
        const value = asArray(host.getElement("test::island::ref__Any_MANY_").expressionSequence)[0];
        assert.equal(one(one(value.target).name), "Person", "the RefLiteral points at the class m::Person");
    } finally {
        host.unregister("test");
    }
});

check("an island value fails loudly without the language that declares it", () => {
    const registry = new ModuleRegistry().useLanguages([languageNamed("Pure").descriptor]);
    registry.register(PdbModule.open(join(SHARED, "core.pdb")));
    registry.register(PdbModule.open(ISLAND_GOLDEN));
    assert.throws(() => metadataHost(registry).metadataRead("test::island::ref__Any_MANY_", "expressionSequence"), /no schema for RefLiteralDef/);
});

// The Pure language's own island extensions live one level down (pure/extensions/pure/<name>), and are
// found there like any other: an extension is a directory holding language_<name>.json.
check("the TDS extension is found under pure/extensions/pure", () => {
    const tds = EXTENSION_DESCRIPTORS.find((d) => d.name === "tds");
    assert.ok(tds, `no tds descriptor in [${EXTENSION_DESCRIPTORS.map((d) => d.name).join(", ")}]`);
    assert.equal(tds.dir, "pure/tds");
    assert.deepEqual(tds.sections, []);
    assert.deepEqual(tds.pdbs, ["pure/extensions/pure/tds/build/tds.pdb"]);
});

// A TDS golden (pure/extensions/pure/tds/tests/compiler-pdb-serialization, regenerated with
// `just extensions::pure::tds::regen-pdb-goldens`): the `#TDS{…}#` in its fixture's function, decoded by THIS
// host with the schema fragment the tds language declares.
const TDS_GOLDEN = join(REPO, "pure/extensions/pure/tds/tests/compiler-pdb-serialization/tds/typedHeader.pdb");

check("a TDS value reads back as the relation it compiled to", () => {
    host.register(PdbModule.open(TDS_GOLDEN));
    try {
        const tds = asArray(host.getElement("test::tds::typedHeader__Any_MANY_").expressionSequence)[0];
        assert.equal(one(tds.csv).split("\n")[0], "id, name, amount", "csv starts with the column names");
        const relation = one(one(asArray(one(tds.genericType).typeArguments)[0]).type);
        const columns = asArray(relation.columns);
        assert.deepEqual(columns.map((c) => one(c.name)), ["id", "name", "amount"]);
        assert.deepEqual(columns.map((c) => one(one(one(c.genericType).type).name)), ["Integer", "String", "Decimal"],
            "a declared type, an inferred one, and a declared [0..1] one");
    } finally {
        host.unregister("test");
    }
});

check("a TDS value fails loudly without the tds language", () => {
    const registry = new ModuleRegistry().useLanguages(CORE);
    registry.register(PdbModule.open(join(SHARED, "core.pdb")));
    registry.register(PdbModule.open(TDS_GOLDEN));
    assert.throws(() => metadataHost(registry).metadataRead("test::tds::typedHeader__Any_MANY_", "expressionSequence"), /no schema for TDSDef/);
});

check("a PDB holding ###Diagram elements fails loudly without the diagram language", () => {
    const registry = new ModuleRegistry().useLanguages(CORE);
    registry.register(PdbModule.open(join(SHARED, "core.pdb")));
    registry.register(PdbModule.open(DIAGRAM_GOLDEN));
    // Read through THIS registry (element proxies read through the process-wide host, which has the
    // diagram language registered).
    assert.throws(() => metadataHost(registry).metadataRead("m::D", "width"), /no schema for DiagramDef/);
});

check("PureRuntime.execute runs a function element", () => {
    const fn = host.getElement("platform::pure::ui::progress::statusWidth__Integer_1_");
    assert.ok(fn);
    assert.equal(Number(runtime.execute(fn)), 78);
});

check("PureRuntime.execute rejects a value that is not a function element", () => {
    assert.throws(() => runtime.execute({}), /expected a function element/);
});

// Last: closing a runtime removes the hooks it installed.
check("every class declares its properties as accessors", () => {
    // A translated class states its shape in `_<property>()` methods: `_x()` reads the slot,
    // `_x(value)` writes it and returns the receiver. Generated Java does the same (`name()` /
    // `_name(value)`), and the methods go on the PROTOTYPE on purpose — an instance's own keys stay
    // exactly the slots Pure set, which is what the pointer guard relies on (declaring the properties
    // as FIELDS instead broke 100 of the 259 compiler fixtures).
    //
    // Accessors own the single-underscore namespace, so nothing else may: `_kind` and `_type` were
    // runtime-internal tags read by __instanceOf, and a `kind` accessor silently turned
    // `instanceOf(ReadPointerRef)` false and made all 149 pdb goldens differ. This check fails if a
    // class stops carrying its accessors; the names below are what keeps the namespace honest.
    //
    // It sits ABOVE the close check on purpose: reading an element's `properties` is a metadata read,
    // and that check uninstalls `globalThis.__pureHost`.
    const missing = [];
    for (const [path, kind] of host.elementKinds()) {
        if (kind !== "Class") continue;
        const cls = globalThis[path.split("::").join("$")];
        if (typeof cls !== "function") continue;   // the check above owns that failure
        const element = runtime.registry.getElement(path);
        // BOTH lists, mirroring the runtime's __classPropertyNames: an association's far side is in
        // `propertiesFromAssociations`. Checking only `properties` is what let a class ship without
        // `_firm` — invisible until construction went through the setters.
        for (const p of [].concat(element?.properties ?? [], element?.propertiesFromAssociations ?? [])) {
            const name = Array.isArray(p?.name) ? p.name[0] : p?.name;
            if (typeof name !== "string") continue;
            if (typeof cls.prototype["_" + name] !== "function") missing.push(`${path}._${name}`);
        }
    }
    assert.deepEqual(missing.slice(0, 20), [],
        `${missing.length} propert(ies) have no accessor; first few: ${missing.slice(0, 20).join(", ")}`);
});

check("PureRuntime.close uninstalls its host object", () => {
    const natives = new NativeRegistry().register(SIG.JS_DRAIN_COMPILED_SOURCES, () => () => ["x"]);
    const scratch = PureRuntime.builder().withRegistry(host).withLanguages(runtime.languages).withNatives(natives).build();
    assert.deepEqual(globalThis.__pureHost.hostJsDrainCompiledSources(), ["x"]);
    scratch.close();
    assert.equal(globalThis.__pureHost, undefined);
});

check("every class in the graph has a translated JavaScript class", () => {
    // Pure classes are emitted as real JavaScript classes, so `^X(...)` can become `new X(...)`. That
    // only holds if EVERY constructible class was translated — a class whose package is not emitted
    // fails at construction with "X is not defined", in translated code, far from the cause.
    //
    // Nothing else enforces it: the emit set is hand-listed in several places, and a newly registered
    // module whose package nobody added would open the gap silently. This check is what makes it loud.
    const missing = [];
    for (const [path, kind] of host.elementKinds()) {
        if (kind !== "Class") continue;
        const mangled = path.split("::").join("$");
        if (typeof globalThis[mangled] !== "function") missing.push(path);
    }
    assert.deepEqual(missing.slice(0, 20), [],
        `${missing.length} class(es) have no translated JavaScript class; first few: ${missing.slice(0, 20).join(", ")}`);
});

check("an accessor reads and writes the slot it names", () => {
    const Point = globalThis["platform$extensions$pure$diagram$metamodel$Point"];
    const p = new Point()._x(3n)._y(4n);   // construction IS a setter chain; there is no constructor
    assert.equal(p._x(), 3n, "_x() reads the slot");
    assert.equal(p._y(), 4n, "_y() reads the slot");
    // Writing returns the receiver, so the slot and the accessor stay one value.
    assert.equal(p._x(7n), p, "_x(value) returns the receiver");
    assert.equal(p._x(), 7n);
    assert.equal(p.x, 7n, "the accessor writes the SLOT, not a parallel field");
    // The accessors are on the prototype, which is the property the pointer guard depends on.
    assert.deepEqual(Object.keys(p), ["x", "y"], "accessors add no own keys");
});

check("a runtime without withLanguages has ###Pure, once", () => {
    const scratch = PureRuntime.builder().withRegistry(new ModuleRegistry()).withNatives(new NativeRegistry()).build();
    const named = PureRuntime.builder().withRegistry(new ModuleRegistry()).withNatives(new NativeRegistry())
        .withLanguages(coreLanguages).build();
    try {
        assert.deepEqual(scratch.languages.map((l) => l.name), coreLanguages.map((l) => l.name));
        assert.deepEqual(named.languages.map((l) => l.name), coreLanguages.map((l) => l.name));
    } finally {
        named.close();
        scratch.close();
        globalThis.__pureHost = runtime.host;   // the scratch runtimes replaced the suite's host
    }
});

check("a runtime without withNatives fails naming it", () => {
    assert.throws(() => PureRuntime.builder().withRegistry(new ModuleRegistry())
        .withLanguages(coreLanguages).build(), /withNatives\(/);
});

check("a registry takes no schema", () => {
    assert.throws(() => new ModuleRegistry("table X {}"), /takes no schema/);
});

check("a registry read before it has languages fails naming withLanguages", () => {
    const registry = new ModuleRegistry();
    registry.register(PdbModule.open(join(SHARED, "core.pdb")));
    assert.throws(() => metadataHost(registry).metadataRead(ANY, "name"), /withLanguages/);
});

check("the schema is the named languages': ###Pure's first, then the extensions'", () => {
    const diagram = extensionNamed("diagram").descriptor;
    const text = languageSchema(WITH_DIAGRAM);
    assert.ok(text.startsWith(readFileSync(join(SHARED, "specification/m3.fbs"), "utf8")));
    assert.ok(text.includes(diagram.fbsText));
    assert.ok(!languageSchema(CORE).includes(diagram.fbsText));
});

await checkAsync("buildAsync registers only the named languages' modules", async () => {
    const opened = [];
    const loader = async (paths) => paths.map((p) => { opened.push(p); return PdbModule.open(join(REPO, p)); });
    const registry = new ModuleRegistry();
    const scratch = await PureRuntime.builder().withRegistry(registry).withLanguages(coreLanguages)
        .withNatives(new NativeRegistry()).withModuleLoader(loader).buildAsync();
    try {
        assert.ok(!registry.module("diagram"), `diagram registered from [${opened.join(", ")}]`);
        assert.ok(registry.module("core"), "###Pure's own modules are registered");
    } finally {
        scratch.close();
        globalThis.__pureHost = runtime.host;   // the scratch runtime replaced the suite's host
    }
});

check("the registry API: register/registerAll/validate return the registry, unregister the module", () => {
    const a = new InMemoryModule("a", []);
    const b = new InMemoryModule("b", ["a"]);
    const registry = new ModuleRegistry();
    assert.equal(registry.registerAll([a, b]), registry);
    assert.deepEqual(registry.modules.map((m) => m.name), ["a", "b"]);
    assert.equal(registry.validate(), registry);
    assert.equal(registry.unregister("b"), b);
    assert.equal(registry.unregister("b"), null);
    assert.equal(registry.register(b), registry);
});

check("a translated instance without a classifier is matched by its own class, not by the first arm", () => {
    // __newCgt builds a GenericTypeValue with no classifierGenericType of its own (the translator emits
    // one for every ^X(...) of a class without a canonical anchor — a Diagram's, say). A match must not
    // take it for a GenericTypeOperation just because nothing says otherwise.
    const gtv = globalThis.__newCgt({ type: globalThis.__pureResolve("platform::pure::metamodel::type::Any") });
    assert.equal(globalThis.__matchType(gtv, "platform::pure::metamodel::type::generics::GenericTypeOperation", 1, 1), false);
    assert.equal(globalThis.__matchType(gtv, "platform::pure::metamodel::type::generics::GenericTypeValue", 1, 1), true);
    assert.equal(globalThis.__matchType(gtv, "platform::pure::metamodel::type::generics::GenericType", 1, 1), true);
});

check("a classifier-less instance of a class the registry does not know stays permissively matched", () => {
    // A class compiled at run time (compileSource, the IDE) has a translated class but no PDB entry, so
    // the registry cannot answer for its supertypes — it must keep matching as it did, not fail every arm.
    // The class's NAME is what the match reads, so it is set to a mangled path the registry has never seen.
    const Unregistered = { ["platform$probe$Unregistered"]: class {} }["platform$probe$Unregistered"];
    assert.equal(globalThis.__matchType(new Unregistered(), "platform::pure::metamodel::type::generics::GenericType", 1, 1), true);
});

// --- summary (later tasks insert their checks above this line) ---------------
const failures = results.filter((r) => r.error);
for (const f of failures) console.log(`  FAIL ${f.name}\n    ${f.error.message}`);
console.log(`host api: ${results.length - failures.length}/${results.length} passed`);
report.write();
process.exit(failures.length ? 1 : 0);
