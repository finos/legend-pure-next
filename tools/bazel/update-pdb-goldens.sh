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

# Write the PDB golden of each compiler fixture that compiles (not '*_E_*') back to the source tree, from the Pure
# writer (just: <owner>::regen-pdb-goldens): bazel run //:update_pdb_goldens__<owner>. Read the diff, and run the
# owner's pdb suite, before keeping them.
#
#   update-pdb-goldens.sh <CLI> <golden function> <fixtures> <subdirectory> <goldens> <CLI option>...
#
# <fixtures> and <goldens> are relative to the repository root; <subdirectory> is that of the fixtures to write
# (relative to <fixtures>), and a golden lands at the path of its fixture below <goldens>.

set -euo pipefail
root="${BUILD_WORKSPACE_DIRECTORY:?run it with bazel run}"
cli="$1"
function="$2"
fixtures="$root/$3"
subdirectory="$4"
goldens="$root/$5"
shift 5
written="$(mktemp)"
trap 'rm -f "$written"' EXIT
(cd "$fixtures" && find "$subdirectory" -name '*.pure' ! -name '*_E_*' | sed 's|^\./||; s|\.pure$||' | sort) | while read -r name; do
    "$cli" execute "$@" --function "$function" --args "$fixtures" "$name" | grep -v '^\[graal' | tail -1 | tr -d '\n' | xxd -r -p > "$written"
    test -s "$written" || { echo "no archive for '$name'" >&2; exit 1; }
    golden="$goldens/$name.pdb"
    if cmp -s "$written" "$golden"; then
        echo "  unchanged '$5/$name.pdb'"
    else
        mkdir -p "$(dirname "$golden")"
        cp "$written" "$golden"
        echo "  updated '$5/$name.pdb'"
    fi
done
