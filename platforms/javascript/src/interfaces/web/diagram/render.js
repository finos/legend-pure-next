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

// render.js — draw a diagram model onto a Canvas 2D context.
//
// Ported from `drawDiagram` / `drawClassView` / `drawRelationshipLine` in the bootstrap
// IDE, which in turn follows legend-studio's DiagramRenderer (canvas, no React).
//
// Two things changed in the port. The bootstrap version read module-level globals
// (`diagramData`, `diagramZoom`, `selectedRelationship`, the `DG` colour table) and drew
// into a fixed `#diagramCanvas`; here everything arrives as arguments, so the renderer is
// pure and testable. And its dark palette was hard-coded, so the diagram stayed dark on a
// light page — the colours now come from the page's CSS custom properties, which is what
// layout.js's initTheme switches.

import { edgeEndpoint, pointInsideBox, typeViewsById } from "./geometry.js";

const FONT_FAMILY = "'Segoe UI', Helvetica, Arial, sans-serif";
const FONT_SIZE = 12;
const LINE_HEIGHT = 16;
const HEADER_PADDING = 8;
const PROP_PADDING_X = 10;
const PROP_PADDING_Y = 4;
const TRIANGLE_SIZE = 10;
const DIAMOND_SIZE = 8;
const CORNER_RADIUS = 4;
/** A floor for a class with no readable definition — still wide enough to click and label. */
const MIN_BOX_WIDTH = 80;
export const RESIZE_HANDLE = 10;   // side of the bottom-right grab square, in diagram units

// Shown inside the box rather than as an edge — an edge to `String` would be noise.
const PRIMITIVES = new Set(["String", "Integer", "Float", "Boolean", "Date", "StrictDate",
                            "DateTime", "Number", "Decimal", "Binary", "Byte", "StrictTime",
                            "LatestDate", "Variant"]);

/** Read the page's theme tokens once per frame, so the canvas follows initTheme. */
export function themeColors(element = document.documentElement) {
    const css = getComputedStyle(element);
    const token = (name, fallback) => css.getPropertyValue(name).trim() || fallback;
    const ink = token("--ink", "#1c1c1e");
    const muted = token("--muted", "#61616b");
    return {
        bg: token("--bg", "#ffffff"),
        panel: token("--panel", "#f7f7f8"),
        rule: token("--rule", "#e0e0e3"),
        ink,
        muted,
        accent: token("--accent", "#6D45D9"),
        className: token("--k-class", "#2f6fd0"),
        // An arrowhead or generalization triangle is filled with the background so the line
        // behind it does not show through.
        hollow: token("--bg", "#ffffff"),
    };
}

const shortName = (path) => path.split("::").pop() || path;

function roundedRectPath(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.arcTo(x + w, y, x + w, y + r, r);
    ctx.lineTo(x + w, y + h - r);
    ctx.arcTo(x + w, y + h, x + w - r, y + h, r);
    ctx.lineTo(x + r, y + h);
    ctx.arcTo(x, y + h, x, y + h - r, r);
    ctx.lineTo(x, y + r);
    ctx.arcTo(x, y, x + r, y, r);
    ctx.closePath();
}

/**
 * The `color=#RRGGBB` a diagram carries was chosen against a white canvas, so on a dark
 * page it is used only as a tint over the panel colour rather than as the fill itself.
 */
