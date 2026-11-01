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
#   run-suite.sh <report path, relative> <command> <argument>...

set -euo pipefail
pending="${PURE_PENDING:-}"
report="${TEST_UNDECLARED_OUTPUTS_DIR:?run it with bazel test}/$1"
relative="$1"
shift
mkdir -p "$(dirname "$report")"
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
