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

# Copy a field-ID registry the schema generation wrote over the tracked one: the only writes of sources the Bazel
# build makes, and only when asked (bazel run //:update_fbs_ids__<registry>).
#
#   update-fbs-ids.sh <generated> <tracked, relative to the repository root>

set -euo pipefail
target="${BUILD_WORKSPACE_DIRECTORY:?run it with bazel run}/$2"
if cmp -s "$1" "$target"; then
    echo "the field-ID registry '$2' is up to date"
else
    cp "$1" "$target"
    echo "updated '$2'"
fi
