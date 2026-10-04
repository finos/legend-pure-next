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

// parse.js — `###Diagram` source text -> diagram models.
//
// Ported from `parseDiagramSource` in the bootstrap IDE's frontend. It reads the same
// syntax legend-pure's ANTLR DiagramParser accepts:
//
//   ###Diagram
//   Diagram model::MyDiagram(width=10.0, height=10.0)
//   {
//      TypeView cv(type=model::Person, position=(100.0, 50.0), width=150.0, height=60.0, …)
//      PropertyView pv(property=model::Person.firm, source=cv, target=cv2, points=[…])
//      GeneralizationView gv(source=cv2, target=cv)
//      AssociationView av(association=model::P2F, source=cv, target=cv2)
//   }
//
// Each parsed diagram records `_diagramStart` / `_diagramEnd` — offsets into the ORIGINAL
// text — so serialize.js can splice an edited diagram back without disturbing the rest of
// the file. Nothing here validates: a `type=` naming a class that does not exist parses
// fine, because the compiler does not check diagrams either (they are carried as text).

import { findAllOccurrences, findMatchingBrace, isWordChar, parsePoint, parsePoints,
         scanWord, skipWs } from "./scanner.js";

const SECTION_TAG = "###Diagram";

/** The `key=value` pairs inside a view's parentheses, as a flat object of raw strings. */
function parseViewProperties(propsStr) {
    // Two passes: find where each key starts, then take each value as the text up to the
    // comma before the NEXT key. A value can itself contain commas — `position=(1.0, 2.0)`,
    // `points=[(1,2), (3,4)]` — so the end of a value cannot be found by splitting on them.
    const keys = [];
    let pos = 0;
    while (pos < propsStr.length) {
        pos = skipWs(propsStr, pos);
        if (pos >= propsStr.length) break;
        if (!isWordChar(propsStr[pos])) { pos++; continue; }
        const word = scanWord(propsStr, pos);
        pos = skipWs(propsStr, word.end);
        if (propsStr[pos] === "=") {
            keys.push({ key: word.value, valueStart: pos + 1 });
            pos += 1;
        } else {
            pos = word.end + 1;
        }
    }

    const result = {};
    for (let i = 0; i < keys.length; i++) {
        const start = keys[i].valueStart;
        const end = i < keys.length - 1
            ? commaBeforeKey(propsStr, keys[i + 1].key, start)
            : propsStr.length;
        let value = propsStr.substring(start, end).trim();
        if (value.endsWith(",")) value = value.slice(0, -1).trim();
        result[keys[i].key] = value;
    }
    return result;
}

/** Where the value before `key` ends: the comma preceding it, else the key itself. */
function commaBeforeKey(str, key, afterPos) {
    let keyIdx = str.indexOf(`${key}=`, afterPos);
    if (keyIdx === -1) keyIdx = str.indexOf(`${key} =`, afterPos);
    if (keyIdx === -1) return str.length;
    for (let i = keyIdx - 1; i >= afterPos; i--) if (str[i] === ",") return i;
    return keyIdx;
}

/** Every `<viewType> <id>( … )` block in a diagram body, with its raw property text. */
function findViewBlocks(body, viewType) {
    const results = [];
    let searchPos = 0;
    for (;;) {
        const idx = body.indexOf(viewType, searchPos);
        if (idx === -1) return results;
        // `PropertyView` must not match inside a longer word.
        if (idx > 0 && isWordChar(body[idx - 1])) { searchPos = idx + viewType.length; continue; }

        let pos = skipWs(body, idx + viewType.length);
        if (pos >= body.length || !isWordChar(body[pos])) { searchPos = pos; continue; }
        const id = scanWord(body, pos);
        pos = skipWs(body, id.end);
        if (body[pos] !== "(") { searchPos = pos; continue; }

        // `[` counts as depth too: `points=[(1,2)]` must not close the block early.
        let depth = 1;
        let i = pos + 1;
        while (i < body.length && depth > 0) {
            const ch = body[i];
            if (ch === "(" || ch === "[") depth++;
            else if (ch === ")" || ch === "]") depth--;
            i++;
        }
        results.push({ id: id.value, propsStr: body.substring(pos + 1, i - 1) });
        searchPos = i;
    }
}

