#!/usr/bin/env node
// tools/test-dashboard/merge-builds.mjs - merges build step records (builds.json files written by build-step.mjs)
// into one file, as the CI publish job does with the published history and each job's steps:
//
//   merge-builds.mjs <out> <input>...
//
// Missing or unreadable inputs are skipped. A step recorded twice (same step, start and invocation, as when a
// job is re-run) is kept once. The result is ordered by start time and keeps the latest 2000 steps, like build-step.mjs.

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

const LIMIT = 2000;
const [out, ...inputs] = process.argv.slice(2);
if (!out) {
  console.error("usage: merge-builds.mjs <out> <input>...");
  process.exit(2);
}

const read = file => {
  try {
    return JSON.parse(readFileSync(file, "utf8")).steps ?? [];
  } catch {
    return [];
  }
};

const present = inputs.filter(file => existsSync(file));
const seen = new Set();
const steps = [];
for (const step of present.flatMap(read)) {
  const key = `${step.step}|${step.at}|${step.invocation?.id ?? ""}`;
  if (seen.has(key)) continue;
  seen.add(key);
  steps.push(step);
}
steps.sort((a, b) => String(a.at).localeCompare(String(b.at)));
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, JSON.stringify({ schema: 1, steps: steps.slice(-LIMIT) }, null, 2) + "\n");
console.log(`merged ${Math.min(steps.length, LIMIT)} build steps from ${present.length} of ${inputs.length} files into ${out}`);
