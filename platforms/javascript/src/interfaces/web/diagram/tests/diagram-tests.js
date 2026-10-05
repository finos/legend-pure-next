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

// Diagram round-trip tests — the property that protects a user's source from being
// corrupted by a diagram edit.
//
// The bootstrap IDE's diagram engine (which these modules were ported from) has no tests
// at all, so this is the first time its parser and composer are pinned. The central check
// is parse -> serialize -> parse: the editor rewrites the `###Diagram` block on every
// change, so any asymmetry between reader and writer silently destroys geometry the user
// placed by hand.
//
// Run: node src/interfaces/web/diagram/tests/diagram-tests.js [--report <path>]

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { parseDiagramSource } from "../parse.js";
import { serializeDiagram, replaceDiagramInSource } from "../serialize.js";
import { adoptPoints, boxesIntersect, edgeEndpoint, pointInsideBox, pruneCollinear, rectFrom,
         typeViewsById } from "../geometry.js";
import { aggregationEnds, associationEndLabels, blendColor, drawDiagram, drawnPath,
         lineBoxIntersection, lineEndLabelPlacements, minimumBoxSize, placeholderLines,
         resizeHandleOf } from "../render.js";
import { addTypeToSource, autoConnect, BOX_HEIGHT, BOX_WIDTH, cleanUpDeadReferences, newTypeView,
         removeView, typeViewId, uniqueTypeViewId } from "../model.js";
import { multiplicityText, qualifiedName } from "../class-defs.js";
import { TestReport } from "../../../../core/tests/test-report.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const FIXTURE = readFileSync(join(HERE, "fixture.pure"), "utf8");

const results = [];
const report = new TestReport("diagram");

function check(name, fn) {
    const started = Date.now();
    try {
        fn();
        results.push({ name });
        report.pass(name, Date.now() - started);
    } catch (error) {
        results.push({ name, error });
        report.fail(name, Date.now() - started, error);
    }
}

/** Parse as the editor does: text in, geometry adopted, ready to drag. */
const load = (text) => parseDiagramSource(text).map(adoptPoints);

/**
 * One full editor cycle: write the model back into its source and read it again.
 * `serializeDiagram` alone emits only the `Diagram … { … }` block — the `###Diagram`
 * header belongs to the source — so a round trip has to go through the splice, which is
 * also exactly the path an edit takes.
 */
const roundTrip = (text, diagram) => load(replaceDiagramInSource(text, diagram))[0];

/** The comparable part of a model — the splice offsets are positions, not content. */
const content = (d) => {
    const { _sectionStart, _diagramStart, _diagramEnd, ...rest } = d;
    return rest;
};

check("parses every view kind out of the fixture", () => {
    const [diagram] = load(FIXTURE);
    assert.equal(diagram.name, "model::PersonDiagram");
    assert.equal(diagram.typeViews.length, 3);
    assert.equal(diagram.generalizationViews.length, 1);
    assert.equal(diagram.propertyViews.length, 1);
    assert.equal(diagram.associationViews.length, 1);
});

check("reads a type view's geometry and styling", () => {
    const tv = load(FIXTURE)[0].typeViews.find((v) => v.id === "tv_legal");
    assert.deepEqual(tv.position, { x: 500, y: 300 });
    assert.equal(tv.width, 200);
    assert.equal(tv.height, 80);
    assert.equal(tv.type, "model::LegalEntity");
    assert.equal(tv.color, "#CCFFFF");
    // The one view in the fixture that switches a flag off, so `=== "true"` is exercised
    // in both directions rather than defaulting.
    assert.equal(tv.stereotypesVisible, false);
    assert.equal(tv.attributesVisible, true);
});

check("reads the qualified names edges point at", () => {
    const [diagram] = load(FIXTURE);
    assert.equal(diagram.propertyViews[0].property, "model::Person.firm");
    assert.equal(diagram.associationViews[0].association, "model::Person_LegalEntity");
    assert.equal(diagram.generalizationViews[0].source, "tv_firm");
    assert.equal(diagram.generalizationViews[0].target, "tv_legal");
});

check("a value containing commas is not split on them", () => {
    // `position=(100.0, 100.0)` and `points=[(a,b), (c,d)]` both hold commas inside the
    // value, which is why the properties are scanned rather than split.
    const [diagram] = load(FIXTURE);
    assert.deepEqual(diagram.typeViews[0].position, { x: 100, y: 100 });
    assert.deepEqual(diagram.propertyViews[0].points, [
        { x: 250, y: 140 }, { x: 400, y: 200 }, { x: 550, y: 140 },
    ]);
});

check("adopts points as offsets from the box centres", () => {
    const [diagram] = load(FIXTURE);
    const pv = diagram.propertyViews[0];
    // tv_person centre is (200, 140); the edge leaves at (250, 140).
    assert.deepEqual(pv.sourceOffset, { x: 50, y: 0 });
    // tv_firm centre is (600, 140); the edge arrives at (550, 140).
    assert.deepEqual(pv.targetOffset, { x: -50, y: 0 });
    // Only the waypoints between the ends are kept as the path.
    assert.deepEqual(pv.path, [{ x: 400, y: 200 }]);
});

check("an edge with no waypoints has an empty path", () => {
    assert.deepEqual(load(FIXTURE)[0].generalizationViews[0].path, []);
});

check("round-trips: parse -> serialize -> parse gives the same model", () => {
    const first = load(FIXTURE)[0];
    assert.deepEqual(content(roundTrip(FIXTURE, first)), content(first));
});

check("round-trips a second time — serializing is idempotent", () => {
    // Guards against a writer that is stable only once, e.g. by re-normalising a default
    // into an explicit value that then survives the next read.
    const once = serializeDiagram(load(FIXTURE)[0]);
    const twice = serializeDiagram(load(`###Diagram\n${once}`)[0]);
    assert.equal(twice, once);
});

check("splicing an edit leaves the rest of the file byte for byte", () => {
    const [diagram] = load(FIXTURE);
    diagram.typeViews[0].position = { x: 111, y: 222 };
    const updated = replaceDiagramInSource(FIXTURE, diagram);

    // Everything before `###Diagram` — the class definitions — is untouched.
    const head = FIXTURE.substring(0, diagram._sectionStart);
    assert.equal(updated.substring(0, head.length), head);
    assert.ok(head.includes("Class model::Person"));

    const reparsed = load(updated)[0];
    assert.deepEqual(reparsed.typeViews[0].position, { x: 111, y: 222 });
    // The move did not disturb the other boxes.
    assert.deepEqual(reparsed.typeViews[1].position, { x: 500, y: 100 });
});

check("a second splice lands correctly — offsets move on after an edit", () => {
    // The editor writes back on every drag, so the model's `_diagramEnd` must track the
    // text it just produced or the next splice overwrites the wrong range.
    const [diagram] = load(FIXTURE);
    diagram.typeViews[0].position = { x: 111, y: 222 };
    let source = replaceDiagramInSource(FIXTURE, diagram);
    diagram.typeViews[1].position = { x: 333, y: 444 };
    source = replaceDiagramInSource(source, diagram);

    const reparsed = load(source)[0];
    assert.equal(reparsed.typeViews.length, 3);
    assert.deepEqual(reparsed.typeViews[0].position, { x: 111, y: 222 });
    assert.deepEqual(reparsed.typeViews[1].position, { x: 333, y: 444 });
    assert.ok(source.includes("Class model::Person"));
    // No duplicated block left behind by a mis-sized splice.
    assert.equal(source.split("###Diagram").length - 1, 1);
});

