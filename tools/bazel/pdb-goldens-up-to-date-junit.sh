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

# Whether the committed PDB goldens of the compiler fixtures are, byte for byte, what the Java writer of the bootstrap
# writes today (PdbGoldenGeneratorTest given -Dpdb.goldens.check): fails, naming each stale, missing or orphan golden,
# until 'bazel run //:update_pdb_goldens__compiler' is run. The ports' golden tests compare decoded nodes and would not
# notice a golden that only differs in layout.
#
#   pdb-goldens-up-to-date-junit.sh <JUnit launcher> <test class> <goldens, relative to the runfiles root>

set -euo pipefail
"$1" "--jvm_flag=-Dpdb.goldens.check=$PWD/$3" execute --select-class="$2" --fail-if-no-tests --disable-banner --details=tree
