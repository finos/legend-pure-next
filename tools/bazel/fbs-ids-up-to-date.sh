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

# Whether a tracked field-ID registry holds every ID the schema generation assigned: fails, with the IDs to add,
# when a model change added a field.
#
#   fbs-ids-up-to-date.sh <tracked> <generated> <update command>

set -euo pipefail
if ! diff -u "$1" "$2"; then
    echo "the field-ID registry '$1' is missing the IDs above: run '$3'" >&2
    exit 1
fi
