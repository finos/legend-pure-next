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

# Run one suite on one host: the host command, with '{report}' in its arguments replaced by where Bazel keeps the
# test outputs ($TEST_UNDECLARED_OUTPUTS_DIR/<owner>/<suite>/<host>.json). The host fails the test on a failed case.
#
# With PURE_PENDING set (its reason), the suite is a known gap: the test passes while the suite fails having run (its
# report written), and fails once it passes, so that its pending entry (tools/bazel/hosts.bzl PENDING) is removed.
#
# With PURE_CASE set (a case id), the command is one case of a report several tests share (each fixture of a host's
# PDB read-backs): when it writes no report itself, this writes one holding that case, passed or failed as the
# command exits, the end of its output as the error. tools/bazel/collect-reports.mjs merges the reports at one path.
#
#   run-suite.sh <report path, relative> <command> <argument>...

set -euo pipefail
pending="${PURE_PENDING:-}"
report="${TEST_UNDECLARED_OUTPUTS_DIR:?run it with bazel test}/$1"
relative="$1"
shift
mkdir -p "$(dirname "$report")"
case_id="${PURE_CASE:-}"
if [[ -n "$case_id" ]]; then
    log="$(mktemp)"
    started=$SECONDS
    set +e
    "${@//\{report\}/$report}" 2>&1 | tee "$log"
    status=${PIPESTATUS[0]}
    set -e
    if [[ ! -s "$report" ]]; then
        # The last lines of the output as a JSON string: progress redraws split on their own lines, colours removed.
        json() { tr '\r' '\n' | perl -pe 's/\e\[[0-9;]*[A-Za-z]//g; s/\t/ /g; s/[\x00-\x08\x0b-\x1f]//g; s/\\/\\\\/g; s/"/\\"/g' | awk 'NF' | tail -20 | awk '{printf "%s%s", (NR > 1 ? "\\n" : ""), $0}'; }
        ms=$(( (SECONDS - started) * 1000 ))
        if [[ $status -eq 0 ]]; then
            outcome="\"status\":\"passed\",\"durationMs\":$ms"
        else
            outcome="\"status\":\"failed\",\"durationMs\":$ms,\"error\":{\"message\":\"$(json < "$log")\",\"stack\":[]}"
        fi
        printf '{"schema":1,"producer":"bazel","suite":"%s","durationMs":%d,"recordedAt":"%s","source":"%s","cases":[{"id":"%s",%s}]}\n' \
            "$(basename "$(dirname "$relative")")" "$ms" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$TEST_TARGET" "$case_id" "$outcome" > "$report"
    fi
    exit "$status"
fi
if [[ -z "$pending" ]]; then
    exec "${@//\{report\}/$report}"
fi
if "${@//\{report\}/$report}"; then
    echo "the suite passes: remove its pending entry in tools/bazel/hosts.bzl ($pending)" >&2
    exit 1
fi
if [[ ! -s "$report" ]]; then
    echo "the suite did not run (no report at '$relative'): a pending entry covers a suite that fails, not one that cannot start" >&2
    exit 1
fi
echo "pending, as expected: $pending"
