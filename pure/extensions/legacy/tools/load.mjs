#!/usr/bin/env node
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

// Stages legacy Pure sources (legend-pure's platform, then legend-engine's modules) for the loader
// (loader/code/loader.pure), which parses them with next's parser and places them on top of the bridge.
//
//   node tools/load.mjs <legend-pure platform/pure dir> <legend-engine dir> <out dir> <module>...
//
// For each module wanted, after those it depends on (as legend-engine's definition files say):
//   - its sources, through the preprocessor (a documentation literal is its doc tag), in <out>/staged/<module>/;
//   - its module.json in <out>/<module>/, where the loader writes its placed sources (code/);
// and in <out>:
//   - plan.txt, what the loader follows: legacy's default imports (the coreImport group of legacy's
//     m3.pure), next's (TopLevelCompiler.DEFAULT_IMPORTS), where next's and the bridge's sources are, the
//     modules, those with their natives and adapters committed (natives/<module>.pure, <module>.*.pure)
//     their elements set aside (natives/<module>.setAside) and those that are next's at another path
//     (natives/<module>.nexts),
//     and the legacy metamodel classes the bridge lays over next's;
//   - modules.txt, the modules in order with their dependencies ("<module> <dependency>..."), for the
//     build to compile one after the other.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { preprocess } from './preprocess.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const repo = path.resolve(root, '../../..');
const [legacyDir, engineDir, outDir, ...wanted] = process.argv.slice(2).map((a, i) => (i < 3 ? path.resolve(a) : a));
if (!legacyDir || !engineDir || !outDir || !wanted.length)
{
   throw new Error('usage: load.mjs <legend-pure platform/pure dir> <legend-engine dir> <out dir> <module>...');
}

const pureFiles = (dir) => fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))
   .flatMap((e) => (e.isDirectory() ? pureFiles(path.join(dir, e.name)) : e.name.endsWith('.pure') ? [path.join(dir, e.name)] : []));
const read = (file) => fs.readFileSync(file, 'utf8');

// The modules: legend-pure's platform (its m3.pure is the bridge's), then its other modules and
// legend-engine's, as their definition files declare them; legacy's 'platform' is the loaded one.
const PLATFORM = 'legacy_platform';
const modules = new Map([[PLATFORM, {
   name: PLATFORM,
   pattern: '(meta)(::.*)?',
   dependencies: [],
   files: pureFiles(legacyDir).filter((f) => path.relative(legacyDir, f) !== path.join('grammar', 'm3.pure')),
   base: legacyDir,
}]]);
// legend-pure's modules beyond its platform (build/legend-pure-<version>/modules/), then legend-engine's
const pureModulesDir = path.resolve(legacyDir, '../../modules');
const artifactDirs = [pureModulesDir, engineDir].filter((d) => fs.existsSync(d))
   .flatMap((d) => fs.readdirSync(d).map((a) => path.join(d, a)).filter((a) => fs.statSync(a).isDirectory()));
for (const dir of artifactDirs)
{
   for (const definition of fs.readdirSync(dir).filter((f) => f.endsWith('.definition.json')))
   {
      const d = JSON.parse(read(path.join(dir, definition)));
      const sources = path.join(dir, d.name);
      modules.set(d.name, {
         name: d.name,
         pattern: d.pattern,
         dependencies: d.dependencies.map((m) => (m === 'platform' ? PLATFORM : m)),
         files: fs.existsSync(sources) ? pureFiles(sources) : [],
         base: sources,
      });
   }
}

// The modules to load: those wanted, after what they depend on.
const order = [];
const visit = (name, from) =>
{
   const m = modules.get(name);
   if (!m) throw new Error(`unknown module ${name}${from ? ` (a dependency of ${from})` : ''}`);
   if (order.includes(m)) return;
   m.dependencies.forEach((d) => visit(d, name));
   order.push(m);
};
wanted.forEach((name) => visit(name));