check("moving a box carries its edges with it", () => {
    // The point of storing offsets instead of endpoints: a drag touches only the box.
    const [diagram] = load(FIXTURE);
    const person = diagram.typeViews.find((v) => v.id === "tv_person");
    person.position = { x: person.position.x + 40, y: person.position.y + 10 };
    const byId = typeViewsById(diagram);
    assert.deepEqual(edgeEndpoint(diagram.propertyViews[0], byId, true), { x: 290, y: 150 });
    // The other end, on a box that did not move, is where it was.
    assert.deepEqual(edgeEndpoint(diagram.propertyViews[0], byId, false), { x: 550, y: 140 });
});

check("an anchor left outside its box falls back to the centre", () => {
    // Shrinking a box would otherwise leave an edge starting in mid-air.
    const [diagram] = load(FIXTURE);
    const person = diagram.typeViews.find((v) => v.id === "tv_person");
    person.width = 20;
    person.height = 20;
    const pv = diagram.propertyViews[0];
    const byId = typeViewsById(diagram);
    assert.deepEqual(edgeEndpoint(pv, byId, true), { x: 110, y: 110 });
    // The stale offset is cleared, not merely ignored, so the next write is clean.
    assert.deepEqual(pv.sourceOffset, { x: 0, y: 0 });
});

check("pointInsideBox includes the border", () => {
    const tv = { position: { x: 10, y: 20 }, width: 100, height: 50 };
    assert.ok(pointInsideBox({ x: 10, y: 20 }, tv));
    assert.ok(pointInsideBox({ x: 110, y: 70 }, tv));
    assert.ok(!pointInsideBox({ x: 111, y: 70 }, tv));
    assert.ok(!pointInsideBox({ x: 60, y: 19 }, tv));
});

check("an edge whose box is missing survives instead of breaking the file", () => {
    // A class renamed in the source leaves a dangling reference; the diagram must still
    // round-trip so the user's other work is not lost with it.
    const broken = FIXTURE.replace("source=tv_person,\n      target=tv_firm", "source=tv_gone,\n      target=tv_firm");
    const [diagram] = load(broken);
    assert.equal(diagram.propertyViews[0].source, "tv_gone");
    const again = roundTrip(broken, diagram);
    assert.equal(again.propertyViews[0].source, "tv_gone");
    assert.equal(again.typeViews.length, 3);
});

check("a source with no diagram yields no diagrams", () => {
    assert.deepEqual(parseDiagramSource("###Pure\nClass a::B { name: String[1]; }"), []);
});

check("styling defaults are written only when they differ", () => {
    // Keeps a diagram nobody has restyled terse, which is also what makes the fixture's
    // own text comparable to what we produce.
    const text = serializeDiagram(load(FIXTURE)[0]);
    const from = text.indexOf("GeneralizationView");
    const generalization = text.substring(from, text.indexOf(")", text.indexOf("points=", from)));
    assert.ok(!generalization.includes("color="));
    assert.ok(!generalization.includes("lineWidth="));
    assert.ok(text.includes("color=#FF0000"));
    assert.ok(text.includes("lineWidth=2.0"));
    assert.ok(text.includes("label=employs"));
});

check("keeps the diagram's declared dimension", () => {
    // The bootstrap IDE parsed this clause and dropped it, so the first drag deleted it
    // from the user's file.
    const [diagram] = load(FIXTURE);
    assert.deepEqual(diagram.dimension, { width: 1000, height: 600 });
    const after = replaceDiagramInSource(FIXTURE, diagram);
    assert.ok(after.includes("Diagram model::PersonDiagram(width=1000.0, height=600.0)"));
    assert.deepEqual(roundTrip(FIXTURE, diagram).dimension, { width: 1000, height: 600 });
});

check("a diagram declared without a dimension does not gain one", () => {
    const plain = FIXTURE.replace("(width=1000.0, height=600.0)", "");
    const [diagram] = load(plain);
    assert.equal(diagram.dimension, undefined);
    assert.ok(!serializeDiagram(diagram).split("\n")[0].includes("("));
});

// --- renderer geometry (no DOM: these are the pure parts the canvas and the hit-testing
// in interaction.js both depend on) --------------------------------------------------

check("clips a line to the box border it points into", () => {
    const tv = { position: { x: 100, y: 100 }, width: 200, height: 80 };
    // Straight out of the right edge.
    assert.deepEqual(lineBoxIntersection({ x: 200, y: 140 }, { x: 500, y: 140 }, tv), { x: 300, y: 140 });
    // Straight up out of the top edge.
    assert.deepEqual(lineBoxIntersection({ x: 200, y: 140 }, { x: 200, y: 0 }, tv), { x: 200, y: 100 });
});

check("a zero-length line clips to itself rather than dividing by zero", () => {
    const tv = { position: { x: 0, y: 0 }, width: 10, height: 10 };
    assert.deepEqual(lineBoxIntersection({ x: 5, y: 5 }, { x: 5, y: 5 }, tv), { x: 5, y: 5 });
});

check("drops waypoints that a box has swallowed", () => {
    // A bend left inside a box would kink the drawn line back on itself.
    const [diagram] = load(FIXTURE);
    const pv = diagram.propertyViews[0];
    pv.path = [{ x: 150, y: 140 }, { x: 400, y: 200 }];   // the first is inside tv_person
    const drawn = drawnPath(pv, typeViewsById(diagram));
    assert.deepEqual(drawn.waypoints, [{ x: 400, y: 200 }]);
});

check("an edge to a missing box has nothing to draw", () => {
    const [diagram] = load(FIXTURE);
    diagram.propertyViews[0].target = "tv_gone";
    assert.equal(drawnPath(diagram.propertyViews[0], typeViewsById(diagram)), null);
});

check("a view colour tints the theme rather than replacing it", () => {
    // The colours in a diagram were picked against a white canvas; used raw they are
    // unreadable on a dark page.
    assert.equal(blendColor("#FFFFFF", "#000000"), "rgb(38, 38, 38)");
    assert.equal(blendColor("#000000", "#ffffff"), "rgb(217, 217, 217)");
    // Anything that is not a plain hex colour leaves the base alone.
    assert.equal(blendColor(undefined, "#123456"), "#123456");
    assert.equal(blendColor("none", "#123456"), "#123456");
});

// --- adding a class to a diagram ------------------------------------------------------

check("adds a class to an existing diagram", () => {
    const { source, added } = addTypeToSource(FIXTURE, "model::Address", load);
    assert.equal(added, true);
    const diagram = load(source)[0];
    assert.equal(diagram.typeViews.length, 4);
    assert.equal(diagram.typeViews[3].type, "model::Address");
    // The boxes that were already there are untouched.
    assert.deepEqual(diagram.typeViews[0].position, { x: 100, y: 100 });
});

check("a class can be added more than once, each view with its own id", () => {
    // Showing a class twice is how you avoid dragging one edge across the whole canvas.
    const once = addTypeToSource(FIXTURE, "model::Address", load);
    const twice = addTypeToSource(once.source, "model::Address", load);
    assert.equal(twice.added, true);
    const views = load(twice.source)[0].typeViews.filter((tv) => tv.type === "model::Address");
    assert.equal(views.length, 2);
    assert.notEqual(views[0].id, views[1].id);
});

