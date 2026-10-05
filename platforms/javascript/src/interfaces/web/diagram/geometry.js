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

// geometry.js — where an edge meets a box, and how an edge's stored form relates to the
// points it is drawn through.
//
// Ported from the bootstrap IDE, which took the design from legend-studio's
// RelationshipView: an edge does NOT store its endpoints. It stores an offset from each
// box's centre plus the intermediate waypoints (`path`), and the endpoints are recomputed
// whenever anything moves. That is what lets a box be dragged without touching its edges.
//
// The text form stores the FULL point list, so the two representations convert both ways:
//   text -> model   adoptPoints()   points          -> sourceOffset/targetOffset + path
//   model -> text   fullPath()      offsets + path  -> points

export const center = (tv) => ({ x: tv.position.x + tv.width / 2, y: tv.position.y + tv.height / 2 });

export const pointInsideBox = (p, tv) =>
    p.x >= tv.position.x && p.x <= tv.position.x + tv.width &&
    p.y >= tv.position.y && p.y <= tv.position.y + tv.height;

/** Type views by id — every edge refers to its ends by id, not by reference. */
export const typeViewsById = (diagram) =>
    Object.fromEntries(diagram.typeViews.map((tv) => [tv.id, tv]));

export const relationshipViews = (diagram) => [
    ...diagram.generalizationViews,
    ...diagram.associationViews,
    ...diagram.propertyViews,
];

/**
 * Where an edge touches one of its boxes: the box centre displaced by the stored offset,
 * but only while that still lands inside the box. A box resized smaller than its offset
 * would otherwise trail an edge that starts in mid-air, so the offset is dropped and the
 * endpoint returns to the centre.
 */
export function edgeEndpoint(view, byId, isSource) {
    const tv = byId[isSource ? view.source : view.target];
    if (!tv) return { x: 0, y: 0 };
    const c = center(tv);
    const offset = isSource ? view.sourceOffset : view.targetOffset;
    if (!offset) return c;
    const anchor = { x: c.x + offset.x, y: c.y + offset.y };
    if (pointInsideBox(anchor, tv)) return anchor;
    if (isSource) view.sourceOffset = { x: 0, y: 0 };
    else view.targetOffset = { x: 0, y: 0 };
    return c;
}

/**
 * Adopt parsed `points` as offsets plus intermediate path — the inverse of {@link fullPath}.
 * Called once after parsing, before anything is drawn or dragged.
 */
export function adoptPoints(diagram) {
    const byId = typeViewsById(diagram);
    for (const view of relationshipViews(diagram)) {
        const source = byId[view.source];
        const target = byId[view.target];
        const points = view.points ?? [];
        // Intermediate waypoints are everything but the two endpoints.
        view.path = points.length > 2 ? points.slice(1, -1) : [];
        if (!source || !target || points.length < 2) {
            // A dangling edge — its box was renamed or removed. Keep it anchored at the
            // centres rather than dropping it, so the source text survives a round trip.
            view.sourceOffset = { x: 0, y: 0 };
            view.targetOffset = { x: 0, y: 0 };
            continue;
        }
        const sc = center(source);
        const tc = center(target);
        const last = points[points.length - 1];
        view.sourceOffset = { x: points[0].x - sc.x, y: points[0].y - sc.y };
        view.targetOffset = { x: last.x - tc.x, y: last.y - tc.y };
    }
    return diagram;
}

/** A rectangle from two corners, in any order — a marquee dragged in any direction. */
export const rectFrom = (a, b) => ({
    position: { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y) },
    width: Math.abs(b.x - a.x),
    height: Math.abs(b.y - a.y),
});

/**
 * Whether two boxes overlap at all.
 *
 * legend-studio asks whether either box contains any CORNER of the other, which misses the
 * case of two rectangles crossing in a plus shape with no corner inside either. This is the
 * overlap test that approximates.
 */
export const boxesIntersect = (a, b) =>
    // Zero area overlaps nothing, for the same reason touching edges do not count: a plain
    // click produces a marquee of no size, and it should catch nothing.
    a.width > 0 && a.height > 0 && b.width > 0 && b.height > 0 &&
    a.position.x < b.position.x + b.width && a.position.x + a.width > b.position.x &&
    a.position.y < b.position.y + b.height && a.position.y + a.height > b.position.y;

/** The points an edge is drawn through, endpoints included. */
export function fullPath(view, byId) {
    return [
        edgeEndpoint(view, byId, true),
        ...(view.path ?? []),
        edgeEndpoint(view, byId, false),
    ];
}

/** Perpendicular distance from `p` to the line through `a` and `b`. */
function distanceToLine(p, a, b) {
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const length = Math.hypot(dx, dy);
    if (length === 0) return Math.hypot(p.x - a.x, p.y - a.y);
    // Twice the triangle's area over its base — the cross product, normalised.
    return Math.abs(dy * (p.x - a.x) - dx * (p.y - a.y)) / length;
}

/**
 * Drop waypoints that have been dragged flat: a bend whose two segments form a straight line
 * is invisible but still grabbable, so the next click lands on a point nobody can see.
 *
 * `tolerance` is in DIAGRAM units; the caller divides a screen distance by the zoom, so the
 * gesture feels the same however far in you are. Neither legend-studio nor the bootstrap IDE
 * does this — they prune points swallowed by a BOX, which is a different thing.
 *
 * @returns the number of waypoints removed
 */
export function pruneCollinear(view, byId, tolerance = 2) {
    const path = view.path ?? [];
    if (path.length === 0) return 0;
    const ends = fullPath(view, byId);
    const kept = [];
    // Compare against the previous KEPT point, so a run of flat bends collapses whole.
    let previous = ends[0];
    for (let i = 0; i < path.length; i++) {
        const next = i + 1 < path.length ? path[i + 1] : ends[ends.length - 1];
        if (distanceToLine(path[i], previous, next) <= tolerance) continue;
        kept.push(path[i]);
        previous = path[i];
    }
    const removed = path.length - kept.length;
    if (removed > 0) view.path = kept;
    return removed;
}

/** Refresh `points` from the offsets and path — what the serializer writes out. */
export function rebuildPoints(view, byId) {
    view.points = fullPath(view, byId);
    return view.points;
}
