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

// console-panel.js — the output pane under the editor.
//
// Pure's `print`/`println` reach the browser through runtime-lib's __writeOut, which falls
// back to console.log when there is no process.stdout. This panel taps that fall-back while
// a program runs and appends as it goes, so a long or looping program shows its output
// while it runs instead of only when it returns. Everything still reaches the devtools
// console — the tap forwards, it does not swallow.
//
// print() writes without a newline and println() appends one, so chunks are concatenated
// verbatim: inserting separators here would corrupt Pure's own formatting.

export function createConsole(host) {
    const body = document.createElement("div");
    body.className = "console-body";
    host.append(body);

    let pending = "";      // program output not yet flushed to the DOM
    let programLine = null; // the <pre> the running program is writing into

    const atBottom = () => body.scrollHeight - body.scrollTop - body.clientHeight < 40;

    function append(node) {
        const stick = atBottom();
        body.append(node);
        if (stick) body.scrollTop = body.scrollHeight;
    }

    /** A diagnostic line from the page itself: status, timings, errors. */
    function line(text, cls = "") {
        const el = document.createElement("div");
        el.className = `line ${cls}`.trim();
        el.textContent = text;
        append(el);
        return el;
    }

    function flush() {
        if (!pending) return;
        if (!programLine) {
            programLine = document.createElement("pre");
            programLine.className = "program-out";
            append(programLine);
        }
        programLine.textContent += pending;
        pending = "";
        if (atBottom()) body.scrollTop = body.scrollHeight;
    }

    return {
        clear() { body.textContent = ""; pending = ""; programLine = null; },
        info: (t) => line(t, "info"),
        error: (t) => line(t, "error"),
        ok: (t) => line(t, "ok"),
        /** A block of text under a heading — the result, a printed graph, generated JS. */
        block(title, text) {
            const wrap = document.createElement("div");
            wrap.className = "block";
            const h = document.createElement("div");
            h.className = "block-title";
            h.textContent = title;
            const pre = document.createElement("pre");
            pre.textContent = text;
            wrap.append(h, pre);
            append(wrap);
        },
        /**
         * Run `fn` with console.log teed into this panel. Output is buffered and flushed on
         * an animation frame: a program that prints in a tight loop would otherwise spend
         * its time in layout rather than in Pure.
         */
        capture(fn) {
            const original = console.log;
            let frame = null;
            programLine = null;
            console.log = (...args) => {
                pending += args.join(" ");
                frame ??= requestAnimationFrame(() => { frame = null; flush(); });
                original.apply(console, args);
            };
            try {
                return fn();
            } finally {
                console.log = original;
                if (frame) cancelAnimationFrame(frame);
                flush();
            }
        },
    };
}
