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

// serialize.js — diagram model -> `###Diagram` text, and splicing an edit back into source.
//
// Ported from `serializeDiagram` / `updateDiagramInSource` in the bootstrap IDE. This is
// the only diagram composer that exists anywhere: legend-pure parses `###Diagram` but never
// writes it, and legend-studio sends grammar text to Legend Engine over HTTP.
//
// What a round trip preserves: every view, its geometry, its colours, its edge points and
// the optional `(width=…, height=…)` on the `Diagram` line. What it does NOT preserve:
// comment text and layout inside the diagram block, and property ORDER within a view —
// output is normalised. So a diagram the user has hand-formatted will be reflowed the first
// time the editor writes it back.

import { rebuildPoints, typeViewsById } from "./geometry.js";

const num = (n) => n.toFixed(1);
const point = (p) => `(${num(p.x)}, ${num(p.y)})`;
const points = (pts) => `[${pts.map(point).join(", ")}]`;

function typeView(tv) {
    return [
        `   TypeView ${tv.id}`,
        "   (",
        [
            `      type=${tv.type}`,
            `      stereotypesVisible=${tv.stereotypesVisible}`,
            `      attributesVisible=${tv.attributesVisible}`,
            "      attributeStereotype=none",
            "      attributeTypes=none",
            `      color=${tv.color}`,
            "      lineWidth=-1.0",
            `      position=${point(tv.position)}`,
            `      width=${num(tv.width)}`,
            `      height=${num(tv.height)}`,
        ].join(",\n"),
        "   )",
    ];
}

function relationshipView(kind, view, byId, extra = []) {
    // The endpoints are derived, never stored, so they are recomputed at write time from
    // wherever the boxes are now (see geometry.js).
    rebuildPoints(view, byId);
    const props = [
        ...extra,
        `      source=${view.source}`,
        `      target=${view.target}`,
        `      points=${points(view.points ?? [])}`,
    ];
    // Defaults are left out, so a diagram nobody has restyled stays terse.
    if (view.color && view.color !== "#000000") props.push(`      color=${view.color}`);
    if (view.lineWidth && view.lineWidth !== 1) props.push(`      lineWidth=${num(view.lineWidth)}`);
    if (view.label) props.push(`      label=${view.label}`);
    return [`   ${kind} ${view.id}`, "   (", props.join(",\n"), "   )"];
}

/** The `Diagram … { … }` block — without the `###Diagram` header, which stays in the source. */
export function serializeDiagram(diagram) {
    const byId = typeViewsById(diagram);
    const dim = diagram.dimension;
    return [
        `Diagram ${diagram.name}${dim ? `(width=${num(dim.width)}, height=${num(dim.height)})` : ""}`,
        "{",
        ...diagram.typeViews.flatMap(typeView),
        ...diagram.generalizationViews.flatMap((v) => relationshipView("GeneralizationView", v, byId)),
        ...diagram.associationViews.flatMap((v) =>
            relationshipView("AssociationView", v, byId, [`      association=${v.association}`])),
        ...diagram.propertyViews.flatMap((v) =>
            relationshipView("PropertyView", v, byId, [`      property=${v.property}`])),
        "}",
    ].join("\n");
}

/**
 * `source` with this diagram's block replaced by its current state, and the diagram's own
 * offsets moved on so a second edit splices correctly. Everything outside `_diagramStart`
 * … `_diagramEnd` is untouched — the rest of the file, and any other diagram in it, is
 * returned byte for byte.
 */
export function replaceDiagramInSource(source, diagram) {
    const serialized = serializeDiagram(diagram);
    const updated = source.substring(0, diagram._diagramStart) + serialized + source.substring(diagram._diagramEnd);
    diagram._diagramEnd = diagram._diagramStart + serialized.length;
    return updated;
}
