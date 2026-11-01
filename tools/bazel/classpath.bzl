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

"""The runtime class path of Java targets, as separate jars: what native-image takes, where a fat jar would merge
the modules Truffle needs on the module path."""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")

def _runtime_jars_impl(ctx):
    jars = depset(transitive = [dep[JavaInfo].transitive_runtime_jars for dep in ctx.attr.deps])
    return [DefaultInfo(files = jars)]

runtime_jars = rule(
    implementation = _runtime_jars_impl,
    attrs = {"deps": attr.label_list(providers = [JavaInfo], doc = "The targets whose runtime jars these are.")},
    doc = "Every runtime jar of 'deps', each its own file.",
)
