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

// scanner.js — character scanning for the `###Diagram` grammar.
//
// Ported from the bootstrap IDE's single-file frontend
// (bootstrap/.../legend-pure-next-bootstrap-ide/src/main/resources/web/index.html), which
// carries the note "no regex — uses indexOf + character scanning". That choice is kept: a
// diagram body contains `(`, `[`, `,` and `::` in positions a regex handles badly, and the
// scanner has to report offsets into the ORIGINAL text so an edit can be spliced back.

export const isWordChar = (ch) =>
    (ch >= "a" && ch <= "z") || (ch >= "A" && ch <= "Z") || (ch >= "0" && ch <= "9") || ch === "_";

export const isWhitespace = (ch) => ch === " " || ch === "\t" || ch === "\n" || ch === "\r";

/** The first index at or after `pos` that is not whitespace. */
export function skipWs(str, pos) {
    while (pos < str.length && isWhitespace(str[pos])) pos++;
    return pos;
}

/** A qualified name: word characters and `::`, so `model::Person` scans as one token. */
export function scanWord(str, pos) {
    let end = pos;
    while (end < str.length && (isWordChar(str[end]) || str[end] === ":")) end++;
    return { value: str.substring(pos, end), end };
}

export function findAllOccurrences(text, keyword) {
    const indices = [];
    let pos = 0;
    for (;;) {
        const idx = text.indexOf(keyword, pos);
        if (idx === -1) return indices;
        indices.push(idx);
        pos = idx + keyword.length;
    }
}

/** The index of the `}` closing the `{` at `openIndex`, or -1. */
export function findMatchingBrace(text, openIndex) {
    let depth = 0;
    for (let i = openIndex; i < text.length; i++) {
        if (text[i] === "{") depth++;
        else if (text[i] === "}") {
            depth--;
            if (depth === 0) return i;
        }
    }
    return -1;
}

/** `(x, y)` -> a point; anything unparseable is the origin, never an exception. */
export function parsePoint(str) {
    const zero = { x: 0, y: 0 };
    if (!str) return zero;
    const open = str.indexOf("(");
    if (open === -1) return zero;
    const comma = str.indexOf(",", open + 1);
    if (comma === -1) return zero;
    const close = str.indexOf(")", comma + 1);
    if (close === -1) return zero;
    const x = parseFloat(str.substring(open + 1, comma).trim());
    const y = parseFloat(str.substring(comma + 1, close).trim());
    return { x: Number.isNaN(x) ? 0 : x, y: Number.isNaN(y) ? 0 : y };
}

/** `[(x, y), (x, y)]` -> points. A malformed pair is skipped rather than failing the parse. */
export function parsePoints(str) {
    const points = [];
    if (!str) return points;
    let pos = 0;
    for (;;) {
        const open = str.indexOf("(", pos);
        if (open === -1) return points;
        const comma = str.indexOf(",", open + 1);
        if (comma === -1) return points;
        const close = str.indexOf(")", comma + 1);
        if (close === -1) return points;
        const x = parseFloat(str.substring(open + 1, comma).trim());
        const y = parseFloat(str.substring(comma + 1, close).trim());
        if (!Number.isNaN(x) && !Number.isNaN(y)) points.push({ x, y });
        pos = close + 1;
    }
}
