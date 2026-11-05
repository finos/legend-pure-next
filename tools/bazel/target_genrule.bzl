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

"""A genrule whose tools are built like what they build, not in the exec configuration of a genrule."""

def _target_genrule_impl(ctx):
    bin_package = ctx.bin_dir.path + ("/" + ctx.label.package if ctx.label.package else "")
    # A genrule command: $$ is a $, and what is left starting with $ is expanded.
    cmd = ctx.attr.cmd.replace("$$", "\0")
    for out in ctx.outputs.outs:
        cmd = cmd.replace("$(execpath %s)" % out.path[len(bin_package) + 1:], out.path)
    cmd = ctx.expand_location(cmd, ctx.attr.srcs + ctx.attr.tools + ctx.attr.exec_tools)
    cmd = cmd.replace("$(BINDIR)", ctx.bin_dir.path).replace("$(RULEDIR)", bin_package)
    if len(ctx.outputs.outs) == 1:
        cmd = cmd.replace("$@", ctx.outputs.outs[0].path)
    if "$" in cmd.replace("${", "\0"):
        fail("%s: a $ the command does not expand: write $$ for the shell, or use $(execpath), $(rootpath), $(BINDIR), $(RULEDIR) or $@ for a rule with one output" % ctx.label)
    tools = ctx.attr.tools + ctx.attr.exec_tools
    ctx.actions.run_shell(
        inputs = depset(transitive = [src[DefaultInfo].files for src in ctx.attr.srcs]),
        tools = [tool[DefaultInfo].files_to_run for tool in tools] + [tool[DefaultInfo].files for tool in tools],
        outputs = ctx.outputs.outs,
        # What a genrule sets before its command.
        command = "set -o errexit -o nounset -o pipefail\n" + cmd.replace("\0", "$"),
        mnemonic = "Genrule",
        progress_message = "Executing genrule %{label}",
        use_default_shell_env = True,
    )
    return [DefaultInfo(files = depset(ctx.outputs.outs))]

target_genrule = rule(
    implementation = _target_genrule_impl,
    doc = """A genrule running the CLIs of this repository, built in the target configuration.

    A genrule builds its tools in the exec configuration, with all they depend on: here the PDBs and the generated
    sources of the CLIs, which the tests and the other rules need in the target configuration too. The build and what
    it builds run on the same machine, so the two copies are the same bytes, and every step of them ran twice.""",
    attrs = {
        "srcs": attr.label_list(allow_files = True),
        "outs": attr.output_list(mandatory = True),
        "cmd": attr.string(mandatory = True),
        "tools": attr.label_list(allow_files = True, cfg = "target", doc = "What the command runs, built as what it builds."),
        "exec_tools": attr.label_list(allow_files = True, cfg = "exec", doc = "Tools no target depends on, such as Bazel's own."),
    },
)
