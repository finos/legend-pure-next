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

// modules-panel.js — where the graph came from, as a dependency tree.
//
// The concept tree answers "what is in the graph"; this answers "which archive holds it and
// what did that archive drag in". Those are different questions: a path tells you nothing
// about which PDB owns it, and a dependency that is declared but not loaded stays invisible
// until `validate()` throws.
//
// A module graph is a DAG, not a tree, so rendering it as one needs two decisions. The ROOTS
// are the modules nothing else depends on — the things the page actually asked for — and
// children are what they pull in, so reading downwards answers "why is this loaded". And
// because a shared module (core, above all) would otherwise appear under every parent and
// expand every time, the SECOND occurrence onwards is marked and not re-expanded, the way
// `npm ls` dedupes. That marking is informative rather than merely economical: it is how the
// sharing becomes visible.
//
// Counts come from `registry.elementKinds()`, which reads each archive's index and decodes
// nothing. Listing modules must stay as cheap as listing concepts — a cold decode of the
// working set is the ~3.5s the page's warmup exists to hide.

/** An archive is read-only; an in-memory module holds the sources the editor compiles. */
const isInMemory = (module) => typeof module.sources === "function";

const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

/**
 * @param onSelectModule called with the module to scope the concept list to, or null
 * @param files `{ list, open, create, rename, remove }` over the workspace's files, or null
 *        when this panel is not editing anything
 */
