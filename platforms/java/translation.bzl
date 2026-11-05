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

"""The Java platform runtime: Pure translated to Java by the Java translator, run on the Truffle CLI."""

load("//tools/bazel:target_genrule.bzl", "target_genrule")

_TRUFFLE_CLI = "//platforms/truffle/legend-pure-next-platform-truffle/legend-pure-next-platform-truffle-interfaces/legend-pure-next-platform-truffle-cli:pure-truffle"

# Every '//// FILE <path>' line starts a file below $$OUT_DIR, which the following lines fill; a file named twice in one
# run is appended to. With KEEP set, only the files below that prefix are written.
_SPLIT = """awk -v root="$$OUT_DIR" -v keep="$$KEEP" '/^\\/\\/\\/\\/ FILE /{ if (file != "") close(file); if (keep == "" || index($$3, keep) == 1) { file = root "/" $$3; dir = file; sub(/\\/[^\\/]*$$/, "", dir); system("mkdir -p \\"" dir "\\"") } else { file = "" } next } file != "" { if (file in seen) print >> file; else { print > file; seen[file] = 1 } }'"""

def java_translation_srcjar(name, steps):
    """'<name>.srcjar', the Java the steps print, and '<name>-positions.jar', the source position tables (.pos) the
    translator writes beside it, which the runtime reads from the class path.

    The steps run in order into one directory: a later step may write a file an
    earlier one wrote.

    Args:
      name: the rule.
      steps: [(pdbs, function, args, keep)]: the PDBs loaded, in order; the translator function, by full id; its
        arguments; and only the files below the 'keep' path prefix ('' for every file).
    """
    pdbs = {pdb: True for step in steps for pdb in step[0]}.keys()
    runs = "\n".join([
        "KEEP=\"%s\"; $(execpath %s) execute %s --function \"%s\" --args %s 2> $$T/stderr | grep -v '^\\[graal-tripwire\\]' | %s || { cat $$T/stderr >&2; exit 1; }" % (
            keep,
            _TRUFFLE_CLI,
            " ".join(["--pdb $(execpath %s)" % pdb for pdb in step_pdbs]),
            function,
            " ".join(['"%s"' % arg for arg in args]),
            _SPLIT,
        )
        for step_pdbs, function, args, keep in steps
    ])
    target_genrule(
        name = name,
        srcs = list(pdbs),
        outs = [name + ".srcjar", name + "-positions.jar"],
        cmd = """
            set -eo pipefail
            T=$$(mktemp -d)
            OUT_DIR=$$T/out
            mkdir -p $$OUT_DIR
            ZIPPER=$$PWD/$(execpath @bazel_tools//tools/zip:zipper)
            SRCJAR=$$PWD/$(execpath %s.srcjar)
            POSITIONS=$$PWD/$(execpath %s-positions.jar)
            %s
            cd $$OUT_DIR
            $$ZIPPER c $$SRCJAR $$(find . -name '*.java' | sed 's|^\\./||' | sort)
            POS=$$(find . -name '*.pos' | sed 's|^\\./||' | sort)
            if [ -n "$$POS" ]; then $$ZIPPER c $$POSITIONS $$POS; else $$ZIPPER c $$POSITIONS; fi
        """ % (name, name, runs),
        tools = [_TRUFFLE_CLI],
        exec_tools = ["@bazel_tools//tools/zip:zipper"],
        visibility = ["//platforms/java:__subpackages__"],
    )
