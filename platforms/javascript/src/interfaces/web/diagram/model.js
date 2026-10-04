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

// model.js — building and editing a diagram model, independent of any canvas or page.
//
// The shape a diagram has, as parse.js produces it and serialize.js consumes it:
//
//   { name, dimension?, typeViews[], generalizationViews[], associationViews[],
//     propertyViews[], _sectionStart, _diagramStart, _diagramEnd }
//
//   typeView      { id, type, position:{x,y}, width, height, color,
//                   stereotypesVisible, attributesVisible }
//   relationship  { id, source, target, points[], color, lineWidth, label,
//                   + property= | association= for those two kinds,
//                   + sourceOffset/targetOffset/path once geometry.js has adopted it }
//
// `source` and `target` are typeView IDs, never class names — two boxes may show the same
// class, and an edge has to know which one it joins.

import { replaceDiagramInSource } from "./serialize.js";

export const BOX_WIDTH = 200;
export const BOX_HEIGHT = 90;
const PER_ROW = 4;
const GAP_X = 60;
const GAP_Y = 70;

/** A typeView id derived from the class path, kept to the identifier chars the grammar allows. */
export const typeViewId = (qualifiedName) => `tv_${qualifiedName.replace(/[^A-Za-z0-9]+/g, "_")}`;

/**
 * A typeView id for `qualifiedName` that none of `taken` uses.
 *
 * A class may appear on a diagram MORE THAN ONCE — to show it in two contexts without
 * dragging an edge across the whole canvas — so the id cannot simply be the class path.
 */
export function uniqueTypeViewId(qualifiedName, taken) {
    const base = typeViewId(qualifiedName);
    if (!taken.includes(base)) return base;
    for (let i = 2; i < 1000; i++) {
        const candidate = `${base}_${i}`;
        if (!taken.includes(candidate)) return candidate;
    }
    return `${base}_${Date.now()}`;
}

/**
 * A box for `qualifiedName`. Without a `position` it goes in a wrapping row, so boxes added
 * from the menu never land on top of each other; with one — a drag and drop — it goes
 * exactly where it was put, CENTRED on the drop point, because that is where the pointer
 * was and the box should appear under it.
 */
export function newTypeView(qualifiedName, index, position, taken = []) {
    return {
        id: uniqueTypeViewId(qualifiedName, taken),
        type: qualifiedName,
        position: position
            ? { x: position.x - BOX_WIDTH / 2, y: position.y - BOX_HEIGHT / 2 }
            : { x: GAP_X + (index % PER_ROW) * (BOX_WIDTH + GAP_X),
                y: GAP_Y + Math.floor(index / PER_ROW) * (BOX_HEIGHT + GAP_Y) },
        width: BOX_WIDTH,
        height: BOX_HEIGHT,
        color: "#FFFFCC",
        stereotypesVisible: true,
        attributesVisible: true,
    };
}

/** An empty diagram whose splice range is the very end of `source`. */
export function emptyDiagram(source, name = "diagram::Untitled") {
    return {
        name,
        dimension: undefined,
        typeViews: [], generalizationViews: [], associationViews: [], propertyViews: [],
        _sectionStart: source.length, _diagramStart: source.length, _diagramEnd: source.length,
    };
}

/**
 * `source` with a `###Diagram` section appended for `diagram`, and `diagram`'s splice
 * offsets moved onto the text just written so the next edit lands in the right place.
 */
export function appendDiagramSection(source, diagram) {
    const head = source.endsWith("\n") || source === "" ? source : `${source}\n`;
    const header = `${head}\n###Diagram\n`;
    diagram._sectionStart = head.length + 1;
    diagram._diagramStart = header.length;
    diagram._diagramEnd = header.length;
    // The trailing newline is what the spliced block is written in front of, so the file
    // still ends with one.
    return replaceDiagramInSource(`${header}\n`, diagram);
}

const edgeId = (prefix, ...parts) => `${prefix}_${parts.join("_").replace(/[^A-Za-z0-9_]+/g, "_")}`;

