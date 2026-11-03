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

"""Pure compiled to PDBs, from exactly the sources a target declares, and the generators run over them."""

load("@rules_shell//shell:sh_test.bzl", "sh_test")

_CLI = "//bootstrap/legend-pure-next-bootstrap/legend-pure-next-bootstrap-interfaces/legend-pure-next-bootstrap-cli:pure-bootstrap"

_TOOLS = {
    "bootstrap": _CLI,
    "truffle": "//platforms/truffle/legend-pure-next-platform-truffle/legend-pure-next-platform-truffle-interfaces/legend-pure-next-platform-truffle-cli:pure-truffle",
    # The native image: only for the self-host check of the native image.
    "truffle-native": "//platforms/truffle/legend-pure-next-platform-truffle/legend-pure-next-platform-truffle-interfaces/legend-pure-next-platform-truffle-cli:pure-truffle-native",
}

def pure_compile(name, srcs, source_dir, outs, base_pdbs = [], sources = ["."], split_tests = False, with_tests = False, generated = {}, tool = "truffle", command = "compile", **kwargs):
    """'<tool> compile' over a staged copy of 'source_dir': its declared files, and generated ones.

    A CLI compiles every .pure file below each --source, each named by its path relative to that source, and finds
    the module.json by walking up from the first: so the copy holds the declared files at their paths below
    'source_dir' (module.json included) and the generated files at the paths 'generated' gives them, and each of
    'sources' is a directory of the copy.

    Args:
      name: the rule.
      srcs: the source files, all below 'source_dir' (its .pure files and module.json).
      source_dir: the directory of the module, relative to the repository root.
      outs: the PDBs the compile writes, by file name (the module name of module.json, '-tests' for the tests),
        in a directory of the package when two compiles of one package write the same names ('selfhost/compiler.pdb').
      base_pdbs: the PDBs compiled against, in order (--base-pdb).
      sources: the --source directories, relative to 'source_dir'.
      split_tests: true for '--tests split': the <<test.*>> elements in their own PDB.
      with_tests: true for '--tests with': one PDB holding them all, '<module>-with-tests.pdb'.
      generated: generated sources: {label: path below source_dir}.
      tool: 'truffle' (the Truffle CLI, which builds every module), 'bootstrap' (only what Truffle is built from), or
        'truffle-native' (the native image, for its self-host check).
      command: the compile command: 'compile', or 'compile-via-pure' (the bootstrap running the compiler written
        in Pure, for its self-host check).
      **kwargs: passed to the genrule.
    """
    cli = _TOOLS[tool]
    copies = " ".join([
        "mkdir -p $$(dirname $$T/pkg/%s) && cp $(execpath %s) $$T/pkg/%s;" % (path, label, path)
        for label, path in generated.items()
    ])
    native.genrule(
        name = name,
        srcs = srcs + base_pdbs + list(generated.keys()),
        outs = outs,
        cmd = """
            set -e
            T=$$(mktemp -d)
            mkdir -p $$T/pkg $$T/out
            cp -RL {source_dir}/. $$T/pkg/
            {copies}
            $(execpath {cli}) {command} {bases} {sources} --output-dir $$T/out {split} > $$T/log 2>&1 \\
                || {{ cat $$T/log >&2; exit 1; }}
            {collect}
        """.format(
            source_dir = source_dir,
            copies = copies,
            cli = cli,
            command = command,
            bases = " ".join(["--base-pdb $(execpath %s)" % pdb for pdb in base_pdbs]),
            sources = " ".join(["--source $$T/pkg/%s" % source for source in sources]),
            split = "--tests split" if split_tests else "--tests with" if with_tests else "",
            collect = " ".join(["cp $$T/out/%s $(RULEDIR)/%s;" % (out.split("/")[-1], out) for out in outs]),
        ),
        tools = [cli],
        **kwargs
    )

def pure_bootstrap_compile(**kwargs):
    """pure_compile with the bootstrap CLI: for what Truffle is built from (core, the compiler, the reader PDBs)."""
    pure_compile(tool = "bootstrap", **kwargs)

_CORE = "//pure/specification"

