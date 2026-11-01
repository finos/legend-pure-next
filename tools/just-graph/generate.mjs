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

// The Justfiles as a graph: every recipe of every module (`just --dump --dump-format json`), its dependencies,
// the `just …` it runs from its body, and the CLI it runs Pure with — written to one self-contained page
// (tools/just-graph/build/just-graph.html) that draws a recipe's dependencies or dependents.
//
//   node tools/just-graph/generate.mjs        (or: just just-graph)
//
// A Justfile mounted by several modules (`mod _mod_bootstrap "../../bootstrap/Justfile"`) is ONE set of recipes:
// a recipe is identified by its Justfile and name, and shown under the shortest path it is reachable by.

import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..", "..");
const out = join(here, "build", "just-graph.html");

const dump = JSON.parse(execFileSync("just", ["--dump", "--dump-format", "json"], { cwd: root, maxBuffer: 64 << 20 }).toString());

// A body fragment list back to text: strings as written, interpolations as {{expression}}.
const expression = (e) => {
    if (typeof e === "string") return JSON.stringify(e);
    if (!Array.isArray(e)) return String(e);
    const [kind, ...rest] = e;
    switch (kind) {
        case "variable": return rest[0];
        case "concatenate": return rest.map(expression).join(" + ");
        case "join": return rest.filter((x) => x !== null).map(expression).join(" / ");
        case "call": return `${rest[0]}(${rest.slice(1).map(expression).join(", ")})`;
        case "if": return `if …`;
        default: return `${kind}(${rest.map(expression).join(", ")})`;
    }
};
const line = (fragments) => fragments.map((f) => (typeof f === "string" ? f : Array.isArray(f) && f.length === 1 ? `{{${expression(f[0])}}}` : `{{${expression(f)}}}`)).join("");
const variables = (fragments, found = new Set()) => {
    for (const f of fragments) {
        if (Array.isArray(f)) {
            if (f[0] === "variable" && typeof f[1] === "string") found.add(f[1]);
            else variables(f, found);
        }
    }
    return found;
};

// What a body runs Pure (or the build) with, from the variables and commands it uses.
const CLIS = [
    { cli: "bootstrap", test: (vars, text) => vars.has("pure_bootstrap") || /pure-bootstrap\b/.test(text) },
    { cli: "truffle", test: (vars, text) => vars.has("pure_truffle") || /pure-truffle\b/.test(text) },
    { cli: "bootstrap|truffle", test: (vars) => vars.has("emit_rt") },
    { cli: "javascript", test: (vars, text) => vars.has("pure_js") || /pure-js\b/.test(text) },
    { cli: "maven", test: (vars, text) => /(^|[\s;&|(])mvn\s/.test(text) || vars.has("mvn") },
    { cli: "node", test: (vars, text) => /(^|[\s;&|(])(node|pnpm)\s/.test(text) },
];
const commands = (text) => [...new Set([...text.matchAll(/\b(?:pure[-_](?:bootstrap|truffle|js)\}*)\s+(compile-via-pure|compile|execute(?:-from-memory)?|diff-pdb)/g)].map((m) => m[1]))];

// `just …` run from a body — or through a shell function wrapping it (`j() { just … "$@"; }`, as the root's
// test-all lanes do): the recipes named, root-relative when the body goes through the root Justfile.
const justCalls = (text) => {
    const wrappers = [...text.matchAll(/\b([A-Za-z_]\w*)\(\)\s*\{\s*just\b/g)].map((m) => m[1]);
    const runner = ["just", ...wrappers].join("|");
    const call = new RegExp(`(?:^|[\\s;&|({])(?:${runner})\\s+((?:--?[\\w-]+(?:\\s+(?:"[^"]*"|\\S+))?\\s+)*)([A-Za-z_][\\w:-]*(?:\\s+[A-Za-z_][\\w:-]*)*)`, "gm");
    return [...text.matchAll(call)]
        .flatMap((m) => m[2].split(/\s+/).filter((name) => /^[A-Za-z_][\w-]*(::[\w-]+)*$/.test(name)));
};

const nodes = new Map(); // id -> node
const byModulePath = new Map(); // module path ("" for the root) -> module dump
const idOf = (source, name) => `${relative(root, source)}#${name}`;

const visit = (module, path) => {
    byModulePath.set(path, module);
    for (const recipe of Object.values(module.recipes)) {
        const id = idOf(module.source, recipe.name);
        const shown = path ? `${path}::${recipe.name}` : recipe.name;
        const existing = nodes.get(id);
        if (existing) {
            if (shown.split("::").length < existing.name.split("::").length) existing.name = shown;
            existing.paths.push(shown);
            continue;
        }
        const body = recipe.body.map(line);
        const text = body.join("\n");
        const vars = variables(recipe.body);
        nodes.set(id, {
            id,
            name: shown,
            paths: [shown],
            recipe: recipe.name,
            file: relative(root, module.source),
            doc: recipe.doc,
            private: recipe.private,
            parameters: recipe.parameters.map((p) => (p.default === null ? p.name : `${p.name}=${expression(p.default)}`)),
            clis: CLIS.filter((c) => c.test(vars, text)).map((c) => c.cli),
            commands: commands(text),
            body: text,
            deps: recipe.dependencies.map((d) => ({ name: d.recipe, args: d.arguments.map(expression) })),
            calls: justCalls(text),
            modulePath: path,
        });
    }
    for (const [name, sub] of Object.entries(module.modules)) visit(sub, path ? `${path}::${name}` : name);
};
visit(dump, "");

// A dependency names a recipe relative to its module; a `just …` call names one relative to the root.
const resolveName = (fromPath, name) => {
    const parts = name.split("::");
    const recipeName = parts.pop();
    const modulePath = [fromPath, ...parts].filter(Boolean).join("::");
    const module = byModulePath.get(modulePath);
    return module && module.recipes[recipeName] ? idOf(module.source, recipeName) : null;
};
const edges = [];
const unresolved = [];
for (const node of nodes.values()) {
    for (const dep of node.deps) {
        const to = resolveName(node.modulePath, dep.name);
        if (to) edges.push({ from: node.id, to, kind: "dep", args: dep.args });
        else unresolved.push(`${node.name} -> ${dep.name}`);
    }
    for (const call of node.calls) {
        const to = resolveName("", call) ?? resolveName(node.modulePath, call);
        if (to && to !== node.id) edges.push({ from: node.id, to, kind: "call", args: [] });
    }
}
const unique = [...new Map(edges.map((e) => [`${e.from}>${e.to}>${e.kind}`, e])).values()];
const data = {
    generated: new Date().toISOString(),
    nodes: [...nodes.values()].map(({ deps, calls, modulePath, ...n }) => n),
    edges: unique,
};

mkdirSync(dirname(out), { recursive: true });
const page = readFileSync(join(here, "page.html"), "utf8").replace("/*__DATA__*/null", () => JSON.stringify(data).replace(/</g, "\\u003c"));
writeFileSync(out, page);
console.log(`  ${data.nodes.length} recipes, ${unique.filter((e) => e.kind === "dep").length} dependencies, ${unique.filter((e) => e.kind === "call").length} calls -> ${relative(root, out)}`);
if (unresolved.length) console.log(`  unresolved dependencies: ${unresolved.join(", ")}`);
