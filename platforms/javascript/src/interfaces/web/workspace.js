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

// workspace.js — the files being edited.
//
// THE IN-MEMORY MODULE IS THE WORKSPACE. A Pure module is already a named set of sources with
// declared dependencies, and `InMemoryModule` already has `setSource`/`removeSource`/
// `sources()`; a workspace is that, plus somewhere to type. So this holds no separate notion
// of a file: each file is one of the module's sources, and every edit writes through to it.
// That is why the Modules tab can list the files at all, and why a compile sees them.
//
// One Monaco EDITOR, one MODEL per file. Swapping `editor.setModel` rather than recreating
// the editor is what gives each file its own undo stack and its own markers for free — a
// parse error in one file must not decorate another.
//
// Persistence mirrors the same map (source-store.js). It is a mirror, never the truth: the
// module is authoritative, storage is a convenience that may be absent or refuse to write.

import { DEFAULT_SOURCE_ID, debounce, loadWorkspace, normalizeSourceId, saveWorkspace,
         clearWorkspace, uniqueSourceId } from "./source-store.js";

export { DEFAULT_SOURCE_ID };

/**
 * @param monaco        the Monaco namespace
 * @param container     the element to create the editor in
 * @param runtimeModule the InMemoryModule the files belong to
 * @param sample        the source a fresh workspace starts from
 * @param onFilesChange called when a file is added, removed or renamed
 * @param onActiveChange called with the newly active file's id
 * @param onSaveFailed  called once when the browser refuses to remember
 * @param editorOptions extra Monaco options
 */
export function createWorkspace({ monaco, container, runtimeModule, sample,
                                  onFilesChange = () => {}, onActiveChange = () => {},
                                  onSaveFailed = () => {}, editorOptions = {} }) {
    /** id -> Monaco model. Insertion order is the order the files are listed in. */
    const models = new Map();
    let active = null;
    let saveFailed = false;

    const stored = loadWorkspace();
    const initial = stored ?? { sources: { [DEFAULT_SOURCE_ID]: sample }, active: DEFAULT_SOURCE_ID };

    const uri = (id) => monaco.Uri.parse(`inmemory://pure/${encodeURIComponent(id)}`);

    function createModel(id, text) {
        // A model is keyed by URI; a stale one from a deleted file of the same name would be
        // reused with its old contents, so dispose before creating.
        monaco.editor.getModel(uri(id))?.dispose();
        const model = monaco.editor.createModel(text, "pure", uri(id));
        models.set(id, model);
        runtimeModule.setSource(id, text);
        model.onDidChangeContent(() => {
            // Write through on every edit: the module is what a compile reads, and the
            // Modules tab reads the same thing.
            runtimeModule.setSource(id, model.getValue());
            persist();
        });
        return model;
    }

    for (const [id, text] of Object.entries(initial.sources)) createModel(id, text);
    active = initial.active;

    const editor = monaco.editor.create(container, {
        model: models.get(active),
        language: "pure",
        ...editorOptions,
    });

    /** Save the whole workspace, debounced; reports a refusal once rather than on every key. */
    const persist = debounce(() => {
        const sources = {};
        for (const [id, model] of models) sources[id] = model.getValue();
        const ok = saveWorkspace({ sources, active });
        if (!ok && !saveFailed) {
            saveFailed = true;
            onSaveFailed();
        }
    }, 400);

    const ids = () => [...models.keys()];

    function open(id) {
        if (!models.has(id) || active === id) return false;
        active = id;
        editor.setModel(models.get(id));
        editor.focus();
        persist();
        onActiveChange(id);
        return true;
    }

    return {
        editor,
        get active() { return active; },
        /** `[{ id, text, lines, chars, active }]`, in creation order. */
        files() {
            return ids().map((id) => {
                const text = models.get(id).getValue();
                return { id, text, lines: text.split("\n").length, chars: text.length, active: id === active };
            });
        },
        /** `[{ sourceId, content }]` for a compile — every file, not just the open one. */
        sources() {
            return ids().map((id) => ({ sourceId: id, content: models.get(id).getValue() }));
        },
        model: (id) => models.get(id) ?? null,
        text: () => editor.getModel().getValue(),
        open,
        /**
         * Add a file. The name is normalised and made unique rather than rejected or allowed
         * to overwrite, so a clumsy name still produces a file instead of an error.
         * Returns its id, or null when the name had nothing usable in it.
         */
        create(name) {
            const id = uniqueSourceId(name || "model", ids());
            if (!id) return null;
            createModel(id, "");
            active = id;
            editor.setModel(models.get(id));
            editor.focus();
            persist();
            onFilesChange();
            onActiveChange(id);
            return id;
        },
        /**
         * Rename a file, carrying its text across. Monaco keys a model by URI, so this is a
         * new model rather than a mutation — which also means the renamed file starts a fresh
         * undo stack, and its old markers are gone with its old model.
         * Returns the new id, or null when the name is unusable or unchanged.
         */
        rename(id, name) {
            if (!models.has(id)) return null;
            const wanted = normalizeSourceId(name);
            if (!wanted || wanted === id) return null;
            const next = uniqueSourceId(wanted, ids().filter((x) => x !== id));
            if (!next) return null;

            const text = models.get(id).getValue();
            const order = ids().map((key) => (key === id ? next : key));
            const old = models.get(id);
            models.delete(id);
            runtimeModule.removeSource(id);
            createModel(next, text);
            old.dispose();
            // Reinsert in the original order: a rename must not move the file to the end of
            // the list, which is where creating its new model just put it.
            const reordered = new Map(order.map((key) => [key, models.get(key)]));
            models.clear();
            for (const [key, model] of reordered) models.set(key, model);

            if (active === id) {
                active = next;
                editor.setModel(models.get(next));
                onActiveChange(next);
            }
            persist();
            onFilesChange();
            return next;
        },
        /**
         * Delete a file. The last file is never deleted — a workspace with no files has no
         * editor to show, so the floor is one.
         * Returns true when it went.
         */
        remove(id) {
            if (!models.has(id) || models.size <= 1) return false;
            const order = ids();
            models.get(id).dispose();
            models.delete(id);
            runtimeModule.removeSource(id);
            if (active === id) {
                // Open the neighbour, preferring the one before it, as an editor should.
                const at = order.indexOf(id);
                const next = order[at - 1] ?? order[at + 1];
                active = next;
                editor.setModel(models.get(next));
                onActiveChange(next);
            }
            persist();
            onFilesChange();
            return true;
        },
        /** Throw the workspace away and start from the sample again. */
        reset() {
            for (const model of models.values()) model.dispose();
            models.clear();
            for (const { sourceId } of runtimeModule.sources()) runtimeModule.removeSource(sourceId);
            // Leave storage EMPTY rather than saving the sample back into it, so a reload
            // after a reset is genuinely fresh. The next edit starts remembering again.
            clearWorkspace();
            createModel(DEFAULT_SOURCE_ID, sample);
            active = DEFAULT_SOURCE_ID;
            editor.setModel(models.get(DEFAULT_SOURCE_ID));
            onFilesChange();
            onActiveChange(active);
        },
        /** Whether this workspace was restored from storage rather than started fresh. */
        restored: stored !== null,
    };
}
