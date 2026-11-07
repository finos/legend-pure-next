#!/usr/bin/env bash
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

# Write the PDB goldens of the compiler fixtures back to the source tree from the Java writer of the bootstrap
# (PdbGoldenGeneratorTest, which writes them when given -Dpdb.goldens.dir): bazel run //:update_pdb_goldens__compiler.
#
#   update-pdb-goldens-junit.sh <JUnit launcher> <test class> <goldens, relative to the repository root>

set -euo pipefail
root="${BUILD_WORKSPACE_DIRECTORY:?run it with bazel run}"
"$1" "--jvm_flag=-Dpdb.goldens.dir=$root/$3" execute --select-class="$2" --fail-if-no-tests --disable-banner --details=summary
git -C "$root" status --short -- "$3"
