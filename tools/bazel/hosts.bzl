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
load(":pure.bzl", "pure_compile")

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

# Modules with nothing of their own to report: the writers are generated, the parser mappings tested through the grammar.
_GENERATED_MODULES = ["core_metamodel_pdb_writer", "island_pdb_writer", "parser-mappings"]

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

def _goldens(fixture_dir):
    """The directory of the PDB goldens of a fixture directory: '<x>/definition' to '<x>/pdb'."""
    return fixture_dir.removesuffix("definition") + "pdb"

def _package(chain, module):
    """The package of a module on a chain: its directory below 'pure/', below the chain."""
    return "//%s/%s" % (chain, MODULES[module]["dir"].removeprefix("pure/"))

def _self_compiled(chain):
    """The PDBs of the compiler as a chain compiles it, for a self-host variant: {module: label}."""
    return {
        "compiler": _package(chain, "compiler") + ":compiler.pdb",
        "compiler-tests": _package(chain, "compiler") + ":compiler-tests.pdb",
        "core_metamodel_pdb_writer": _package(chain, "core_metamodel_pdb_writer") + ":core_metamodel_pdb_writer.pdb",
        "island_pdb_writer": _package(chain, "island_pdb_writer") + ":island_pdb_writer-with-tests.pdb",
    }

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
    # The goldens of a fixture directory '<x>/definition' are in '<x>/pdb' ('{goldens}').
    "pdbGolden": ("platform::pure::compiler::test::pdb::runPdbGoldenTests_String_1__String_1__String_1__String_1__String_1_", ["{target}", "{goldens}", "{m3_fbs}", "{report}"]),
}

_PACKAGE_RUNNERS = ["tests", "pct"]

# TRANSITIONAL (Section 2.2): the owners whose suites still run a runner of their own, until it becomes the shared
# one: {(owner, runner): (function, arguments)}. '{owner_fbs}' is the schema fragment of the owner.
_OWNER_RUNNERS = {
    ("diagram", "grammar"): ("platform::extensions::pure::diagram::tests::runGrammarTests_String_1__String_1__String_1_", ["{target}", "{report}"]),
    ("diagram", "compiler"): ("platform::extensions::pure::diagram::tests::runCompilerTests_String_1__String_1__String_1_", ["{target}", "{report}"]),
    ("diagram", "pdbGolden"): ("platform::extensions::pure::diagram::tests::runPdbGoldenTests_String_1__String_1__String_1__String_1__String_1__String_1_", ["{target}", "{goldens}", "{m3_fbs}", "{owner_fbs}", "{report}"]),
    ("tds", "compiler"): ("platform::extensions::pure::tds::tests::runCompilerTests_String_1__String_1__String_1_", ["{target}", "{report}"]),
    ("tds", "printerCompiled"): ("platform::extensions::pure::tds::tests::runPrinterCorpus_String_1__String_1__String_1_", ["{target}", "{report}"]),
    ("tds", "pdbGolden"): ("platform::extensions::pure::tds::tests::runPdbGoldenTests_String_1__String_1__String_1__String_1__String_1__String_1_", ["{target}", "{goldens}", "{m3_fbs}", "{owner_fbs}", "{report}"]),
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

_HOST_CLI = "//bootstrap/legend-pure-next-bootstrap/legend-pure-next-bootstrap-interfaces/legend-pure-next-bootstrap-cli:pure-bootstrap"

_TRUFFLE_CLI = "//platforms/truffle/legend-pure-next-platform-truffle/legend-pure-next-platform-truffle-interfaces/legend-pure-next-platform-truffle-cli:pure-truffle"

# The hosts (Section 2.3). Each variant: its report name, the command, the PDBs it always loads, the suites it runs (a
# capability: what it cannot run is derived here, with the reason, never listed per test), and what its test names add
# ('suffix'), beside the others of its chain.
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
                "suffix": "_selfhost",
                "command": ["execute"],
                "prelude": ["core", "parser-mappings", "core-tests", "compiler"],
                "pdbs": _self_compiled("platforms/truffle/test/jit"),
                "tags": ["manual", "selfhost"],
                "runs": lambda owner, test: owner == "compiler" and test["runner"] == "compiler",
            },
            # The compiler corpus on compiler-pure as the native image compiles it (self-host): on demand.
            "native_selfhost": {
                "tool": "//platforms/truffle/legend-pure-next-platform-truffle/legend-pure-next-platform-truffle-interfaces/legend-pure-next-platform-truffle-cli:pure-truffle-native",
                "report": "pure@cli-truffle-native+selfhost",
                "suffix": "_selfhost",
                "command": ["execute"],
                "prelude": ["core", "parser-mappings", "core-tests", "compiler"],
                "pdbs": _self_compiled("platforms/truffle/test/native"),
                "tags": ["manual", "selfhost"],
                "runs": lambda owner, test: owner == "compiler" and test["runner"] == "compiler",
            },
            # The owner compiled from its sources in memory, on top of core.pdb, in place of its PDBs.
            "in_memory": {
                "report": "pure@cli-truffle+inMemory",
                "suffix": "_in_memory",
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
                "suffix": "_selfhost",
                "command": ["execute"],
                "prelude": ["core", "core-tests", "compiler"],
                "pdbs": _self_compiled("bootstrap/test/pure"),
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
                "suffix": "_translated",
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
                "command": ["execute", "--root", "."],
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
}

