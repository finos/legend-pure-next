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

"""GraalVM Community for JDK 25, the Java runtime Bazel runs binaries and tests on (--java_runtime_version=graalvm_25).

Truffle needs it: its optimizing runtime compiles with the Graal JIT (-XX:+UseJVMCICompiler), which a plain JDK has
not, and its tests fail on a Graal compilation that bails out. The version CI installs (build.yml GRAALVM_VERSION).
"""

load("@rules_java//toolchains:remote_java_repository.bzl", "remote_java_repository")

_VERSION = "25.0.2"

_ROOT = "graalvm-community-openjdk-25.0.2+10.1"

# {platform: (sha256, the JDK home in the archive, constraints)}
_ARCHIVES = {
    "linux-aarch64": ("b4580d9f223d0a4b3a1757e58b18ff4c1db950e67e105fc5cb741457d2384a71", _ROOT, ["@platforms//os:linux", "@platforms//cpu:aarch64"]),
    "linux-x64": ("e0be791c8fda4d03b6b0a0cb824fef3149736170057b3a515252b44419606af0", _ROOT, ["@platforms//os:linux", "@platforms//cpu:x86_64"]),
    "macos-aarch64": ("50201fb8e8e653e7a253d955877a6b6ad634b42e60c84f45df7e1bb51361635f", _ROOT + "/Contents/Home", ["@platforms//os:macos", "@platforms//cpu:aarch64"]),
}

def _graalvm_impl(_module_ctx):
    [remote_java_repository(
        name = "graalvm_25_" + platform.replace("-", "_"),
        prefix = "graalvm",
        sha256 = sha256,
        strip_prefix = home,
        target_compatible_with = constraints,
        urls = ["https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-%s/graalvm-community-jdk-%s_%s_bin.tar.gz" % (_VERSION, _VERSION, platform)],
        version = "25",
    ) for platform, (sha256, home, constraints) in _ARCHIVES.items()]

graalvm = module_extension(implementation = _graalvm_impl)