_M3_FBS = "//bootstrap/legend-pure-next-bootstrap/legend-pure-next-bootstrap-core/legend-pure-next-bootstrap-generators:generated-specification/m3.fbs"

def pure_generate(name, out, pdbs, function, args = [], srcs = [], markers = None, tool = "truffle", **kwargs):
    """'out': what a Pure generator function prints, run with 'tool' over 'pdbs'.

    Args:
      name: the rule.
      out: the file written.
      pdbs: the PDBs loaded (--pdb), in order.
      function: the generator, by its full id.
      args: its arguments (--args); '$(execpath …)' of a label in 'srcs' names an input.
      srcs: the inputs 'args' names.
      markers: (begin, end): only the lines between the two marker lines; None for the whole output.
      tool: 'truffle' or 'bootstrap'.
      **kwargs: passed to the genrule.
    """
    cli = _TOOLS[tool]
    # The JIT tripwire summary is the tool own output, never the generator.
    keep = ("awk '/^%s$$/{f=1;next} /^%s$$/{f=0} f' $$T/stdout" % markers) if markers else "grep -v '^\\[graal-tripwire\\]' $$T/stdout"
    native.genrule(
        name = name,
        srcs = pdbs + srcs,
        outs = [out],
        cmd = """
            set -e
            T=$$(mktemp -d)
            $(execpath {cli}) execute {pdbs} --function "{function}" {args} > $$T/stdout 2> $$T/stderr \\
                || {{ cat $$T/stderr >&2; exit 1; }}
            {keep} > $@
            test -s $@ || {{ echo "'{function}' printed nothing" >&2; cat $$T/stdout $$T/stderr >&2; exit 1; }}
        """.format(
            cli = cli,
            pdbs = " ".join(["--pdb $(execpath %s)" % pdb for pdb in pdbs]),
            function = function,
            args = ("--args " + " ".join(['"%s"' % arg for arg in args])) if args else "",
            keep = keep,
        ),
        tools = [cli],
        **kwargs
    )