_MODULES_DIR = "pure/modules/"

def job_of(host, variant, owner, suite):
    """The CI job that runs a suite of 'owner' on a variant of 'host'."""
    if host == "truffle" and variant == "native":
        return "truffle-native/specifications"
    # A module under pure/modules: its own job in the matrix of its platform (modules-<platform>/<module>).
    if host in ["truffle", "java", "javascript"] and MODULES[owner]["dir"].startswith(_MODULES_DIR):
        return "modules-%s/%s" % (host, MODULES[owner]["dir"][len(_MODULES_DIR):].strip("/").replace("/", "-"))
    if host == "truffle":
        return _TRUFFLE_JOBS.get("%s/%s" % (owner, suite), "truffle-specifications")
    return "translations/" + host

# What a runner tests, which names its test ('test_<kind>') and tags it ('kind:<kind>'), so that a kind runs on every host:
# bazel test --test_tag_filters=kind:pct //...
_KINDS = {
    "tests": "pure",
    "pct": "pct",
    "grammar": "grammar",
    "compiler": "compiler",
    "pdbGolden": "pdb_golden",
    "printerCompiled": "printer_compiled",
    "printerProtocol": "printer_protocol",
}

# The suites of a kind a module has more than one of: what tells them apart.
_QUALIFIERS = {"compiler-unit": "_compiler", "pdb-format": "_pdb_format"}

def _test_name(test, variant):
    """A test of a suite: its kind, what tells it from the others of that kind in its module, the suffix of the variant."""
    return "test_%s%s%s" % (_KINDS[test["runner"]], _QUALIFIERS.get(test["suite"], ""), variant.get("suffix", ""))

def _suite_test(host_name, host, variant_name, variant, owner, test):
    manifest = MODULES[owner]
    tool = variant.get("tool", host["tool"])
    target = test["target"] if test["runner"] in _PACKAGE_RUNNERS else _normalize(manifest["dir"] + "/" + test["target"])
    function, template = variant.get("runners", {}).get(test["runner"]) or _runner(owner, test["runner"])
    owner_fbs = [_owner_fbs(owner)] if [arg for arg in template if "{owner_fbs}" in arg] else []
    args = [
        arg.replace("{target}", target).replace("{goldens}", _goldens(target)).replace("{m3_fbs}", "$(rootpath %s)" % _M3_FBS).replace("{owner_fbs}", " ".join(["$(rootpath %s)" % f for f in owner_fbs]))
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
        name = _test_name(test, variant),
        srcs = ["//tools/bazel:run-suite.sh"],
        args = [report, "$(rootpath %s)" % tool] + command + loads +
               ["--function", function, "--args"] + args,
        # //:shared: the schemas, read as a directory.
        data = [tool, "//:shared", _M3_FBS] + pdbs + owner_fbs + manifest["data"] + variant.get("data", []),
        env = env,
        size = "large",
        timeout = variant.get("timeout"),
        tags = ["host:" + host_name, "kind:" + _KINDS[test["runner"]]] + variant.get("tags", []) + (["pending"] if reason else []) +
               ([] if "manual" in variant.get("tags", []) else ["job:" + job_of(host_name, variant_name, owner, test["suite"])]),
        visibility = ["//visibility:public"],
    )

def _suites(chain, owner):
    """The suites of 'owner' a chain runs: [(variant name, suite)]."""
    description = HOSTS[chain["host"]]
    return [(variant_name, test)
            for variant_name in chain["variants"]
            for test in MODULES[owner]["tests"]
            if description["variants"][variant_name]["runs"](owner, test)]

