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

# The report of a module that declares no suite: no case, so the dashboard shows the module, 0/0, rather than nothing
# (tools/bazel/hosts.bzl _suite_tests).
#
#   empty-report.sh <report path, relative to the test outputs>

set -euo pipefail
report="${TEST_UNDECLARED_OUTPUTS_DIR:?run it with bazel test}/$1"
mkdir -p "$(dirname "$report")"
printf '{"schema":1,"producer":"bazel","suite":"unit","durationMs":0,"recordedAt":"%s","source":"%s","cases":[]}\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${TEST_TARGET:-}" > "$report"
