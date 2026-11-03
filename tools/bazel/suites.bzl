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

"""The modules and the suites their owners declare, read from their module.json files (@pure_suites//:suites.bzl).

A module is listed once in MODULE.bazel (pure_suites.module), with its manifest; the extension reads each manifest
and writes MODULES, {name: {dir, dependencies, testDependencies, tests, reportDir, data, sources, compileSrcs, sourceDirs, generated, testsMode}}, which
hosts.bzl turns into one test per suite and host.
"""

_module = tag_class(attrs = {
    "manifest": attr.label(mandatory = True, doc = "The module.json of the module."),
    "report_dir": attr.string(doc = "The directory of its reports below test-results/; its name by default."),
    "data": attr.string_list(doc = "The files its suites read (fixture directories), as labels."),
    "sources": attr.string_list(doc = "Its source directories, relative to its own: what an in-memory host compiles."),
    "compile_srcs": attr.string_list(doc = "What a host compiles, as labels; '<its directory>:compile_sources' by default."),
    "source_dirs": attr.string_list(doc = "The --source directories of a compile, relative to its own; itself by default."),
    "generated": attr.string_dict(doc = "The generated sources of a compile: {label: path below its directory}."),
    "tests_mode": attr.string(doc = "'split' by default: '<name>.pdb' and '<name>-tests.pdb'; 'none': only the first; 'with': '<name>-with-tests.pdb'."),
})

def _repo_impl(rctx):
    rctx.file("BUILD.bazel", "")
    rctx.file("suites.bzl", "MODULES = " + rctx.attr.modules + "\n")

_repo = repository_rule(
    implementation = _repo_impl,
    attrs = {"modules": attr.string()},
)

def _manifest_dir(label):
    name_dir = label.name.rpartition("/")[0]
    return "/".join([part for part in [label.package, name_dir] if part])

def _module_of(mctx, tag):
    manifest = json.decode(mctx.read(tag.manifest))
    return (manifest["name"], {
        "dir": _manifest_dir(tag.manifest),
        "dependencies": manifest.get("dependencies", []),
        "testDependencies": manifest.get("testDependencies", []),
        "tests": manifest.get("tests", []),
        "reportDir": tag.report_dir or manifest["name"],
        "data": tag.data,
        "sources": tag.sources,
        "compileSrcs": tag.compile_srcs,
        "sourceDirs": tag.source_dirs,
        "generated": tag.generated,
        "testsMode": tag.tests_mode or "split",
    })

def _impl(mctx):
    modules = dict([_module_of(mctx, tag) for mod in mctx.modules for tag in mod.tags.module])
    _repo(name = "pure_suites", modules = json.encode_indent(modules, indent = "    "))
    return mctx.extension_metadata(reproducible = True)

pure_suites = module_extension(implementation = _impl, tag_classes = {"module": _module})