const relationship = (id, props, extra) => ({
    id,
    source: props.source || "",
    target: props.target || "",
    points: parsePoints(props.points),
    color: props.color || "#000000",
    lineWidth: parseFloat(props.lineWidth) || 1,
    label: props.label || "",
    ...extra,
});

/** Every diagram in `text`. An empty array when it contains no `###Diagram` section. */
export function parseDiagramSource(text) {
    const diagrams = [];
    for (const headerStart of findAllOccurrences(text, SECTION_TAG)) {
        const contentStart = headerStart + SECTION_TAG.length;
        // A section runs to the next `###` header, or to the end of the file.
        const nextSection = text.indexOf("###", contentStart);
        const sectionText = text.substring(contentStart, nextSection === -1 ? text.length : nextSection);

        let searchPos = 0;
        for (;;) {
            const dIdx = sectionText.indexOf("Diagram", searchPos);
            if (dIdx === -1) break;
            if (dIdx > 0 && isWordChar(sectionText[dIdx - 1])) { searchPos = dIdx + 7; continue; }

            let pos = skipWs(sectionText, dIdx + 7);
            if (pos >= sectionText.length || !isWordChar(sectionText[pos])) { searchPos = pos; continue; }
            const name = scanWord(sectionText, pos);
            pos = skipWs(sectionText, name.end);

            // The optional `(width=…, height=…)` after the name. The bootstrap IDE parsed
            // and discarded it, so the first edit stripped it from the user's file; it is
            // kept here and re-emitted by serialize.js.
            let dimension;
            if (sectionText[pos] === "(") {
                let depth = 1;
                let pp = pos + 1;
                while (pp < sectionText.length && depth > 0) {
                    if (sectionText[pp] === "(") depth++;
                    else if (sectionText[pp] === ")") depth--;
                    pp++;
                }
                const props = parseViewProperties(sectionText.substring(pos + 1, pp - 1));
                if (props.width !== undefined && props.height !== undefined) {
                    dimension = { width: parseFloat(props.width), height: parseFloat(props.height) };
                }
                pos = skipWs(sectionText, pp);
            }

            if (sectionText[pos] !== "{") { searchPos = pos; continue; }
            const bodyEnd = findMatchingBrace(sectionText, pos);
            if (bodyEnd === -1) { searchPos = pos + 1; continue; }
            const body = sectionText.substring(pos + 1, bodyEnd);

            diagrams.push({
                name: name.value,
                dimension,
                typeViews: findViewBlocks(body, "TypeView").map(({ id, propsStr }) => {
                    const props = parseViewProperties(propsStr);
                    return {
                        id,
                        type: props.type || "",
                        position: parsePoint(props.position),
                        width: parseFloat(props.width) || 150,
                        height: parseFloat(props.height) || 60,
                        color: props.color || "#FFFFCC",
                        stereotypesVisible: props.stereotypesVisible === "true",
                        attributesVisible: props.attributesVisible === "true",
                    };
                }),
                generalizationViews: findViewBlocks(body, "GeneralizationView")
                    .map(({ id, propsStr }) => relationship(id, parseViewProperties(propsStr))),
                associationViews: findViewBlocks(body, "AssociationView")
                    .map(({ id, propsStr }) => {
                        const props = parseViewProperties(propsStr);
                        return relationship(id, props, { association: props.association || "" });
                    }),
                propertyViews: findViewBlocks(body, "PropertyView")
                    .map(({ id, propsStr }) => {
                        const props = parseViewProperties(propsStr);
                        return relationship(id, props, { property: props.property || "" });
                    }),
                // Offsets into the ORIGINAL text, for splicing an edit back in.
                _sectionStart: headerStart,
                _diagramStart: contentStart + dIdx,
                _diagramEnd: contentStart + bodyEnd + 1,
            });
            searchPos = bodyEnd + 1;
        }
    }
    return diagrams;
}