def pure_extension_generated(name, metamodel, metamodel_package, ids, writer_package, protocol_package = None):
    """The generated sources of a language extension, under generated/:

    - 'generated/<name>.fbs' and 'generated/<name>-fbs-field-ids.txt', its FlatBuffer fragment and the field-ID
      registry it was generated with (the tracked 'ids' is an input, never written);
    - 'generated/writer.pure', its PDB writer;
    - 'generated/protocol.pure', its protocol classes, when 'protocol_package' is given.

    Each from the metamodel alone, staged with the module.json of the package and compiled against core and the
    compiler on the bootstrap ('<name>_metamodel_compile'). The bootstrap writes the fragment, which the Truffle jars
    package; the Truffle CLI writes the writer and the protocol.

    Args:
      name: the extension, as its module.json names it.
      metamodel: the metamodel source, below the package.
      metamodel_package: the Pure package of the metamodel.
      ids: the tracked field-ID registry.
      writer_package: the Pure package of the generated writer.
      protocol_package: the Pure package of the generated protocol classes.
    """
    pure_compile(
        name = name + "_metamodel_compile",
        srcs = ["module.json", metamodel],
        base_pdbs = [_CORE + ":core.pdb", "//pure/specification:compiler.pdb", "//pure/specification:core_metamodel_pdb_writer.pdb", _CORE + ":core-tests.pdb", "//pure/specification:compiler-tests.pdb"],
        outs = ["metamodel/%s.pdb" % name],
        source_dir = native.package_name(),
        tool = "bootstrap",
    )
    metamodel_pdbs = [_CORE + ":core.pdb", "//pure/specification:compiler.pdb", "//pure/specification:core_metamodel_pdb_writer.pdb", ":metamodel/%s.pdb" % name]
    native.genrule(
        name = name + "_fbs_generate",
        srcs = metamodel_pdbs + [ids, _M3_FBS],
        outs = ["generated/%s.fbs" % name, "generated/%s-fbs-field-ids.txt" % name],
        cmd = """
            set -e
            T=$$(mktemp -d)
            $(execpath {cli}) execute {pdbs} \\
                --function "platform::pure::compiler::pdb::fbsgen::printFbs_String_1__String_1__String_1__Boolean_1_" \\
                --args "{package}" $(execpath {m3_fbs}) $(execpath {ids}) > $$T/gen 2> $$T/log || {{ cat $$T/log >&2; exit 1; }}
            awk '/^===FBS-BEGIN===$$/{{f=1;next}} /^===FBS-END===$$/{{f=0}} f' $$T/gen > $(execpath generated/{name}.fbs)
            awk '/^===IDS-BEGIN===$$/{{f=1;next}} /^===IDS-END===$$/{{f=0}} f' $$T/gen > $(execpath generated/{name}-fbs-field-ids.txt)
            test -s $(execpath generated/{name}.fbs) || {{ echo "printFbs produced nothing for '{package}'" >&2; cat $$T/gen >&2; exit 1; }}
        """.format(
            name = name,
            package = metamodel_package,
            ids = ids,
            m3_fbs = _M3_FBS,
            cli = _CLI,
            pdbs = " ".join(["--pdb $(execpath %s)" % pdb for pdb in metamodel_pdbs]),
        ),
        tools = [_CLI],
        visibility = ["//visibility:public"],
    )
    pure_generate(
        name = name + "_pdb_writer_generate",
        visibility = ["//visibility:public"],
        out = "generated/writer.pure",
        pdbs = metamodel_pdbs,
        function = "platform::pure::compiler::pdb::generator::printExtensionWriter_String_1__String_1__String_1__String_1__Boolean_1_",
        args = ["$(execpath %s)" % _M3_FBS, "$(execpath :generated/%s.fbs)" % name, metamodel_package, writer_package],
        srcs = [_M3_FBS, ":generated/%s.fbs" % name],
        markers = ("===GENWRITER-BEGIN===", "===GENWRITER-END==="),
    )
    if protocol_package:
        pure_generate(
            name = name + "_protocol_generate",
            visibility = ["//visibility:public"],
            out = "generated/protocol.pure",
            pdbs = [_CORE + ":core.pdb", "//pure/specification:compiler.pdb", "//pure/specification:core_metamodel_pdb_writer.pdb", "//pure/modules/protocol:build/protocol.pdb", ":metamodel/%s.pdb" % name],
            function = "platform::pure::protocol::generation::generateProtocol_String_1__String_1__String_1_",
            args = [metamodel_package, protocol_package],
        )

_PARSER_MAPPINGS = "//pure/specification:parser-mappings.pdb"

def pure_module(name, base_pdbs, sources = ["."], generated = {}, srcs = None):
    """A module or extension: 'build/<name>.pdb' and 'build/<name>-tests.pdb', compiled by the Truffle CLI ('pdbs'),
    where the language manifests and module runfiles name them.

    The Truffle CLI parses with the compiler written in Pure, so core, parser-mappings and the compiler come first
    in its base PDBs, before 'base_pdbs'. 'compile_sources' is what it compiles, for the PDB checks of the hosts (hosts.bzl:
    module_pdb_checks).
    Args:
      name: the module, as its module.json names it.
      base_pdbs: the PDBs of its dependencies and test dependencies, after core, parser-mappings and the compiler.
      sources: the --source directories, relative to the package.
      generated: generated sources: {label: path below the package}.
      srcs: the source files; every .pure file and the module.json of the package by default.
    """
    srcs = srcs if srcs != None else native.glob(["**/*.pure", "module.json"])
    outs = ["build/%s.pdb" % name, "build/%s-tests.pdb" % name]
    pure_compile(
        name = "compile",
        srcs = srcs,
        base_pdbs = [_CORE + ":core.pdb", _PARSER_MAPPINGS, "//pure/specification:compiler.pdb", "//pure/specification:core_metamodel_pdb_writer.pdb"] + base_pdbs,
        outs = outs,
        source_dir = native.package_name(),
        sources = sources,
        split_tests = True,
        generated = generated,
        visibility = ["//visibility:public"],
    )
    native.filegroup(
        name = "compile_sources",
        srcs = srcs,
        visibility = ["//visibility:public"],
    )
