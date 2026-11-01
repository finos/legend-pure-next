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

"""ANTLR grammars compiled to Java, as the antlr4 Maven plugin compiles them."""

def antlr_srcjar(name, srcs, grammar_dir, package, grammars, **kwargs):
    """'<name>.srcjar': the visitors and parsers of 'grammars', in 'package' and the packages below it.

    The grammars are staged by package, as the Maven plugin stages them, so each generated file names the same
    grammar path ('// Generated from org/…/M3Parser.g4'). Each lexer is compiled before its parser, which reads the
    tokens file it writes (tokenVocab) through -lib.

    Args:
      name: the rule, and its output '<name>.srcjar'.
      srcs: the grammar files.
      grammar_dir: the directory of the grammars, relative to the repository root.
      package: the Java package of the grammars at the top of 'grammar_dir'.
      grammars: [(subdirectory, [grammar file, …])], each file a lexer before its parser; '' for the top.
      **kwargs: passed to the genrule.
    """
    root = package.replace(".", "/")
    steps = []
    [[steps.append(
        "$$ANTLR -visitor -no-listener -o ../out -lib ../out/%s -package %s %s/%s" % (
            (root + "/" + sub) if sub else root,
            (package + "." + sub.replace("/", ".")) if sub else package,
            (root + "/" + sub) if sub else root,
            grammar,
        ),
    ) for grammar in files] for sub, files in grammars]
    native.genrule(
        name = name,
        srcs = srcs,
        outs = [name + ".srcjar"],
        cmd = """
            set -e
            T=$$(mktemp -d)
            mkdir -p $$T/src/{root} {outs}
            cp -R {grammar_dir}/. $$T/src/{root}/
            ANTLR=$$PWD/$(execpath //tools/bazel:antlr)
            ZIPPER=$$PWD/$(execpath @bazel_tools//tools/zip:zipper)
            OUT=$$PWD/$@
            cd $$T/src
            {steps}
            cd ../out
            $$ZIPPER c $$OUT $$(find . -name '*.java' | sed 's|^\\./||' | sort)
        """.format(
            root = root,
            outs = " ".join(["$$T/out/%s" % ((root + "/" + sub) if sub else root) for sub, _ in grammars]),
            grammar_dir = grammar_dir,
            steps = "\n            ".join(steps),
        ),
        tools = [
            "//tools/bazel:antlr",
            "@bazel_tools//tools/zip:zipper",
        ],
        **kwargs
    )
