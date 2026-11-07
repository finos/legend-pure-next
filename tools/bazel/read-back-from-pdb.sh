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

# What the decoder of a host reads back of the PDB goldens of a module. A golden is loaded as the host starts, and
# goldens may define the same element, so each fixture runs in a process of its own: a case of one report, passed or
# failed as the host exits, the end of its output as the error. Fails if a case failed.
#
#   read-back-from-pdb.sh <report> <function> <goldens directory> <fixtures directory> <fixture>=<element>... -- <host command>...

set -uo pipefail
report="$1"
function="$2"
goldens="$3"
sources="$4"
shift 4
fixtures=()
while [[ "$1" != "--" ]]; do
    fixtures+=("$1")
    shift
done
shift

# The last lines of the output as a JSON string: progress redraws split on their own lines, colours removed.
json() { tr '\r' '\n' | perl -pe 's/\e\[[0-9;]*[A-Za-z]//g; s/\t/ /g; s/[\x00-\x08\x0b-\x1f]//g; s/\\/\\\\/g; s/"/\\"/g' | awk 'NF' | tail -20 | awk '{printf "%s%s", (NR > 1 ? "\\n" : ""), $0}'; }

log="$(mktemp)"
started=$SECONDS
cases=""
failed=0
for spec in "${fixtures[@]}"; do
    fixture="${spec%%=*}"
    element="${spec#*=}"
    case_started=$SECONDS
    "$@" --pdb "$goldens/$fixture.pdb" --function "$function" --args "$sources/$fixture.pure" "$element" 2>&1 | tee "$log"
    status=${PIPESTATUS[0]}
    ms=$(( (SECONDS - case_started) * 1000 ))
    if [[ $status -eq 0 ]]; then
        outcome="\"status\":\"passed\",\"durationMs\":$ms"
    else
        failed=1
        outcome="\"status\":\"failed\",\"durationMs\":$ms,\"error\":{\"message\":\"$(json < "$log")\",\"stack\":[]}"
    fi
    cases="$cases${cases:+,}{\"id\":\"$fixture\",$outcome}"
done
printf '{"schema":1,"producer":"bazel","suite":"%s","durationMs":%d,"recordedAt":"%s","source":"%s","cases":[%s]}\n' \
    "$(basename "$(dirname "$report")")" "$(( (SECONDS - started) * 1000 ))" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$TEST_TARGET" "$cases" > "$report"
exit "$failed"
