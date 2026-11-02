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

# Serve the browser IDE as publish-gallery deploys it (:ide_site), on a port (default 8000; just: javascript::serve):
# bazel run //platforms/javascript:serve [-- <port>].
#
#   serve.sh <site> [<port>]

set -euo pipefail
port="${2:-8000}"
echo "open  http://localhost:$port/platforms/javascript/src/interfaces/web/index.html"
exec python3 -m http.server "$port" --directory "$1"