/** A fresh edge anchored at both box centres, with no bends. */
function newEdge(id, source, target, extra) {
    return {
        id, source, target,
        points: [],
        sourceOffset: { x: 0, y: 0 },
        targetOffset: { x: 0, y: 0 },
        path: [],
        color: "#000000",
        lineWidth: 1,
        label: "",
        ...extra,
    };
}

/**
 * Draw the edges implied by the model between the boxes on the diagram: a
 * GeneralizationView wherever one shown class extends another, a PropertyView wherever a
 * shown class has a property whose type is also shown, and an AssociationView wherever two
 * shown classes take part in the same association.
 *
 * This is how legend-studio behaves when a class is dropped onto a diagram, and it is the
 * only way edges get created here — there is no free-hand "draw a connector" tool, because
 * an edge that does not correspond to a property or a generalization would be a drawing,
 * not a diagram of the model.
 *
 * Idempotent: running it again adds nothing, so it is safe to call after every change. It
 * never removes an edge the user has, only adds the missing ones.
 *
 * @returns the number of edges added
 */
export function autoConnect(diagram, classDefs) {
    // Keyed by type to a LIST: the same class may be shown more than once, and each view
    // carries its own relationships — a second box with no edges would look broken.
    const boxesByType = new Map();
    for (const tv of diagram.typeViews) {
        if (!boxesByType.has(tv.type)) boxesByType.set(tv.type, []);
        boxesByType.get(tv.type).push(tv);
    }

    // An association gives BOTH its classes a property, so it is reached twice — once from
    // each end. The key is the association plus the UNORDERED pair, so the two sightings
    // collapse into the single edge the relationship actually is.
    // Every key names BOTH boxes, so an edge is one per (source view, relationship, target
    // view) — which is what lets a duplicated class carry its own copies of its edges.
    const pair = (a, b) => [a, b].sort().join("|");
    const existing = new Set([
        ...diagram.generalizationViews.map((v) => `g:${v.source}>${v.target}`),
        ...diagram.propertyViews.map((v) => `p:${v.source}>${v.target}>${v.property}`),
        ...diagram.associationViews.map((v) => `a:${v.association}:${pair(v.source, v.target)}`),
    ]);
    let added = 0;

    for (const box of diagram.typeViews) {
        const def = classDefs[box.type];
        if (!def) continue;

        for (const superType of def.generalizations ?? []) {
            for (const superBox of boxesByType.get(superType) ?? []) {
                const key = `g:${box.id}>${superBox.id}`;
                if (superBox === box || existing.has(key)) continue;
                diagram.generalizationViews.push(newEdge(edgeId("gv", box.id, superBox.id), box.id, superBox.id));
                existing.add(key);
                added++;
            }
        }

        for (const { type: otherType, association } of def.associations ?? []) {
            for (const otherBox of boxesByType.get(otherType) ?? []) {
                const key = `a:${association}:${pair(box.id, otherBox.id)}`;
                if (otherBox === box || existing.has(key)) continue;
                diagram.associationViews.push(
                    newEdge(edgeId("av", box.id, otherBox.id), box.id, otherBox.id, { association }));
                existing.add(key);
                added++;
            }
        }

        for (const property of def.properties ?? []) {
            for (const targetBox of boxesByType.get(property.type) ?? []) {
                const qualified = `${box.type}.${property.name}`;
                const key = `p:${box.id}>${targetBox.id}>${qualified}`;
                if (existing.has(key)) continue;
                diagram.propertyViews.push(
                    newEdge(edgeId("pv", box.id, targetBox.id, property.name), box.id, targetBox.id,
                            { property: qualified }));
                existing.add(key);
                added++;
            }
        }
    }
    return added;
}

