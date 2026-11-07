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

"""Java sources a generator writes to a directory, as a .srcjar a java_library compiles."""

load("//tools/bazel:target_genrule.bzl", "target_genrule")

def generated_srcjar(name, cmd, srcs = [], tools = [], **kwargs):
    """'<name>.srcjar': the .java files that 'cmd' writes below $$OUT_DIR, at their paths relative to it.

    Args:
      name: the rule, and its output '<name>.srcjar'.
      cmd: a genrule command, with $$OUT_DIR the empty directory to write to; its standard output is dropped.
      srcs: the inputs of the command.
      tools: the tools of the command.
      **kwargs: passed to the genrule.
    """
    target_genrule(
        name = name,
        srcs = srcs,
        outs = [name + ".srcjar"],
        cmd = """
            set -e
            OUT_DIR=$$(mktemp -d)
            ZIPPER=$$PWD/$(execpath @bazel_tools//tools/zip:zipper)
            SRCJAR=$$PWD/$@
            ( %s ) > /dev/null
            cd $$OUT_DIR
            $$ZIPPER c $$SRCJAR $$(find . -name '*.java' | sed 's|^\\./||' | sort)
        """ % cmd,
        tools = tools,
        exec_tools = ["@bazel_tools//tools/zip:zipper"],
        **kwargs
    )