def _reports_empty(chain, owner):
    """Whether a chain reports 'owner' empty: it declares no suite, and the dashboard shows the module, 0/0, rather
    than nothing."""
    description = HOSTS[chain["host"]]
    default = description["default"]
    return default in chain["variants"] and not MODULES[owner]["tests"] and owner not in _GENERATED_MODULES and \
        description["variants"][default]["runs"](owner, {"suite": "unit", "runner": "tests", "target": ""})

def _suite_tests(chain, owner):
    """One test per suite of 'owner' the chain runs: 'test_<kind><suffix>'; or 'test_none', with a report of no case."""
    host = chain["host"]
    description = HOSTS[host]
    for variant_name, test in _suites(chain, owner):
        _suite_test(host, description, variant_name, description["variants"][variant_name], owner, test)
    if _reports_empty(chain, owner):
        default = description["default"]
        variant = description["variants"][default]
        sh_test(
            name = "test_none",
            srcs = ["//tools/bazel:empty-report.sh"],
            # Where the module's unit suite would report (the dashboard reads modules there).
            args = ["%s/unit/%s.json" % (MODULES[owner]["reportDir"], variant["report"])],
            tags = ["host:" + host, "kind:none"] + variant.get("tags", []) +
                   ([] if "manual" in variant.get("tags", []) else ["job:" + job_of(host, default, owner, "unit")]),
            visibility = ["//visibility:public"],
        )

# What the decoder of a host reads back of the goldens of an owner, which must hold the element its fixture defines:
# {owner: (the read-back function, of a fixture source and an element path, {fixture, below 'tests/compiler/definition':
# the element path})}.
_READ_BACKS = {
    "diagram": (
        "platform::extensions::pure::diagram::tests::readBack_String_1__String_1__String_1_",
        {
            "diagram/" + name: "m::D"
            for name in [
                "propertyAndAssociation",
                "complexGeometry",
                "complexTypeProperties",
            ]
        },
    ),
    "tds": (
        "platform::extensions::pure::tds::tests::readBack_String_1__String_1__String_1_",
        {
            "tds/" + name: "test::tds::%s__Any_MANY_" % name
            for name in [
                "typedHeader",
                "inferredColumns",
                "mixedInference",
                "quotedNamesAndCells",
                "enumeration",
                "quotedValues",
                "manyMultiplicity",
                "primitiveExtension",
            ]
        },
    ),
}

_READ_BACK_HOSTS = ["truffle", "java", "javascript"]

_FIXTURE_DIR = "tests/compiler/definition"

def _read_backs(chain, owner):
    """The goldens of 'owner' read back by the decoder of the host: 'test_read_back_from_pdb', on the first variant of
    the chain, in a process per fixture (read-back-from-pdb.sh)."""
    host = chain["host"]
    if owner not in _READ_BACKS or host not in _READ_BACK_HOSTS:
        return
    description = HOSTS[host]
    function, fixtures = _READ_BACKS[owner]
    variant_name = chain["variants"][0]
    variant = description["variants"][variant_name]
    tool = variant.get("tool", description["tool"])
    manifest = MODULES[owner]
    pdbs = _variant_loads(owner, variant)
    reason = PENDING.get((host, variant_name, owner, "read-back"))
    sh_test(
        name = "test_read_back_from_pdb",
        srcs = ["//tools/bazel:run-suite.sh"],
        args = ["%s/read-back/%s.json" % (manifest["reportDir"], variant["report"]),
                "$(rootpath //tools/bazel:read-back-from-pdb.sh)", "{report}", function,
                "%s/%s" % (manifest["dir"], _goldens(_FIXTURE_DIR)), "%s/%s" % (manifest["dir"], _FIXTURE_DIR)] +
               ["%s=%s" % (fixture, element) for fixture, element in sorted(fixtures.items())] +
               ["--", "$(rootpath %s)" % tool] + [c for c in variant["command"] if c != "--strict-jit"] +
               [a for pdb in pdbs for a in ["--pdb", "$(rootpath %s)" % pdb]],
        data = ["//tools/bazel:read-back-from-pdb.sh", tool, "//:shared"] + pdbs + manifest["data"] + variant.get("data", []),
        env = {"PURE_PENDING": reason} if reason else {},
        size = "large",
        tags = ["host:" + host, "kind:read_back_from_pdb"] + (["pending"] if reason else []) +
               ["job:" + job_of(host, variant_name, owner, "read-back")],
    )

