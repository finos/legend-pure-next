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

# A test that a PDB holds no element below the given paths ('elements/<package path>/', as an archive names its
# entries), so that what belongs elsewhere does not come back there.
#
#   pdb-lacks.sh <pdb> <path>...

set -euo pipefail
pdb="$1"
shift
found=0
for path in "$@"; do
    if LC_ALL=C grep -a -q -F "$path" "$pdb"; then
        echo "'$pdb' holds '$path'" >&2
        found=1
    fi
done
exit "$found"
