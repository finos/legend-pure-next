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

// ide-tests.js — the browser IDE, driven in a real browser.
//
// The only part of this platform a Node test cannot reach. index.html loads runtime-lib, the two
// bundles and Monaco as classic <script>s and the rest as ES modules, so what the page gets is not
// what any Node harness assembles: a module moved, renamed, or newly importing something the bundle
// also carries will pass every other suite and break only here. That has happened — the page quietly
// stopped compiling diagrams after a bundle move, and nothing noticed until someone drove it by hand.
//
// NO BROWSER AUTOMATION DEPENDENCY. Node has a built-in WebSocket, so the Chrome DevTools Protocol is
// reachable directly; Playwright or Puppeteer would add a browser download to CI for what is ~80 lines
// of protocol here. Chrome itself comes from the environment (CHROME_PATH, or the usual locations) and
// is preinstalled on GitHub's ubuntu runners.
//
// EVERY WAIT IS CONDITION-BASED. The page downloads 13.6 MB of pdb archives before it is ready, so a
// fixed budget would either flake or have to be absurd — `--virtual-time-budget` in particular cuts
// the page off mid-download. One overall deadline bounds the run; nothing else sleeps.

import assert from "node:assert/strict";
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { dirname, join, normalize, extname } from "node:path";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { TestReport } from "../../../core/tests/test-report.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, "../../../../../..");
const PAGE = "/platforms/javascript/src/interfaces/web/index.html";
// Two budgets, because the two kinds of wait are nothing alike: the first load fetches 13.6 MB of
// pdb archives, while every later condition is a DOM update that either happens in a moment or is a
// bug. One global deadline made a failing check sit for the whole of it before reporting.
const LOAD_MS = Number(process.env.IDE_TEST_LOAD_MS ?? 180000);
const STEP_MS = Number(process.env.IDE_TEST_STEP_MS ?? 20000);

const results = [];
const report = new TestReport("ide");
const queue = [];
const check = (name, fn) => queue.push([name, fn]);

// --- the page's own source, with a ###Diagram section -------------------------
// A diagram is the point: its grammar, section parser and compiler extension all come from the
// extension's generated code, and the grammar table that resolves `###Diagram` lives inside
// antlr-bundle.js. Compiling this proves the whole chain is wired in the browser.
const SOURCE = [
    "###Pure",
    "Class ide::Person",
    "{",
    "   name : String[1];",
    "}",
    "",
    "###Diagram",
    "Diagram ide::PersonDiagram",
    "{",
    "   TypeView tv(type=ide::Person, position=(10.0, 20.0), width=120.0, height=40.0)",
    "}",
    "",
].join("\n");

// --- a static server over the repo -------------------------------------------
// Node rather than `python3 -m http.server`: one less thing CI has to have.
const MIME = {
    ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css",
    ".json": "application/json", ".map": "application/json", ".svg": "image/svg+xml",
    ".ttf": "font/ttf", ".woff": "font/woff", ".woff2": "font/woff2", ".wasm": "application/wasm",
    ".pdb": "application/octet-stream", ".fbs": "text/plain", ".ico": "image/x-icon",
};

function serveRepo() {
    const misses = [];
    const server = createServer(async (req, res) => {
        // index.html declares an inline icon, so Chrome should not ask for this — but a browser that
        // asks anyway would log a console error on a 404, and `pageErrors` has to stay a signal about
        // the page. (Finding this cost a run: the first failure here was an unexplained 404.)
        if (req.url === "/favicon.ico") { res.writeHead(204).end(); return; }
        // Strip the query and refuse anything that climbs out of the repo.
        const rel = normalize(decodeURIComponent(req.url.split("?")[0]));
        if (rel.includes("..")) { res.writeHead(403).end(); return; }
        const file = join(REPO, rel);
        try {
            const body = await readFile(file);
            res.writeHead(200, { "content-type": MIME[extname(file)] ?? "application/octet-stream" });
            res.end(body);
        } catch {
            misses.push(rel);           // named in the failure, so a 404 is never a mystery
            res.writeHead(404).end();
        }
    });
    server.misses = misses;
    return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve(server)));
}

