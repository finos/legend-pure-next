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
import { DEFAULT_LANGUAGE_EXTENSIONS, compilerTestSectionsExtension } from "../runtime/LanguageExtensions.js";
import * as SIG from "../execution/natives/native-signatures.js";
import { TestReport } from "./test-report.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const SHARED = join(HERE, "../../../../../shared");
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
        [SIG.COMPILE_SOURCE, SIG.EVALUATE, SIG.JS_COMPILE_MODULE, SIG.JS_DRAIN_COMPILED_SOURCES, SIG.JS_EXECUTE, ...SIG.ANTLR_NATIVES.map(([, signature]) => signature)].sort());
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
        [SIG.READ_FILE]: "meta::pure::functions::io",
        [SIG.READ_FILE_BYTES]: "meta::pure::functions::io",
        [SIG.DIRECTORY_TREE]: "meta::pure::functions::io",
        [SIG.WRITE_FILE]: "meta::pure::functions::io",
        ...Object.fromEntries(SIG.ANTLR_NATIVES.map(([, signature]) => [signature, "meta::pure::functions::meta::antlr"])),
    };
    for (const [signature, pkg] of Object.entries(declaredIn)) {
        assert.ok(host.hasElement(`${pkg}::${signature}`), `${pkg}::${signature} is not declared`);
    }
});

check("PureRuntime installs each native on its host object", () => {
    assert.equal(globalThis.__pureHost, runtime.host);
    for (const key of ["hostCompileSource", "hostJsCompile", "hostJsExecute", "hostJsDrainCompiledSources", "hostEvaluateFunctionDefinition", "hostReadFile", "hostReadFileBytes", "hostDirectoryTree", "hostWriteFile", "hostSourceText", "pureParseTop", "metadataRead", "metadataInvoke", "findAllTypes", ...SIG.ANTLR_NATIVES.map(([name]) => name)]) {
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
    assert.ok(compilerTestSectionsExtension.sectionParsers({ registry: withTests }).length > 0);
    assert.equal(compilerTestSectionsExtension.sectionParsers({ registry: withoutTests }).length, 0);
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
check("PureRuntime.close uninstalls its host object", () => {
    const natives = new NativeRegistry().register(SIG.JS_DRAIN_COMPILED_SOURCES, () => () => ["x"]);
    const scratch = PureRuntime.builder().withRegistry(host).withNatives(natives).build();
    assert.deepEqual(globalThis.__pureHost.hostJsDrainCompiledSources(), ["x"]);
    scratch.close();
    assert.equal(globalThis.__pureHost, undefined);
});

// --- summary (later tasks insert their checks above this line) ---------------
const failures = results.filter((r) => r.error);
for (const f of failures) console.log(`  FAIL ${f.name}\n    ${f.error.message}`);
console.log(`host api: ${results.length - failures.length}/${results.length} passed`);
report.write();
process.exit(failures.length ? 1 : 0);
