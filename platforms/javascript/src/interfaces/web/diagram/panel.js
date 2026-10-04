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

// panel.js — the diagram tab: canvas, toolbar, and the loop that keeps it and the source
// text in step. Everything the page needs from the diagram editor is behind this one
// factory, so main.js gains a handful of lines rather than a second editor.
//
// THE SOURCE IS THE MODEL. There is no separate diagram document: the tab parses the
// `###Diagram` section out of the editor buffer when it is shown, and every gesture writes
// the section straight back. So Undo works, the diff is readable, and Compile and Run see
// the change with nothing extra to keep in sync.
//
// Writing back sets `#writingBack`, because the write lands as a Monaco content change and
// would otherwise re-enter and reparse mid-drag, replacing the very views being dragged.

import { parseDiagramSource } from "./parse.js";
import { replaceDiagramInSource } from "./serialize.js";
import { adoptPoints } from "./geometry.js";
import { DiagramController } from "./interaction.js";
import { classDefsFor } from "./class-defs.js";
import { addTypeToSource, cleanUpDeadReferences, removeView } from "./model.js";
import { CONCEPT_MIME } from "../concepts.js";

/**
 * @param host        `{ tabs, editorEl, panelEl, canvas, tools, zoomLabel }` DOM elements
 * @param getSource   current editor text
 * @param setSource   replace the editor text (an undoable edit)
 * @param getRegistry the ModuleRegistry to read class definitions from
 * @param onOpenType  called with a qualified class name when a box is double-clicked
 * @param onRelayout  called after the tab is shown, so Monaco can re-measure
 */
