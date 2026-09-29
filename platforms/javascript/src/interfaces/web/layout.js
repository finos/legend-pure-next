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

// layout.js — the two draggable splitters and the light/dark theme.
//
// Sizes live in two CSS custom properties on <main> (--sidew, --consoleh) that the grid
// reads, so a drag is one property write and the browser does the rest; Monaco reflows
// itself because it is created with automaticLayout. Both the sizes and an explicit theme
// choice are remembered in localStorage, which can throw or come back empty (private
// windows, cleared site data), so every access is guarded and the page renders correctly
// without it.

const STORE = "pure-web-ui";

function load() {
    try {
        return JSON.parse(localStorage.getItem(STORE) ?? "{}") ?? {};
    } catch {
        return {};
    }
}

function save(patch) {
    try {
        localStorage.setItem(STORE, JSON.stringify({ ...load(), ...patch }));
    } catch {
        // A remembered layout is a convenience, never a requirement.
    }
}

/**
 * Wire one splitter. `axis` is "x" (a column divider, sized from the left) or "y" (a row
 * divider, sized from the BOTTOM — the console grows upwards, so its height is the
 * distance from the pointer to the container's bottom edge).
 */
export function wireSplitter(handle, host, axis, prop, { min, max, remember = true }) {
    const apply = (px) => {
        const clamped = Math.max(min, Math.min(max(), px));
        host.style.setProperty(prop, `${clamped}px`);
        if (remember) save({ [prop]: clamped });
    };

    handle.addEventListener("pointerdown", (e) => {
        e.preventDefault();
        handle.setPointerCapture(e.pointerId);
        handle.classList.add("dragging");
        const rect = () => host.getBoundingClientRect();
        const move = (ev) => apply(axis === "x" ? ev.clientX - rect().left : rect().bottom - ev.clientY);
        const up = () => {
            handle.classList.remove("dragging");
            handle.removeEventListener("pointermove", move);
            handle.removeEventListener("pointerup", up);
        };
        handle.addEventListener("pointermove", move);
        handle.addEventListener("pointerup", up);
    });

    // Double-click restores the default, which is otherwise unreachable once dragged.
    handle.addEventListener("dblclick", () => {
        host.style.removeProperty(prop);
        if (remember) save({ [prop]: null });
    });
}

export function initSplitters(host) {
    const saved = load();
    for (const prop of ["--sidew", "--consoleh"]) {
        if (typeof saved[prop] === "number") host.style.setProperty(prop, `${saved[prop]}px`);
    }
    wireSplitter(document.getElementById("vsplit"), host, "x", "--sidew",
        { min: 140, max: () => host.clientWidth - 320 });
    wireSplitter(document.getElementById("hsplit"), host, "y", "--consoleh",
        { min: 60, max: () => host.clientHeight - 140 });
}

/**
 * Light or dark. With no explicit choice the page follows the system, and keeps following
 * it as it changes; pressing the button pins one and remembers it. Monaco is told
 * separately — its own themes are what colour the editor.
 */
export function initTheme(monaco, button) {
    const media = matchMedia("(prefers-color-scheme: dark)");
    let choice = load().theme ?? null; // null = follow the system

    const effective = () => choice ?? (media.matches ? "dark" : "light");

    function apply() {
        const theme = effective();
        document.documentElement.dataset.theme = theme;
        monaco?.editor.setTheme(theme === "dark" ? "pure-dark" : "pure-light");
        button.textContent = theme === "dark" ? "☾" : "☀";
        button.title = choice
            ? `${theme} theme (click to follow the system again)`
            : `following the system (${theme})`;
    }

    button.addEventListener("click", () => {
        // light -> dark -> follow the system -> light …
        choice = choice === null ? (media.matches ? "light" : "dark") : choice === "dark" ? null : "dark";
        save({ theme: choice });
        apply();
    });
    media.addEventListener("change", () => { if (choice === null) apply(); });
    apply();
    return apply;
}

/** Monaco themes for the `pure` token types the Monarch tokenizer emits. */
export function definePureThemes(monaco) {
    const rules = (c) => [
        { token: "annotation", foreground: c.annotation },
        { token: "number.multiplicity", foreground: c.multiplicity },
        { token: "type.identifier", foreground: c.path },
        { token: "variable", foreground: c.variable },
    ];
    monaco.editor.defineTheme("pure-light", {
        base: "vs", inherit: true,
        rules: rules({ annotation: "9147bf", multiplicity: "1e8a4c", path: "2f6fd0", variable: "b8860b" }),
        colors: {},
    });
    monaco.editor.defineTheme("pure-dark", {
        base: "vs-dark", inherit: true,
        rules: rules({ annotation: "c58af9", multiplicity: "6fd08f", path: "7fb2ff", variable: "e0b341" }),
        colors: { "editor.background": "#15151a" },
    });
}