check("a second view of a class does not disturb the first", () => {
    const once = addTypeToSource(FIXTURE, "model::Address", load, null, { x: 100, y: 100 });
    const twice = addTypeToSource(once.source, "model::Address", load, null, { x: 500, y: 400 });
    const views = load(twice.source)[0].typeViews.filter((tv) => tv.type === "model::Address");
    assert.deepEqual(views[0].position, { x: 100 - BOX_WIDTH / 2, y: 100 - BOX_HEIGHT / 2 });
    assert.deepEqual(views[1].position, { x: 500 - BOX_WIDTH / 2, y: 400 - BOX_HEIGHT / 2 });
});

check("a unique view id is derived, not collided", () => {
    assert.equal(uniqueTypeViewId("a::B", []), "tv_a_B");
    assert.equal(uniqueTypeViewId("a::B", ["tv_a_B"]), "tv_a_B_2");
    assert.equal(uniqueTypeViewId("a::B", ["tv_a_B", "tv_a_B_2"]), "tv_a_B_3");
});

check("creates the ###Diagram section when the source has none", () => {
    // The common case: the user opens a plain .pure file and adds their first class.
    const plain = "###Pure\nClass a::B\n{\n   name : String[1];\n}\n";
    const { source, added } = addTypeToSource(plain, "a::B", load);
    assert.equal(added, true);
    assert.ok(source.startsWith(plain), "the original source is kept verbatim at the front");
    assert.ok(source.includes("###Diagram"));
    const diagram = load(source)[0];
    assert.equal(diagram.typeViews.length, 1);
    assert.equal(diagram.typeViews[0].type, "a::B");
    assert.ok(source.endsWith("\n"), "the file still ends with a newline");
});

check("a section created from scratch accepts a second class", () => {
    // Proves the offsets appendDiagramSection leaves behind are usable, not just the text.
    const plain = "###Pure\nClass a::B {}\nClass a::C {}\n";
    const once = addTypeToSource(plain, "a::B", load).source;
    const twice = addTypeToSource(once, "a::C", load).source;
    const diagram = load(twice)[0];
    assert.deepEqual(diagram.typeViews.map((tv) => tv.type), ["a::B", "a::C"]);
    assert.equal(twice.split("###Diagram").length - 1, 1, "only one diagram section");
});

check("new boxes are laid out without overlapping", () => {
    const boxes = Array.from({ length: 6 }, (_, i) => newTypeView(`a::C${i}`, i));
    const seen = new Set(boxes.map((b) => `${b.position.x},${b.position.y}`));
    assert.equal(seen.size, boxes.length);
    // The row wraps rather than running off to the right forever.
    assert.equal(boxes[4].position.x, boxes[0].position.x);
    assert.ok(boxes[4].position.y > boxes[0].position.y);
});

check("a type view id survives the grammar's identifier rules", () => {
    assert.equal(typeViewId("model::my::Person"), "tv_model_my_Person");
    const { source } = addTypeToSource(FIXTURE, "model::deep::nest::Thing", load);
    // The id it generated parses back out, which is the only thing that matters.
    assert.ok(load(source)[0].typeViews.some((tv) => tv.type === "model::deep::nest::Thing"));
});

// --- reading class definitions out of the graph ---------------------------------------

check("formats multiplicities the way Pure writes them", () => {
    const m = (lower, upper) => ({ lowerBound: { value: lower }, upperBound: upper === undefined ? undefined : { value: upper } });
    assert.equal(multiplicityText(m(1, 1)), "1");
    assert.equal(multiplicityText(m(0, 1)), "0..1");
    assert.equal(multiplicityText(m(1, 3)), "1..3");
    assert.equal(multiplicityText(m(0, undefined)), "*");
    assert.equal(multiplicityText(m(1, undefined)), "1..*");
    // Pure Integers arrive as BigInt.
    assert.equal(multiplicityText({ lowerBound: { value: 0n }, upperBound: { value: 1n } }), "0..1");
    assert.equal(multiplicityText(undefined), "*");
});

check("builds a qualified name from a package chain, and trusts a pointer's own path", () => {
    assert.equal(qualifiedName({ __purePath: "meta::pure::x::Y" }), "meta::pure::x::Y");
    assert.equal(qualifiedName({ name: "Person", package: { name: "m", package: { name: "Root" } } }), "m::Person");
    assert.equal(qualifiedName(undefined), "");
    // Values cross from Pure as one-element arrays just as often as bare values.
    assert.equal(qualifiedName({ name: ["Firm"], package: [{ name: ["m"] }] }), "m::Firm");
    // A package that knows its own full path ends the walk in one step.
    assert.equal(qualifiedName({ name: "Firm", package: { __purePath: "m::deep" } }), "m::deep::Firm");
});

check("a name that cannot be read yields nothing rather than throwing", () => {
    // Values in the graph may be unresolved POINTERS, which answer only a few whitelisted
    // slots and THROW on the rest. That throw escaping once aborted "add to diagram"
    // entirely, leaving the class off the diagram with only a console error to show for it.
    const pointer = new Proxy({}, { get(_, key) {
        if (key === "name") return "Firm";
        if (key === "package") return new Proxy({}, { get() { throw new Error("Unresolved pointer access"); } });
        return undefined;
    } });
    assert.equal(qualifiedName(pointer), "Firm");
    const hostile = new Proxy({}, { get() { throw new Error("Unresolved pointer access"); } });
    assert.equal(qualifiedName(hostile), "");
    assert.equal(multiplicityText(hostile), "*");
});

// --- drawing the edges the model implies ----------------------------------------------

const DEFS = {
    "m::Person":      { properties: [{ name: "firm", type: "m::Firm", multiplicity: "1" },
                                     { name: "name", type: "String", multiplicity: "1" }],
                        generalizations: [], associations: [] },
    "m::Firm":        { properties: [], generalizations: ["m::LegalEntity"], associations: [] },
    "m::LegalEntity": { properties: [], generalizations: [], associations: [] },
};

// An association gives BOTH classes a property, so the graph reports it from each end.
const ASSOC = {
    "m::Person": { properties: [], generalizations: [],
                   associations: [{ property: "employer", type: "m::Firm", association: "m::Employment" }] },
    "m::Firm":   { properties: [], generalizations: [],
                   associations: [{ property: "employees", type: "m::Person", association: "m::Employment" }] },
};
const boxes = (...types) => ({
    typeViews: types.map((t, i) => ({ ...newTypeView(t, i) })),
    generalizationViews: [], associationViews: [], propertyViews: [],
});

check("connects a property whose type is also on the diagram", () => {
    const diagram = boxes("m::Person", "m::Firm");
    assert.equal(autoConnect(diagram, DEFS), 1);
    const [pv] = diagram.propertyViews;
    assert.equal(pv.property, "m::Person.firm");
    assert.equal(pv.source, typeViewId("m::Person"));
    assert.equal(pv.target, typeViewId("m::Firm"));
});

