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

// class-defs.js — what goes inside a diagram's boxes, read from the compiled graph.
//
// This is the one place the port deliberately does NOT follow the bootstrap IDE. That IDE
// had no graph to consult, so `parseClassDefinitions` scraped `Class x::Y { … }` out of the
// editor text with string scanning — which only ever saw classes in the open file, and only
// while the file happened to parse. Here the compiled graph already holds every class, from
// the editor and from the loaded PDBs alike, with inheritance resolved. So a diagram can
// show a class that lives in core.pdb, and a class the user is midway through editing keeps
// its last good shape instead of flickering empty.
//
// Pure values cross into JavaScript as a value OR a one-element array, and Integers arrive
// as BigInt — hence `one`/`many` and the Number coercion, as elsewhere on this page.

const one = (v) => (Array.isArray(v) ? v[0] : v);
const many = (v) => (v === undefined || v === null ? [] : Array.isArray(v) ? v : [v]);
const num = (v) => { const x = one(v); return x === undefined || x === null ? undefined : Number(x); };

/** Read a slot that may belong to an unresolved pointer, where the read itself throws. */
const slot = (object, key) => { try { return one(object?.[key]); } catch { return undefined; } };

/**
 * `m::Person` for a type, however it reaches us.
 *
 * Total by construction: every read goes through `slot`, because a value in the graph may
 * be an unresolved POINTER, and a pointer answers only the few slots its guard whitelists —
 * reading `name` on a `PackagePointer` throws "Unresolved pointer access". That throw
 * escaping this function once aborted the whole "add to diagram" action, so the rule here
 * is that a name we cannot read yields "" and the caller carries on.
 */
export function qualifiedName(type) {
    if (!type) return "";
    const direct = slot(type, "__purePath") ?? slot(type, "path");
    if (typeof direct === "string" && direct) return direct;
    const name = slot(type, "name");
    if (typeof name !== "string" || !name) return "";

    const parts = [name];
    let pkg = slot(type, "package");
    for (let depth = 0; pkg && depth < 24; depth++) {
        // A package that knows its own full path ends the walk in one step.
        const path = slot(pkg, "__purePath") ?? slot(pkg, "path");
        if (typeof path === "string" && path) { parts.unshift(path); break; }
        const pkgName = slot(pkg, "name");
        // The unnamed root package is not part of a path.
        if (typeof pkgName !== "string" || !pkgName || pkgName === "Root") break;
        parts.unshift(pkgName);
        pkg = slot(pkg, "package");
    }
    return parts.join("::");
}

/**
 * `"None"`, `"Shared"` or `"Composite"` — `(shared)` / `(composite)` in Pure source, and in
 * UML the hollow and filled diamonds. Defaults to `"None"` when it cannot be read, so an
 * unreadable aggregation draws no marker rather than a wrong one.
 */
export function aggregationOf(property) {
    const name = slot(slot(property, "aggregation"), "name");
    return name === "Shared" || name === "Composite" ? name : "None";
}

/**
 * `[1]`, `[*]`, `[0..1]` — how a multiplicity reads in Pure source. Total for the same
 * reason as {@link qualifiedName}: the bound may sit behind an unresolved pointer.
 */
export function multiplicityText(multiplicity) {
    const m = one(multiplicity);
    const bound = (end) => num(slot(slot(m, end), "value"));
    const lower = bound("lowerBound");
    const upper = bound("upperBound");
    if (lower === undefined) return "*";                       // unreadable: say nothing precise
    if (upper === undefined) return lower === 0 ? "*" : `${lower}..*`;
    return lower === upper ? String(lower) : `${lower}..${upper}`;
}

/** The classes this one directly extends, as qualified names. */
function generalizationsOf(cls) {
    const out = [];
    for (const generalization of many(cls?.generalizations)) {
        const general = slot(generalization, "general");
        const name = qualifiedName(slot(general, "rawType") ?? slot(general, "type"));
        if (name) out.push(name);
    }
    return out;
}

/**
 * The associations this class takes part in, as `{ property, type, multiplicity, association }`.
 * `property` is what this class navigates BY, and `type` is what it reaches — so in UML terms
 * the pair labels the FAR end of the edge, not this one.
 *
 * An association is a separate element that gives BOTH its classes a property, reachable as
 * `propertiesFromAssociations` rather than `properties` — which is why a class related only
 * by an association looked, to the diagram, like a class with no relationships at all.
 */
function associationsOf(cls) {
    const out = [];
    for (const property of many(cls?.propertiesFromAssociations)) {
        const name = slot(property, "name");
        if (typeof name !== "string") continue;
        const generic = slot(property, "genericType");
        const type = qualifiedName(slot(generic, "rawType") ?? slot(generic, "type"));
        const association = qualifiedName(slot(property, "owner"));
        if (type && association) {
            out.push({ property: name, type, association,
                       multiplicity: multiplicityText(slot(property, "multiplicity")),
                       aggregation: aggregationOf(property) });
        }
    }
    return out;
}

/** One class's own properties, in declaration order. Inherited ones are not included. */
function propertiesOf(cls) {
    const out = [];
    for (const property of many(cls?.properties)) {
        const name = slot(property, "name");
        if (typeof name !== "string") continue;
        const generic = slot(property, "genericType");
        const type = slot(generic, "rawType") ?? slot(generic, "type");
        out.push({ name, type: qualifiedName(type),
                   multiplicity: multiplicityText(slot(property, "multiplicity")),
                   aggregation: aggregationOf(property) });
    }
    return out;
}

/**
 * `{ 'm::Person': { properties, generalizations, associations } }` for the classes a diagram
 * shows. A type view naming a class that no longer exists is simply absent from the
 * result, and the renderer draws its box empty rather than refusing to draw at all — a
 * rename in the source must not make the diagram unopenable.
 */
export function classDefsFor(diagram, registry) {
    const defs = {};
    if (!diagram || !registry) return defs;
    for (const tv of diagram.typeViews) {
        if (!tv.type || defs[tv.type]) continue;
        let cls;
        try { cls = registry.getElement(tv.type); } catch { continue; }
        if (!cls) continue;
        defs[tv.type] = {
            properties: propertiesOf(cls),
            generalizations: generalizationsOf(cls),
            associations: associationsOf(cls),
        };
    }
    return defs;
}