// --- Chrome ------------------------------------------------------------------
function chromeBinary() {
    const candidates = [
        process.env.CHROME_PATH,
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable",
        "/usr/bin/chromium", "/usr/bin/chromium-browser",
    ].filter(Boolean);
    const found = candidates.find((c) => existsSync(c));
    if (!found) {
        throw new Error("no Chrome found. Set CHROME_PATH, or install Chrome/Chromium.\n  looked at: "
            + candidates.join(", "));
    }
    return found;
}

async function launchChrome(url) {
    const profile = mkdtempSync(join(tmpdir(), "pure-ide-test-"));
    const child = spawn(chromeBinary(), [
        "--headless=new",
        "--disable-gpu",
        "--no-sandbox",                 // CI containers run as root; harmless locally
        "--disable-dev-shm-usage",      // small /dev/shm in containers crashes the renderer
        "--no-first-run", "--no-default-browser-check",
        "--remote-debugging-port=0",    // the port is read back, so parallel runs cannot collide
        `--user-data-dir=${profile}`,
        url,
    ], { stdio: ["ignore", "ignore", "pipe"] });
    let stderr = "";
    child.stderr.on("data", (d) => { stderr += d; });
    // Chrome writes the chosen port to DevToolsActivePort once the socket is up.
    const portFile = join(profile, "DevToolsActivePort");
    const port = await until(() => {
        if (child.exitCode !== null) {
            throw new Error(`Chrome exited with ${child.exitCode} before listening:\n${stderr.slice(-2000)}`);
        }
        if (!existsSync(portFile)) return undefined;
        const first = readFileSync(portFile, "utf8").split("\n")[0].trim();
        return first ? Number(first) : undefined;
    }, "Chrome to open its debugging port", STEP_MS);
    return {
        port,
        close() {
            child.kill("SIGKILL");
            try { rmSync(profile, { recursive: true, force: true }); } catch { /* best effort */ }
        },
    };
}

// --- CDP over the built-in WebSocket -----------------------------------------
async function attach(port, pageUrl) {
    // The page target appears once Chrome has started loading the url it was given.
    const target = await until(async () => {
        const res = await fetch(`http://127.0.0.1:${port}/json/list`).catch(() => undefined);
        if (!res) return undefined;
        const targets = await res.json();
        return targets.find((t) => t.type === "page" && t.url.includes(pageUrl));
    }, `the page target for ${pageUrl}`, STEP_MS);

    const ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
        ws.addEventListener("open", resolve, { once: true });
        ws.addEventListener("error", () => reject(new Error("could not open the CDP socket")), { once: true });
    });

    let nextId = 0;
    const pending = new Map();
    const pageErrors = [];
    ws.addEventListener("message", (event) => {
        const msg = JSON.parse(event.data);
        if (msg.id !== undefined) {
            const entry = pending.get(msg.id);
            pending.delete(msg.id);
            if (!entry) return;
            if (msg.error) entry.reject(new Error(`${msg.error.message} (CDP)`));
            else entry.resolve(msg.result);
            return;
        }
        // An uncaught exception or a console/network error is a page failure, wherever it came from.
        if (msg.method === "Runtime.exceptionThrown") {
            const d = msg.params.exceptionDetails;
            pageErrors.push(d.exception?.description ?? d.text ?? "uncaught exception");
        }
        if (msg.method === "Runtime.consoleAPICalled" && msg.params.type === "error") {
            pageErrors.push(msg.params.args.map((a) => a.description ?? a.value).join(" "));
        }
        if (msg.method === "Log.entryAdded" && msg.params.entry.level === "error") {
            pageErrors.push(msg.params.entry.text);
        }
    });

    const send = (method, params = {}) => new Promise((resolve, reject) => {
        const id = ++nextId;
        pending.set(id, { resolve, reject });
        ws.send(JSON.stringify({ id, method, params }));
    });

    await send("Runtime.enable");
    await send("Log.enable");

    // `evaluate` returns the VALUE, and a thrown page-side error becomes a thrown test error.
    const evaluate = async (expression) => {
        const r = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
        if (r.exceptionDetails) {
            throw new Error(r.exceptionDetails.exception?.description ?? r.exceptionDetails.text);
        }
        return r.result.value;
    };
    return { evaluate, pageErrors, close: () => ws.close() };
}