export function createModulePanel(host, onSelectModule, files = null) {
    let modules = [];
    let counts = new Map();
    let selected = null;
    let renaming = null;    // the file whose name is being edited
    let confirming = null;  // the file whose delete is awaiting a second click
    let creating = false;   // a new file's name is being typed

    const body = document.createElement("div");
    body.className = "mod-body";
    const total = document.createElement("div");
    total.className = "mod-total";
    host.append(body, total);

    const byName = () => new Map(modules.map((m) => [m.name, m]));

    /**
     * The modules nothing else depends on. Everything is reachable from them, so they are
     * where reading starts. If every module is depended upon — a cycle, which the registry's
     * topological sort should prevent — fall back to listing them all rather than showing
     * nothing.
     */
    function roots() {
        const depended = new Set(modules.flatMap((m) => m.dependencies));
        const tops = modules.filter((m) => !depended.has(m.name));
        return tops.length ? tops : modules;
    }

    /** The clickable module name: filters the concept tree without toggling the disclosure. */
    function nameButton(module) {
        const name = document.createElement("span");
        name.className = "mod-name" + (selected === module.name ? " selected" : "");
        name.textContent = module.name;
        name.title = `${module.name} — click to list only this module's concepts`;
        name.addEventListener("click", (e) => {
            // The name sits inside a <summary>; without this the click would also open or
            // close the node, so selecting a module would shuffle the tree under the cursor.
            e.preventDefault();
            e.stopPropagation();
            selected = selected === module.name ? null : module.name;
            render();
            onSelectModule?.(selected);
        });
        return name;
    }

    function countSpan(module) {
        const count = document.createElement("span");
        count.className = "mod-count";
        count.textContent = plural(counts.get(module.name) ?? 0, "element");
        return count;
    }

    /** A dependency that is declared but absent — what `validate()` fails on. */
    function missingNode(name) {
        const row = document.createElement("div");
        row.className = "mod-leaf missing";
        row.textContent = name;
        row.title = `${name} is declared as a dependency but is NOT loaded`;
        return row;
    }

    /** A second or later occurrence: named, counted, but not expanded again. */
    function repeatNode(module) {
        const row = document.createElement("div");
        row.className = "mod-leaf repeat";
        row.append(nameButton(module), countSpan(module));
        row.title = `${module.name} is shared — already expanded above`;
        return row;
    }

    /** An input row: used for both naming a new file and renaming an existing one. */
    function nameInput(initial, onCommit) {
        const input = document.createElement("input");
        input.className = "mod-file-input";
        input.value = initial;
        input.spellcheck = false;
        input.addEventListener("keydown", (e) => {
            if (e.key === "Enter") { e.preventDefault(); onCommit(input.value); }
            // Escape abandons: a rename you cannot back out of is a rename you hesitate over.
            if (e.key === "Escape") { e.preventDefault(); onCommit(null); }
        });
        // Losing focus abandons too, rather than committing something half-typed.
        input.addEventListener("blur", () => onCommit(null));
        queueMicrotask(() => { input.focus(); input.select(); });
        return input;
    }

    /**
     * The sources an in-memory module holds: the files being edited, with the actions on
     * them. No `prompt()` or `confirm()` anywhere — a browser dialog blocks the page, cannot
     * be styled, and cannot be driven by a test, so naming happens in an input and deleting
     * takes a second click on the same button.
     */
    function sourceNodes(module) {
        const nodes = [];
        const list = files ? files.list() : module.sources().map((s) => ({ id: s.sourceId, text: s.content }));

        for (const file of list) {
            const row = document.createElement("div");
            row.className = "mod-file" + (file.active ? " active" : "");

            if (renaming === file.id) {
                row.append(nameInput(file.id, (value) => {
                    renaming = null;
                    if (value !== null) files.rename(file.id, value);
                    render();
                }));
                nodes.push(row);
                continue;
            }

            const open = document.createElement("button");
            open.className = "mod-file-open";
            const name = document.createElement("span");
            name.className = "mod-file-name";
            name.textContent = file.id;
            const size = document.createElement("span");
            size.className = "mod-count";
            const text = file.text ?? "";
            size.textContent = `${plural(text.split("\n").length, "line")}, ${text.length.toLocaleString()} chars`;
            open.append(name, size);
            open.title = `${file.id} — click to edit it`;
            open.addEventListener("click", () => { files?.open(file.id); render(); });
            row.append(open);

            if (files) {
                const rename = document.createElement("button");
                rename.className = "mod-file-act";
                rename.textContent = "✎";
                rename.title = `rename ${file.id}`;
                rename.addEventListener("click", () => { renaming = file.id; confirming = null; render(); });

                const remove = document.createElement("button");
                remove.className = "mod-file-act" + (confirming === file.id ? " danger" : "");
                remove.textContent = confirming === file.id ? "delete?" : "✕";
                // The last file cannot go: with no files there is nothing to edit.
                const only = list.length <= 1;
                remove.disabled = only;
                remove.title = only ? "the last file cannot be deleted" : `delete ${file.id}`;
                remove.addEventListener("click", () => {
                    if (confirming === file.id) { confirming = null; files.remove(file.id); }
                    else confirming = file.id;
                    render();
                });
                row.append(rename, remove);
            }
            nodes.push(row);
        }

        if (files) {
            if (creating) {
                const row = document.createElement("div");
                row.className = "mod-file";
                row.append(nameInput("", (value) => {
                    creating = false;
                    if (value !== null) files.create(value);
                    render();
                }));
                nodes.push(row);
            } else {
                const add = document.createElement("button");
                add.className = "mod-file mod-file-add";
                add.textContent = "+ new file";
                add.addEventListener("click", () => { creating = true; confirming = null; render(); });
                nodes.push(add);
            }
        } else if (nodes.length === 0) {
            const empty = document.createElement("div");
            empty.className = "mod-leaf";
            empty.textContent = "(no sources yet)";
            nodes.push(empty);
        }
        return nodes;
    }

    /**
     * One module and, below it, its dependencies and (for an in-memory module) its files.
     * `expanded` carries the modules already shown in full anywhere in the forest, so a
     * shared module is expanded once; `ancestors` guards a cycle on the current path.
     */
    function moduleNode(module, expanded, ancestors) {
        const children = module.dependencies.length + (isInMemory(module) ? 1 : 0);
        if (children === 0)
        {
            const row = document.createElement("div");
            row.className = "mod-leaf";
            row.append(nameButton(module), countSpan(module));
            return row;
        }

        const node = document.createElement("details");
        node.className = "pkg mod-node";
        // Open the top of the forest; deeper levels start closed so the pane is readable.
        node.open = ancestors.size === 0;
        const summary = document.createElement("summary");
        summary.append(nameButton(module), countSpan(module));
        if (isInMemory(module)) {
            const badge = document.createElement("span");
            badge.className = "mod-kind";
            badge.textContent = "editor";
            summary.append(badge);
        }
        node.append(summary);

        const inner = document.createElement("div");
        inner.className = "pkg-body";
        const known = byName();
        const nextAncestors = new Set(ancestors).add(module.name);

        if (isInMemory(module)) inner.append(...sourceNodes(module));

        for (const dep of module.dependencies) {
            const depModule = known.get(dep);
            if (!depModule) {
                inner.append(missingNode(dep));
            } else if (ancestors.has(dep) || expanded.has(dep)) {
                inner.append(repeatNode(depModule));
            } else {
                expanded.add(dep);
                inner.append(moduleNode(depModule, expanded, nextAncestors));
            }
        }
        node.append(inner);
        return node;
    }

    function render() {
        body.textContent = "";
        if (modules.length === 0) {
            total.textContent = "loading…";
            return;
        }
        const expanded = new Set();
        for (const root of roots()) {
            expanded.add(root.name);
            body.append(moduleNode(root, expanded, new Set()));
        }
        const elements = [...counts.values()].reduce((a, b) => a + b, 0);
        total.textContent = `${plural(modules.length, "module")} · ${plural(elements, "element")}`
            + (selected ? ` · showing ${selected}` : "");
    }

    return {
        /**
         * Show `registry`'s modules. Counts are recomputed, so this is safe to call after
         * every compile — the editor's module grows as you type.
         */
        setRegistry(registry) {
            modules = registry.modules;
            counts = new Map();
            for (const [, , moduleName] of registry.elementKinds()) {
                counts.set(moduleName, (counts.get(moduleName) ?? 0) + 1);
            }
            render();
        },
        /**
         * Redraw — for when a file's contents changed but the list did not, so the sizes
         * shown stop being stale as you type. Skipped while a name is being typed: rebuilding
         * would replace the input under the cursor and lose what was half-entered.
         */
        refresh() {
            if (renaming !== null || creating) return;
            render();
        },
        /** The module the concept tree is filtered to, or null. */
        get selected() { return selected; },
        /**
         * Drop the selection without notifying — for when the filter was cleared elsewhere
         * (the concept tab's scope chip). Notifying would bounce the change back and forth.
         */
        clearSelection() {
            if (selected === null) return;
            selected = null;
            render();
        },
    };
}
