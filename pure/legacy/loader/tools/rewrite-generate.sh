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

# Regenerate code/rewrite.pure (the copy of a parsed source with its references rewritten), from next's protocol model:
# bazel run //pure/legacy/loader:rewrite_generate.
#
#   rewrite-generate.sh <m3_protocol.pure, below the runfiles>

set -euo pipefail
root="${BUILD_WORKSPACE_DIRECTORY:?run it with bazel run}"
protocol="$PWD/$1"
cd "$root/pure/legacy/loader"
exec node tools/generate-protocol-rewrite.mjs "$protocol"