/**
 * Poll `fn` until it returns something other than undefined, or the deadline passes. The only
 * waiting primitive here: every wait is for a condition the page reaches, never for a duration.
 */
async function until(fn, what, timeoutMs = STEP_MS) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        const value = await fn();
        if (value !== undefined) return value;
        await new Promise((r) => setTimeout(r, 100));
    }
    throw new Error(`timed out after ${timeoutMs} ms waiting for ${what}`);
}

// --- the run -----------------------------------------------------------------
let server; let chrome; let page;

check("the IDE loads and reaches Ready", async () => {
    server = await serveRepo();
    const url = `http://127.0.0.1:${server.address().port}${PAGE}`;
    chrome = await launchChrome(url);
    page = await attach(chrome.port, PAGE);
    // The page reports its own readiness; it downloads 13.6 MB of archives to get there.
    const status = await until(async () => {
        const text = await page.evaluate("document.getElementById('status')?.textContent ?? ''");
        return text.trim() === "Ready." ? text.trim() : undefined;
    }, "the IDE to report Ready.", LOAD_MS);
    assert.equal(status, "Ready.");
    assert.deepEqual(page.pageErrors, [],
        `the page logged errors while loading:\n  ${page.pageErrors.join("\n  ")}`
        + (server.misses.length ? `\n  unserved requests: ${server.misses.join(", ")}` : ""));
});

check("it compiles a source containing a ###Diagram section", async () => {
    // Through Monaco, so the page's own change handling runs — every edit writes through to the
    // in-memory module the compiler reads.
    await page.evaluate(`monaco.editor.getEditors()[0].getModel().setValue(${JSON.stringify(SOURCE)})`);
    await page.evaluate("document.getElementById('compile').click()");
    // compileOnly reports the graph size; an unregistered ###Diagram section fails the compile
    // instead ("No parser registered for section: ###Diagram"), so this message IS the assertion.
    const consoleText = await until(async () => {
        const text = await page.evaluate("document.getElementById('console')?.textContent ?? ''");
        return /element(s)? in the graph/.test(text) ? text : undefined;
    }, "the console to report a compiled graph");
    assert.match(consoleText, /element(s)? in the graph/);
    assert.doesNotMatch(consoleText, /No parser registered/);
    assert.deepEqual(page.pageErrors, [],
        `the page logged errors while compiling:\n  ${page.pageErrors.join("\n  ")}`
        + (server.misses.length ? `\n  unserved requests: ${server.misses.join(", ")}` : ""));
});

check("the compiled diagram element is browsable in the concept tree", async () => {
    // Through the FILTER, not by reading the tree: a package renders as <details> whose children are
    // built the first time it opens, so an unopened `ide` package has no PersonDiagram in the DOM at
    // all. Filtering is also what a user would do, and it answers with a flat list of full paths.
    await page.evaluate(`
        const search = document.querySelector('#concepts .concept-filter');
        search.value = 'PersonDiagram';
        search.dispatchEvent(new Event('input'));
    `);
    const text = await until(async () => {
        const body = await page.evaluate("document.querySelector('#concepts .concept-body')?.textContent ?? ''");
        return body.includes("PersonDiagram") ? body : undefined;
    }, "the filtered concept list to show ide::PersonDiagram");
    assert.match(text, /ide::PersonDiagram/);
});

for (const [name, fn] of queue) {
    const started = Date.now();
    try { await fn(); results.push({ name }); report.pass(name, Date.now() - started); }
    catch (error) { results.push({ name, error }); report.fail(name, Date.now() - started, error); }
}
page?.close();
chrome?.close();
server?.close();

const failures = results.filter((r) => r.error);
for (const f of failures) console.log(`  FAIL ${f.name}\n    ${f.error.message}`);
console.log(`ide: ${results.length - failures.length}/${results.length} passed`);
report.write();
process.exit(failures.length ? 1 : 0);
