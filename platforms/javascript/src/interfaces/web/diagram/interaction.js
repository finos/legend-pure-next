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

// interaction.js — dragging, panning, zooming and selection on a diagram canvas.
//
// Ported from the bootstrap IDE's diagram mouse handlers, which follow legend-studio's
// DiagramRenderer. The port turns eight module-level globals (`diagramData`, `diagramZoom`,
// `diagramOffsetX/Y`, `diagramDragging`, `diagramAnchorDragging`, `diagramLineDragging`,
// `diagramPanning`, `selectedRelationship`) into fields of one controller, so the page can
// hold more than one canvas and tests can drive it without a DOM singleton.
//
// The gestures, all from the original:
//   click a box           select it; shift-click adds or removes it from the selection
//   drag empty space      marquee — every box it touches is selected
//   drag a box            move it, and everything else selected, together
//   drag its corner grip  resize it (the grip shows on the selected box only)
//   drag an edge anchor   slide where the edge meets its box
//   drag a waypoint       move a bend; drag it into a box and it BECOMES that box's anchor
//   drag a line segment   insert a new waypoint there and drag it (drag it flat and it goes)
//   drag with the right or middle button   pan (the left button marquees now)
//   wheel                 zoom about the pointer
//   double-click a box    reveal its class in the code
//   Delete / Backspace    remove the selected view
//
// Revealing is on DOUBLE click, not single: a single click is how every drag begins, so
// opening the code there would hide the canvas the moment you tried to move anything.
//
// Every gesture that changes the model ends by calling `onChange`, which is what writes the
// diagram back into the source text.

import { drawDiagram, drawnPath, minimumBoxSize, resizeHandleOf, themeColors } from "./render.js";
import { boxesIntersect, center, pointInsideBox, pruneCollinear, rebuildPoints,
         rectFrom, relationshipViews, typeViewsById } from "./geometry.js";

const MIN_ZOOM = 0.1;
const MAX_ZOOM = 4;
const HIT_SLOP = 8;       // screen pixels
const COLLINEAR_SLOP = 3; // screen pixels: how flat a bend must be before it is dropped

/** Distance from a point to a line segment — how "near the line" is judged. */
function pointToSegmentDistance(p, a, b) {
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const lengthSq = dx * dx + dy * dy;
    if (lengthSq === 0) return Math.hypot(p.x - a.x, p.y - a.y);
    const t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSq));
    return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy));
}

export class DiagramController {
    #canvas;
    #diagram = null;
    #classDefs = {};
    #zoom = 1;
    #offsetX = 0;
    #offsetY = 0;
    #selection = new Set();   // the chosen views: any number of boxes, or a single edge
    #gesture = null;      // { kind, … } while the mouse is down
    #onChange;
    #onSelect;
    #onOpen;
    #onDelete;
    #listeners = [];

    /**
     * @param canvas   the canvas to draw on and listen to
     * @param onChange called with the diagram after any gesture that changed it
     * @param onSelect called with the selected view (a TypeView or an edge), or null
     * @param onOpen   called with a double-clicked TypeView — "show me this class"
     * @param onDelete called with the view the user asked to delete
     */
    constructor(canvas, { onChange = () => {}, onSelect = () => {}, onOpen = () => {},
                          onDelete = () => {} } = {}) {
        this.#canvas = canvas;
        this.#onChange = onChange;
        this.#onSelect = onSelect;
        this.#onOpen = onOpen;
        this.#onDelete = onDelete;
        // A canvas takes no keyboard focus of its own, so Delete would never reach it. Making
        // it focusable also scopes the key correctly: it fires only when the canvas itself has
        // focus, never while you are typing in the editor.
        if (!canvas.hasAttribute("tabindex")) canvas.setAttribute("tabindex", "0");
        this.#listen("keydown", this.#onKeyDown);
        // Without this a right-drag opens the browser menu instead of panning.
        this.#listen("contextmenu", (event) => event.preventDefault());
        this.#listen("dblclick", this.#onDoubleClick);
        this.#listen("mousedown", this.#onMouseDown);
        this.#listen("mousemove", this.#onMouseMove);
        this.#listen("mouseup", this.#endGesture);
        this.#listen("mouseleave", this.#endGesture);
        this.#listen("wheel", this.#onWheel, { passive: false });
    }

