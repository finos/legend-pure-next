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

// progress.js — start-up progress, and the streaming read that feeds it.
//
// The page pulls ~13 MB of archives before it can do anything, which on a slow connection is
// a long time to look at a static "Loading…". Progress is only worth showing if it is honest,
// so two things are deliberate here:
//
//  - The bar is DETERMINATE only while downloading, where bytes received over bytes expected
//    is a real fraction. The phases after it (decode, generated JavaScript, Monaco, the
//    compiler warm-up) have no measurable denominator, so the bar goes indeterminate rather
//    than inventing one and appearing to stall at 90%.
//  - `Content-Length` describes the COMPRESSED body while the stream yields decompressed
//    bytes, so the ratio can exceed 1 on a gzipped response. PDBs are already-deflated zips
//    and compress by only a few percent, so the error is small — but it is clamped, because a
//    bar that reads 112% is worse than one that pauses at 100%.

const MB = 1024 * 1024;
const megabytes = (bytes) => `${(bytes / MB).toFixed(1)} MB`;

/**
 * Drive an existing bar element and an existing label element.
 *
 * Nothing is created or removed here, and that is the point: the bar is positioned out of
 * flow and the label is a box that already absorbs the header's slack, so start-up progress
 * appearing and finishing cannot reflow the page. The previous version inserted its own row,
 * which pushed the whole app down on load and pulled it back up at Ready.
 *
 * `determinate(fraction, text)` while a real fraction is known, `indeterminate(text)` for
 * work with no measurable end, `done()` when start-up is over.
 */
export function createProgress({ bar: host, label }) {
    const bar = document.createElement("div");
    bar.className = "progress-bar";
    host.append(bar);
    host.hidden = false;

    let finished = false;
    const say = (text) => { if (label && text) label.textContent = text; };

    return {
        determinate(fraction, text) {
            if (finished) return;
            host.classList.remove("indeterminate");
            // Clamped: see the note about Content-Length and gzip.
            bar.style.width = `${Math.min(100, Math.max(0, fraction * 100)).toFixed(1)}%`;
            say(text);
        },
        indeterminate(text) {
            if (finished) return;
            host.classList.add("indeterminate");
            say(text);
        },
        /** Hide the bar. The label is the page's status line and stays, for its next message. */
        done() {
            finished = true;
            host.classList.remove("indeterminate");
            host.hidden = true;
            bar.remove();
        },
    };
}

/**
 * Fetch `url` as bytes, reporting progress as the body arrives.
 *
 * `onProgress(receivedForThisFile, expectedForThisFile)` is called as chunks land;
 * `expected` is the `Content-Length` when the server sent one and 0 when it did not — a
 * caller showing a fraction has to cope with not knowing the total, because a chunked or
 * proxied response simply does not say.
 *
 * Falls back to a plain `arrayBuffer()` read when the response has no readable body, so this
 * never becomes the reason a file fails to load.
 */
export async function fetchBytesWithProgress(url, onProgress = () => {}) {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);

    const expected = Number(response.headers.get("content-length")) || 0;
    if (!response.body || typeof response.body.getReader !== "function") {
        const bytes = new Uint8Array(await response.arrayBuffer());
        onProgress(bytes.length, expected || bytes.length);
        return bytes;
    }

    const reader = response.body.getReader();
    const chunks = [];
    let received = 0;
    for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(value);
        received += value.length;
        onProgress(received, expected);
    }
    // One copy at the end rather than growing a buffer per chunk.
    const bytes = new Uint8Array(received);
    let at = 0;
    for (const chunk of chunks) {
        bytes.set(chunk, at);
        at += chunk.length;
    }
    return bytes;
}

/**
 * Ask each URL how big it is, in parallel, so the total is known before any of it is
 * downloaded. Returns a size per URL, 0 where the server would not say.
 *
 * One round trip for all of them, which buys a denominator that never changes.
 */
async function probeSizes(urls) {
    return Promise.all(urls.map(async (url) => {
        try {
            const response = await fetch(url, { method: "HEAD" });
            return response.ok ? Number(response.headers.get("content-length")) || 0 : 0;
        } catch {
            return 0;   // no HEAD, no CORS for it, no matter — see the fallback below
        }
    }));
}

/**
 * Download every `url` in order, driving `progress` across the whole set. Returns the bytes
 * in the order asked for.
 *
 * THE DENOMINATOR IS FIXED BEFORE THE FIRST BYTE. Sizes are probed up front, because the
 * obvious alternative — estimating the unmeasured files from the ones measured so far —
 * makes the bar run BACKWARDS as soon as the files differ in size: a small first archive
 * sets a small estimate, and the bar drops when a larger one arrives. Measured here: 16.5%
 * back to 7.7%. A bar that retreats is worse than one that advances unevenly.
 *
 * When the sizes cannot be probed at all, progress falls back to counting files — each
 * archive is an equal slice, interpolated by its own bytes. Lumpy, but still monotonic.
 */
export async function downloadAll(urls, progress, { label = (u) => u } = {}) {
    progress.indeterminate("checking archive sizes…");
    const sizes = await probeSizes(urls);
    const measured = sizes.every((n) => n > 0);
    const total = sizes.reduce((a, b) => a + b, 0);
    // Bytes belonging to files already finished, so the bar never recomputes the past.
    const before = sizes.map((_, i) => sizes.slice(0, i).reduce((a, b) => a + b, 0));

    const results = new Array(urls.length);
    for (let i = 0; i < urls.length; i++) {
        const name = label(urls[i]);
        const show = (got) => {
            const fraction = measured
                ? (before[i] + Math.min(got, sizes[i])) / total
                // Count-based: file i contributes its own slice, interpolated by its bytes.
                : (i + (sizes[i] > 0 ? Math.min(got / sizes[i], 1) : 0)) / urls.length;
            const bytes = measured
                ? `${megabytes(before[i] + got)} of ${megabytes(total)}`
                : megabytes(got);
            progress.determinate(fraction, `${name} — ${bytes} · archive ${i + 1} of ${urls.length}`);
        };
        show(0);
        results[i] = await fetchBytesWithProgress(urls[i], (got, contentLength) => {
            // A server that declined HEAD may still send Content-Length on the GET; take it
            // for this file's own interpolation, but never revise the total.
            if (!sizes[i] && contentLength > 0) sizes[i] = contentLength;
            show(got);
        });
        if (!sizes[i]) sizes[i] = results[i].length;
        show(results[i].length);
    }
    return results;
}
