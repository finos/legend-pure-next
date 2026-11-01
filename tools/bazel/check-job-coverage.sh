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

# Every test CI runs on a pull request belongs to exactly one job of .github/workflows/build.yml: it carries one
# 'job:<job>' tag (docs/superpowers/specs/2026-11-01-bazel-migration-design.md, Section 4). A manual test carries
# none: the manual jobs select theirs by the 'bootstrap' and 'selfhost' tags.
#
#   tools/bazel/check-job-coverage.sh

set -euo pipefail
required="tests(//...) except attr(tags, '\bmanual\b', //...)"
untagged=$(bazel query --noshow_progress "$required except attr(tags, 'job:', //...)" 2>/dev/null)
twice=$(bazel query --noshow_progress --output=build "$required" 2>/dev/null | awk '
    /^[a-z_]+\(/ { name = "" }
    /^  name = / { gsub(/[",]/, "", $3); name = $3 }
    /"job:/ { n = gsub(/"job:/, "&"); if (n > 1) print name }')
if [[ -n "$untagged" || -n "$twice" ]]; then
    [[ -n "$untagged" ]] && { echo "tests in no CI job (add a 'job:<job>' tag):"; echo "$untagged"; } >&2
    [[ -n "$twice" ]] && { echo "tests in more than one CI job:"; echo "$twice"; } >&2
    exit 1
fi
echo "every required test belongs to exactly one CI job ($(bazel query --noshow_progress "$required" 2>/dev/null | wc -l | tr -d ' ') tests)"
