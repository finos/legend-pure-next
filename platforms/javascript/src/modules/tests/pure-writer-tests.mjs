// ©2026 JP Morgan Chase & Co. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Runs the translated Pure pdb-writer <<test.Test>>s in Node — including the
// wire-format byte pins captured from the JVM reference writer (bootstrap
// GeneratedFlatBufferWriter + FlatBufferBuilder). Passing means the TRANSLATED
// self-hosted Pure writer produces byte-identical output on the JS platform.
//
// Usage: node --stack-size=4000 src/modules/tests/pure-writer-tests.mjs
// (requires generated/ JS: `just javascript::generate-all`)
import { loadCompiler } from "../../compiler/host.js";

await loadCompiler();

const TESTS = [
  "meta$pure$compiler$pdb$schema$tests$testParseFbs__Boolean_1_",
  "meta$pure$compiler$pdb$writer$tests$testEncodePointerRefWireFormat__Boolean_1_",
  "meta$pure$compiler$pdb$writer$tests$testEncodeAncestorRefWireFormat__Boolean_1_",
  "meta$pure$compiler$pdb$writer$tests$testSharedStringDedupWireFormat__Boolean_1_",
  "meta$pure$compiler$pdb$writer$tests$testPointerKindAndSegs__Boolean_1_",
  "meta$pure$compiler$pdb$generator$tests$testSourceInformationPropertyOrder__Boolean_1_",
  "meta$pure$compiler$pdb$generator$tests$testToFbsFieldName__Boolean_1_",
  "meta$pure$compiler$pdb$gen$tests$testWriteSourceInformationWireFormat__Boolean_1_",
  "meta$pure$compiler$pdb$gen$tests$testWriteAtomicValueFloatWireFormat__Boolean_1_",
  "meta$pure$compiler$pdb$archive$tests$testElementTypeNameAndEntryName__Boolean_1_",
  "meta$pure$compiler$pdb$archive$tests$testElementEntryWireFormat__Boolean_1_",
  "meta$pure$compiler$pdb$archive$tests$testElementIndexWireFormat__Boolean_1_",
  "meta$pure$compiler$pdb$archive$tests$testManifestWireFormat__Boolean_1_",
  "meta$pure$compiler$pdb$archive$tests$testArchiveEntriesVsJavaPdb__Boolean_1_",
  "meta$pure$compiler$pdb$archive$tests$testZipStoredLayout__Boolean_1_",
  "meta$pure$compiler$pdb$archive$tests$testWriteArchive__Boolean_1_",
  "meta$pure$compiler$pdb$reader$tests$testLeInts__Boolean_1_",
  "meta$pure$compiler$pdb$reader$tests$testReadPackageEntry__Boolean_1_",
  "meta$pure$compiler$pdb$reader$tests$testReadProfileEntry__Boolean_1_",
  "meta$pure$compiler$pdb$reader$tests$testReadSourceInformationRoundTrip__Boolean_1_",
  "meta$pure$compiler$pdb$reader$tests$testReadAtomicValueFloatRoundTrip__Boolean_1_",
  "meta$pure$compiler$pdb$reader$tests$testReadRefs__Boolean_1_",
  "meta$pure$compiler$pdb$reader$tests$testReadArchiveRoundTrip__Boolean_1_",
];

let pass = 0,
  fail = 0;
for (const t of TESTS) {
  const fn = globalThis[t];
  const short = t.replace(/^meta\$pure\$compiler\$pdb\$/, "").replace(/__Boolean_1_$/, "");
  if (typeof fn !== "function") {
    console.log(`MISSING ${short}`);
    fail++;
    continue;
  }
  try {
    fn();
    console.log(`PASS ${short}`);
    pass++;
  } catch (e) {
    console.log(`FAIL ${short}: ${String(e && e.message ? e.message : e).slice(0, 300)}`);
    fail++;
  }
}
console.log(`\npdb writer tests (translated JS): ${pass}/${pass + fail} passed`);
process.exit(fail === 0 ? 0 : 1);