check("leaves primitives and absent classes unconnected", () => {
    // `name : String` is shown inside the box, and there is no m::Firm box to point at.
    const diagram = boxes("m::Person");
    assert.equal(autoConnect(diagram, DEFS), 0);
    assert.equal(diagram.propertyViews.length, 0);
});

check("connects a generalization between two shown classes", () => {
    const diagram = boxes("m::Firm", "m::LegalEntity");
    assert.equal(autoConnect(diagram, DEFS), 1);
    assert.equal(diagram.generalizationViews[0].source, typeViewId("m::Firm"));
    assert.equal(diagram.generalizationViews[0].target, typeViewId("m::LegalEntity"));
});

check("connecting twice adds nothing the second time", () => {
    const diagram = boxes("m::Person", "m::Firm", "m::LegalEntity");
    assert.equal(autoConnect(diagram, DEFS), 2);
    assert.equal(autoConnect(diagram, DEFS), 0);
    assert.equal(diagram.propertyViews.length + diagram.generalizationViews.length, 2);
});

check("a generated edge serializes and parses back", () => {
    // A new edge has no points until they are rebuilt from the box centres at write time.
    const source = addTypeToSource(addTypeToSource(FIXTURE, "m::Person", load, DEFS).source,
                                   "m::Firm", load, DEFS).source;
    const diagram = load(source)[0];
    const pv = diagram.propertyViews.find((v) => v.property === "m::Person.firm");
    assert.ok(pv, "the generated property view survived the round trip");
    assert.equal(pv.points.length, 2);
    // Anchored at the two box centres.
    const byId = typeViewsById(diagram);
    assert.deepEqual(pv.points[0], { x: byId[pv.source].position.x + 100, y: byId[pv.source].position.y + 45 });
});

check("adding a class does not disturb the diagram's existing edges", () => {
    const before = load(FIXTURE)[0];
    const after = load(addTypeToSource(FIXTURE, "m::Person", load, DEFS).source)[0];
    assert.equal(after.generalizationViews.length, before.generalizationViews.length);
    assert.equal(after.associationViews.length, before.associationViews.length);
    assert.deepEqual(after.associationViews[0].points, before.associationViews[0].points);
});

// --- clean-up and resize ---------------------------------------------------------------

check("removes views whose class is gone, and the edges that hung off them", () => {
    const diagram = load(FIXTURE)[0];
    const gone = new Set(["model::Firm"]);
    const removed = cleanUpDeadReferences(diagram, (type) => !gone.has(type));
    assert.deepEqual(removed, { removedTypeViews: 1, removedEdges: 2 });
    assert.deepEqual(diagram.typeViews.map((tv) => tv.type), ["model::Person", "model::LegalEntity"]);
    // The association between the two surviving boxes is untouched.
    assert.equal(diagram.associationViews.length, 1);
    assert.equal(diagram.generalizationViews.length, 0);
    assert.equal(diagram.propertyViews.length, 0);
});

check("clean-up removes nothing when every class is still there", () => {
    const diagram = load(FIXTURE)[0];
    assert.deepEqual(cleanUpDeadReferences(diagram, () => true), { removedTypeViews: 0, removedEdges: 0 });
    assert.equal(diagram.typeViews.length, 3);
});

check("a cleaned diagram still round-trips", () => {
    const diagram = load(FIXTURE)[0];
    cleanUpDeadReferences(diagram, (type) => type !== "model::Firm");
    const again = roundTrip(FIXTURE, diagram);
    assert.deepEqual(again.typeViews.map((tv) => tv.type), ["model::Person", "model::LegalEntity"]);
    assert.equal(again.generalizationViews.length, 0);
});

check("the resize grip sits in the box's bottom-right corner", () => {
    const tv = { position: { x: 100, y: 100 }, width: 200, height: 80 };
    const grip = resizeHandleOf(tv);
    // Inside the box, at its far corner.
    assert.ok(pointInsideBox({ x: grip.position.x + 1, y: grip.position.y + 1 }, tv));
    assert.equal(grip.position.x + grip.width, tv.position.x + tv.width);
    assert.equal(grip.position.y + grip.height, tv.position.y + tv.height);
    // A press in the middle of the box is NOT on the grip.
    assert.ok(!pointInsideBox({ x: 200, y: 140 }, grip));
});

// --- what an empty canvas says --------------------------------------------------------

check("a hand-written but empty diagram is not reported as missing", () => {
    // Writing `###Diagram / Diagram a::D { }` by hand and being told "No diagram in this
    // source" reads as the feature being broken. Name the diagram and say what to do.
    const lines = placeholderLines({ name: "a::Diagra", typeViews: [] });
    assert.ok(lines);
    assert.ok(lines[0].includes("a::Diagra"), lines[0]);
    assert.ok(!lines.join(" ").includes("No ###Diagram section"));
    assert.ok(lines[1].includes("Add to diagram"));
});

check("a source with no diagram section says exactly that", () => {
    const lines = placeholderLines(null);
    assert.ok(lines[0].includes("No ###Diagram section"));
    assert.ok(lines[1].includes("Add to diagram"));
});

check("a diagram with boxes has nothing to say", () => {
    assert.equal(placeholderLines(load(FIXTURE)[0]), null);
});

check("an empty hand-written diagram keeps its name when a class is added", () => {
    // The user's own diagram name and section must survive; a second ###Diagram section
    // would not compile the way they expect.
    const hand = "###Pure\nClass a::B {}\n\n###Diagram\nDiagram a::Diagra\n{\n  \n}\n";
    const { source, added } = addTypeToSource(hand, "a::B", load);
    assert.equal(added, true);
    const diagram = load(source)[0];
    assert.equal(diagram.name, "a::Diagra");
    assert.equal(diagram.typeViews.length, 1);
    assert.equal(source.split("###Diagram").length - 1, 1);
});

check("an empty diagram round-trips without gaining anything", () => {
    const hand = "###Diagram\nDiagram a::Diagra\n{\n  \n}\n";
    const [diagram] = load(hand);
    assert.equal(diagram.typeViews.length, 0);
    const again = load(replaceDiagramInSource(hand, diagram))[0];
    assert.equal(again.name, "a::Diagra");
    assert.equal(again.typeViews.length, 0);
});

// --- more than one diagram in a source ------------------------------------------------

const TWO = `###Pure
Class a::B {}

###Diagram
Diagram a::First
{
   TypeView tv1
   (
      type=a::B,
      position=(10.0, 10.0),
      width=100.0,
      height=50.0
   )
}
Diagram a::Second
{
   TypeView tv2
   (
      type=a::B,
      position=(300.0, 10.0),
      width=100.0,
      height=50.0
   )
}
`;

check("finds every diagram in a section, in order", () => {
    // The concept tree lists them all, so they have to be found — and each needs its own
    // splice range or editing one would overwrite the other.
    const all = load(TWO);
    assert.deepEqual(all.map((d) => d.name), ["a::First", "a::Second"]);
    assert.ok(all[0]._diagramEnd <= all[1]._diagramStart, "their ranges do not overlap");
});

check("editing the second diagram leaves the first untouched", () => {
    const all = load(TWO);
    all[1].typeViews[0].position = { x: 999, y: 888 };
    const updated = replaceDiagramInSource(TWO, all[1]);
    const after = load(updated);
    assert.deepEqual(after.map((d) => d.name), ["a::First", "a::Second"]);
    assert.deepEqual(after[0].typeViews[0].position, { x: 10, y: 10 });
    assert.deepEqual(after[1].typeViews[0].position, { x: 999, y: 888 });
});

