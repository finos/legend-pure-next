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

// section-hint.js — turning "No parser registered for section: ###Diargam" into something
// a person can act on.
//
// `dispatchSection` (pure/specification/grammar/mapping/mappings-pure/mapping_top.pure)
// fails hard on an unregistered `###Section` and, correctly, does not know what else is
// registered — the registry is assembled by the host. So the host is the only place that
// can say "you wrote ###Diargam; the sections here are Pure, CompiledGraph and Diagram, and
// you probably meant Diagram".
//
// A misspelt section header is a cliff: nothing in the file compiles, and the message names
// only the thing that does not exist.

/** The `###Name` a failed parse complained about, or null if that is not what went wrong. */
export function unknownSectionName(error) {
    const message = String(error?.message ?? error ?? "");
    return /No parser registered for section: ###(\w+)/.exec(message)?.[1] ?? null;
}

/** Levenshtein distance, capped by the caller's threshold — enough to catch a typo. */
function distance(a, b) {
    let previous = Array.from({ length: b.length + 1 }, (_, i) => i);
    for (let i = 1; i <= a.length; i++) {
        const current = [i];
        for (let j = 1; j <= b.length; j++) {
            current[j] = Math.min(
                previous[j] + 1,
                current[j - 1] + 1,
                previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1),
            );
        }
        previous = current;
    }
    return previous[b.length];
}

/**
 * The registered name `written` was most likely meant to be, or null when nothing is close.
 * Case is ignored first — `###diagram` is a plain miscapitalisation — then a small edit
 * distance covers transpositions like `Diargam`.
 */
export function closestSection(written, known) {
    const lower = written.toLowerCase();
    const sameLetters = known.find((name) => name.toLowerCase() === lower);
    if (sameLetters) return sameLetters;
    // Allow roughly one edit per four characters, so short names do not match everything.
    const limit = Math.max(2, Math.floor(written.length / 4));
    let best = null;
    let bestDistance = Infinity;
    for (const name of known) {
        const d = distance(lower, name.toLowerCase());
        if (d < bestDistance && d <= limit) { best = name; bestDistance = d; }
    }
    return best;
}

/**
 * The lines to print after the raw failure: what was written, what is available, and the
 * likely intent. Returns [] when the error is not an unknown-section failure.
 */
export function unknownSectionHint(error, known) {
    const written = unknownSectionName(error);
    if (!written) return [];
    const lines = [];
    const suggestion = closestSection(written, known);
    if (suggestion) lines.push(`###${written} is not a section — did you mean ###${suggestion}?`);
    lines.push(known.length
        ? `Sections available here: ${known.map((n) => `###${n}`).join(", ")}.`
        : "No section parsers are registered.");
    return lines;
}

/** 1-based line of the `###Name` header in `text`, or null if it is not there. */
export function sectionHeaderLine(text, name) {
    const lines = text.split("\n");
    const index = lines.findIndex((line) => line.trimEnd() === `###${name}` || line.startsWith(`###${name}`));
    return index === -1 ? null : index + 1;
}
