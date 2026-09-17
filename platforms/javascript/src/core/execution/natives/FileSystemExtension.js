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

// File-system natives as a NativesExtension, through Node's fs: Pure code run by this host (the
// specification corpus runners, test reports) reads and writes files as it does on the JVM hosts.

import { mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { DIRECTORY_TREE, READ_FILE, READ_FILE_BYTES, WRITE_FILE } from "./native-signatures.js";

/** Host natives for meta::pure::functions::io that need a file system. */
export const fileSystemExtension = {
    registerAll(natives) {
        natives.register(READ_FILE, () => readFile);
        natives.register(READ_FILE_BYTES, () => readFileBytes);
        natives.register(DIRECTORY_TREE, () => directoryTree);
        natives.register(WRITE_FILE, () => writeFile);
    },
};

// readFile(path): the file's content (UTF-8).
function readFile(path) {
    return readFileSync(String(path), "utf8");
}

// readFileBytes(path): the file's raw bytes, as the BigInt list a Pure Integer[*] is on this host.
function readFileBytes(path) {
    return Array.from(readFileSync(String(path)), (b) => BigInt(b));
}

// directoryTree(root): every regular file under root, recursively, sorted — as the JVM hosts return it.
// Paths are built by appending names to root as given (no normalisation), so callers can strip the
// root's length to get the relative path, as the corpus runners do.
function directoryTree(root) {
    const files = [];
    const visit = (dir) => {
        for (const entry of readdirSync(dir, { withFileTypes: true })) {
            const path = `${dir}/${entry.name}`;
            if (entry.isDirectory()) visit(path);
            else if (entry.isFile()) files.push(path);
        }
    };
    visit(String(root).replace(/\/+$/, ""));
    return files.sort();
}

// writeFile(path, content): writes content (UTF-8), creating parent directories; returns the path.
function writeFile(path, content) {
    mkdirSync(dirname(String(path)), { recursive: true });
    writeFileSync(String(path), String(content), "utf8");
    return path;
}
