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

// inspector.js — "Inspect" on a concept: the element's graph as a tree on one side, the
// JavaScript it translates to on the other.
//
// The graph side cannot enumerate an element's fields directly — elements are read lazily
// BY NAME through the metadata host, so they have no enumerable keys. The property names
// come from the metamodel instead: an element's classifier is a Class, whose `properties`
// name what it holds, and whose `generalizations` lead to the inherited ones. That is the
// same walk the compiled-graph printer does, and it means the tree describes the element
// the way Pure does rather than the way the JavaScript decoder happens to.
//
// Children are built on first expand, so opening the inspector on a core element does not
// decode its whole neighbourhood.

import { wireSplitter } from "./layout.js";

/** Pure multiplicity crosses into JS as a value, an array, or nothing. */
const many = (v) => (Array.isArray(v) ? v : v === undefined || v === null ? [] : [v]);
const one = (v) => many(v)[0];

const isElement = (v) => v !== null && typeof v === "object" && !Array.isArray(v);

/** A short label for a value: enough to read, never enough to decode a whole subgraph. */
function label(v) {
    if (v === null || v === undefined) return "[]";
    if (typeof v === "string") return `'${v}'`;
    if (typeof v !== "object") return String(v);
    try {
        const path = v.__purePath ?? globalThis.__elementToPath?.(v);
        if (typeof path === "string" && path) return path;
    } catch {
        // Not a packageable element — fall through to its classifier.
    }
    try {
        const cls = one(one(v.classifierGenericType)?.type);
        const name = one(cls?.name);
        if (name) return `⟨${name}⟩`;
    } catch {
        // An object the metadata cannot describe still gets a row, just an opaque one.
    }
    return "⟨object⟩";
}

/**
 * The properties an element carries, grouped by the class that DECLARES them: the
 * element's own classifier first, then its supertypes.
 *
 * Grouping is the closest thing to "the order the metamodel specifies them" that the graph
 * can actually answer. A class's `properties` holds only its own, in declaration order, so
 * each group is faithful — but `generalizations` comes back in storage order (measured:
 * Class reports Testable, NamedType, TypeAndMultiplicityParametersOwner,
 * ElementWithConstraints, PropertyOwner, while m3.ttl declares NamedType, PropertyOwner,
 * ElementWithConstraints, Testable, TypeAndMultiplicityParametersOwner) and properties
 * carry no sourceInformation to sort by. Supertypes are therefore taken breadth-first and
 * sorted by name within a level, which is stable across rebuilds even though it is not the
 * declaration order of the extends clause.
 *
 * A name declared more than once down the chain is listed once, at its most derived class.
 */
function propertyGroups(element) {
    const groups = [];
    const takenNames = new Set();
    const seenClasses = new Set();
    let level = [];
    try {
        level = [one(one(element.classifierGenericType)?.type)].filter(Boolean);
    } catch {
        return groups;
    }
    for (let depth = 0; level.length && depth < 12; depth++) {
        const next = [];
        for (const cls of level) {
            let className;
            try { className = one(cls?.name); } catch { continue; }
            if (!className || seenClasses.has(className)) continue;
            seenClasses.add(className);

            const names = [];
            for (const prop of many(cls.properties)) {
                const n = one(prop?.name);
                if (n && !takenNames.has(n)) { takenNames.add(n); names.push(n); }
            }
            if (names.length) groups.push({ className, names, inherited: depth > 0 });

            for (const g of many(cls.generalizations)) {
                const sup = one(one(g?.general)?.type);
                if (sup) next.push(sup);
            }
        }
        // Storage order is not the metamodel's, so make it at least deterministic.
        level = next.sort((a, b) => String(one(a?.name)).localeCompare(String(one(b?.name))));
    }
    return groups;
}