export function blendColor(hex, base) {
    if (!/^#[0-9a-fA-F]{6}$/.test(hex ?? "")) return base;
    const channel = (s) => parseInt(s, 16);
    const [r, g, b] = [hex.substring(1, 3), hex.substring(3, 5), hex.substring(5, 7)].map(channel);
    const m = /^#?([0-9a-fA-F]{6})$/.exec(base ?? "");
    if (!m) return hex;
    const [br, bg, bb] = [m[1].substring(0, 2), m[1].substring(2, 4), m[1].substring(4, 6)].map(channel);
    const mix = (c, bc) => Math.round(c * 0.15 + bc * 0.85);
    return `rgb(${mix(r, br)}, ${mix(g, bg)}, ${mix(b, bb)})`;
}

/** The bottom-right corner square that resizes a box. */
export const resizeHandleOf = (tv) => ({
    position: { x: tv.position.x + tv.width - RESIZE_HANDLE, y: tv.position.y + tv.height - RESIZE_HANDLE },
    width: RESIZE_HANDLE,
    height: RESIZE_HANDLE,
});

/** Where a line from `from` towards `to` crosses the box's border. */
export function lineBoxIntersection(from, to, tv) {
    const { x: bx, y: by } = tv.position;
    const { width: bw, height: bh } = tv;
    const dx = to.x - from.x;
    const dy = to.y - from.y;
    if (dx === 0 && dy === 0) return to;

    // The nearest crossing along the segment: each edge contributes a candidate `t`.
    let best = 1;
    const consider = (t, on) => { if (t > 0 && t <= 1 && on && t < best) best = t; };
    if (dx !== 0) {
        for (const edgeX of [bx, bx + bw]) {
            const t = (edgeX - from.x) / dx;
            const yy = from.y + t * dy;
            consider(t, yy >= by && yy <= by + bh);
        }
    }
    if (dy !== 0) {
        for (const edgeY of [by, by + bh]) {
            const t = (edgeY - from.y) / dy;
            const xx = from.x + t * dx;
            consider(t, xx >= bx && xx <= bx + bw);
        }
    }
    return { x: from.x + best * dx, y: from.y + best * dy };
}

function drawArrow(ctx, from, to, colors) {
    const size = 8;
    ctx.save();
    ctx.translate(to.x, to.y);
    ctx.rotate(Math.atan2(to.y - from.y, to.x - from.x));
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.lineTo(-size, -size / 3);
    ctx.moveTo(0, 0);
    ctx.lineTo(-size, size / 3);
    ctx.strokeStyle = colors.muted;
    ctx.lineWidth = 1;
    ctx.stroke();
    ctx.restore();
}


/**
 * The UML aggregation marker at the WHOLE end of a relationship: hollow for `(shared)`,
 * filled for `(composite)`. Drawn only when the model says so — a diamond on every edge
 * claims an aggregation that is not there.
 */
function drawDiamond(ctx, from, to, colors, filled) {
    const size = DIAMOND_SIZE;
    ctx.save();
    ctx.translate(to.x, to.y);
    ctx.rotate(Math.atan2(to.y - from.y, to.x - from.x));
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.lineTo(-size, -size / 2);
    ctx.lineTo(-size * 2, 0);
    ctx.lineTo(-size, size / 2);
    ctx.closePath();
    ctx.fillStyle = filled ? colors.muted : colors.hollow;
    ctx.fill();
    ctx.strokeStyle = colors.muted;
    ctx.lineWidth = 1;
    ctx.stroke();
    ctx.restore();
}

function drawGeneralizationTriangle(ctx, from, to, at, colors) {
    // atan plus a half-turn when the line runs leftwards, rather than atan2 — kept from
    // legend-studio so the triangle sits the same way round as it does in Studio.
    const raw = Math.atan((to.y - from.y) / (to.x - from.x));
    const angle = to.x >= from.x ? raw : raw + Math.PI;
    const size = TRIANGLE_SIZE;
    const corners = [{ x: 0, y: 0 }, { x: -size, y: -size / 2 }, { x: -size, y: size / 2 }];
    ctx.beginPath();
    corners.forEach((p, i) => {
        const rx = at.x + (p.x * Math.cos(angle) - p.y * Math.sin(angle));
        const ry = at.y + (p.x * Math.sin(angle) + p.y * Math.cos(angle));
        i === 0 ? ctx.moveTo(rx, ry) : ctx.lineTo(rx, ry);
    });
    ctx.closePath();
    ctx.fillStyle = colors.hollow;
    ctx.fill();
    ctx.strokeStyle = colors.muted;
    ctx.lineWidth = 1;
    ctx.stroke();
}

/**
 * The points an edge is actually drawn through: anchors clipped to the box borders, and
 * waypoints that have ended up inside either box dropped — a waypoint swallowed by a box
 * the user has since enlarged would otherwise kink the line back on itself.
 */
export function drawnPath(view, byId) {
    const source = byId[view.source];
    const target = byId[view.target];
    if (!source || !target) return null;
    const sourceAnchor = edgeEndpoint(view, byId, true);
    const targetAnchor = edgeEndpoint(view, byId, false);
    const waypoints = (view.path ?? []).filter((p) => !pointInsideBox(p, source) && !pointInsideBox(p, target));
    const full = [sourceAnchor, ...waypoints, targetAnchor];
    return {
        waypoints,
        sourceEdge: lineBoxIntersection(sourceAnchor, full[1], source),
        targetEdge: lineBoxIntersection(targetAnchor, full[full.length - 2], target),
    };
}

/**
 * The role labels at each end of an association: `name [multiplicity]`, placed UML-style at
 * the end they describe.
 *
 * A class's association entry records what it navigates BY and what it reaches, so the
 * property found on the SOURCE's class labels the TARGET end, and vice versa. Getting this
 * backwards is easy and silently produces a diagram that reads as the opposite relationship.
 */
export function associationEndLabels(view, byId, classDefs = {}) {
    const sourceType = byId[view.source]?.type;
    const targetType = byId[view.target]?.type;
    if (!sourceType || !targetType) return { source: null, target: null };
    const role = (fromType, toType) => {
        const entry = (classDefs[fromType]?.associations ?? [])
            .find((a) => a.association === view.association && a.type === toType);
        // Name and multiplicity stay apart: they are drawn on opposite sides of the line.
        return entry ? { name: entry.property, multiplicity: `[${entry.multiplicity}]` } : null;
    };
    return {
        // What the source navigates to sits at the target's end, and vice versa.
        target: role(sourceType, targetType),
        source: role(targetType, sourceType),
    };
}

/**
 * Which end of an edge carries an aggregation diamond, and whether it is filled.
 *
 * The diamond belongs at the WHOLE — the class that DECLARES the aggregating property. In
 * Pure `(composite) parts: m::Wheel[*]` puts `parts` on the other class, so the class that
 * ends up holding the property is the whole, and that is the end the marker goes on.
 */
export function aggregationEnds(view, byId, kind, classDefs = {}) {
    const sourceType = byId[view.source]?.type;
    const targetType = byId[view.target]?.type;
    const none = { source: "None", target: "None" };
    if (!sourceType || !targetType) return none;

    if (kind === "property") {
        const name = String(view.property ?? "").split(".").pop();
        const prop = (classDefs[sourceType]?.properties ?? []).find((p) => p.name === name);
        return { source: prop?.aggregation ?? "None", target: "None" };
    }
    if (kind !== "association") return none;
    const at = (fromType, toType) => (classDefs[fromType]?.associations ?? [])
        .find((a) => a.association === view.association && a.type === toType)?.aggregation ?? "None";
    return { source: at(sourceType, targetType), target: at(targetType, sourceType) };
}

const PROPERTY_SPACING = 10;   // legend-studio's DiagramRenderer.propertySpacing

/**
 * WHERE a relationship's role name and multiplicity go beside the box they describe —
 * measured, not drawn. Returns `[{ text, x, y, width }]`, empty when the line does not meet
 * this box's border.
 *
 * Ported from legend-studio's `DiagramRenderer.drawLinePropertyText`. Two things in it are
 * not obvious, and are why this is a port rather than something simpler:
 *
 *  - The label sits where the LINE CROSSES THE BOX BORDER, just outside it — not a fixed
 *    distance along the line, which is what put the text over the class.
 *  - The name and the multiplicity go on OPPOSITE SIDES of that crossing point: either side
 *    of the line where it leaves the top or bottom edge, above and below it where it leaves
 *    the left or right. They straddle the line rather than sitting together.
 *
 * ONE DELIBERATE DIVERGENCE from Studio: which of the two goes on which side is FIXED here —
 * name above / left, multiplicity below / right. Studio picks the side from which half of the
 * box the line enters, so the pair swaps places the moment a drag carries the crossing past
 * the box's midpoint. The labels visibly jump, for no gain the reader can use.
 *
 * Separating measurement from drawing is what lets every label be painted in one pass at the
 * very end — see the note on `labels` in drawDiagram.
 *
 * `from`/`to` are the last two points of the path, `to` being the endpoint at this box.
 */
export function lineEndLabelPlacements(ctx, from, to, box, name, multiplicity) {
    if (!name && !multiplicity) return [];
    ctx.font = `${FONT_SIZE - 1}px ${FONT_FAMILY}`;

    const nameWidth = ctx.measureText(name || "").width;
    const mulWidth = ctx.measureText(multiplicity || "").width;
    const { x: bx, y: by } = box.position;
    const bw = box.width;
    const bh = box.height;
    const sp = PROPERTY_SPACING;
    const both = (nameX, nameY, mulX, mulY) => [
        name ? { text: name, x: nameX, y: nameY, width: nameWidth } : null,
        multiplicity ? { text: multiplicity, x: mulX, y: mulY, width: mulWidth } : null,
    ].filter(Boolean);

    // Crossing the top edge (the line descends into the box) or the bottom edge.
    if (from.y !== to.y) {
        const descending = from.y < to.y;
        const edgeY = descending ? by : by + bh;
        const x = from.x + ((to.x - from.x) / (to.y - from.y)) * (edgeY - from.y);
        if (x > bx && x < bx + bw) {
            const y = descending ? by - LINE_HEIGHT - sp : by + bh + sp;
            // Name left of the crossing, multiplicity right — ALWAYS, see the note below.
            return both(x - nameWidth - sp, y, x + sp, y);
        }
    }
    // Otherwise it crosses the left or the right edge.
    if (from.x !== to.x) {
        const rightwards = from.x < to.x;
        const edgeX = rightwards ? bx : bx + bw;
        const y = from.y + ((to.y - from.y) / (to.x - from.x)) * (edgeX - from.x);
        if (y > by && y < by + bh) {
            const nameX = rightwards ? bx - nameWidth - sp : bx + bw + sp;
            const mulX = rightwards ? bx - mulWidth - sp : bx + bw + sp;
            // Name above the line, multiplicity below — ALWAYS.
            return both(nameX, y - LINE_HEIGHT - sp, mulX, y + sp);
        }
    }
    return [];
}

/**
 * Paint every label, after everything else.
 *
 * ALL the backing chips first, then ALL the glyphs. Drawing each label complete before
 * starting the next lets one label's chip erase the previous label's text where they
 * overlap, which is exactly where legibility matters most.
 */
function drawLabels(ctx, labels, colors) {
    if (labels.length === 0) return;
    ctx.font = `${FONT_SIZE - 1}px ${FONT_FAMILY}`;
    ctx.textAlign = "left";
    ctx.textBaseline = "top";

    ctx.save();
    ctx.globalAlpha = 0.85;
    ctx.fillStyle = colors.bg;
    for (const { x, y, width } of labels) ctx.fillRect(x - 2, y - 1, width + 4, LINE_HEIGHT);
    ctx.restore();

    ctx.fillStyle = colors.muted;
    for (const { text, x, y } of labels) ctx.fillText(text, x, y);
    ctx.textBaseline = "top";
}

function drawRelationship(ctx, view, byId, kind, colors, isSelected, zoom, classDefs) {
    const path = drawnPath(view, byId);
    if (!path) return;
    const { waypoints, sourceEdge, targetEdge } = path;
    const points = [sourceEdge, ...waypoints, targetEdge];

    ctx.beginPath();
    ctx.strokeStyle = view.color && view.color !== "#000000" ? view.color : colors.muted;
    ctx.lineWidth = Math.max(view.lineWidth || 1, 1);
    points.forEach((p, i) => (i === 0 ? ctx.moveTo(p.x, p.y) : ctx.lineTo(p.x, p.y)));
    ctx.stroke();

    if (kind === "generalization") {
        drawGeneralizationTriangle(ctx, points[points.length - 2], points[points.length - 1], targetEdge, colors);
    } else if (kind === "association") {
        // A plain line, no arrowhead: an association is not directed. Its end labels are
        // drawn later, over the boxes. (The bootstrap IDE drew an arrow and a hollow diamond
        // on every edge, claiming navigability and aggregation that were not in the model.)
    } else {
        // A property IS navigable in one direction, so it keeps its arrowhead.
        drawArrow(ctx, points[points.length - 2], targetEdge, colors);
    }

    // The aggregation marker, wherever the model actually puts one.
    if (kind !== "generalization") {
        const ends = aggregationEnds(view, byId, kind, classDefs);
        if (ends.source !== "None") {
            drawDiamond(ctx, points[1], sourceEdge, colors, ends.source === "Composite");
        }
        if (ends.target !== "None") {
            drawDiamond(ctx, points[points.length - 2], targetEdge, colors, ends.target === "Composite");
        }
    }

    if (view.label) {
        const mid = points[Math.floor(points.length / 2)];
        ctx.font = `${FONT_SIZE - 1}px ${FONT_FAMILY}`;
        ctx.fillStyle = colors.muted;
        ctx.textBaseline = "bottom";
        ctx.fillText(view.label, mid.x + 4, mid.y - 2);
        ctx.textBaseline = "top";
    }

    if (isSelected(view)) {
        // Handles are sized in screen pixels, so they stay grabbable at any zoom.
        ctx.fillStyle = colors.accent;
        for (const p of waypoints) {
            ctx.beginPath();
            ctx.arc(p.x, p.y, 4 / zoom, 0, Math.PI * 2);
            ctx.fill();
        }
        for (const p of [sourceEdge, targetEdge]) {
            ctx.beginPath();
            ctx.arc(p.x, p.y, 3 / zoom, 0, Math.PI * 2);
            ctx.fill();
        }
    }
}

/**
 * Which of a class's properties belong in the box rather than on an edge: primitives
 * always, and complex ones only when no edge already shows them.
 */
function boxedProperties(diagram, classDef) {
    const onEdges = new Set();
    for (const view of [...diagram.propertyViews, ...diagram.associationViews]) {
        const name = view.property ?? view.association ?? "";
        const dot = name.lastIndexOf(".");
        if (dot !== -1) onEdges.add(name.substring(dot + 1));
    }
    return classDef.properties.filter((p) => PRIMITIVES.has(shortName(p.type)) || !onEdges.has(p.name));
}

function drawTypeView(ctx, tv, diagram, classDefs, colors, isSelected) {
    const { x, y } = tv.position;
    const { width: w, height: h } = tv;

    roundedRectPath(ctx, x, y, w, h, CORNER_RADIUS);
    ctx.fillStyle = blendColor(tv.color, colors.panel);
    ctx.fill();
    const chosen = isSelected(tv);
    ctx.strokeStyle = chosen ? colors.accent : colors.rule;
    ctx.lineWidth = chosen ? 2 : 1;
    ctx.stroke();

    const headerH = LINE_HEIGHT + HEADER_PADDING * 2;
    ctx.save();
    ctx.beginPath();
    ctx.rect(x, y, w, headerH);
    ctx.clip();
    roundedRectPath(ctx, x, y, w, h, CORNER_RADIUS);
    ctx.fillStyle = colors.rule;
    ctx.fill();
    ctx.restore();

    ctx.beginPath();
    ctx.moveTo(x, y + headerH);
    ctx.lineTo(x + w, y + headerH);
    ctx.strokeStyle = colors.rule;
    ctx.lineWidth = 0.5;
    ctx.stroke();

    const name = shortName(tv.type);
    ctx.font = `bold ${FONT_SIZE}px ${FONT_FAMILY}`;
    ctx.fillStyle = colors.ink;
    ctx.textBaseline = "middle";
    ctx.fillText(name, x + (w - ctx.measureText(name).width) / 2, y + headerH / 2);
    ctx.textBaseline = "top";

    if (chosen) {
        // The bottom-right resize grip, borrowed from legend-studio's
        // buildBottomRightCornerBox. Only the selected box shows one, so an unselected
        // diagram stays clean.
        ctx.fillStyle = colors.accent;
        ctx.fillRect(x + w - RESIZE_HANDLE, y + h - RESIZE_HANDLE, RESIZE_HANDLE, RESIZE_HANDLE);
    }

    const classDef = classDefs[tv.type];
    if (!classDef?.properties?.length) return;
    ctx.font = `${FONT_SIZE - 1}px ${FONT_FAMILY}`;
    ctx.save();
    // Clip so a class with more properties than the box is tall does not spill over its
    // neighbours; the user resizes the box to see the rest.
    ctx.beginPath();
    ctx.rect(x, y + headerH, w, h - headerH);
    ctx.clip();
    let propY = y + headerH + PROP_PADDING_Y;
    for (const prop of boxedProperties(diagram, classDef)) {
        const type = shortName(prop.type);
        // The NAME is the content, so it gets the ink; the type is supporting detail and is
        // muted — except a class, which is a reference to something else and is coloured as
        // one. Primitives used to be green, which collided with `--ok`: a String property
        // read as a success message. Hierarchy says more here than a second hue.
        const label = `${prop.name} : `;
        ctx.fillStyle = colors.ink;
        ctx.fillText(label, x + PROP_PADDING_X, propY);
        ctx.fillStyle = PRIMITIVES.has(type) ? colors.muted : colors.className;
        ctx.fillText(`${type}[${prop.multiplicity}]`,
                     x + PROP_PADDING_X + ctx.measureText(label).width, propY);
        propY += LINE_HEIGHT;
    }
    ctx.restore();
}

/**
 * The smallest a box may be and still show what it contains: its class name, and every
 * property row that belongs inside it.
 *
 * Measured with the same context, fonts and padding the renderer draws with, so the answer
 * is what the drawing actually needs rather than a guess. Used to clamp a RESIZE only — a
 * diagram loaded from source is left exactly as written, because silently growing someone's
 * boxes when they open a file would rewrite their work for them.
 */
export function minimumBoxSize(ctx, typeView, diagram, classDefs = {}) {
    const headerH = LINE_HEIGHT + HEADER_PADDING * 2;
    const classDef = classDefs[typeView.type];
    const rows = classDef ? boxedProperties(diagram, classDef) : [];

    ctx.save();
    ctx.font = `bold ${FONT_SIZE}px ${FONT_FAMILY}`;
    // The header centres the name, so it needs the padding on both sides.
    let widest = ctx.measureText(shortName(typeView.type)).width + PROP_PADDING_X * 2;
    ctx.font = `${FONT_SIZE - 1}px ${FONT_FAMILY}`;
    for (const prop of rows) {
        const text = `${prop.name} : ${shortName(prop.type)}[${prop.multiplicity}]`;
        widest = Math.max(widest, ctx.measureText(text).width + PROP_PADDING_X * 2);
    }
    ctx.restore();

    return {
        width: Math.ceil(Math.max(widest, MIN_BOX_WIDTH)),
        // The resize grip lives in the bottom-right corner, so a box must stay tall enough
        // to still be grabbed by it after being shrunk.
        height: Math.ceil(Math.max(headerH + rows.length * LINE_HEIGHT + PROP_PADDING_Y * 2,
                                   headerH + RESIZE_HANDLE)),
    };
}

/**
 * What to say when there is nothing to draw, or null when there is.
 *
 * "No diagram" and "an empty diagram" look the same on screen but are not the same
 * problem, and telling someone who has just hand-written a `###Diagram` section that there
 * is no diagram in their source reads as a broken feature. Name what is actually missing.
 */
export function placeholderLines(diagram) {
    const ADD = "Right-click a class in the concept tree and choose \u201CAdd to diagram\u201D";
    if (!diagram) return ["No ###Diagram section in this source.", `${ADD} to start one.`];
    if (diagram.typeViews.length === 0) return [`${diagram.name} is empty.`, `${ADD}.`];
    return null;
}

/** Centred lines of guidance on an otherwise empty canvas. */
function drawPlaceholder(ctx, w, h, colors, lines) {
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    lines.forEach((line, i) => {
        ctx.fillStyle = i === 0 ? colors.ink : colors.muted;
        ctx.font = i === 0 ? `600 14px ${FONT_FAMILY}` : `13px ${FONT_FAMILY}`;
        ctx.fillText(line, w / 2, h / 2 + (i - (lines.length - 1) / 2) * 22);
    });
    ctx.textAlign = "left";
    ctx.textBaseline = "top";
}

/**
 * Draw `diagram` onto `canvas`. `classDefs` maps a qualified class name to
 * `{ properties: [{ name, type, multiplicity }] }` — what fills the boxes; a class the
 * editor cannot see simply draws empty. `selected` is the one view drawn highlighted.
 *
 * Returns nothing; the canvas is the output.
 */
export function drawDiagram(canvas, diagram, { classDefs = {}, zoom = 1, offsetX = 0, offsetY = 0,
                                               selected = null, marquee = null,
                                               colors = themeColors() } = {}) {
    // `selected` may be a single view or a set of them — multi-select made the set the
    // general case, and a lone view is just a set of one.
    const chosen = selected instanceof Set ? selected : new Set(selected ? [selected] : []);
    const isSelected = (view) => chosen.has(view);
    const ctx = canvas.getContext("2d");
    const dpr = globalThis.devicePixelRatio || 1;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const w = canvas.width / dpr;
    const h = canvas.height / dpr;

    ctx.fillStyle = colors.bg;
    ctx.fillRect(0, 0, w, h);
    ctx.textBaseline = "top";

    const placeholder = placeholderLines(diagram);
    if (placeholder) {
        drawPlaceholder(ctx, w, h, colors, placeholder);
        return;
    }

    ctx.save();
    ctx.translate(offsetX, offsetY);
    ctx.scale(zoom, zoom);

    const byId = typeViewsById(diagram);
    // Edges first, so a box always covers the line that runs under it.
    for (const v of diagram.generalizationViews) drawRelationship(ctx, v, byId, "generalization", colors, isSelected, zoom, classDefs);
    for (const v of diagram.associationViews) drawRelationship(ctx, v, byId, "association", colors, isSelected, zoom, classDefs);
    for (const v of diagram.propertyViews) drawRelationship(ctx, v, byId, "property", colors, isSelected, zoom, classDefs);
    for (const tv of diagram.typeViews) drawTypeView(ctx, tv, diagram, classDefs, colors, isSelected);

    // Text is drawn ONCE, at the end, from a list collected here — so "text on top" is a
    // property of the structure rather than of the order these loops happen to be in. A
    // drawing pass added below cannot land over a label by accident.
    const labels = [];
    const collect = (from, to, box, name, multiplicity) =>
        labels.push(...lineEndLabelPlacements(ctx, from, to, box, name, multiplicity));

    for (const view of diagram.associationViews) {
        const path = drawnPath(view, byId);
        if (!path) continue;
        const points = [path.sourceEdge, ...path.waypoints, path.targetEdge];
        const ends = associationEndLabels(view, byId, classDefs);
        if (byId[view.source] && ends.source) {
            collect(points[1], path.sourceEdge, byId[view.source],
                    ends.source.name, ends.source.multiplicity);
        }
        if (byId[view.target] && ends.target) {
            collect(points[points.length - 2], path.targetEdge, byId[view.target],
                    ends.target.name, ends.target.multiplicity);
        }
    }
    // A property shown as an edge is NOT listed inside its box, so without this its name and
    // multiplicity appear nowhere at all. Studio labels the `to` end; so does this.
    for (const view of diagram.propertyViews) {
        const path = drawnPath(view, byId);
        const targetBox = byId[view.target];
        if (!path || !targetBox) continue;
        const points = [path.sourceEdge, ...path.waypoints, path.targetEdge];
        const name = String(view.property ?? "").split(".").pop();
        const prop = (classDefs[byId[view.source]?.type]?.properties ?? []).find((p) => p.name === name);
        if (prop) {
            collect(points[points.length - 2], path.targetEdge, targetBox,
                    prop.name, `[${prop.multiplicity}]`);
        }
    }

    drawLabels(ctx, labels, colors);

    // The marquee, over everything: it is transient and has to be visible against whatever
    // it is dragged across.
    if (marquee) {
        ctx.save();
        ctx.setLineDash([4 / zoom, 3 / zoom]);
        ctx.strokeStyle = colors.accent;
        ctx.lineWidth = 1 / zoom;
        ctx.fillStyle = colors.accent;
        ctx.globalAlpha = 0.08;
        ctx.fillRect(marquee.position.x, marquee.position.y, marquee.width, marquee.height);
        ctx.globalAlpha = 1;
        ctx.strokeRect(marquee.position.x, marquee.position.y, marquee.width, marquee.height);
        ctx.restore();
    }

    ctx.restore();
}