/**
 * Drop views that point at things the graph no longer has: a box whose class was renamed
 * or deleted, and any edge left dangling by that — or by a box the user removed by hand.
 *
 * legend-studio runs its equivalent (`cleanUpDeadReferencesInDiagram`) when it loads a
 * graph. Here it is deliberately NOT automatic: this page recompiles on every keystroke, so
 * a class that is briefly absent because the file does not parse yet would have its box
 * silently deleted mid-edit. It runs only when the user asks for it.
 *
 * @param exists tells whether a qualified class name is still in the graph
 * @returns what was removed, so the caller can say so
 */
export function cleanUpDeadReferences(diagram, exists) {
    const live = diagram.typeViews.filter((tv) => exists(tv.type));
    const liveIds = new Set(live.map((tv) => tv.id));
    const removedTypeViews = diagram.typeViews.length - live.length;
    diagram.typeViews = live;

    let removedEdges = 0;
    for (const kind of ["generalizationViews", "associationViews", "propertyViews"]) {
        const kept = diagram[kind].filter((v) => liveIds.has(v.source) && liveIds.has(v.target));
        removedEdges += diagram[kind].length - kept.length;
        diagram[kind] = kept;
    }
    return { removedTypeViews, removedEdges };
}

/**
 * Remove a view and anything left dangling by it: deleting a box takes its edges with it,
 * because an edge with one end missing is not a diagram, it is a broken file.
 *
 * Returns the number of views removed, 0 when there was nothing to remove.
 */
export function removeView(diagram, viewId) {
    const before = diagram.typeViews.length + diagram.generalizationViews.length
                 + diagram.associationViews.length + diagram.propertyViews.length;

    const wasBox = diagram.typeViews.some((tv) => tv.id === viewId);
    if (wasBox) {
        diagram.typeViews = diagram.typeViews.filter((tv) => tv.id !== viewId);
        for (const kind of ["generalizationViews", "associationViews", "propertyViews"]) {
            diagram[kind] = diagram[kind].filter((v) => v.source !== viewId && v.target !== viewId);
        }
    } else {
        for (const kind of ["generalizationViews", "associationViews", "propertyViews"]) {
            diagram[kind] = diagram[kind].filter((v) => v.id !== viewId);
        }
    }

    return before - (diagram.typeViews.length + diagram.generalizationViews.length
                   + diagram.associationViews.length + diagram.propertyViews.length);
}

/**
 * Add `qualifiedName` to the source's diagram, creating the section when there is none.
 *
 * A class may be added MORE THAN ONCE — each call makes another view, with its own id and
 * its own edges. Returns `{ source, diagram, added, id }`; `added` is false only when the
 * name could not be used at all. Pass `classDefs` (see class-defs.js) to also draw the edges
 * the new view implies, `position` to place it there rather than in the next free slot, and
 * `measure` — `(view, diagram) => { width, height }` — to size it to its contents.
 */
export function addTypeToSource(source, qualifiedName, parse, classDefs = null, position = null,
                                measure = null) {
    const [existing] = parse(source);
    const diagram = existing ?? emptyDiagram(source);
    const view = newTypeView(qualifiedName, diagram.typeViews.length, position,
                             diagram.typeViews.map((tv) => tv.id));
    diagram.typeViews.push(view);
    // Wire the new box to what is already there, so a diagram built class by class shows
    // its relationships without the user drawing a single line.
    if (classDefs) autoConnect(diagram, classDefs);

    // Size the box to what it has to show. The default 200x90 clipped any class with a long
    // name or more than a few properties, and the user had to resize every one by hand.
    // Measured AFTER autoConnect, because a property drawn as an edge is not inside the box
    // and must not make it taller.
    if (measure) {
        const needed = measure(view, diagram);
        if (needed) {
            const width = Math.max(BOX_WIDTH, needed.width);
            const height = Math.max(BOX_HEIGHT, needed.height);
            // A dropped box is centred on the cursor, so growing it has to keep it there.
            if (position) {
                view.position = { x: position.x - width / 2, y: position.y - height / 2 };
            }
            view.width = width;
            view.height = height;
        }
    }
    return {
        source: existing ? replaceDiagramInSource(source, diagram) : appendDiagramSection(source, diagram),
        diagram,
        added: true,
        id: view.id,
    };
}
