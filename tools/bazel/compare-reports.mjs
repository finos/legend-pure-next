#!/usr/bin/env node
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

// Whether a tree of test reports still holds everything a baseline tree held: the guard that no suite and no case
// disappears in the Bazel port, and that nothing that passed stops passing.
//
//   node tools/bazel/compare-reports.mjs <baselineDir> <actualDir> [--only <pathPrefix>] [--only-run <runName>]
//
// A report is `{cases: [{id, status, durationMs}], durationMs, schema, suite}`, at a path relative to its tree
// (`spec/compiler/pure@cli-truffle.json`). Exit 1, listing each problem, when the actual tree:
//   - misses a report of the baseline;
//   - misses a case of a baseline report;
//   - has a case that was `passed` and is anything else.
// Extra reports and cases are allowed, and listed. `--only` keeps the reports under a path prefix; `--only-run`
// keeps the reports of one run, by file name without `.json` (`pure@cli-truffle`).

import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { basename, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

/** Every `.json` file below `root`, as paths relative to it, sorted. */
function reportPaths(root) {
    if (!existsSync(root)) return [];
    const walk = (dir) => readdirSync(dir).flatMap((name) => {
        const path = join(dir, name);
        if (statSync(path).isDirectory()) return walk(path);
        return name.endsWith(".json") ? [relative(root, path)] : [];
    });
    return walk(root).sort();
}

/** A report's cases by id. */
function casesOf(root, path) {
    const report = JSON.parse(readFileSync(join(root, path), "utf8"));
    return new Map((report.cases ?? []).map((c) => [c.id, c.status]));
}

const keep = (options) => (path) =>
    (!options.only || path.startsWith(options.only)) &&
    (!options.onlyRun || basename(path, ".json") === options.onlyRun);

/**
 * The comparison of `actualDir` against `baselineDir`: `{ok, problems, extras}`, each problem and extra a sentence
 * naming the report or case it is about.
 */
export function compareReports(baselineDir, actualDir, options = {}) {
    const baseline = reportPaths(baselineDir).filter(keep(options));
    const actual = new Set(reportPaths(actualDir).filter(keep(options)));
    const compared = baseline.reduce((acc, path) => {
        if (!actual.has(path)) return { ...acc, problems: [...acc.problems, `missing report '${path}'`] };
        const before = casesOf(baselineDir, path);
        const after = casesOf(actualDir, path);
        const problems = [...before].flatMap(([id, status]) => {
            if (!after.has(id)) return [`missing case '${id}' in '${path}'`];
            const now = after.get(id);
            return status === "passed" && now !== "passed" ? [`case '${id}' was 'passed' and is '${now}' in '${path}'`] : [];
        });
        const extras = [...after.keys()].filter((id) => !before.has(id)).map((id) => `extra case '${id}' in '${path}'`);
        return { problems: [...acc.problems, ...problems], extras: [...acc.extras, ...extras] };
    }, { problems: [], extras: [] });
    const baselineSet = new Set(baseline);
    const extraReports = [...actual].filter((path) => !baselineSet.has(path)).map((path) => `extra report '${path}'`);
    return { ok: compared.problems.length === 0, problems: compared.problems, extras: [...extraReports, ...compared.extras] };
}

/** `--only` and `--only-run` from the command line, after the two directories. */
function parseOptions(args) {
    const value = (flag) => {
        const at = args.indexOf(flag);
        return at >= 0 ? args[at + 1] : undefined;
    };
    return { only: value("--only"), onlyRun: value("--only-run") };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
    const [baselineDir, actualDir, ...rest] = process.argv.slice(2);
    if (!baselineDir || !actualDir) {
        console.error("usage: compare-reports.mjs <baselineDir> <actualDir> [--only <pathPrefix>] [--only-run <runName>]");
        process.exit(2);
    }
    const result = compareReports(baselineDir, actualDir, parseOptions(rest));
    result.extras.forEach((line) => console.log(`  + ${line}`));
    result.problems.forEach((line) => console.log(`  ✗ ${line}`));
    console.log(result.ok ? "reports match the baseline" : `${result.problems.length} problem(s) against the baseline`);
    process.exit(result.ok ? 0 : 1);
}
