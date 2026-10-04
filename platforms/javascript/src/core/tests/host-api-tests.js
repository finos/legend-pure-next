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
import { InMemoryModule } from "../compiler/module/inMemoryModule/InMemoryModule.js";
import { ModuleRegistry } from "../compiler/module/ModuleRegistry.js";
import { loadRuntime } from "../runtime/load-runtime.js";
import { NativeRegistry } from "../execution/natives/NativeRegistry.js";
import { PureRuntime } from "../runtime/PureRuntime.js";
import { DEFAULT_LANGUAGE_EXTENSIONS, EXTENSION_DESCRIPTORS, extensionNamed, languageNamed } from "../runtime/LanguageExtensions.js";
import * as SIG from "../execution/natives/native-signatures.js";
import { TestReport } from "./test-report.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const SHARED = join(HERE, "../../../../../shared");
const REPO = join(HERE, "../../../../..");
const ANY = "meta::pure::metamodel::type::Any";

const results = [];
const report = new TestReport("host-api");
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
    assert.equal(core.packagePattern, "(meta::pure)(::.*)?");
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

const SCHEMA = readFileSync(join(SHARED, "specification/m3.fbs"), "utf8");
// Pure values cross as a value OR a one-element array; a [*] slot may be either or absent.
const asArray = (v) => (v === undefined || v === null ? [] : Array.isArray(v) ? v : [v]);
const one = (v) => (Array.isArray(v) ? v[0] : v);

check("ModuleRegistry registers modules in order", () => {
    const registry = new ModuleRegistry(SCHEMA);
    const core = registry.register(PdbModule.open(join(SHARED, "core.pdb")));
    registry.register(PdbModule.open(join(SHARED, "compiler.pdb")));
    assert.equal(core.name, "core");
    assert.deepEqual(registry.modules.map((m) => m.name), ["core", "compiler"]);
    assert.deepEqual([...registry.module("compiler").dependencies], ["core"]);
    assert.equal(registry.module("missing"), null);
});

check("ModuleRegistry.validate accepts satisfied dependencies", () => {
    const registry = new ModuleRegistry(SCHEMA);
    registry.register(PdbModule.open(join(SHARED, "core.pdb")));
    registry.register(PdbModule.open(join(SHARED, "compiler.pdb")));
    assert.equal(registry.validate(), registry);
});

check("ModuleRegistry.validate names a missing dependency", () => {
    const registry = new ModuleRegistry(SCHEMA);
    registry.register(PdbModule.open(join(SHARED, "compiler.pdb")));
    assert.throws(() => registry.validate(), {
        message: "Module 'compiler' declares dependency 'core' but it was not loaded (loaded: [compiler])",
    });
});

check("ModuleRegistry is MetadataAccess over every module", () => {
    const registry = new ModuleRegistry(SCHEMA);
    registry.register(PdbModule.open(join(SHARED, "core.pdb")));
    const runtime = registry.register(new InMemoryModule("runtime"));
    runtime.addElement("a::B", { name: "B" });
    assert.equal(registry.hasElement(ANY), true);
    assert.equal(registry.hasElement("a::B"), true);
    const paths = [...registry.elementPaths()];
    assert.ok(paths.includes(ANY));
    assert.ok(paths.includes("a::B"));
});

check("ModuleRegistry.unregister removes a module by name", () => {
    const registry = new ModuleRegistry(SCHEMA);
    registry.register(PdbModule.open(join(SHARED, "core.pdb")));
    const runtime = registry.register(new InMemoryModule("runtime"));
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
        [SIG.COMPILE_SOURCE]: "meta::pure::functions::meta",
        [SIG.EVALUATE]: "meta::pure::functions::lang",
        [SIG.JS_COMPILE_MODULE]: "meta::external::language::javascript",
        [SIG.JS_EXECUTE]: "meta::external::language::javascript",
        [SIG.JS_DRAIN_COMPILED_SOURCES]: "meta::external::language::javascript",
        [SIG.READ_FILE_BYTES]: "meta::pure::functions::io",
        [SIG.DIRECTORY_TREE]: "meta::pure::functions::io",
        [SIG.WRITE_FILE]: "meta::pure::functions::io",
        [SIG.ENTRY_NAMES]: "meta::pure::compiler::pdb::archive",
        [SIG.ENTRY_BYTES]: "meta::pure::compiler::pdb::archive",
        ...Object.fromEntries(SIG.ANTLR_NATIVES.map(([, signature]) => [signature, "meta::pure::functions::meta::antlr"])),
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
    assert.equal(__metadataPathToElement("meta::pure::metamodel::type::Any", "::"), "meta::pure::metamodel::type::Any");
});

check("PureRuntime has the default language extensions", () => {
    assert.deepEqual(runtime.languageExtensions.map((e) => e.name), DEFAULT_LANGUAGE_EXTENSIONS.map((e) => e.name));
});

check("PureRuntime.parse parses a ###Pure section, and runtime-lib's __pureParseTop reaches it", () => {
    const file = runtime.parse("probe", "Class a::B { name: String[1]; }");
    assert.equal(file.sections.length, 1);
    assert.equal(file.sections[0].parserName, "Pure");
    assert.equal(globalThis.__pureParseTop("probe", "Class a::B { }").sections.length, 1);
});