check("editing the first diagram leaves the second untouched", () => {
    // The riskier direction: the first diagram's block changes length, so the second's
    // stored offsets are stale — which is why an edit reparses rather than reusing them.
    const all = load(TWO);
    all[0].typeViews[0].position = { x: 111, y: 222 };
    const after = load(replaceDiagramInSource(TWO, all[0]));
    assert.deepEqual(after.map((d) => d.name), ["a::First", "a::Second"]);
    assert.deepEqual(after[0].typeViews[0].position, { x: 111, y: 222 });
    assert.deepEqual(after[1].typeViews[0].position, { x: 300, y: 10 });
});

// --- dropping a class at a point -------------------------------------------------------

check("a dropped class is centred on where it was dropped", () => {
    // The pointer is at the drop point, so the box should appear under it rather than
    // hanging below and to the right of it.
    const tv = newTypeView("a::B", 0, { x: 500, y: 300 });
    assert.deepEqual(tv.position, { x: 500 - BOX_WIDTH / 2, y: 300 - BOX_HEIGHT / 2 });
    assert.equal(tv.position.x + tv.width / 2, 500);
    assert.equal(tv.position.y + tv.height / 2, 300);
});

check("without a drop point a class still goes in the next free slot", () => {
    assert.deepEqual(newTypeView("a::B", 0).position, newTypeView("a::B", 0, null).position);
    assert.notDeepEqual(newTypeView("a::B", 0).position, newTypeView("a::C", 1).position);
});

check("a drop writes the position through to the source", () => {
    const { source, added } = addTypeToSource(FIXTURE, "model::Address", load, null, { x: 640, y: 480 });
    assert.equal(added, true);
    const tv = load(source)[0].typeViews.find((v) => v.type === "model::Address");
    assert.deepEqual(tv.position, { x: 640 - BOX_WIDTH / 2, y: 480 - BOX_HEIGHT / 2 });
});

check("dropping a class already shown adds a second view where it was dropped", () => {
    const { source, added } = addTypeToSource(FIXTURE, "model::Person", load, null, { x: 640, y: 480 });
    assert.equal(added, true);
    const views = load(source)[0].typeViews.filter((tv) => tv.type === "model::Person");
    assert.equal(views.length, 2);
    // The box that was already there did not move.
    assert.deepEqual(views[0].position, { x: 100, y: 100 });
    assert.deepEqual(views[1].position, { x: 640 - BOX_WIDTH / 2, y: 480 - BOX_HEIGHT / 2 });
});

check("a drop onto a source with no diagram creates the section at that point", () => {
    const plain = "###Pure\nClass a::B {}\n";
    const { source } = addTypeToSource(plain, "a::B", load, null, { x: 300, y: 200 });
    const tv = load(source)[0].typeViews[0];
    assert.deepEqual(tv.position, { x: 300 - BOX_WIDTH / 2, y: 200 - BOX_HEIGHT / 2 });
});

// --- how small a box may be made ------------------------------------------------------

/**
 * A stand-in for a canvas context: one unit of width per character, which is enough to
 * check that the measurement is driven by the text, without needing a real canvas.
 */
const fakeCtx = (perChar = 7) => ({
    font: "",
    save() {}, restore() {},
    measureText: (text) => ({ width: text.length * perChar }),
});

const DEF_WIDE = {
    "m::Person": { properties: [
        { name: "firstName", type: "String", multiplicity: "1" },
        { name: "aVeryLongPropertyNameIndeed", type: "meta::pure::SomeLongType", multiplicity: "0..1" },
    ], generalizations: [] },
};
const emptyDiagram = () => ({ typeViews: [], generalizationViews: [], associationViews: [], propertyViews: [] });

check("a box cannot be shrunk below its own class name", () => {
    const tv = { type: "m::AClassWithARatherLongName", position: { x: 0, y: 0 }, width: 200, height: 90 };
    const min = minimumBoxSize(fakeCtx(), tv, emptyDiagram(), {});
    // The header centres the name, so it needs room for the name plus padding on both sides.
    assert.ok(min.width > "AClassWithARatherLongName".length * 7, `${min.width}`);
});

check("a box cannot be shrunk below its property rows", () => {
    const tv = { type: "m::Person", position: { x: 0, y: 0 }, width: 400, height: 200 };
    const min = minimumBoxSize(fakeCtx(), tv, emptyDiagram(), DEF_WIDE);
    const longest = "aVeryLongPropertyNameIndeed : SomeLongType[0..1]".length * 7;
    assert.ok(min.width >= longest, `${min.width} should fit ${longest}`);
    // Two rows, plus the header and its padding.
    assert.ok(min.height >= 32 + 2 * 16, `${min.height}`);
});

check("more properties means a taller minimum", () => {
    const one = { "m::A": { properties: [{ name: "a", type: "String", multiplicity: "1" }], generalizations: [] } };
    const many = { "m::A": { properties: Array.from({ length: 8 },
        (_, i) => ({ name: `p${i}`, type: "String", multiplicity: "1" })), generalizations: [] } };
    const tv = { type: "m::A", position: { x: 0, y: 0 }, width: 200, height: 90 };
    assert.ok(minimumBoxSize(fakeCtx(), tv, emptyDiagram(), many).height
              > minimumBoxSize(fakeCtx(), tv, emptyDiagram(), one).height);
});

check("a property shown as an edge does not force the box wider", () => {
    // It is drawn on the line, not inside the box, so it must not count towards the minimum.
    const defs = { "m::Person": { properties: [
        { name: "firm", type: "m::AnExtremelyLongClassNameHere", multiplicity: "1" },
    ], generalizations: [] } };
    const tv = { id: "tv_p", type: "m::Person", position: { x: 0, y: 0 }, width: 200, height: 90 };
    const bare = { ...emptyDiagram(), typeViews: [tv] };
    const withEdge = { ...emptyDiagram(), typeViews: [tv],
                       propertyViews: [{ id: "pv", source: "tv_p", target: "tv_f", property: "m::Person.firm" }] };
    assert.ok(minimumBoxSize(fakeCtx(), tv, withEdge, defs).width
              < minimumBoxSize(fakeCtx(), tv, bare, defs).width);
});

check("a class with no readable definition still has a usable floor", () => {
    const tv = { type: "m::X", position: { x: 0, y: 0 }, width: 200, height: 90 };
    const min = minimumBoxSize(fakeCtx(), tv, emptyDiagram(), {});
    assert.ok(min.width >= 80, `${min.width}`);
    // Tall enough that the bottom-right resize grip is still grabbable.
    assert.ok(min.height >= 32 + 10, `${min.height}`);
});

check("draws an association between two shown classes", () => {
    const diagram = boxes("m::Person", "m::Firm");
    assert.equal(autoConnect(diagram, ASSOC), 1);
    const [av] = diagram.associationViews;
    assert.equal(av.association, "m::Employment");
    assert.deepEqual([av.source, av.target].sort(), [typeViewId("m::Firm"), typeViewId("m::Person")].sort());
});

