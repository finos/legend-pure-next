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

# Whether each pair of PDBs holds the same elements, property by property (pure-bootstrap diff-pdb --deep): the check
# that what a compiler made of a module is what the reference made of it. Every pair is compared, and the ones that
# differ are named at the end.
#
#   pdb-diff.sh <pure-bootstrap> <core.pdb> <reference.pdb> <compared.pdb> [<reference.pdb> <compared.pdb>]...

set -uo pipefail
cli="$1"
core="$2"
shift 2
different=()
while [[ $# -gt 0 ]]; do
    echo "== $(basename "$2")"
    "$cli" diff-pdb --deep --core "$core" "$1" "$2" || different+=("$(basename "$2")")
    shift 2
done
if [[ ${#different[@]} -gt 0 ]]; then
    echo "differ from the reference: ${different[*]}" >&2
    exit 1
fi