export function createDiagramPanel({ host, getSource, setSource, getRegistry,
                                     onOpenType = () => {}, onRelayout = () => {},
                                     onDiagramsChanged = () => {} }) {
    const { tabs, editorEl, panelEl, canvas, tools, zoomLabel } = host;
    let visible = false;
    let writingBack = false;
    let diagram = null;
    // Which diagram is on screen when the source holds more than one. Kept by NAME rather
    // than by reference, because every refresh reparses and builds new objects.
    let shownName = null;

    const showZoom = () => { zoomLabel.textContent = `${Math.round(controller.zoom * 100)}%`; };

    const controller = new DiagramController(canvas, {
        onChange: (edited) => {
            if (!edited) return;
            writingBack = true;
            try {
                setSource(replaceDiagramInSource(getSource(), edited));
            } finally {
                writingBack = false;
            }
        },
        // Selecting is what a drag starts with, so it must not disturb the view; revealing
        // the class in the code is the separate double-click gesture below.
        onSelect: () => {},
        onOpen: (view) => { if (view?.type) onOpenType(view.type); },
        // Delete removes every selected view and writes once: a box takes its edges with it,
        // and Undo in the editor is what takes it all back, because the source is the model.
        onDelete: (views) => {
            if (!diagram) return;
            let removed = 0;
            for (const view of views) if (view?.id) removed += removeView(diagram, view.id);
            if (removed === 0) return;
            writingBack = true;
            try { setSource(replaceDiagramInSource(getSource(), diagram)); } finally { writingBack = false; }
            refresh();
        },
    });

    const parseAll = (text) => parseDiagramSource(text).map(adoptPoints);

    /** Re-read the source and redraw. Cheap enough to run on every show and every edit. */
    function refresh() {
        const all = parseAll(getSource());
        diagram = all.find((d) => d.name === shownName) ?? all[0] ?? null;
        shownName = diagram?.name ?? null;
        controller.setDiagram(diagram, classDefsFor(diagram, getRegistry()));
        showZoom();
    }

    function select(tab) {
        visible = tab === "diagram";
        for (const button of tabs.querySelectorAll(".tab")) {
            button.classList.toggle("selected", button.dataset.tab === tab);
        }
        editorEl.hidden = visible;
        panelEl.hidden = !visible;
        tools.hidden = !visible;
        if (visible) {
            refresh();
            controller.resize();
        } else {
            onRelayout();
        }
    }

    tabs.addEventListener("click", (event) => {
        const button = event.target.closest(".tab");
        if (button) select(button.dataset.tab);
    });

    host.fit?.addEventListener("click", () => { controller.fitToScreen(); showZoom(); });
    host.zoomIn?.addEventListener("click", () => { controller.zoomIn(); showZoom(); });
    host.zoomOut?.addEventListener("click", () => { controller.zoomOut(); showZoom(); });
    canvas.addEventListener("wheel", showZoom, { passive: true });

    // Drag a class out of the concept tree and drop it on the canvas. `dragover` must
    // preventDefault on EVERY move or the browser refuses the drop entirely.
    const isConcept = (event) => [...(event.dataTransfer?.types ?? [])].includes(CONCEPT_MIME);
    canvas.addEventListener("dragover", (event) => {
        if (!isConcept(event)) return;
        event.preventDefault();
        event.dataTransfer.dropEffect = "copy";
        canvas.classList.add("dropping");
    });
    canvas.addEventListener("dragleave", () => canvas.classList.remove("dropping"));
    canvas.addEventListener("drop", (event) => {
        canvas.classList.remove("dropping");
        if (!isConcept(event)) return;
        event.preventDefault();
        const path = event.dataTransfer.getData(CONCEPT_MIME);
        if (path) addType(path, controller.modelPoint(event.clientX, event.clientY));
    });

    // The canvas has no intrinsic size, so a splitter drag or a window resize has to be
    // turned into a new backing store or the drawing stretches.
    if (typeof ResizeObserver === "function") {
        new ResizeObserver(() => { if (visible) controller.resize(); }).observe(panelEl);
    }

    /**
     * Put `qualifiedNameOfClass` on the diagram, at `position` (diagram coordinates) when a
     * drop supplied one. Creates the `###Diagram` section if the source has none.
     *
     * A class may be added MORE THAN ONCE: each call makes another view, with its own id,
     * its own size and its own edges.
     */
    function addType(qualifiedNameOfClass, position = null) {
        const parse = (text) => {
            // Only the diagram on screen may be edited; a second one in the same file
            // must not be touched by an edit meant for this one.
            const all = parseAll(text);
            const pick = all.find((d) => d.name === shownName) ?? all[0];
            return pick ? [pick] : [];
        };
        // The definitions have to cover the class being added AND the ones already on
        // the diagram, so autoConnect can see both ends of every edge it might draw.
        const [current] = parse(getSource());
        const probe = { typeViews: [...(current?.typeViews ?? []), { type: qualifiedNameOfClass }] };
        const defs = classDefsFor(probe, getRegistry());
        // `measure` is the canvas's own text metrics, so a new box is sized with exactly the
        // numbers it will be drawn with.
        const edit = addTypeToSource(getSource(), qualifiedNameOfClass, parse, defs, position,
                                     (view, diagram) => controller.measureBox(view, diagram, defs));
        if (edit.added) {
            writingBack = true;
            try { setSource(edit.source); } finally { writingBack = false; }
            shownName = edit.diagram.name;
            onDiagramsChanged();
        }
        select("diagram");
        if (!edit.added) controller.selectType(qualifiedNameOfClass);
    }

    return {
        get visible() { return visible; },
        /** The editor text changed — unless we are the ones who changed it. */
        sourceChanged() { if (visible && !writingBack) refresh(); },
        /** The graph changed (a compile): class boxes may have new properties. */
        graphChanged() { if (visible) refresh(); },
        /** The `###Diagram` names in the open source, for the concept tree. */
        names: () => parseAll(getSource()).map((d) => d.name),
        /** Open the Diagram tab, on `name` when the source has more than one diagram. */
        show(name) {
            if (name) shownName = name;
            select("diagram");
        },
        showCode: () => select("code"),
        refresh,
        addType,
        /**
         * Remove views whose class is gone. Returns what it removed, or null when there is
         * no diagram; the caller reports it, because a silent delete is not acceptable.
         */
        cleanUp() {
            if (!diagram) return null;
            const registry = getRegistry();
            const exists = (type) => {
                try { return Boolean(registry?.getElement(type)); } catch { return false; }
            };
            const removed = cleanUpDeadReferences(diagram, exists);
            if (removed.removedTypeViews || removed.removedEdges) {
                writingBack = true;
                try { setSource(replaceDiagramInSource(getSource(), diagram)); } finally { writingBack = false; }
                refresh();
                onDiagramsChanged();
            }
            return removed;
        },
        destroy: () => controller.destroy(),
    };
}