check("draws ONE edge for an association, not one per end", () => {
    // Reached from both classes; without deduping the diagram would gain two parallel edges
    // for a single relationship.
    const diagram = boxes("m::Person", "m::Firm");
    autoConnect(diagram, ASSOC);
    assert.equal(diagram.associationViews.length, 1);
    assert.equal(autoConnect(diagram, ASSOC), 0, "and running again adds nothing");
    assert.equal(diagram.associationViews.length, 1);
});

check("leaves an association alone when the other end is not shown", () => {
    const diagram = boxes("m::Person");
    assert.equal(autoConnect(diagram, ASSOC), 0);
    assert.equal(diagram.associationViews.length, 0);
});

check("does not duplicate an association already written in the source", () => {
    const diagram = boxes("m::Person", "m::Firm");
    diagram.associationViews.push({ id: "av_existing", association: "m::Employment",
                                    source: typeViewId("m::Firm"), target: typeViewId("m::Person"),
                                    points: [], path: [] });
    // Declared the other way round, and still the same relationship.
    assert.equal(autoConnect(diagram, ASSOC), 0);
    assert.equal(diagram.associationViews.length, 1);
});

check("a generated association survives a round trip", () => {
    const source = addTypeToSource(addTypeToSource(FIXTURE, "m::Person", load, ASSOC).source,
                                   "m::Firm", load, ASSOC).source;
    const diagram = load(source)[0];
    const av = diagram.associationViews.find((v) => v.association === "m::Employment");
    assert.ok(av, "the generated association view survived serialization");
    assert.equal(av.points.length, 2);
    // And the fixture's own association is untouched.
    assert.ok(diagram.associationViews.some((v) => v.association === "model::Person_LegalEntity"));
});

// --- deleting a view -------------------------------------------------------------------

check("deleting a class takes its edges with it", () => {
    // An edge with one end missing is not a diagram, it is a broken file.
    const diagram = load(FIXTURE)[0];
    const firm = diagram.typeViews.find((tv) => tv.type === "model::Firm");
    const removed = removeView(diagram, firm.id);
    assert.equal(removed, 3, "the box plus its generalization and property views");
    assert.ok(!diagram.typeViews.some((tv) => tv.id === firm.id));
    for (const kind of ["generalizationViews", "associationViews", "propertyViews"]) {
        assert.ok(!diagram[kind].some((v) => v.source === firm.id || v.target === firm.id), kind);
    }
});

check("deleting an edge leaves the classes alone", () => {
    const diagram = load(FIXTURE)[0];
    assert.equal(removeView(diagram, diagram.propertyViews[0].id), 1);
    assert.equal(diagram.typeViews.length, 3);
    assert.equal(diagram.propertyViews.length, 0);
});

check("deleting something that is not there removes nothing", () => {
    const diagram = load(FIXTURE)[0];
    assert.equal(removeView(diagram, "tv_nope"), 0);
    assert.equal(diagram.typeViews.length, 3);
});

check("deleting one view of a duplicated class keeps the other", () => {
    const source = addTypeToSource(FIXTURE, "model::Person", load).source;
    const diagram = load(source)[0];
    const views = diagram.typeViews.filter((tv) => tv.type === "model::Person");
    removeView(diagram, views[1].id);
    const left = load(replaceDiagramInSource(source, diagram))[0].typeViews.filter((tv) => tv.type === "model::Person");
    assert.equal(left.length, 1);
    assert.equal(left[0].id, views[0].id);
});

check("a diagram survives a round trip after a delete", () => {
    const diagram = load(FIXTURE)[0];
    removeView(diagram, diagram.typeViews.find((tv) => tv.type === "model::Firm").id);
    const again = load(replaceDiagramInSource(FIXTURE, diagram))[0];
    assert.deepEqual(again.typeViews.map((tv) => tv.type), ["model::Person", "model::LegalEntity"]);
    assert.equal(again.generalizationViews.length, 0);
    assert.equal(again.propertyViews.length, 0);
    assert.equal(again.associationViews.length, 1);
});

// --- duplicated classes and their edges --------------------------------------------------

check("each view of a duplicated class gets its own edges", () => {
    // A second box with no edges would look broken.
    const diagram = boxes("m::Person", "m::Firm");
    diagram.typeViews.push({ ...newTypeView("m::Firm", 2, null, diagram.typeViews.map((tv) => tv.id)) });
    assert.equal(autoConnect(diagram, DEFS), 2, "one edge from Person to each Firm view");
    assert.equal(new Set(diagram.propertyViews.map((v) => v.target)).size, 2);
    assert.equal(autoConnect(diagram, DEFS), 0, "and it stays idempotent");
});

// --- association ends: roles, multiplicities, aggregation -------------------------------

const BY_ID = { tv_p: { type: "m::Person" }, tv_f: { type: "m::Firm" } };
const AV = { id: "av", source: "tv_p", target: "tv_f", association: "m::Employment" };
const ROLES = {
    "m::Person": { properties: [], generalizations: [],
                   associations: [{ property: "employer", type: "m::Firm", multiplicity: "1",
                                    association: "m::Employment", aggregation: "None" }] },
    "m::Firm":   { properties: [], generalizations: [],
                   associations: [{ property: "employees", type: "m::Person", multiplicity: "*",
                                    association: "m::Employment", aggregation: "None" }] },
};

check("an association end is labelled with the role you reach there", () => {
    // Person navigates `employer` to Firm, so `employer` belongs at the FIRM end. Getting
    // this backwards silently draws the opposite relationship. (legend-studio's
    // drawPropertyOrAssociation labels its `to` end with the property, the same way.)
    const labels = associationEndLabels(AV, BY_ID, ROLES);
    assert.deepEqual(labels.target, { name: "employer", multiplicity: "[1]" });
    assert.deepEqual(labels.source, { name: "employees", multiplicity: "[*]" });
});

check("the name and multiplicity are kept apart, to be drawn either side of the line", () => {
    // They are not one string: Studio straddles the line with them, so they need separate
    // positions.
    const { target } = associationEndLabels(AV, BY_ID, ROLES);
    assert.equal(typeof target.name, "string");
    assert.equal(typeof target.multiplicity, "string");
});

check("an end with nothing readable is simply unlabelled", () => {
    assert.deepEqual(associationEndLabels(AV, BY_ID, {}), { source: null, target: null });
    assert.deepEqual(associationEndLabels(AV, {}, ROLES), { source: null, target: null });
});

check("no aggregation means no diamond", () => {
    assert.deepEqual(aggregationEnds(AV, BY_ID, "association", ROLES), { source: "None", target: "None" });
});

check("the diamond goes at the end of the class that declares it", () => {
    // `(composite) parts: m::Wheel[*]` puts `parts` on Car, so CAR is the whole.
    const byId = { tv_car: { type: "m::Car" }, tv_w: { type: "m::Wheel" } };
    const view = { source: "tv_car", target: "tv_w", association: "m::Owns" };
    const defs = {
        "m::Car":   { associations: [{ property: "parts", type: "m::Wheel", multiplicity: "*",
                                       association: "m::Owns", aggregation: "Composite" }] },
        "m::Wheel": { associations: [{ property: "owner", type: "m::Car", multiplicity: "1",
                                       association: "m::Owns", aggregation: "None" }] },
    };
    assert.deepEqual(aggregationEnds(view, byId, "association", defs), { source: "Composite", target: "None" });
    // And from the other side the diamond moves with it, rather than staying put.
    const flipped = { source: "tv_w", target: "tv_car", association: "m::Owns" };
    assert.deepEqual(aggregationEnds(flipped, byId, "association", defs), { source: "None", target: "Composite" });
});

