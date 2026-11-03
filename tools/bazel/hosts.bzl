# ©2026 JP Morgan Chase & Co. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""The test hosts, and the one test per (owner suite × host variant) they generate (docs/superpowers/specs/
2026-11-01-bazel-migration-design.md, Section 2).

A suite is declared once, by its owner, in its module.json (@pure_suites); what it loads is derived from the
dependencies and testDependencies of the manifests; what runs it is the host description here.
"""

load("@pure_suites//:suites.bzl", "MODULES")
load("@rules_shell//shell:sh_test.bzl", "sh_test")

_CORE = "//pure/specification"

_PARSER_MAPPINGS = "//pure/specification:parser-mappings.pdb"

# The M3 schema beside the fragments of the test island values, as a reader of the schema expects (//:shared).
_M3_FBS = "//:shared/specification/m3.fbs"

# The PDBs of the modules the bootstrap builds where the others do not: {module: label}. Every other module 'm' is
# '<its directory>:build/m.pdb', and its tests '<its directory>:build/m-tests.pdb'.
_PDBS = {
    "core": _CORE + ":core.pdb",
    "core-tests": _CORE + ":core-tests.pdb",
    "compiler": "//pure/specification:compiler.pdb",
    "compiler-tests": "//pure/specification:compiler-tests.pdb",
    "core_metamodel_pdb_writer": "//pure/specification:core_metamodel_pdb_writer.pdb",
    "island_pdb_writer": "//pure/specification:island_pdb_writer-with-tests.pdb",
    "parser-mappings": _PARSER_MAPPINGS,
}

# What is generated from a PDB, compiled after it, so no manifest of the compiler can name it: what loads the first
# loads the second right after. {module: what is generated from it}.
_GENERATED_FROM = {
    "compiler": "core_metamodel_pdb_writer",
    "compiler-tests": "island_pdb_writer",
}

def _pdb(module, overrides = {}):
    if module in overrides:
        return overrides[module]
    if module in _PDBS:
        return _PDBS[module]
    base = module.removesuffix("-tests")
    return "//%s:build/%s.pdb" % (MODULES[base]["dir"], module)

def _closure(names):
    """The modules 'names' need, dependencies first, each once (Starlark has no recursion: a fixed point)."""
    order = []
    pending = list(names)
    for _ in range(100):
        if not pending:
            return order
        name = pending[0]
        deps = [d for d in (MODULES[name]["dependencies"] if name in MODULES else []) if d not in order]
        if deps:
            pending = deps + [p for p in pending if p not in deps]
        else:
            pending = pending[1:]
            if name not in order:
                order.append(name)
    fail("module dependencies too deep (a cycle?): %s" % names)

def _loads(owner, prelude, overrides = {}):
    """What a suite of 'owner' loads: the host prelude and the closure of the owner and its test dependencies, then
    the test PDBs (the owner and its '-tests' test dependencies), each after what it depends on."""
    manifest = MODULES[owner]
    test_deps = manifest["testDependencies"]
    mains = _closure(prelude + [owner] + [d for d in test_deps if not d.endswith("-tests")])
    wanted = [owner + "-tests"] + [d for d in test_deps if d.endswith("-tests")]
    tests = [m + "-tests" for m in mains if m + "-tests" in wanted and m + "-tests" not in mains]
    loaded = []
    for module in mains + tests:
        for m in [module, _GENERATED_FROM.get(module)]:
            if m and m not in loaded:
                loaded.append(m)
    return [_pdb(m, overrides) for m in loaded]

def _variant_loads(owner, variant):
    """The PDBs a variant gives a suite of 'owner': all it loads, but those the host loads itself; then what the
    variant always adds."""
    supplied = [_pdb(m) for m in variant.get("supplies", [])]
    pdbs = [pdb for pdb in _loads(owner, variant["prelude"], variant.get("pdbs", {})) if pdb not in supplied]
    return pdbs + [pdb for pdb in variant.get("extra_pdbs", []) if pdb not in pdbs]

def _normalize(path):
    """A path with its '.' and '..' segments resolved."""
    parts = []
    for part in path.split("/"):
        if part == "..":
            parts = parts[:-1]
        elif part not in ["", "."]:
            parts.append(part)
    return "/".join(parts)

# The runners every host runs (Section 2.2): {runner: (function, arguments)}; in the arguments '{target}' is the
# suite target (a package, or a directory relative to the owner), '{report}' its report, '{m3_fbs}' the M3 schema.
RUNNERS = {
    "tests": ("platform::pure::test::runTests_String_1__String_1__String_1_", ["{target}", "{report}"]),
    "pct": ("platform::pure::test::runPCTTests_String_1__String_1__String_1_", ["{target}", "{report}"]),
    "compiler": ("platform::pure::compiler::test::runCompiledGraphTests_String_1__String_1__String_1_", ["{target}", "{report}"]),
    "grammar": ("platform::pure::test::grammar::runGrammarCorpus_String_1__String_1__String_1_", ["{target}", "{report}"]),
    "printerCompiled": ("platform::pure::compiler::test::printer::runCompiledPrinterCorpus_String_1__String_1__String_1_", ["{target}", "{report}"]),
    "printerProtocol": ("platform::pure::functions::printer::protocol::tests::runProtocolPrinterCorpus_String_1__String_1__String_1_", ["{target}", "{report}"]),
    # The goldens of a fixture directory sit beside it, in '<directory>-pdb-serialization'.
    "pdbGolden": ("platform::pure::compiler::test::pdb::runPdbGoldenTests_String_1__String_1__String_1__String_1__String_1_", ["{target}", "{target}-pdb-serialization", "{m3_fbs}", "{report}"]),
}

_PACKAGE_RUNNERS = ["tests", "pct"]

# TRANSITIONAL (Section 2.2): the owners whose suites still run a runner of their own, until it becomes the shared
# one: {(owner, runner): (function, arguments)}. '{owner_fbs}' is the schema fragment of the owner.
_OWNER_RUNNERS = {
    ("diagram", "grammar"): ("platform::extensions::pure::diagram::tests::runGrammarTests_String_1__String_1__String_1_", ["{target}", "{report}"]),
    ("diagram", "compiler"): ("platform::extensions::pure::diagram::tests::runCompilerTests_String_1__String_1__String_1_", ["{target}", "{report}"]),
    ("diagram", "pdbGolden"): ("platform::extensions::pure::diagram::tests::runPdbGoldenTests_String_1__String_1__String_1__String_1__String_1__String_1_", ["{target}", "{target}-pdb-serialization", "{m3_fbs}", "{owner_fbs}", "{report}"]),
    ("tds", "compiler"): ("platform::extensions::pure::tds::tests::runCompilerTests_String_1__String_1__String_1_", ["{target}", "{report}"]),
    ("tds", "printerCompiled"): ("platform::extensions::pure::tds::tests::runPrinterCorpus_String_1__String_1__String_1_", ["{target}", "{report}"]),
    ("tds", "pdbGolden"): ("platform::extensions::pure::tds::tests::runPdbGoldenTests_String_1__String_1__String_1__String_1__String_1__String_1_", ["{target}", "{target}-pdb-serialization", "{m3_fbs}", "{owner_fbs}", "{report}"]),
}

def _runner(owner, runner):
    """The function and arguments a suite of 'owner' runs 'runner' with."""
    if (owner, runner) in _OWNER_RUNNERS:
        return _OWNER_RUNNERS[(owner, runner)]
    if runner in RUNNERS:
        return RUNNERS[runner]
    fail("unknown runner '%s' of '%s': one of %s" % (runner, owner, sorted(RUNNERS.keys())))

def _owner_fbs(owner):
    return "//%s:generated/%s.fbs" % (MODULES[owner]["dir"], owner)

_JAVA_CLI = "//platforms/java/legend-pure-next-platform-java/legend-pure-next-platform-java-interfaces/legend-pure-next-platform-java-cli:pure-java"

_TRUFFLE_CLI = "//platforms/truffle/legend-pure-next-platform-truffle/legend-pure-next-platform-truffle-interfaces/legend-pure-next-platform-truffle-cli:pure-truffle"

# The hosts (Section 2.3). Each variant: its report name, the command, the PDBs it always loads, and the suites it
# runs (a capability: what it cannot run is derived here, with the reason, never listed per test).
HOSTS = {
    "truffle": {
        "tool": _TRUFFLE_CLI,
        # Every suite loads core-tests: the runners are its <<test.TestDependency>> functions.
        "variants": {
            "jit": {
                "report": "pure@cli-truffle",
                "command": ["execute", "--strict-jit"],
                # Truffle parses with the compiler written in Pure.
                "prelude": ["core", "parser-mappings", "core-tests", "compiler"],
                "runs": lambda owner, test: _runs_javascript_free(owner),
            },
            # The native image: no JIT, so no strict JIT check.
            "native": {
                "tool": "//platforms/truffle/legend-pure-next-platform-truffle/legend-pure-next-platform-truffle-interfaces/legend-pure-next-platform-truffle-cli:pure-truffle-native",
                "report": "pure@cli-truffle-native",
                "command": ["execute"],
                "prelude": ["core", "parser-mappings", "core-tests", "compiler"],
                # A native image has no Java compiler at run time (compileAndExecute): not the suites of the Java
                # language model, nor those of the JavaScript translator.
                "runs": lambda owner, test: _runs_javascript_free(owner) and "java" not in _closure([owner]),
            },
            # The compiler corpus on compiler-pure as Truffle itself compiles it (self-host): on demand.
            "selfhost": {
                "report": "pure@cli-truffle+selfhost",
                "command": ["execute"],
                "prelude": ["core", "parser-mappings", "core-tests", "compiler"],
                "pdbs": {
                    "compiler": "//platforms/truffle:selfhost_truffle/compiler.pdb",
                    "compiler-tests": "//platforms/truffle:selfhost_truffle/compiler-tests.pdb",
                    "core_metamodel_pdb_writer": "//platforms/truffle:selfhost_truffle/core_metamodel_pdb_writer.pdb",
                    "island_pdb_writer": "//platforms/truffle:selfhost_truffle/island_pdb_writer-with-tests.pdb",
                },
                "tags": ["manual", "selfhost"],
                "runs": lambda owner, test: owner == "compiler" and test["runner"] == "compiler",
            },
            # The compiler corpus on compiler-pure as the native image compiles it (self-host): on demand.
            "native_selfhost": {
                "tool": "//platforms/truffle/legend-pure-next-platform-truffle/legend-pure-next-platform-truffle-interfaces/legend-pure-next-platform-truffle-cli:pure-truffle-native",
                "report": "pure@cli-truffle-native+selfhost",
                "command": ["execute"],
                "prelude": ["core", "parser-mappings", "core-tests", "compiler"],
                "pdbs": {
                    "compiler": "//platforms/truffle:selfhost_truffle_native/compiler.pdb",
                    "compiler-tests": "//platforms/truffle:selfhost_truffle_native/compiler-tests.pdb",
                    "core_metamodel_pdb_writer": "//platforms/truffle:selfhost_truffle_native/core_metamodel_pdb_writer.pdb",
                    "island_pdb_writer": "//platforms/truffle:selfhost_truffle_native/island_pdb_writer-with-tests.pdb",
                },
                "tags": ["manual", "selfhost"],
                "runs": lambda owner, test: owner == "compiler" and test["runner"] == "compiler",
            },
            # The owner compiled from its sources in memory, on top of core.pdb, in place of its PDBs.
            "in_memory": {
                "report": "pure@cli-truffle+inMemory",
                "command": ["execute-from-memory"],
                # The owner tests come from its sources, runners included: no test PDB.
                "prelude": ["core", "parser-mappings", "compiler"],
                "runs": lambda owner, test: bool(MODULES[owner]["sources"]) and test["runner"] in _PACKAGE_RUNNERS,
            },
        },
        "default": "jit",
        # Suites whose Graal compilations may bail out on a graph just over the size limit (the strict JIT check
        # fails on them): the compiler corpus and its unit tests.
        "lenient_jit": ["compiler/compiler", "compiler/compiler-unit"],
    },
    # The bootstrap: a host for the specification only (Section 1), and on demand (Section 3).
    "bootstrap": {
        "tool": "//bootstrap/legend-pure-next-bootstrap/legend-pure-next-bootstrap-interfaces/legend-pure-next-bootstrap-cli:pure-bootstrap",
        "default": "cli",
        "variants": {
            "cli": {
                "report": "pure@cli-bootstrap",
                "command": ["execute"],
                "prelude": ["core", "core-tests", "compiler"],
                # Interpreted: its PDB goldens take over 15 minutes (Bazel's large timeout) on a CI runner.
                "timeout": "eternal",
                "tags": ["manual", "bootstrap"],
                # And the legacy layer, which passes only on the bootstrap.
                "runs": lambda owner, test: owner in ["core", "compiler", "legacy", "legacy_loader"],
            },
            # The compiler corpus on compiler-pure as the compiler written in Pure, run by the bootstrap, compiles it.
            "selfhost": {
                "report": "pure@cli-bootstrap+selfhost",
                "command": ["execute"],
                "prelude": ["core", "core-tests", "compiler"],
                "pdbs": {
                    "compiler": "//bootstrap:selfhost_bootstrap/compiler.pdb",
                    "compiler-tests": "//bootstrap:selfhost_bootstrap/compiler-tests.pdb",
                    "core_metamodel_pdb_writer": "//bootstrap:selfhost_bootstrap/core_metamodel_pdb_writer.pdb",
                    "island_pdb_writer": "//bootstrap:selfhost_bootstrap/island_pdb_writer-with-tests.pdb",
                },
                "tags": ["manual", "selfhost", "bootstrap"],
                "runs": lambda owner, test: owner == "compiler" and test["runner"] == "compiler",
            },
        },
    },
    # The JavaScript platform: Pure translated to JavaScript, run by Node.
    "javascript": {
        "tool": "//platforms/javascript:pure-js",
        "default": "cli",
        "variants": {
            "cli": {
                "report": "pure@cli-javascript",
                "command": ["execute"],
                "prelude": ["core", "core-tests", "compiler"],
                # Its own modules: the JavaScript metamodel and the Pure -> JavaScript translator.
                "extra_pdbs": [
                    "//pure/modules/language/javascript:build/javascript.pdb",
                    "//pure/modules/translation/shared:build/translation-shared.pdb",
                    "//pure/modules/translation/javascript:build/javascript-translation.pdb",
                    "//pure/modules/translation/javascript:build/javascript-translation-tests.pdb",
                ],
                # It cannot compile and run Java (compileAndExecute): not the suites of the Java language model.
                "runs": lambda owner, test: "java" not in _closure([owner]),
            },
            # The PCT through the JavaScript translator: each test translated and run as JavaScript.
            "translated": {
                "report": "javascript-translation@cli-javascript",
                "command": ["execute"],
                "prelude": ["core", "core-tests", "compiler"],
                "extra_pdbs": [
                    "//pure/modules/language/javascript:build/javascript.pdb",
                    "//pure/modules/translation/shared:build/translation-shared.pdb",
                    "//pure/modules/translation/javascript:build/javascript-translation.pdb",
                    "//pure/modules/translation/javascript:build/javascript-translation-tests.pdb",
                ],
                "runners": {
                    "pct": ("platform::extensions::language::javascript::translation::runPCTTestsViaTranslator_String_1__String_1__String_1_", ["{target}", "{report}"]),
                },
                "runs": lambda owner, test: test["runner"] == "pct",
            },
        },
    },
    # The Java platform: Pure translated to Java. It loads the language PDBs from their manifests, and its own
    # modules (PlatformModules), below --root: it is given the others.
    "java": {
        "tool": _JAVA_CLI,
        "default": "platform",
        "variants": {
            "platform": {
                "report": "java@platform",
                "command": ["--root", "."],
                "prelude": ["core-tests"],
                # The PDBs it loads itself: the languages (language_*.json) and its own modules.
                "supplies": ["core", "parser-mappings", "compiler", "core_metamodel_pdb_writer", "diagram", "tds", "java", "translation-shared", "java-translation"],
                # The test companion of the translator the platform runs.
                "extra_pdbs": ["//pure/modules/translation/java:build/java-translation-tests.pdb"],
                "data": ["//platforms/java:runtime_data"],
                "runs": lambda owner, test: _runs_javascript_free(owner),
            },
        },
    },
}

# Known gaps (Section 2.5): {(host, variant, owner, suite): reason}. Each runs, and its test passes while the suite
# fails; it fails once the suite passes, asking for its entry to be removed, so the list only shrinks.
PENDING = {
    ("java", "platform", "relation", "pct"): "the relation natives have no Java coder yet (spec Section 6: relation on Java and JavaScript): 'No coder for Pure function: size'",
    ("javascript", "cli", "relation", "pct"): "the relation natives have no JavaScript coder yet (spec Section 6: relation on Java and JavaScript), so the PCT tests that call them are not translated: 'no translated function for ...::size::testSimpleSize_Function_1__Boolean_1_'",
    ("javascript", "translated", "relation", "pct"): "the relation natives have no JavaScript coder yet (spec Section 6: relation on Java and JavaScript), so the PCT tests that call them are not translated: 'no translated function for ...::size::testSimpleSize_Function_1__Boolean_1_'",
    # The legacy layer, which passes only on the bootstrap.
    ("truffle", "jit", "legacy", "unit"): "3 of 35 bridge tests fail on Truffle: 'type parameter T could not be resolved at runtime' (natives::testEnumValuesOfAView), and a dynamic new from a view returns no value (testDynamicNewFromAViewOf*)",
    ("truffle", "native", "legacy", "unit"): "3 of 35 bridge tests fail on Truffle: 'type parameter T could not be resolved at runtime' (natives::testEnumValuesOfAView), and a dynamic new from a view returns no value (testDynamicNewFromAViewOf*)",
    ("truffle", "jit", "legacy_loader", "loader"): "29 of 57 loader tests fail on Truffle: 'String cannot be cast to Any' (and Integer, Boolean) in meta::pure::bridge::loader::rewrite (code/rewrite.pure)",
    ("truffle", "native", "legacy_loader", "loader"): "29 of 57 loader tests fail on Truffle: 'String cannot be cast to Any' (and Integer, Boolean) in meta::pure::bridge::loader::rewrite (code/rewrite.pure)",
    ("java", "platform", "legacy", "unit"): "27 of 35 bridge tests fail on the Java platform: 'ClassImpl cannot be cast to Class', 'Cannot call PureLambdaValue.execute(FunctionDefinition)'",
    ("javascript", "cli", "legacy", "unit"): "0 of 35 bridge tests run on the JavaScript platform: 'no translated function for meta::pure::bridge::tests::...' (the layer, package meta, is not translated)",
    ("javascript", "cli", "legacy_loader", "loader"): "0 of 57 loader tests run on the JavaScript platform: 'no translated function for meta::pure::bridge::loader::tests::...' (package meta is not translated)",
}

def _runs_javascript_free(owner):
    """A host that does not run JavaScript (its javascript::execute native is NOT_ON_THIS_PLATFORM): every suite but
    those of the JavaScript translator, which run the JavaScript they translate."""
    return "javascript-translation" not in _closure([owner])

# The CI job of a suite (.github/workflows/build.yml: 'job:<job>' or 'job:<job>/<matrix entry>'), as the jobs ran
# them before Bazel. A manual suite has none: tools/bazel/check-job-coverage.sh checks every other test has one.
_TRUFFLE_JOBS = {
    "compiler/compiler": "truffle",
    "compiler/printer-compiled": "printer-compiled",
    "compiler/pdb": "pdb-golden",
    "core/printer-protocol": "printer-protocol",
    "java/unit": "translation-deps/language-java",
    "javascript/unit": "translation-deps/language-javascript",
    "translation-shared/unit": "translation-deps/translation-shared",
    "java-translation/unit": "translations/truffle",
}

def job_of(host, variant, owner, suite):
    """The CI job that runs a suite of 'owner' on a variant of 'host'."""
    if host == "truffle" and variant == "native":
        return "truffle-native/specifications"
    if host == "truffle":
        return _TRUFFLE_JOBS.get("%s/%s" % (owner, suite), "truffle-specifications")
    # The module suites on the Java platform compile and run Java as they go: minutes each, on a runner of their own.
    if host == "java" and owner in ["java", "java-translation", "javascript", "translation-shared"]:
        return "translations/java-modules"
    return "translations/" + host

def _suite_test(host_name, host, variant_name, variant, owner, test):
    manifest = MODULES[owner]
    tool = variant.get("tool", host["tool"])
    target = test["target"] if test["runner"] in _PACKAGE_RUNNERS else _normalize(manifest["dir"] + "/" + test["target"])
    function, template = variant.get("runners", {}).get(test["runner"]) or _runner(owner, test["runner"])
    owner_fbs = [_owner_fbs(owner)] if [arg for arg in template if "{owner_fbs}" in arg] else []
    args = [
        arg.replace("{target}", target).replace("{m3_fbs}", "$(rootpath %s)" % _M3_FBS).replace("{owner_fbs}", " ".join(["$(rootpath %s)" % f for f in owner_fbs]))
        for arg in template
    ]
    command = variant["command"]
    if "--strict-jit" in command and ("%s/%s" % (owner, test["suite"])) in host.get("lenient_jit", []):
        command = [c for c in command if c != "--strict-jit"]
    if variant_name == "in_memory":
        pdbs = [_pdb(m) for m in variant["prelude"]]
        loads = [a for pdb in pdbs for a in ["--pdb", "$(rootpath %s)" % pdb]] + \
                [a for source in manifest["sources"] for a in ["--source-dir", manifest["dir"] + "/" + source]]
    else:
        pdbs = _variant_loads(owner, variant)
        loads = [a for pdb in pdbs for a in ["--pdb", "$(rootpath %s)" % pdb]]
    report = "%s/%s/%s.json" % (manifest["reportDir"], test["suite"], variant["report"])
    reason = PENDING.get((host_name, variant_name, owner, test["suite"]))
    # In the environment: Bazel splits args as a shell would, and a reason has spaces and quotes.
    env = {"PURE_PENDING": reason} if reason else {}
    sh_test(
        name = "test_%s_%s_%s" % (owner, test["suite"], variant_name),
        srcs = ["//tools/bazel:run-suite.sh"],
        args = [report, "$(rootpath %s)" % tool] + command + loads +
               ["--function", function, "--args"] + args,
        # //:shared: the schemas, read as a directory.
        data = [tool, "//:shared", _M3_FBS] + pdbs + owner_fbs + manifest["data"] + variant.get("data", []),
        env = env,
        size = "large",
        timeout = variant.get("timeout"),
        tags = ["host:" + host_name] + variant.get("tags", []) + (["pending"] if reason else []) +
               ([] if "manual" in variant.get("tags", []) else ["job:" + job_of(host_name, variant_name, owner, test["suite"])]),
    )

def host_specs(host):
    """One test per suite of every owner, on each variant of 'host' that runs it: '<owner>_<suite>_<variant>'; and,
    for an owner that declares no suite, '<owner>_none_<variant>' on the default variant, whose report has no case:
    the dashboard shows the module, 0/0, rather than nothing."""
    description = HOSTS[host]
    [_suite_test(host, description, variant_name, variant, owner, test)
     for owner in sorted(MODULES.keys())
     for test in MODULES[owner]["tests"]
     for variant_name, variant in description["variants"].items()
     if variant["runs"](owner, test)]
    default = description["default"]
    variant = description["variants"][default]
    [sh_test(
        name = "test_%s_none_%s" % (owner, default),
        srcs = ["//tools/bazel:empty-report.sh"],
        # Where the module's unit suite would report (the dashboard reads modules there).
        args = ["%s/unit/%s.json" % (MODULES[owner]["reportDir"], variant["report"])],
        tags = ["host:" + host] + variant.get("tags", []) +
               ([] if "manual" in variant.get("tags", []) else ["job:" + job_of(host, default, owner, "unit")]),
    ) for owner in sorted(MODULES.keys())
      if not MODULES[owner]["tests"] and variant["runs"](owner, {"suite": "unit", "runner": "tests", "target": ""})]

def pdb_read_backs(host, owner, function, fixtures, fixture_dir = "tests/compiler"):
    """Host checks: each golden of 'owner' read back by the host decoder, which must find in it the element its
    fixture defines: '<owner>_read_back_<fixture>', on the default variant of 'host'.

    Args:
      host: the host.
      owner: the owner of the goldens.
      function: the read-back function, (fixture source, element path).
      fixtures: {fixture, as '<directory>/<name>' below 'fixture_dir': the element path it defines}.
      fixture_dir: the fixture directory of the owner; its goldens are in '<fixture_dir>-pdb-serialization'.
    """
    description = HOSTS[host]
    variant = description["variants"][description["default"]]
    manifest = MODULES[owner]
    pdbs = _variant_loads(owner, variant)
    command = [c for c in variant["command"] if c != "--strict-jit"]
    [sh_test(
        name = "test_%s_read_back_%s" % (owner, fixture.replace("/", "_")),
        srcs = ["//tools/bazel:run-suite.sh"],
        # Every read-back of the host shares one report, a case per fixture (run-suite.sh: PURE_CASE).
        args = ["%s/read-back/%s.json" % (manifest["reportDir"], variant["report"]), "$(rootpath %s)" % description["tool"]] + command +
               [a for pdb in pdbs for a in ["--pdb", "$(rootpath %s)" % pdb]] +
               ["--pdb", "%s/%s-pdb-serialization/%s.pdb" % (manifest["dir"], fixture_dir, fixture),
                "--function", function, "--args", "%s/%s/%s.pure" % (manifest["dir"], fixture_dir, fixture), element],
        data = [description["tool"], "//:shared"] + pdbs + manifest["data"] + variant.get("data", []),
        env = {"PURE_CASE": fixture},
        tags = ["host:" + host, "job:" + job_of(host, description["default"], owner, "read-back")],
    ) for fixture, element in fixtures.items()]