function valueRow(name, value, depth) {
    const values = many(value);
    const expandable = values.some(isElement) && depth < 8;
    if (!expandable) {
        const row = document.createElement("div");
        row.className = "insp-leaf";
        row.innerHTML = `<span class="insp-name"></span><span class="insp-val"></span>`;
        row.querySelector(".insp-name").textContent = name;
        row.querySelector(".insp-val").textContent =
            values.length === 0 ? "[]" : values.length === 1 ? label(values[0]) : `[${values.map(label).join(", ")}]`;
        return row;
    }
    const d = document.createElement("details");
    d.className = "insp-node";
    const s = document.createElement("summary");
    s.innerHTML = `<span class="insp-name"></span><span class="insp-val"></span>`;
    s.querySelector(".insp-name").textContent = name;
    s.querySelector(".insp-val").textContent =
        values.length === 1 ? label(values[0]) : `[${values.length}]`;
    d.append(s);
    let filled = false;
    d.addEventListener("toggle", () => {
        if (!d.open || filled) return;
        filled = true;
        const body = document.createElement("div");
        body.className = "insp-body";
        values.forEach((v, i) => {
            if (!isElement(v)) { body.append(valueRow(String(i), v, depth + 1)); return; }
            const child = valueRow(values.length === 1 ? name : `${name}[${i}]`, v, depth + 1);
            body.append(values.length === 1 ? elementBody(v, depth + 1) : child);
        });
        d.append(body);
    });
    return d;
}

/** One element's properties, as rows under a heading per declaring class. */
function elementBody(element, depth) {
    const wrap = document.createElement("div");
    wrap.className = "insp-body";
    const groups = propertyGroups(element);
    if (!groups.length) {
        const empty = document.createElement("div");
        empty.className = "insp-leaf";
        empty.textContent = "(no readable properties)";
        wrap.append(empty);
        return wrap;
    }
    for (const { className, names, inherited } of groups) {
        const head = document.createElement("div");
        head.className = `insp-group${inherited ? " inherited" : ""}`;
        head.textContent = inherited ? `inherited from ${className}` : className;
        wrap.append(head);
        for (const name of names) {
            let value;
            try { value = element[name]; } catch { value = undefined; }
            wrap.append(valueRow(name, value, depth));
        }
    }
    return wrap;
}

let open = null; // the overlay while one is open — only ever one at a time

export function closeInspector() {
    open?.remove();
    open = null;
}

/**
 * @param path       the concept's path, shown in the header
 * @param kind       its stored kind
 * @param element    the live element
 * @param translate  () => the element's generated JavaScript, or null when it has none
 */
export function openInspector({ path, kind, element, translate }) {
    closeInspector();
    const overlay = document.createElement("div");
    overlay.className = "insp-overlay";
    overlay.innerHTML = `
      <div class="insp-panel" role="dialog" aria-modal="true" aria-label="Inspect ${path}">
        <header class="insp-head">
          <span class="insp-path"></span><span class="insp-kind"></span>
          <button class="insp-close" title="close (Esc)">×</button>
        </header>
        <div class="insp-split">
          <section class="insp-pane"><h4>Graph</h4><div class="insp-tree"></div></section>
          <div class="insp-divider" role="separator" aria-orientation="vertical"></div>
          <section class="insp-pane"><h4>JavaScript</h4><pre class="insp-js"></pre></section>
        </div>
      </div>`;
    overlay.querySelector(".insp-path").textContent = path;
    overlay.querySelector(".insp-kind").textContent = kind || "element";
    overlay.querySelector(".insp-tree").append(elementBody(element, 0));

    const js = overlay.querySelector(".insp-js");
    try {
        const source = translate();
        js.textContent = source && source.trim() ? source : "// this element translates to nothing";
    } catch (e) {
        js.textContent = "// translation failed: " + (e && e.message ? e.message : String(e));
    }

    overlay.querySelector(".insp-close").addEventListener("click", closeInspector);
    overlay.addEventListener("mousedown", (e) => { if (e.target === overlay) closeInspector(); });
    document.body.append(overlay);
    open = overlay;

    const split = overlay.querySelector(".insp-split");
    wireSplitter(overlay.querySelector(".insp-divider"), split, "x", "--graphw",
        { min: 200, max: () => split.clientWidth - 200, remember: false });
    overlay.querySelector(".insp-close").focus();
}

document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeInspector(); });