    #listen(type, handler, options) {
        const bound = handler.bind(this);
        this.#canvas.addEventListener(type, bound, options);
        this.#listeners.push([type, bound, options]);
    }

    /** Stop listening — the page calls this when it tears the canvas down. */
    destroy() {
        for (const [type, handler, options] of this.#listeners) {
            this.#canvas.removeEventListener(type, handler, options);
        }
        this.#listeners = [];
    }

    get diagram() { return this.#diagram; }
    get zoom() { return this.#zoom; }
    /** The chosen views — boxes and/or an edge. */
    get selected() { return [...this.#selection]; }

    /**
     * Show `diagram`, filling boxes from `classDefs` (qualified class name ->
     * `{ properties }`). Selection is dropped, because the views are new objects.
     */
    setDiagram(diagram, classDefs = {}) {
        this.#diagram = diagram;
        this.#classDefs = classDefs;
        // The views are new objects after a reparse, so nothing in the old selection refers
        // to anything on screen.
        this.#selection = new Set();
        this.draw();
    }

    /** Match the backing store to the canvas's CSS size, then redraw. */
    resize() {
        const rect = this.#canvas.getBoundingClientRect();
        const dpr = globalThis.devicePixelRatio || 1;
        this.#canvas.width = Math.max(1, Math.round(rect.width * dpr));
        this.#canvas.height = Math.max(1, Math.round(rect.height * dpr));
        this.#canvas.style.width = `${rect.width}px`;
        this.#canvas.style.height = `${rect.height}px`;
        this.draw();
    }

    draw() {
        drawDiagram(this.#canvas, this.#diagram, {
            classDefs: this.#classDefs,
            zoom: this.#zoom,
            offsetX: this.#offsetX,
            offsetY: this.#offsetY,
            selected: this.#selection,
            marquee: this.#gesture?.kind === "marquee" ? this.#gesture.rect : null,
            colors: themeColors(),
        });
    }

    setZoom(zoom, about) {
        const next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        if (about) {
            // Keep the point under the cursor fixed while the scale changes.
            this.#offsetX = about.x - (about.x - this.#offsetX) * (next / this.#zoom);
            this.#offsetY = about.y - (about.y - this.#offsetY) * (next / this.#zoom);
        }
        this.#zoom = next;
        this.draw();
    }

    zoomIn() { this.setZoom(this.#zoom * 1.1); }
    zoomOut() { this.setZoom(this.#zoom / 1.1); }

    /** Scale and centre so every box is visible, with a margin. */
    fitToScreen(margin = 20) {
        if (!this.#diagram?.typeViews.length) return;
        const xs = this.#diagram.typeViews.flatMap((tv) => [tv.position.x, tv.position.x + tv.width]);
        const ys = this.#diagram.typeViews.flatMap((tv) => [tv.position.y, tv.position.y + tv.height]);
        const minX = Math.min(...xs), maxX = Math.max(...xs);
        const minY = Math.min(...ys), maxY = Math.max(...ys);
        const dpr = globalThis.devicePixelRatio || 1;
        const w = this.#canvas.width / dpr - margin * 2;
        const h = this.#canvas.height / dpr - margin * 2;
        this.#zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, Math.min(w / (maxX - minX || 1), h / (maxY - minY || 1))));
        this.#offsetX = margin - minX * this.#zoom + (w - (maxX - minX) * this.#zoom) / 2;
        this.#offsetY = margin - minY * this.#zoom + (h - (maxY - minY) * this.#zoom) / 2;
        this.draw();
    }

    /** Pointer position in diagram coordinates. */
    #toModel(event) {
        return this.modelPoint(event.clientX, event.clientY);
    }

    /**
     * A point on the screen in diagram coordinates. Public because a drop lands as an
     * HTML5 drag event, which the controller does not own but which needs the same
     * zoom-and-pan arithmetic to know where it fell.
     */
    modelPoint(clientX, clientY) {
        const rect = this.#canvas.getBoundingClientRect();
        return {
            x: (clientX - rect.left - this.#offsetX) / this.#zoom,
            y: (clientY - rect.top - this.#offsetY) / this.#zoom,
        };
    }

    /**
     * The smallest `view` may be and still show its contents, measured with this canvas's
     * context so the answer matches what will actually be drawn.
     */
    measureBox(view, diagram = this.#diagram, classDefs = this.#classDefs) {
        return minimumBoxSize(this.#canvas.getContext("2d"), view, diagram, classDefs);
    }

    /** Highlight the box showing `type`, if there is one. */
    selectType(type) {
        const tv = this.#diagram?.typeViews.find((v) => v.type === type);
        if (!tv) return false;
        this.#select([tv]);
        this.draw();
        return true;
    }

    /** The topmost box under `p` — last drawn is on top, so search backwards. */
    #typeViewAt(p) {
        const views = this.#diagram.typeViews;
        for (let i = views.length - 1; i >= 0; i--) if (pointInsideBox(p, views[i])) return views[i];
        return null;
    }

    /**
     * What part of which edge is under `p`: an end anchor, an existing waypoint, or a
     * segment. Anchors and waypoints win over segments, so a handle stays grabbable where
     * it sits on its own line.
     */
    #edgeHitAt(p, slop) {
        const byId = typeViewsById(this.#diagram);
        for (const view of relationshipViews(this.#diagram)) {
            const path = drawnPath(view, byId);
            if (!path) continue;
            const { waypoints, sourceEdge, targetEdge } = path;
            if (Math.hypot(p.x - sourceEdge.x, p.y - sourceEdge.y) < slop) return { view, anchorEnd: "source" };
            if (Math.hypot(p.x - targetEdge.x, p.y - targetEdge.y) < slop) return { view, anchorEnd: "target" };

            const own = view.path ?? [];
            for (let i = 0; i < own.length; i++) {
                if (Math.hypot(p.x - own[i].x, p.y - own[i].y) < slop) return { view, pointIndex: i };
            }
            const points = [sourceEdge, ...waypoints, targetEdge];
            for (let i = 0; i < points.length - 1; i++) {
                if (pointToSegmentDistance(p, points[i], points[i + 1]) < slop) return { view, segmentIndex: i };
            }
        }
        return null;
    }

    #onKeyDown(event) {
        if (event.key === "Escape") {
            this.#select([]);
            this.draw();
            return;
        }
        if (event.key === "a" && (event.metaKey || event.ctrlKey)) {
            event.preventDefault();
            this.#select(this.#diagram?.typeViews ?? []);
            this.draw();
            return;
        }
        if (event.key !== "Delete" && event.key !== "Backspace") return;
        if (!this.#diagram || this.#selection.size === 0) return;
        event.preventDefault();
        this.#onDelete([...this.#selection]);
    }

    #onDoubleClick(event) {
        if (!this.#diagram) return;
        const tv = this.#typeViewAt(this.#toModel(event));
        if (tv) {
            event.preventDefault();
            this.#onOpen(tv);
        }
    }

    #onMouseDown(event) {
        if (!this.#diagram) return;
        const p = this.#toModel(event);
        event.preventDefault();
        this.#canvas.focus({ preventScroll: true });

        // The right or middle button pans, because the left button now draws a selection
        // marquee. The canvas suppresses its context menu so a right-drag is usable.
        if (event.button === 1 || event.button === 2) {
            this.#gesture = { kind: "pan", grab: { x: event.clientX, y: event.clientY },
                              origin: { x: this.#offsetX, y: this.#offsetY } };
            this.#canvas.classList.add("dragging");
            return;
        }
        if (event.button !== 0) return;

        // The grip is inside the box, so it has to be tested before the box itself. Only a
        // lone selected box offers one — resizing several at once is not a gesture.
        const boxes = this.#selectedBoxes;
        if (boxes.length === 1 && pointInsideBox(p, resizeHandleOf(boxes[0]))) {
            this.#gesture = { kind: "resize", view: boxes[0], grab: p,
                              origin: { width: boxes[0].width, height: boxes[0].height } };
            this.#canvas.classList.add("moving");
            return;
        }

        const additive = event.shiftKey || event.metaKey || event.ctrlKey;
        const tv = this.#typeViewAt(p);
        if (tv) {
            if (additive) this.#toggle(tv);
            // Clicking a box that is ALREADY selected keeps the selection, so a group can be
            // dragged by any of its members; clicking an unselected one selects just it.
            else if (!this.#selection.has(tv)) this.#select([tv]);

            if (this.#selection.has(tv)) {
                this.#gesture = {
                    kind: "box", grab: p,
                    // Every selected box moves together, each from where it started.
                    moving: this.#selectedBoxes.map((box) => ({ box, origin: { ...box.position } })),
                };
                this.#canvas.classList.add("moving");
            }
            this.draw();
            return;
        }

        const hit = this.#edgeHitAt(p, HIT_SLOP / this.#zoom);
        if (hit) {
            this.#select([hit.view]);
            if (hit.anchorEnd) {
                this.#gesture = { kind: "anchor", view: hit.view, anchorEnd: hit.anchorEnd };
            } else {
                // Clicking a segment inserts a bend there and drags it straight away, so
                // one gesture both creates and places the waypoint.
                const index = hit.pointIndex ?? hit.segmentIndex;
                if (hit.segmentIndex !== undefined) {
                    hit.view.path = hit.view.path ?? [];
                    hit.view.path.splice(index, 0, { ...p });
                }
                this.#gesture = { kind: "waypoint", view: hit.view, pointIndex: index };
            }
            this.#canvas.classList.add("moving");
            this.draw();
            return;
        }

        // Empty space: start a marquee. Shift keeps what is already selected, so a group can
        // be built up from several sweeps.
        if (!additive) this.#select([]);
        this.#gesture = { kind: "marquee", grab: p, rect: rectFrom(p, p),
                          keep: new Set(this.#selection) };
        this.draw();
    }

    #onMouseMove(event) {
        const g = this.#gesture;
        if (!g) return;
        if (g.kind === "pan") {
            this.#offsetX = g.origin.x + (event.clientX - g.grab.x);
            this.#offsetY = g.origin.y + (event.clientY - g.grab.y);
            this.draw();
            return;
        }
        const p = this.#toModel(event);
        if (g.kind === "marquee") {
            g.rect = rectFrom(g.grab, p);
            // Live preview: what would be selected if the button came up now.
            const hit = this.#diagram.typeViews.filter((tv) => boxesIntersect(g.rect, tv));
            this.#selection = new Set([...g.keep, ...hit]);
        } else if (g.kind === "box") {
            // Every selected box moves by the same delta, each from where it started.
            const dx = p.x - g.grab.x;
            const dy = p.y - g.grab.y;
            for (const { box, origin } of g.moving) {
                box.position = { x: origin.x + dx, y: origin.y + dy };
                this.#absorbPointsInside(box);
            }
        } else if (g.kind === "resize") {
            // A box never shrinks past what it has to show: its class name and the property
            // rows inside it. Measured from the content, not a fixed floor, so a class with a
            // long name or many properties stops at a size that still reads.
            const floor = minimumBoxSize(this.#canvas.getContext("2d"), g.view, this.#diagram, this.#classDefs);
            g.view.width = Math.max(floor.width, g.origin.width + (p.x - g.grab.x));
            g.view.height = Math.max(floor.height, g.origin.height + (p.y - g.grab.y));
            this.#absorbPointsInside(g.view);
        } else if (g.kind === "anchor") {
            this.#setAnchor(g.view, g.anchorEnd, p);
        } else if (g.kind === "waypoint") {
            this.#dragWaypoint(g, p);
        }
        this.draw();
    }

    /** Move an edge's end within its box, as an offset from the centre. */
    #setAnchor(view, end, p) {
        const byId = typeViewsById(this.#diagram);
        const tv = byId[end === "source" ? view.source : view.target];
        if (!tv) return;
        const c = center(tv);
        const offset = { x: p.x - c.x, y: p.y - c.y };
        if (end === "source") view.sourceOffset = offset;
        else view.targetOffset = offset;
    }

    /**
     * Move a bend. Dragged into either end box it stops being a bend and becomes that end's
     * anchor, and the gesture continues as an anchor drag — so pulling a line's corner into
     * a class re-attaches it rather than leaving a point stranded under the box.
     */
    #dragWaypoint(gesture, p) {
        const view = gesture.view;
        const point = view.path?.[gesture.pointIndex];
        if (!point) return;
        const byId = typeViewsById(this.#diagram);
        for (const end of ["source", "target"]) {
            const tv = byId[end === "source" ? view.source : view.target];
            if (!tv || !pointInsideBox(p, tv)) continue;
            this.#setAnchor(view, end, p);
            view.path.splice(gesture.pointIndex, 1);
            this.#gesture = { kind: "anchor", view, anchorEnd: end };
            return;
        }
        point.x = p.x;
        point.y = p.y;
    }

    /**
     * A box moved onto a bend swallows it: the bend becomes that end's anchor rather than
     * sitting invisibly inside the box. legend-studio calls this
     * manageInsidePointsDynamically.
     */
    #absorbPointsInside(tv) {
        const c = center(tv);
        for (const view of relationshipViews(this.#diagram)) {
            if (view.source !== tv.id && view.target !== tv.id) continue;
            const path = view.path ?? [];
            const swallowed = path.filter((p) => pointInsideBox(p, tv));
            if (swallowed.length === 0) continue;
            const isSource = view.source === tv.id;
            const pick = isSource ? swallowed[0] : swallowed[swallowed.length - 1];
            const offset = { x: pick.x - c.x, y: pick.y - c.y };
            if (isSource) view.sourceOffset = offset;
            else view.targetOffset = offset;
            view.path = path.filter((p) => !pointInsideBox(p, tv));
        }
    }

    /** Replace the selection. */
    #select(views) {
        const next = new Set(views.filter(Boolean));
        if (next.size === this.#selection.size && [...next].every((v) => this.#selection.has(v))) return;
        this.#selection = next;
        this.#onSelect([...next]);
    }

    /** Add or remove one view, for shift-click. */
    #toggle(view) {
        if (this.#selection.has(view)) this.#selection.delete(view);
        else this.#selection.add(view);
        this.#onSelect([...this.#selection]);
    }

    get #selectedBoxes() {
        return [...this.#selection].filter((v) => v.type !== undefined);
    }

    #endGesture() {
        const g = this.#gesture;
        if (!g) return;
        this.#gesture = null;
        this.#canvas.classList.remove("moving", "dragging");
        if (g.kind === "pan") return;   // panning is a view change, not a model change
        if (g.kind === "marquee") {
            // A marquee changes what is chosen, never the model, so nothing is written back.
            this.#onSelect([...this.#selection]);
            this.draw();
            return;
        }
        const byId = typeViewsById(this.#diagram);
        // A bend dragged until its segments form a straight line is invisible but still
        // grabbable, so drop it. The tolerance is a screen distance, divided by the zoom so
        // the gesture feels the same however far in you are.
        for (const view of relationshipViews(this.#diagram)) {
            pruneCollinear(view, byId, COLLINEAR_SLOP / this.#zoom);
        }
        // Refresh the stored point lists before anyone serializes them.
        for (const view of relationshipViews(this.#diagram)) rebuildPoints(view, byId);
        this.#onChange(this.#diagram);
        this.draw();
    }

    #onWheel(event) {
        event.preventDefault();
        const rect = this.#canvas.getBoundingClientRect();
        this.setZoom(this.#zoom * (event.deltaY < 0 ? 1.1 : 0.9),
                     { x: event.clientX - rect.left, y: event.clientY - rect.top });
    }
}
