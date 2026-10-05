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

// source-store.js — the workspace's files, remembered in the browser.
//
// Kept under its own key, separate from layout.js's `pure-web-ui`: remembered splitter sizes
// are disposable, the user's source is not, so a quota failure or a cleared UI preference
// must not be able to take the other with it.
//
// Every access is guarded. `localStorage` throws in a private window, comes back empty after
// cleared site data, and throws again on quota — and in each case the page must still open,
// on the sample, rather than not open at all.
//
// WHAT THIS IS NOT: it is per-browser and per-origin. It is not a save-to-file, it is not
// shared between devices, and on the published site each visitor sees only their own work.
// Roughly 5 MB is available, which is ample for Pure source and why the ~13 MB of PDBs stay
// in the HTTP cache instead.

const KEY = "pure-web-sources";
const VERSION = 1;

/** The default file's id. Also the sourceId the compiler reports errors against. */
export const DEFAULT_SOURCE_ID = "editor";

/** Characters a file name may keep — everything else is dropped, including separators. */
const SAFE_NAME = /[^A-Za-z0-9._:-]+/g;

/**
 * A storage-like object, defaulting to the real one. Passing a fake is how the tests cover
 * the failure modes that matter — absent, corrupt, and throwing.
 */
const storage = () => {
    try {
        return globalThis.localStorage ?? null;
    } catch {
        return null;   // accessing the property itself can throw
    }
};

/**
 * `{ sources: { id: text }, active: id }` as last saved, or null when there is nothing
 * usable. A stored blob from a future version is IGNORED rather than guessed at: showing
 * someone a misread version of their own file is worse than showing them the sample.
 */
export function loadWorkspace(store = storage()) {
    if (!store) return null;
    let raw;
    try {
        raw = store.getItem(KEY);
    } catch {
        return null;
    }
    if (!raw) return null;
    let parsed;
    try {
        parsed = JSON.parse(raw);
    } catch {
        return null;
    }
    if (!parsed || parsed.v !== VERSION || typeof parsed.sources !== "object" || !parsed.sources) {
        return null;
    }
    const sources = {};
    for (const [id, text] of Object.entries(parsed.sources)) {
        if (typeof id === "string" && id && typeof text === "string") sources[id] = text;
    }
    if (Object.keys(sources).length === 0) return null;
    const active = typeof parsed.active === "string" && sources[parsed.active] !== undefined
        ? parsed.active
        : Object.keys(sources)[0];
    return { sources, active };
}

/**
 * Remember the whole workspace. Returns false when it could not be saved — the caller may
 * want to say so, because silently losing someone's work is the one outcome worth reporting.
 */
export function saveWorkspace({ sources, active }, store = storage()) {
    if (!store) return false;
    try {
        store.setItem(KEY, JSON.stringify({ v: VERSION, sources, active }));
        return true;
    } catch {
        // Full, or blocked. The editor keeps working; only the memory of it is lost.
        return false;
    }
}

/** Forget everything, so the next load starts from the sample. */
export function clearWorkspace(store = storage()) {
    if (!store) return;
    try {
        store.removeItem(KEY);
    } catch {
        // Nothing more we can do, and nothing depends on it having worked.
    }
}

/**
 * Wrap `fn` so rapid calls collapse into one after `ms` of quiet. Saving on every keystroke
 * would serialize the whole workspace per character typed.
 */
export function debounce(fn, ms = 400) {
    let timer = null;
    const wrapped = (...args) => {
        clearTimeout(timer);
        timer = setTimeout(() => fn(...args), ms);
    };
    /** Run any pending call now, e.g. before an action that discards state. */
    wrapped.flush = (...args) => {
        clearTimeout(timer);
        fn(...args);
    };
    return wrapped;
}

/**
 * A file name that will survive being both a Pure sourceId and a display label: no path
 * separators, no surprises, always `.pure`. Returns null when nothing usable is left.
 */
export function normalizeSourceId(name) {
    if (typeof name !== "string") return null;
    const cleaned = name.trim().replace(SAFE_NAME, "");
    if (!cleaned || cleaned === ".pure") return null;
    return cleaned.endsWith(".pure") ? cleaned : `${cleaned}.pure`;
}

/** `name.pure`, `name-2.pure`, `name-3.pure`… — the first form not already taken. */
export function uniqueSourceId(name, taken) {
    const base = normalizeSourceId(name);
    if (!base) return null;
    if (!taken.includes(base)) return base;
    const stem = base.replace(/\.pure$/, "");
    for (let i = 2; i < 1000; i++) {
        const candidate = `${stem}-${i}.pure`;
        if (!taken.includes(candidate)) return candidate;
    }
    return null;
}