check("shared and composite are distinguished", () => {
    const byId = { tv_c: { type: "m::Car" }, tv_w: { type: "m::Wheel" } };
    const defs = { "m::Car": { properties: [
        { name: "wheels", type: "m::Wheel", multiplicity: "4", aggregation: "Shared" },
        { name: "engine", type: "m::Wheel", multiplicity: "1", aggregation: "Composite" },
        { name: "plain", type: "m::Wheel", multiplicity: "1", aggregation: "None" },
    ] } };
    const pv = (name) => ({ source: "tv_c", target: "tv_w", property: `m::Car.${name}` });
    assert.equal(aggregationEnds(pv("wheels"), byId, "property", defs).source, "Shared");
    assert.equal(aggregationEnds(pv("engine"), byId, "property", defs).source, "Composite");
    assert.equal(aggregationEnds(pv("plain"), byId, "property", defs).source, "None");
    // A property is owned by its source, so the target end never carries the marker.
    assert.equal(aggregationEnds(pv("engine"), byId, "property", defs).target, "None");
});

check("a generalization never carries an aggregation marker", () => {
    assert.deepEqual(aggregationEnds(AV, BY_ID, "generalization", ROLES), { source: "None", target: "None" });
});

// --- bends dragged flat ------------------------------------------------------------------

/** Two boxes on a horizontal line, with `waypoints` bending the edge between them. */
const bent = (waypoints) => {
    const diagram = {
        typeViews: [
            { id: "a", type: "m::A", position: { x: 0, y: 0 }, width: 100, height: 100 },
            { id: "b", type: "m::B", position: { x: 400, y: 0 }, width: 100, height: 100 },
        ],
        generalizationViews: [], associationViews: [],
        propertyViews: [{ id: "pv", source: "a", target: "b", path: waypoints,
                          sourceOffset: { x: 0, y: 0 }, targetOffset: { x: 0, y: 0 }, points: [] }],
    };
    return [diagram, diagram.propertyViews[0], typeViewsById(diagram)];
};

check("three aligned points drop the middle one", () => {
    // Endpoints are both at y=50, so a waypoint on that line is the middle of three.
    const [, view, byId] = bent([{ x: 250, y: 50 }]);
    assert.equal(pruneCollinear(view, byId), 1);
    assert.deepEqual(view.path, []);
});

check("a real bend is kept", () => {
    const [, view, byId] = bent([{ x: 250, y: 180 }]);
    assert.equal(pruneCollinear(view, byId), 0);
    assert.equal(view.path.length, 1);
});

check("a bend just off the line survives, one on it does not", () => {
    // The tolerance decides, and it is the caller's — a screen distance over the zoom.
    const [, nearly, byId1] = bent([{ x: 250, y: 54 }]);
    assert.equal(pruneCollinear(nearly, byId1, 2), 0);
    const [, flat, byId2] = bent([{ x: 250, y: 51 }]);
    assert.equal(pruneCollinear(flat, byId2, 2), 1);
});

check("a run of aligned points collapses completely", () => {
    const [, view, byId] = bent([{ x: 180, y: 50 }, { x: 250, y: 50 }, { x: 320, y: 50 }]);
    assert.equal(pruneCollinear(view, byId), 3);
    assert.deepEqual(view.path, []);
});

check("only the flat one goes, its neighbours stay", () => {
    // The three waypoints share y=200, so the MIDDLE one lies on the line between the other
    // two and goes; those two are real corners against the endpoints at y=50 and stay.
    const [, view, byId] = bent([{ x: 200, y: 200 }, { x: 250, y: 200 }, { x: 300, y: 200 }]);
    assert.equal(pruneCollinear(view, byId), 1);
    assert.deepEqual(view.path, [{ x: 200, y: 200 }, { x: 300, y: 200 }]);
});

check("a corner is judged against its own neighbours, not the endpoints", () => {
    // (180,50) sits on the line from the source endpoint, but its NEXT neighbour is a bend,
    // so the three are not aligned and it stays.
    const [, view, byId] = bent([{ x: 180, y: 50 }, { x: 250, y: 200 }, { x: 320, y: 50 }]);
    assert.equal(pruneCollinear(view, byId), 0);
    assert.equal(view.path.length, 3);
});

check("an edge with no bends is left alone", () => {
    const [, view, byId] = bent([]);
    assert.equal(pruneCollinear(view, byId), 0);
});

// --- text is drawn on top, once ----------------------------------------------------------

/** A canvas context that records the order of what was drawn. */
function recordingCtx() {
    const calls = [];
    const ctx = {
        canvas: { width: 800, height: 600 },
        font: "", fillStyle: "", strokeStyle: "", lineWidth: 1, textAlign: "", textBaseline: "",
        globalAlpha: 1,
        save() {}, restore() {}, beginPath() {}, closePath() {}, moveTo() {}, lineTo() {},
        arc() {}, arcTo() {}, stroke() {}, fill() {}, clip() {}, rect() {},
        translate() {}, rotate() {}, scale() {}, setTransform() {},
        measureText: (t) => ({ width: String(t).length * 7 }),
        fillRect: () => calls.push("rect"),
        fillText: (t) => calls.push(`text:${t}`),
    };
    return { ctx, calls };
}

const LABELLED = () => {
    const diagram = {
        name: "m::D",
        typeViews: [
            { id: "a", type: "m::Person", position: { x: 0, y: 0 }, width: 150, height: 80 },
            { id: "b", type: "m::Firm", position: { x: 400, y: 0 }, width: 150, height: 80 },
        ],
        generalizationViews: [], propertyViews: [],
        associationViews: [{ id: "av", source: "a", target: "b", association: "m::Employment",
                             path: [], sourceOffset: { x: 0, y: 0 }, targetOffset: { x: 0, y: 0 },
                             points: [] }],
    };
    return diagram;
};
/** Explicit colours: themeColors() reads CSS custom properties and needs a document. */
const LABEL_COLORS = { bg: "#15151a", panel: "#1c1c22", rule: "#2e2e37", ink: "#e6e6ea",
                       muted: "#9a9aa6", accent: "#A98BFF", className: "#7fb2ff", hollow: "#15151a" };

const LABEL_DEFS = {
    "m::Person": { properties: [{ name: "firstName", type: "String", multiplicity: "1" }],
                   generalizations: [],
                   associations: [{ property: "employer", type: "m::Firm", multiplicity: "1",
                                    association: "m::Employment", aggregation: "None" }] },
    "m::Firm": { properties: [], generalizations: [],
                 associations: [{ property: "employees", type: "m::Person", multiplicity: "*",
                                  association: "m::Employment", aggregation: "None" }] },
};

check("every label is painted after every box", () => {
    // Boxes are drawn after edges so they cover the line beneath them — which covered the
    // labels too, until the text got its own final pass.
    const { ctx, calls } = recordingCtx();
    drawDiagram({ getContext: () => ctx, width: 800, height: 600 }, LABELLED(), { classDefs: LABEL_DEFS, colors: LABEL_COLORS });
    const lastBoxText = calls.lastIndexOf("text:Person") > calls.lastIndexOf("text:Firm")
        ? calls.lastIndexOf("text:Person") : calls.lastIndexOf("text:Firm");
    const firstLabel = calls.findIndex((c) => c === "text:employer" || c === "text:employees");
    assert.ok(firstLabel > lastBoxText, `labels at ${firstLabel}, last box text at ${lastBoxText}`);
});

