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
    // Not a graph element: a `###Diagram` is carried as text and the compiler ignores it,
    // so it reaches the tree from the open source rather than from the registry. It is
    // listed all the same, because it IS a thing in the workspace with a name and a place.
    Diagram: { icon: "▦", cls: "k-diagram", label: "Diagram" },
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
 * The drag payload for a concept dragged out of the tree. A private type keeps the diagram
 * from accepting arbitrary text dropped on it; `text/plain` is set alongside so the same
 * drag still does something sensible when it lands in the editor.
 */
export const CONCEPT_MIME = "application/x-pure-concept";

/**
 * The row context menu. One item today, so it is built where it is used rather than made
 * configurable; it closes on the next pointer down, on Escape, and on scroll, because a
 * menu left floating over a tree that has moved is worse than no menu.
 */
function openMenu(x, y, items) {
    document.querySelector(".concept-menu")?.remove();
    const menu = document.createElement("div");
    menu.className = "concept-menu";
    for (const [label, run] of items) {
        const item = document.createElement("button");
        item.textContent = label;
        item.addEventListener("click", () => { menu.remove(); run(); });
        menu.append(item);
    }
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
 * @param onAddToDiagram called with the qualified name of a class to put on the diagram
 * @param onModuleFilterChange called with the module now being shown, or null for all of
 *        them — so whatever set the filter can stay in step when it is cleared from here
 */
export function createConceptTree(host, onSelect, onInspect, onAddToDiagram, onModuleFilterChange) {
    let entries = [];          // [path, kind, module][] — everything listed
    let roots = null;          // lazily built package forest
    let filter = "";
    let moduleFilter = null;   // when set, only this module's elements are listed
    // Survives the rebuild that every refresh performs: which packages are open, which row
    // was chosen, and where the list was scrolled to.
    const expanded = new Set();
    let selectedPath = null;

    const search = document.createElement("input");
    search.type = "search";
    search.placeholder = "Filter concepts…";
    search.className = "concept-filter";
    const body = document.createElement("div");
    body.className = "concept-body";
    const count = document.createElement("div");
    count.className = "concept-count";
    // A filter you cannot see is a filter you cannot undo: when the list is narrowed to one
    // module, say so HERE, next to the list it narrowed, with the way out attached. Setting
    // it happens on the Modules tab, so without this the only way back was to return there
    // and click the same module again — discoverable by accident at best.
    const scope = document.createElement("button");
    scope.className = "concept-scope";
    scope.hidden = true;
    scope.addEventListener("click", () => setModuleFilter(null, true));
    host.append(search, scope, count, body);

    search.addEventListener("input", () => { filter = search.value.trim().toLowerCase(); render(); });

    /** Package forest over `from`: { name, path, children: Map, leaves: [] }. */
    function build(from) {
        const root = { name: "", path: "", children: new Map(), leaves: [] };
        for (const [path, kind, module] of from) {
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
        if (path === selectedPath) row.classList.add("selected");
        const select = () => {
            body.querySelectorAll(".concept.selected").forEach((e) => e.classList.remove("selected"));
            row.classList.add("selected");
            selectedPath = path;
        };
        row.addEventListener("click", () => { select(); onSelect(path, kind, module); });
        // Only a class can be dropped on a diagram, so only a class is draggable — a row
        // that lifts but can never land is worse than one that does not move.
        if (kind === "Class") {
            row.draggable = true;
            row.addEventListener("dragstart", (e) => {
                e.dataTransfer.effectAllowed = "copy";
                e.dataTransfer.setData(CONCEPT_MIME, path);
                e.dataTransfer.setData("text/plain", path);
            });
        }
        row.addEventListener("contextmenu", (e) => {
            e.preventDefault();
            select();
            // A diagram has no graph element behind it, so there is nothing to inspect.
            const items = kind === "Diagram" ? [] : [["Inspect", () => onInspect?.(path, kind, module)]];
            // Only a class can go on a diagram; offering it for a function would be noise.
            if (kind === "Class" && onAddToDiagram) items.push(["Add to diagram", () => onAddToDiagram(path)]);
            if (items.length) openMenu(e.clientX, e.clientY, items);
        });
        return row;
    }

    /**
     * A package renders as <details>; its children are built the first time it opens.
     *
     * Which packages are open is remembered in `expanded`, because the tree is rebuilt from
     * scratch on every refresh — and a refresh happens whenever the graph changes, including
     * when a class is dropped onto a diagram. Without this, adding a class collapsed the tree
     * the user had just navigated.
     */
    function packageRow(node) {
        const d = document.createElement("details");
        d.className = "pkg";
        const s = document.createElement("summary");
        s.textContent = node.name;
        s.title = node.path;
        d.append(s);

        let filled = false;
        const fill = () => {
            if (filled) return;
            filled = true;
            const inner = document.createElement("div");
            inner.className = "pkg-body";
            for (const child of [...node.children.values()].sort(byName)) inner.append(packageRow(child));
            for (const leaf of node.leaves.sort(byName)) inner.append(leafRow(leaf));
            d.append(inner);
        };
        d.addEventListener("toggle", () => {
            if (d.open) { fill(); expanded.add(node.path); } else { expanded.delete(node.path); }
        });
        // Restore by calling fill() rather than waiting for the toggle event: setting `open`
        // queues that event asynchronously, so the children would arrive a frame late — and
        // any nested package that should also be open would not be built in time to restore.
        if (expanded.has(node.path)) {
            d.open = true;
            fill();
        }
        return d;
    }

    const byName = (a, b) => a.name.localeCompare(b.name);

    /** Narrow the list to one module, or null for all. `notify` reports it back out. */
    function setModuleFilter(moduleName, notify = false) {
        moduleFilter = moduleName;
        roots = null;
        render();
        if (notify) onModuleFilterChange?.(moduleName);
    }

    function render() {
        // Keep the viewport where it was: a refresh that scrolled back to the top would lose
        // the user's place just as surely as collapsing the tree did.
        const scroll = body.scrollTop;
        renderInto();
        body.scrollTop = scroll;
    }

    function renderInto() {
        scope.hidden = moduleFilter === null;
        if (moduleFilter !== null) {
            scope.textContent = `module: ${moduleFilter}`;
            scope.title = `showing only ${moduleFilter} — click to show every module again`;
        }
        body.textContent = "";
        if (!entries.length) { count.textContent = "loading…"; return; }
        const visible = moduleFilter === null
            ? entries
            : entries.filter(([, , module]) => module === moduleFilter);
        if (!visible.length) {
            count.textContent = `no concepts in ${moduleFilter}`;
            return;
        }
        if (filter) {
            // Flat result list: a filter is a search, and a tree of one-child packages
            // would bury the answer.
            const hits = visible.filter(([path, kind]) => !HIDDEN_KINDS.has(kind) && path.toLowerCase().includes(filter));
            count.textContent = `${hits.length} match${hits.length === 1 ? "" : "es"}${hits.length > MAX_MATCHES ? ` (showing ${MAX_MATCHES})` : ""}`;
            for (const [path, kind, module] of hits.slice(0, MAX_MATCHES)) {
                const row = leafRow({ name: path, path, kind, module });
                row.classList.add("flat");
                body.append(row);
            }
            return;
        }
        roots = build(visible);
        const shown = visible.filter(([, kind]) => !HIDDEN_KINDS.has(kind)).length;
        count.textContent = `${shown} concepts${moduleFilter ? ` in ${moduleFilter}` : ""}`;
        for (const child of [...roots.children.values()].sort(byName)) body.append(packageRow(child));
        for (const leaf of roots.leaves.sort(byName)) body.append(leafRow(leaf));
    }

    return {
        /** Replace the listing; called once after load and again after each Run. */
        setEntries(next) { entries = next; roots = null; render(); },
        /**
         * List only `moduleName`'s elements, or everything when null. The forest is rebuilt
         * rather than reused: packages are derived from the paths present, so a filtered
         * tree has a different shape, not just fewer leaves.
         */
        setModuleFilter(moduleName) { setModuleFilter(moduleName); },
        /** The module currently being shown, or null. */
        get moduleFilter() { return moduleFilter; },
        /** Open the tree down to `path` and select it (used after a Run). */
        reveal(path) {
            search.value = path;
            filter = path.toLowerCase();
            render();
        },
    };
}