# The modules the bootstrap builds, as Truffle is generated from them; every other is built by the Truffle CLI. 'core' has
# no PDB to be compiled against, so it is not compiled by a host.
_BOOTSTRAP_BUILT = [
    "compiler",
    "core_metamodel_pdb_writer",
    "island_pdb_writer",
    "parser-mappings",
    "java",
    "translation-shared",
    "java-translation",
]

# What the Java compiler cannot compile, so is compared with the PDB the build made, by Truffle: {module: why}. The hosts
# then agree with each other; that the output is right rests on the goldens of the extension.
_NOT_JAVA_COMPILED = {
    "relation": "its tests hold #TDS islands, and the Java compiler has no handler for an island",
}

_CHECKED = [module for module in sorted(MODULES.keys()) if module != "core"]

_TRUFFLE_BUILT = [module for module in _CHECKED if module not in _BOOTSTRAP_BUILT]

def _pdb_files(module):
    """The PDBs a module compiles to: {file: the label of the one built by the build}."""
    mode = MODULES[module]["testsMode"]
    if mode == "with":
        return {module + "-with-tests.pdb": _pdb(module)}
    if mode == "none":
        return {module + ".pdb": _pdb(module)}
    return {module + ".pdb": _pdb(module), module + "-tests.pdb": _pdb(module + "-tests")}

def _dependency_pdbs(module):
    """What a module is compiled against, from its manifest: its dependencies, then its test dependencies."""
    manifest = MODULES[module]
    tests = manifest["testDependencies"]
    return [_pdb(m) for m in _closure(manifest["dependencies"] + [d for d in tests if not d.endswith("-tests")])] + \
           [_pdb(d) for d in tests if d.endswith("-tests")]

def _module_compile(chain, module):
    """Step 1: the module compiled again with the compiler of a chain, to compare with: 'recompile', and its PDBs. What
    builds a module is the 'compile' below pure/, once."""
    manifest = MODULES[module]
    mode = manifest["testsMode"]
    compiler = chain["compiler"]
    if compiler.get("prelude", True):
        prelude = [_pdb("core")] + ([_pdb("parser-mappings")] if compiler.get("parser_mappings", True) else []) + \
                  [_pdb("core-tests"), _pdb("compiler"), _pdb("core_metamodel_pdb_writer")]
    else:
        prelude = []
    # What the CLI loads itself is not given to it, and what it always needs is.
    variant = HOSTS[chain["host"]]["variants"][chain["variants"][0]]
    supplied = [_pdb(m) for m in variant.get("supplies", [])]
    bases = prelude + [pdb for pdb in _dependency_pdbs(module) if pdb not in prelude]
    pure_compile(
        name = "recompile",
        srcs = manifest["compileSrcs"] or ["//%s:compile_sources" % manifest["dir"]],
        base_pdbs = [pdb for pdb in bases if pdb not in supplied] + [pdb for pdb in variant.get("extra_pdbs", []) if pdb not in bases],
        outs = _pdb_files(module).keys(),
        source_dir = manifest["dir"],
        sources = manifest["sourceDirs"] or ["."],
        generated = manifest["generated"],
        split_tests = mode == "split",
        with_tests = mode == "with",
        tool = compiler["tool"],
        command = compiler.get("command", "compile"),
        root_data = compiler.get("root_data", []),
        tags = ["manual"] + compiler.get("tags", []),
        visibility = ["//visibility:public"],
    )

def _pdb_checks(prefix, chain, module):
    """Step 2: every PDB of the module against its reference, element for element: 'test_pdb_check'."""
    pairs = []
    for pdb, built in _pdb_files(module).items():
        reference = built if module in _BOOTSTRAP_BUILT or module in _NOT_JAVA_COMPILED else "%s:%s" % (_package(_REFERENCE, module), pdb)
        # What this compiler made: its own recompile, or the build's PDB where the build used this compiler.
        platform = "%s:%s" % (_package(prefix, module), pdb) if module in chain.get("compiles", []) else built
        pairs += [reference, platform]
    sh_test(
        name = "test_pdb_check",
        srcs = ["//tools/bazel:pdb-diff.sh"],
        args = ["$(rootpath %s)" % _HOST_CLI, "$(rootpath %s)" % _pdb("core")] + ["$(rootpath %s)" % pdb for pdb in pairs],
        data = [_HOST_CLI, _pdb("core")] + pairs,
        tags = ["manual", "selfhost", "kind:pdb_check"] + chain["compiler"].get("tags", []),
        visibility = ["//visibility:public"],
    )