check("all label backings are painted before any label text", () => {
    // Otherwise one label's chip erases the previous label's glyphs where they overlap —
    // precisely where legibility matters most.
    const { ctx, calls } = recordingCtx();
    drawDiagram({ getContext: () => ctx, width: 800, height: 600 }, LABELLED(), { classDefs: LABEL_DEFS, colors: LABEL_COLORS });
    const labelTexts = ["text:employer", "text:employees", "text:[1]", "text:[*]"];
    const firstLabelText = Math.min(...labelTexts.map((t) => calls.indexOf(t)).filter((i) => i >= 0));
    const labelRects = calls.map((c, i) => [c, i]).filter(([c, i]) => c === "rect" && i > firstLabelText - 5);
    // No chip is drawn after the first label glyph.
    assert.ok(!calls.slice(firstLabelText).includes("rect"),
              `a backing was drawn after label text began (${labelRects.length} late rects)`);
});

check("both the role and its multiplicity are drawn", () => {
    const { ctx, calls } = recordingCtx();
    drawDiagram({ getContext: () => ctx, width: 800, height: 600 }, LABELLED(), { classDefs: LABEL_DEFS, colors: LABEL_COLORS });
    for (const t of ["text:employer", "text:[1]", "text:employees", "text:[*]"]) {
        assert.ok(calls.includes(t), `${t} missing from ${JSON.stringify(calls.slice(-10))}`);
    }
});

check("placements are measured, not drawn", () => {
    // The measuring function must not touch the canvas — that is what lets the caller
    // collect every label and paint them together at the end.
    const { ctx, calls } = recordingCtx();
    const box = { position: { x: 400, y: 0 }, width: 150, height: 80 };
    const placed = lineEndLabelPlacements(ctx, { x: 100, y: 40 }, { x: 400, y: 40 }, box, "employer", "[1]");
    assert.equal(calls.length, 0, "nothing was drawn");
    assert.equal(placed.length, 2);
    // Name and multiplicity straddle the line: same side of the box, different y.
    assert.notEqual(placed[0].y, placed[1].y);
    assert.ok(placed.every((l) => l.x < box.position.x), "both sit outside the box's left edge");
});

check("the role and its multiplicity never swap sides as the line moves", () => {
    // legend-studio picks the side from which HALF of the box the line enters, so the pair
    // jumps the moment a drag carries the crossing past the midpoint. Deliberately fixed
    // here: name above, multiplicity below — the swap is movement the reader cannot use.
    const { ctx } = recordingCtx();
    const box = { position: { x: 400, y: 0 }, width: 150, height: 80 };
    const sides = new Set();
    for (let y = 2; y < 79; y++) {
        const placed = lineEndLabelPlacements(ctx, { x: 100, y }, { x: 400, y }, box, "employer", "[1]");
        const name = placed.find((l) => l.text === "employer");
        const mul = placed.find((l) => l.text === "[1]");
        sides.add(name.y < mul.y ? "name-above" : "name-below");
    }
    assert.deepEqual([...sides], ["name-above"], "the side changed as the entry point moved");
});

check("the same holds for a line entering the top edge", () => {
    const { ctx } = recordingCtx();
    const box = { position: { x: 0, y: 400 }, width: 150, height: 80 };
    const sides = new Set();
    for (let x = 2; x < 149; x++) {
        const placed = lineEndLabelPlacements(ctx, { x, y: 100 }, { x, y: 400 }, box, "employer", "[1]");
        const name = placed.find((l) => l.text === "employer");
        const mul = placed.find((l) => l.text === "[1]");
        sides.add(name.x < mul.x ? "name-left" : "name-right");
    }
    assert.deepEqual([...sides], ["name-left"]);
});

check("a line that misses the box yields no placement", () => {
    const { ctx } = recordingCtx();
    const box = { position: { x: 400, y: 0 }, width: 150, height: 80 };
    // Passing well below the box.
    assert.deepEqual(lineEndLabelPlacements(ctx, { x: 100, y: 500 }, { x: 400, y: 500 }, box, "x", "[1]"), []);
});

// --- selecting several classes -----------------------------------------------------------

const box = (x, y, w = 100, h = 60) => ({ position: { x, y }, width: w, height: h });

check("a marquee is built from any two corners, dragged in any direction", () => {
    const downRight = rectFrom({ x: 10, y: 20 }, { x: 110, y: 120 });
    const upLeft = rectFrom({ x: 110, y: 120 }, { x: 10, y: 20 });
    assert.deepEqual(downRight, upLeft, "dragging back up gives the same rectangle");
    assert.deepEqual(downRight, { position: { x: 10, y: 20 }, width: 100, height: 100 });
});

check("a marquee selects every box it touches, not only those it swallows", () => {
    const marquee = rectFrom({ x: 50, y: 50 }, { x: 250, y: 150 });
    assert.ok(boxesIntersect(marquee, box(100, 80)), "fully inside");
    assert.ok(boxesIntersect(marquee, box(200, 120)), "overlapping a corner");
    assert.ok(boxesIntersect(marquee, box(0, 0, 400, 400)), "a box that swallows the marquee");
    assert.ok(!boxesIntersect(marquee, box(300, 80)), "clear to the right");
    assert.ok(!boxesIntersect(marquee, box(100, 300)), "clear below");
});

check("boxes that merely touch edges do not count as overlapping", () => {
    // Half-open comparison: a marquee dragged up to a box's edge has not reached it.
    const marquee = rectFrom({ x: 0, y: 0 }, { x: 100, y: 100 });
    assert.ok(!boxesIntersect(marquee, box(100, 0)), "sharing the right edge");
    assert.ok(boxesIntersect(marquee, box(99, 0)), "one unit of real overlap");
});

check("a zero-size marquee selects nothing", () => {
    // A plain click on empty space: the rectangle is empty, so nothing is caught.
    const marquee = rectFrom({ x: 50, y: 50 }, { x: 50, y: 50 });
    assert.ok(!boxesIntersect(marquee, box(0, 0, 200, 200)));
});

check("deleting a multi-selection takes every box and its edges", () => {
    // What the Delete key does with several boxes chosen.
    const diagram = load(FIXTURE)[0];
    const chosen = diagram.typeViews.filter((tv) => tv.type !== "model::Person");
    let removed = 0;
    for (const view of chosen) removed += removeView(diagram, view.id);
    assert.equal(diagram.typeViews.length, 1);
    assert.equal(diagram.typeViews[0].type, "model::Person");
    // Every edge touched one of the two, so none survive.
    assert.equal(diagram.generalizationViews.length + diagram.associationViews.length
                 + diagram.propertyViews.length, 0);
    assert.ok(removed >= 2);
});

const failures = results.filter((r) => r.error);
for (const f of failures) console.log(`  FAIL ${f.name}\n    ${f.error.message}`);
console.log(`diagram: ${results.length - failures.length}/${results.length} passed`);
report.write();
process.exit(failures.length ? 1 : 0);
