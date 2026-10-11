#!/usr/bin/env node
// Copyright 2026 Goldman Sachs
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

// tools/test-dashboard/build-step.mjs - records how long a heavy build step takes, for the dashboard's build time trend.
//
//   build-step.mjs start <step>   a recipe's first dependency: (_step-start "<step>")
//   build-step.mjs end <step>     the recipe body's last line
//
// `end` appends { step, at, durationMs, commit, dirty, invocation: { id, command } } to test-results/builds.json.
// A step that fails never reaches `end`, so only completed steps are recorded. The invocation is the outermost
// `just` process running the step: the dashboard stacks the steps of one command (say, `just test-all`) into one bar.

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const LIMIT = 2000;
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const resultsDir = join(repoRoot, "test-results");
const buildsFile = join(resultsDir, "builds.json");

const run = (cmd, args) => {
  try {
    return execFileSync(cmd, args, { cwd: repoRoot, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
  } catch {
    return null;
  }
};

// Walks up the process tree and keeps the outermost `just`, so nested `just` calls share their parent's invocation.
function invocation() {
  let pid = process.ppid, found = null;
  while (pid > 1) {
    const line = run("ps", ["-o", "ppid=,comm=", "-p", String(pid)]);
    if (!line) break;
    const [, ppid, comm] = line.match(/^\s*(\d+)\s+(.*)$/) ?? [];
    if (comm && comm.split("/").pop() === "just") found = pid;
    pid = Number(ppid);
  }
  if (!found) return { id: `pid-${process.ppid}`, command: null };
  const started = run("ps", ["-o", "lstart=", "-p", String(found)]);
  const args = run("ps", ["-o", "args=", "-p", String(found)]) ?? "";
  return { id: `${found}-${started}`, command: args.replace(/^\S*\/just\b/, "just") };
}

const [action, step] = process.argv.slice(2);
if (!["start", "end"].includes(action) || !step) {
  console.error("usage: build-step.mjs start|end <step>");
  process.exit(2);
}
const inv = invocation();
const stamp = join(resultsDir, ".build-steps", `${inv.id}__${step}`.replace(/[^\w.+-]+/g, "_"));

if (action === "start") {
  mkdirSync(dirname(stamp), { recursive: true });
  writeFileSync(stamp, String(Date.now()));
  process.exit(0);
}

if (!existsSync(stamp)) process.exit(0); // started outside this invocation: nothing to measure
const startedAt = Number(readFileSync(stamp, "utf8"));
rmSync(stamp);

// builds.json is read, extended and rewritten, and steps of a parallel run end at the same time:
// without a lock two of them read the same file and the second write drops the first's step. The
// lock is a directory because mkdir is atomic; one older than STALE_MS is a writer that died
// holding it, and is broken rather than waited on forever.
const lock = `${buildsFile}.lock`;
const STALE_MS = 30000;
const pause = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const acquire = (deadline) => {
  try {
    mkdirSync(lock);
    return true;
  } catch {
    const age = (() => { try { return Date.now() - statSync(lock).mtimeMs; } catch { return 0; } })();
    if (age > STALE_MS) rmSync(lock, { recursive: true, force: true });
    if (Date.now() > deadline) return false;
    pause(50);
    return acquire(deadline);
  }
};
if (!acquire(Date.now() + 2 * STALE_MS)) {
  console.error(`build-step: could not lock ${buildsFile}; step ${step} not recorded`);
  process.exit(0);
}
process.on("exit", () => rmSync(lock, { recursive: true, force: true }));

let steps = [];
try {
  steps = JSON.parse(readFileSync(buildsFile, "utf8")).steps ?? [];
} catch {
  // no build times yet
}
steps.push({
  step,
  at: new Date(startedAt).toISOString(),
  durationMs: Date.now() - startedAt,
  commit: run("git", ["rev-parse", "--short", "HEAD"]),
  dirty: (run("git", ["status", "--porcelain"]) ?? "") !== "",
  invocation: inv
});
writeFileSync(buildsFile, JSON.stringify({ schema: 1, steps: steps.slice(-LIMIT) }, null, 2) + "\n");