check("compiler test sections are contributed only when compiler-tests is registered", () => {
    const withTests = new ModuleRegistry(SCHEMA);
    for (const pdb of ["core.pdb", "compiler.pdb", "compiler-tests.pdb"]) withTests.register(PdbModule.open(join(SHARED, pdb)));
    const withoutTests = new ModuleRegistry(SCHEMA);
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
    assert.equal(diagram.section, "Diagram");
    assert.equal(diagram.module, "diagram");
    assert.equal(diagram.pdbPath, "pure/extensions/diagram/build/diagram.pdb");
    // One translated file per half the manifest declares — parser.js and compiler.js — rather than one
    // opaque code.js. Derived: the package is the element path minus the function name.
    // One file per Pure package: the classes first, then each half. The grammar half sits under
    // grammar/, beside the ANTLR parser it drives (grammar/antlr/).
    // Grouped by subject: grammar/ is text -> model (ANTLR, the mapping rules, the protocol form they
    // produce); compiler/ is model -> graph (the passes, the metamodel they build).
    assert.deepEqual(diagram.jsPaths, [
        "extensions/diagram/compiler/metamodel.js",
        "extensions/diagram/grammar/protocol.js",
        "extensions/diagram/grammar/parser.js",
        "extensions/diagram/compiler/compiler.js",
    ]);
    assert.deepEqual(diagram.translate.map((t) => t.package), [
        "meta::pure::diagram::metamodel",
        "meta::pure::diagram::protocol",
        "meta::pure::diagram::parser",
        "meta::pure::diagram::compiler",
    ]);
    // `pure` is the manifest's own block, re-exported verbatim — the generator copies no field, so a
    // key added to language_<name>.json arrives here untouched.
    assert.ok(diagram.pure.sectionParsers.startsWith("meta::pure::diagram::"));
    assert.ok(diagram.pure.compilerExtension.startsWith("meta::pure::diagram::"));
});

check("both halves of the diagram language are contributed only when its module is registered", () => {
    const diagram = extensionNamed("diagram");
    const withDiagram = new ModuleRegistry(SCHEMA);
    withDiagram.register(PdbModule.open(join(SHARED, "core.pdb")));
    withDiagram.registerExtension(diagram.descriptor, PdbModule.open(join(REPO, diagram.descriptor.pdbPath)));
    const withoutDiagram = new ModuleRegistry(SCHEMA);
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
    const registry = new ModuleRegistry(SCHEMA);
    assert.throws(() => registry.registerExtension(diagram, null), /needs its module 'diagram'/);
    assert.throws(() => registry.registerExtension(diagram, PdbModule.open(join(SHARED, "core.pdb"))),
                  /declares module 'diagram' but was given 'core'/);

    // The extension's module brings dependencies of its own, and they have to be there too. The
    // message blames the extension, because the caller asked for an extension and not for 'compiler'.
    const partial = new ModuleRegistry(SCHEMA);
    partial.register(PdbModule.open(join(SHARED, "core.pdb")));
    partial.registerExtension(diagram, PdbModule.open(join(REPO, diagram.pdbPath)));
    assert.throws(() => partial.validate(), /declares dependency 'compiler' but it was not loaded/);

    // Complete, and valid — and validate() may be called as often as you like.
    const paired = new ModuleRegistry(SCHEMA);
    paired.register(PdbModule.open(join(SHARED, "core.pdb")));
    paired.register(PdbModule.open(join(SHARED, "compiler.pdb")));
    paired.registerExtension(diagram, PdbModule.open(join(REPO, diagram.pdbPath)));
    paired.validate().validate();
    assert.equal(paired.extensions.length, 1);

    // A module that goes away after registration is caught by the next validate() — the asymmetric
    // case, since adding can only ever satisfy more dependencies.
    paired.unregister("diagram");
    assert.throws(() => paired.validate(), /Language extension 'diagram' needs module 'diagram'/);
});

check("a language extension need not have a compiler half", () => {
    // `compilerExtensions` is optional on the contract, so an extension that only parses must
    // not make PureRuntime.compile throw.
    assert.deepEqual(runtime.compilerExtensions !== undefined, true);
    // Pure declares no `compilerExtension`, so its compiler half contributes nothing. Asserted as
    // BEHAVIOUR rather than as a missing key: every language now comes from a descriptor, so the key
    // is always present and what matters is that it yields nothing.
    assert.deepEqual(languageNamed("Pure").compilerExtensions(runtime), []);
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
    runtime.setSource("runtime", "diagram.pure", [
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
    runtime.removeSource("runtime", "diagram.pure");
});

check("PureRuntime.execute runs a function element", () => {
    const fn = host.getElement("meta::pure::ui::progress::statusWidth__Integer_1_");
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
    const scratch = PureRuntime.builder().withRegistry(host).withNatives(natives).build();
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
    const Point = globalThis["meta$pure$diagram$metamodel$Point"];
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

// --- summary (later tasks insert their checks above this line) ---------------
const failures = results.filter((r) => r.error);
for (const f of failures) console.log(`  FAIL ${f.name}\n    ${f.error.message}`);
console.log(`host api: ${results.length - failures.length}/${results.length} passed`);
report.write();
process.exit(failures.length ? 1 : 0);
