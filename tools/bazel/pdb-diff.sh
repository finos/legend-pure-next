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

# Whether two PDBs hold the same elements, property by property (pure-bootstrap diff-pdb --deep): the check that a
# module compiled by the Truffle CLI is the one the bootstrap compiles.
#
#   pdb-diff.sh <pure-bootstrap> <core.pdb> <a.pdb> <b.pdb>

set -euo pipefail
"$1" diff-pdb --deep --core "$2" "$3" "$4"