// Next's default imports, as its compiler declares them.
const compiler = read(path.join(repo, 'bootstrap/legend-pure-next-bootstrap/legend-pure-next-bootstrap-core/legend-pure-next-bootstrap-compiler/src/main/java/org/finos/legend/pure/m3/module/sourceModule/topLevel/TopLevelCompiler.java'));
const nextImports = [...compiler.match(/DEFAULT_IMPORTS = Lists\.mutable\.with\(([^)]*)\)/)[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);

// Legacy's default imports: the coreImport group of its m3.pure.
const m3 = read(path.join(legacyDir, 'grammar', 'm3.pure'));
const coreImport = m3.slice(m3.indexOf('children[ImportGroup] coreImport'));
const legacyImports = [...coreImport.slice(0, coreImport.indexOf('\n}')).matchAll(/properties\[path\]:'([^']+)'/g)].map((m) => m[1]);

// The legacy metamodel classes the bridge lays over next's, and the next class each wraps (the first of
// tools/m3-mapping.json's 'wraps', the one it holds).
function wraps()
{
   const mapping = JSON.parse(read(path.join(here, 'm3-mapping.json')));
   return Object.entries(mapping.classes).filter(([, m]) => m.wraps)
      .map(([legacy, m]) => `wraps ${m.path ?? legacy} platform::pure::metamodel::${m.wraps[0]}`);
}

// Next's bootstrap elements, which its compiler reads from m3.ttl and no Pure source declares: its primitive
// types and packageable multiplicities, which legacy code names as values.
function bootstrapElements()
{
   const ttl = read(path.join(repo, 'pure/specification/m3.ttl'));
   const kinds = { PrimitiveType: 'PrimitiveType', UserDefinedPackageableMultiplicity: 'PackageableMultiplicity' };
   return [...ttl.matchAll(/^:(\w+) a :(PrimitiveType|UserDefinedPackageableMultiplicity) ;[\s\S]*?:package :(\w+)/gm)]
      .map((m) => `nextElement ${m[3].replace(/_/g, '::')}::${m[1]} ${kinds[m[2]]}`);
}

const plan = [
   ...legacyImports.map((p) => `import ${p}`),
   ...nextImports.map((p) => `nextImport ${p}`),
   `next ${path.join(repo, 'pure/specification/runtime')}`,
   `next ${path.join(repo, 'shared/specification')}`,
   `bridge ${path.join(root, 'code')}`,
   ...wraps(),
   ...bootstrapElements(),
];
for (const m of order)
{
   const staged = path.join(outDir, 'staged', m.name);
   const dir = path.join(outDir, m.name);
   fs.rmSync(staged, { recursive: true, force: true });
   fs.rmSync(dir, { recursive: true, force: true });
   for (const file of m.files)
   {
      const target = path.join(staged, path.relative(m.base, file));
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.writeFileSync(target, preprocess(read(file)));
   }
   fs.mkdirSync(dir, { recursive: true });
   fs.writeFileSync(path.join(dir, 'module.json'),
      `${JSON.stringify({ name: m.name, packagePattern: m.pattern, dependencies: ['core', 'compiler', 'tds', 'legacy', ...m.dependencies] }, null, 2)}\n`);
   plan.push(`module ${m.name} ${staged} ${path.join(dir, 'code')}`);
   const natives = path.join(root, 'natives', `${m.name}.pure`);
   if (fs.existsSync(natives)) plan.push(`natives ${m.name} ${natives}`);
   for (const extra of fs.existsSync(path.join(root, 'natives')) ? fs.readdirSync(path.join(root, 'natives')).sort() : [])
   {
      const file = path.join(root, 'natives', extra);
      if (extra === `${m.name}.setAside`) plan.push(`setAside ${file}`);
      else if (extra === `${m.name}.nexts`) plan.push(`nexts ${file}`);
      else if (extra.startsWith(`${m.name}.`) && extra.endsWith('.pure') && extra !== `${m.name}.pure`) plan.push(`include ${m.name} ${file}`);
   }
   console.log(`  staged ${m.name}: ${m.files.length} sources`);
}
fs.writeFileSync(path.join(outDir, 'plan.txt'), `${plan.join('\n')}\n`);
fs.writeFileSync(path.join(outDir, 'modules.txt'), `${order.map((m) => [m.name, ...m.dependencies].join(' ')).join('\n')}\n`);
