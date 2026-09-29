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

// concepts.js — the graph browser on the left of the editor.
//
// Built from `registry.elementKinds()`, which reads each archive's index and decodes
// NOTHING: the reader is lazy on purpose (a cold decode of the working set is the ~3.5s
// the page's warmup exists to hide), so listing ~2000 concepts must not touch element
// bodies. Only a click decodes, and only the element clicked.
//
// Packages come from the paths themselves rather than from the stored `Package` elements,
// so a package with elements in several modules appears once.

/** A leaf's icon and colour by stored kind — the kinds an archive actually contains. */
const KINDS = {
    Class: { icon: "C", cls: "k-class", label: "Class" },
    Enumeration: { icon: "E", cls: "k-enum", label: "Enumeration" },
    PrimitiveType: { icon: "P", cls: "k-prim", label: "PrimitiveType" },
    Profile: { icon: "@", cls: "k-profile", label: "Profile" },
    Association: { icon: "A", cls: "k-assoc", label: "Association" },
    UserDefinedFunction: { icon: "ƒ", cls: "k-fn", label: "function" },
    NativeFunction: { icon: "ƒ", cls: "k-native", label: "native function" },
};
const OTHER = { icon: "·", cls: "k-other", label: "element" };
const kindOf = (kind) => KINDS[kind] ?? OTHER;

/** Elements the tree does not show: packages (they ARE the tree) and generated helpers. */
const HIDDEN_KINDS = new Set([
    "Package",
    // Compiler-internal singletons for a generic type / multiplicity that some element
    // reuses. They are graph plumbing, not concepts anyone browses.
    "UserDefinedPackageableGenericType",
    "UserDefinedPackageableMultiplicity",
    "InferredPackageableMultiplicity",
]);

const MAX_MATCHES = 300;

/**
 * The row context menu. One item today, so it is built where it is used rather than made
 * configurable; it closes on the next pointer down, on Escape, and on scroll, because a
 * menu left floating over a tree that has moved is worse than no menu.
 */
function openMenu(x, y, inspect) {
    document.querySelector(".concept-menu")?.remove();
    const menu = document.createElement("div");
    menu.className = "concept-menu";
    const item = document.createElement("button");
    item.textContent = "Inspect";
    item.addEventListener("click", () => { menu.remove(); inspect(); });
    menu.append(item);
    menu.style.left = `${x}px`;
    menu.style.top = `${y}px`;
    document.body.append(menu);
    // Close on the next pointer press ANYWHERE ELSE. The listener is on the capture phase so
    // it beats whatever was clicked, which means it must ignore presses inside the menu —
    // otherwise pressing "Inspect" removes the menu before the button's own click fires and
    // the item appears dead. (A synthetic .click() in a test never shows this, because it
    // sends no pointerdown.)
    const close = (e) => {
        if (e && menu.contains(e.target)) return;
        menu.remove();
        document.removeEventListener("pointerdown", close, true);
    };
    setTimeout(() => document.addEventListener("pointerdown", close, true), 0);
    addEventListener("scroll", () => close(), { once: true, capture: true });
}

/**
 * @param host        the container element
 * @param onSelect    called with (path, kind, module) when a concept is clicked
 * @param onInspect   called with (path, kind, module) from the row's context menu
 */
export function createConceptTree(host, onSelect, onInspect) {
    let entries = [];          // [path, kind, module][] — everything listed
    let roots = null;          // lazily built package forest
    let filter = "";

    const search = document.createElement("input");
    search.type = "search";
    search.placeholder = "Filter concepts…";
    search.className = "concept-filter";
    const body = document.createElement("div");
    body.className = "concept-body";
    const count = document.createElement("div");
    count.className = "concept-count";
    host.append(search, count, body);

    search.addEventListener("input", () => { filter = search.value.trim().toLowerCase(); render(); });

    /** Package forest: { name, path, children: Map, leaves: [] }. Built once per load. */
    function build() {
        const root = { name: "", path: "", children: new Map(), leaves: [] };
        for (const [path, kind, module] of entries) {
            if (HIDDEN_KINDS.has(kind)) continue;
            const parts = path.split("::");
            const leaf = parts.pop();
            let node = root;
            for (const part of parts) {
                if (!node.children.has(part)) {
                    node.children.set(part, {
                        name: part,
                        path: node.path ? `${node.path}::${part}` : part,
                        children: new Map(), leaves: [],
                    });
                }
                node = node.children.get(part);
            }
            node.leaves.push({ name: leaf, path, kind, module });
        }
        return root;
    }

    function leafRow({ name, path, kind, module }) {
        const k = kindOf(kind);
        const row = document.createElement("button");
        row.className = `concept ${k.cls}`;
        row.title = `${path}\n${k.label} · ${module}`;
        row.innerHTML = `<span class="icon">${k.icon}</span><span class="name"></span>`;
        row.querySelector(".name").textContent = name;
        const select = () => {
            body.querySelectorAll(".concept.selected").forEach((e) => e.classList.remove("selected"));
            row.classList.add("selected");
        };
        row.addEventListener("click", () => { select(); onSelect(path, kind, module); });
        row.addEventListener("contextmenu", (e) => {
            e.preventDefault();
            select();
            openMenu(e.clientX, e.clientY, () => onInspect?.(path, kind, module));
        });
        return row;
    }

    /** A package renders as <details>; its children are built the first time it opens. */
    function packageRow(node) {
        const d = document.createElement("details");
        d.className = "pkg";
        const s = document.createElement("summary");
        s.textContent = node.name;
        s.title = node.path;
        d.append(s);
        let filled = false;
        d.addEventListener("toggle", () => {
            if (!d.open || filled) return;
            filled = true;
            const inner = document.createElement("div");
            inner.className = "pkg-body";
            for (const child of [...node.children.values()].sort(byName)) inner.append(packageRow(child));
            for (const leaf of node.leaves.sort(byName)) inner.append(leafRow(leaf));
            d.append(inner);
        });
        return d;
    }

    const byName = (a, b) => a.name.localeCompare(b.name);

    function render() {
        body.textContent = "";
        if (!entries.length) { count.textContent = "loading…"; return; }
        if (filter) {
            // Flat result list: a filter is a search, and a tree of one-child packages
            // would bury the answer.
            const hits = entries.filter(([path, kind]) => !HIDDEN_KINDS.has(kind) && path.toLowerCase().includes(filter));
            count.textContent = `${hits.length} match${hits.length === 1 ? "" : "es"}${hits.length > MAX_MATCHES ? ` (showing ${MAX_MATCHES})` : ""}`;
            for (const [path, kind, module] of hits.slice(0, MAX_MATCHES)) {
                const row = leafRow({ name: path, path, kind, module });
                row.classList.add("flat");
                body.append(row);
            }
            return;
        }
        roots ??= build();
        const shown = entries.filter(([, kind]) => !HIDDEN_KINDS.has(kind)).length;
        count.textContent = `${shown} concepts`;
        for (const child of [...roots.children.values()].sort(byName)) body.append(packageRow(child));
        for (const leaf of roots.leaves.sort(byName)) body.append(leafRow(leaf));
    }

    return {
        /** Replace the listing; called once after load and again after each Run. */
        setEntries(next) { entries = next; roots = null; render(); },
        /** Open the tree down to `path` and select it (used after a Run). */
        reveal(path) {
            search.value = path;
            filter = path.toLowerCase();
            render();
        },
    };
}