_SELF_HOSTED = ["compiler", "core_metamodel_pdb_writer", "island_pdb_writer"]

# The Java compiler of the bootstrap: what every other compiler is compared with, where it can compile the module.
_REFERENCE = "bootstrap/test/java"

# What runs the modules: {the directory of its packages: its host, the variants of the host it runs, and its compiler:
# the CLI, what that compiles again ('compiles'); what it checks ('checks')}.
_CHAINS = {
    _REFERENCE: {
        "host": "bootstrap",
        "variants": ["cli"],
        "compiler": {"tool": "bootstrap", "prelude": False},
        "compiles": [module for module in _TRUFFLE_BUILT if module not in _NOT_JAVA_COMPILED],
    },
    # The compiler written in Pure, run by the bootstrap.
    "bootstrap/test/pure": {
        "host": "bootstrap",
        "variants": ["selfhost"],
        "compiler": {"tool": "bootstrap", "command": "compile-via-pure", "parser_mappings": False, "tags": ["bootstrap"]},
        "compiles": _CHECKED,
        "checks": _CHECKED,
    },
    "platforms/truffle/test/jit": {
        "host": "truffle",
        "variants": ["jit", "selfhost", "in_memory"],
        "compiler": {"tool": "truffle"},
        # The build compiles every other with the JIT. What is compared with that build is compiled again: the JIT against itself.
        "compiles": _BOOTSTRAP_BUILT + sorted(_NOT_JAVA_COMPILED.keys()),
        "checks": _CHECKED,
    },
    "platforms/truffle/test/native": {
        "host": "truffle",
        "variants": ["native", "native_selfhost"],
        "compiler": {"tool": "truffle-native"},
        "compiles": _CHECKED,
        "checks": _CHECKED,
    },
    "platforms/java/test": {
        "host": "java",
        "variants": ["platform"],
        "compiler": {"tool": "java", "root_data": ["//platforms/java:runtime_data"]},
        "compiles": _CHECKED,
        "checks": _CHECKED,
    },
    "platforms/javascript/test": {
        "host": "javascript",
        "variants": ["cli", "translated"],
        "compiler": {"tool": "javascript"},
        "compiles": _CHECKED,
        "checks": _CHECKED,
    },
}

_MODULE_OF_DIR = {manifest["dir"]: module for module, manifest in MODULES.items()}

def platform_module():
    """All that a chain does with a module, from the package it is called in: '<chain>/<the module directory below
    pure/>': 'recompile', 'test_pdb_check', 'test_<kind>' and 'test_read_back_from_pdb'."""
    package = native.package_name()
    prefixes = [prefix for prefix in _CHAINS.keys() if package.startswith(prefix + "/")]
    if not prefixes:
        fail("%s: not below a chain: one of %s" % (package, sorted(_CHAINS.keys())))
    prefix = prefixes[0]
    chain = _CHAINS[prefix]
    module = _MODULE_OF_DIR.get("pure/" + package.removeprefix(prefix + "/"))
    if not module:
        fail("%s: no module in the directory it names" % package)
    if module in chain.get("compiles", []):
        _module_compile(chain, module)
    if module in chain.get("checks", []):
        _pdb_checks(prefix, chain, module)
    _suite_tests(chain, module)
    _read_backs(chain, module)

def _package_target(chain, module):
    """A target of the package of a module on a chain; None when the chain does nothing with the module."""
    if module in chain.get("compiles", []):
        return "recompile"
    if module in chain.get("checks", []):
        return "test_pdb_check"
    suites = _suites(chain, module)
    if suites:
        variant_name, test = suites[0]
        return _test_name(test, HOSTS[chain["host"]]["variants"][variant_name])
    return "test_none" if _reports_empty(chain, module) else None

def module_packages(name):
    """A target of every package platform_module should be called in: a package is a checked-in file, which no macro
    can write, so tools/bazel/check-job-coverage.sh loads these, and a missing one fails it. Never built."""
    native.filegroup(
        name = name,
        srcs = [
            "%s:%s" % (_package(prefix, module), _package_target(chain, module))
            for prefix, chain in _CHAINS.items()
            for module in sorted(MODULES.keys())
            if _package_target(chain, module)
        ],
        tags = ["manual"],
        testonly = True,
    )
