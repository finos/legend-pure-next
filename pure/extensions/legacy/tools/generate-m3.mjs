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

// Generates code/m3/m3.pure: legend-pure's m3 metamodel as wrappers over next's.
//
//   node tools/generate-m3.mjs <legend-pure version> <its m3.pure>      (`just generate` fetches it)
//
// Inputs:
//   m3.pure of legend-pure-m3-core <version>   the legacy metamodel (M4 syntax)
//   ../../../shared/specification/m3.pure   next's metamodel, rendered as Pure from m3.ttl
//   tools/m3-mapping.json                   how each legacy class is laid over next's
//   code/m3/overrides.pure                  hand-written bodies for legacy properties next models differently
//
// Each legacy class becomes a class at its legacy path. A wrapping class extends meta::pure::bridge::Wrapper
// (through its legacy parents) and computes each legacy property from _next: through the override
// meta::pure::bridge::m3::overrides::<Class>_<property> when there is one, else from the next property of
// the same name, else a stub failing with 'not bridged'. Metamodel objects in a result are wrapped; values
// next and legacy share (primitives, enumerations, Any) pass through. A legacyOnly class keeps its legacy
// properties as stored properties. wrap() turns any next object into the most specific wrapper.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { legacyClasses } from './m4.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const [LEGACY_VERSION, legacyFile] = process.argv.slice(2).filter((a) => !a.startsWith('--'));
if (!LEGACY_VERSION || !legacyFile) throw new Error('usage: generate-m3.mjs <legend-pure version> <m3.pure> [--verbose]');
const nextFile = path.resolve(root, '../../../shared/specification/m3.pure');
const mappingFile = path.join(here, 'm3-mapping.json');
const overridesFile = path.join(root, 'code/m3/overrides.pure');
const outFile = path.join(root, 'code/m3/m3.pure');

const NEXT = 'platform::pure::metamodel::';
const BRIDGE = 'meta::pure::bridge';
const WRAPPER = `${BRIDGE}::Wrapper`;
const WRAP = `${BRIDGE}::m3::wrap`;
const OVERRIDES = `${BRIDGE}::m3::overrides`;
const PRIMITIVES = new Set(['String', 'Boolean', 'Integer', 'Float', 'Decimal', 'Number', 'Date', 'StrictDate', 'DateTime', 'LatestDate', 'StrictTime', 'Binary', 'Byte']);

// ---------- next's metamodel ----------

function nextClasses(text)
{
   const classes = new Map();
   const re = /^Class\s+(?:<<([^>]*)>>\s*)?(?:\{[^}]*\}\s*)?([\w:]+)(?:<([^\n]*?)>)?(?:\s+extends\s+([^\n{]+))?\s*\{([\s\S]*?)^\}/gm;
   for (const m of text.matchAll(re))
   {
      const [, stereotypes, classPath, generics, extendsList, body] = m;
      const [typeParams, multParams] = (generics ?? '').split('|');
      classes.set(classPath, {
         path: classPath,
         abstract: (stereotypes ?? '').includes('typemodifiers.abstract'),
         typeParams: (typeParams ?? '').split(',').map((s) => s.trim().replace(/^-/, '')).filter(Boolean),
         multParams: (multParams ?? '').split(',').map((s) => s.trim()).filter(Boolean),
         generalizations: extendsList ? splitTopLevel(extendsList).map((g) => g.trim().replace(/<.*$/, '')) : [],
         properties: [...body.matchAll(/^\s*(?:<<[^>]*>>\s*)?(\w+)\s*:\s*(.+)\[([^\]]+)\];\s*$/gm)].map((p) => ({ name: p[1], type: p[2].trim(), multiplicity: p[3] })),
      });
   }
   return classes;
}

function splitTopLevel(s)
{
   const out = [];
   let depth = 0, cur = '';
   for (const c of s)
   {
      if (c === '<' || c === '{') depth++;
      if (c === '>' || c === '}') depth--;
      if (c === ',' && depth === 0) { out.push(cur); cur = ''; } else { cur += c; }
   }
   if (cur.trim()) out.push(cur);
   return out;
}

// ---------- model ----------

const mapping = JSON.parse(fs.readFileSync(mappingFile, 'utf8'));
const next = nextClasses(fs.readFileSync(nextFile, 'utf8'));
const legacy = legacyClasses(fs.readFileSync(legacyFile, 'utf8'));
const overrides = new Set(fs.existsSync(overridesFile)
   ? [...fs.readFileSync(overridesFile, 'utf8').matchAll(new RegExp(`function\\s+${OVERRIDES}::(\\w+)\\s*[(<]`, 'g'))].map((m) => m[1])
   : []);

const modeOf = (legacyPath) =>
{
   const m = mapping.classes[legacyPath];
   if (!m) throw new Error(`No mapping for legacy class ${legacyPath}`);
   return m;
};
const pathOf = (legacyPath) => modeOf(legacyPath).path ?? legacyPath;
const isWrapping = (legacyPath) => !!(mapping.classes[legacyPath] && (mapping.classes[legacyPath].wraps || mapping.classes[legacyPath].abstract));

function nextAncestors(nextPath)
{
   const seen = [];
   const queue = [nextPath];
   while (queue.length)
   {
      const p = queue.shift();
      if (seen.includes(p) || !next.has(p)) continue;
      seen.push(p);
      queue.push(...next.get(p).generalizations);
   }
   return seen;
}

function nextProperty(nextPath, name)
{
   for (const a of nextAncestors(nextPath))
   {
      const p = next.get(a).properties.find((x) => x.name === name);
      if (p) return p;
   }
   return null;
}

// ---------- rendering legacy types ----------

function typePath(p)
{
   if (PRIMITIVES.has(p)) return p;
   if (mapping.types[p]) return mapping.types[p];
   const m = mapping.classes[p];
   if (m && m.alias) return NEXT + m.alias;
   if (m) return pathOf(p);
   throw new Error(`Unknown legacy type ${p}`);
}

function renderGT(gt)
{
   if (gt.typeParam) return gt.typeParam;
   if (gt.functionType)
   {
      const ft = gt.functionType;
      return `{${ft.parameters.map((p) => `${renderGT(p.genericType)}[${p.multiplicity}]`).join(', ')}->${renderGT(ft.returnType)}[${ft.returnMultiplicity}]}`;
   }
   const args = (gt.typeArguments ?? []).map(renderGT);
   const mults = gt.multiplicityArguments ?? [];
   const generics = args.length || mults.length ? `<${args.join(', ')}${mults.length ? `|${mults.join(', ')}` : ''}>` : '';
   return typePath(gt.path) + generics;
}

// Whether values of this legacy type are next metamodel objects to wrap (vs values both sides share)
function needsWrap(gt)
{
   if (gt.typeParam) return true;
   if (gt.functionType) return false;
   if (PRIMITIVES.has(gt.path) || mapping.types[gt.path]) return false;
   const m = mapping.classes[gt.path];
   return !!m && !m.legacyOnly;
}

const typeParamsDecl = (c) => c.typeParams.length || c.multParams.length
   ? `<${c.typeParams.map((t) => (t.contravariant ? '-' : '') + t.name).join(', ')}${c.multParams.length ? `|${c.multParams.join(', ')}` : ''}>`
   : '';
const anyArgs = (tps, mps) => tps.length || mps.length ? `<${tps.map(() => 'Any').join(', ')}${mps.length ? `|${mps.map(() => '*').join(', ')}` : ''}>` : '';

// The next class _next is read as, with the legacy class's type parameters where they line up
function castTarget(c, nextPath)
{
   const n = next.get(nextPath);
   const tps = n.typeParams.map((_, i) => (c.typeParams.length === n.typeParams.length ? c.typeParams[i].name : 'Any'));
   const mps = n.multParams.map((_, i) => (c.multParams.length === n.multParams.length ? c.multParams[i] : '*'));
   return nextPath + (tps.length || mps.length ? `<${tps.join(', ')}${mps.length ? `|${mps.join(', ')}` : ''}>` : '');
}

// ---------- property bodies ----------

const stub = (c, p) => `[]->toOne('not bridged: ${c.name}.${p.name}')`;

function propertyBody(c, p, stats)
{
   const key = `${c.name}_${p.name}`;
   if (overrides.has(key))
   {
      stats.overridden.push(key);
      return `${OVERRIDES}::${key}($this)`;
   }
   const wraps = modeOf(c.path).wraps;
   const np = wraps ? nextProperty(NEXT + wraps[0], p.name) : null;
   if (!np || p.genericType.functionType)
   {
      stats.stubbed.push(key);
      return stub(c, p);
   }
   stats.direct.push(key);
   let expr = `$this._next->cast(@${castTarget(c, NEXT + wraps[0])}).${p.name}`;
   if (needsWrap(p.genericType))
   {
      const type = renderGT(p.genericType);
      expr = p.multiplicity === '1' && np.multiplicity === '1'
         ? `${WRAP}(${expr})->cast(@${type})`
         : `${expr}->map(x | ${WRAP}($x))->cast(@${type})`;
   }
   if (p.multiplicity === '1' && np.multiplicity !== '1' && !(needsWrap(p.genericType) && np.multiplicity === '1')) expr += '->toOne()';
   else if (p.multiplicity === '0..1' && !['1', '0..1'].includes(np.multiplicity)) expr += '->head()';
   else if (p.multiplicity === '1..*' && np.multiplicity !== '1..*' && np.multiplicity !== '1') expr += '->toOneMany()';
   return expr;
}

// ---------- emit ----------

function emitClass(c, stats)
{
   const mode = modeOf(c.path);
   const gens = c.generalizations
      .filter((g) => !(g.path && mapping.classes[g.path]?.alias))
      .map(renderGT);
   if (gens.length === 0 && !mode.legacyOnly) gens.push(WRAPPER);
   const header = `Class ${pathOf(c.path)}${typeParamsDecl(c)}${gens.length ? ` extends ${gens.join(', ')}` : ''}`;
   const body = c.properties.map((p) =>
   {
      const type = `${renderGT(p.genericType)}[${p.multiplicity}]`;
      if (mode.legacyOnly) return `   ${p.name} : ${type};`;
      return `   ${p.name}()\n   {\n      ${propertyBody(c, p, stats)}\n   }: ${type};`;
   });
   return `${header}\n{\n${body.join('\n\n')}${body.length ? '\n' : ''}}`;
}

// An instantiable next class reaching two wrapped classes neither of which is more specific is wrapped by
// whichever comes first: map it explicitly instead.
function checkUnambiguous(branches)
{
   const wrapped = branches.map((b) => b.next);
   const problems = [];
   for (const n of [...next.keys()].filter((p) => !next.get(p).abstract))
   {
      const reached = wrapped.filter((w) => nextAncestors(n).includes(w));
      const mostSpecific = reached.filter((w) => !reached.some((o) => o !== w && nextAncestors(o).includes(w)));
      if (mostSpecific.length > 1) problems.push(`${n}: ${mostSpecific.join(', ')}`);
   }
   if (problems.length) throw new Error(`Ambiguous wrapping, map these next classes explicitly:\n  ${problems.join('\n  ')}`);
}

function emitWrapper()
{
   return `// Every legacy metamodel class is a Wrapper: it holds the next object it stands for, and computes its
// legacy properties from it. Two wrappers of the same next object are equal, so legacy code comparing,
// deduplicating or keying on metamodel objects sees the next graph's identity.
Class ${WRAPPER}
{
   <<equality.Key>> _next : Any[1];
}`;
}

function emitWrap()
{
   const branches = [];
   for (const c of legacy)
   {
      const mode = modeOf(c.path);
      for (const w of mode.wraps ?? [])
      {
         const n = next.get(NEXT + w);
         if (!n) throw new Error(`Unknown next class ${NEXT}${w} (wrapped by ${c.path})`);
         branches.push({
            next: n.path,
            depth: nextAncestors(n.path).length,
            text: `      n: ${n.path}${anyArgs(n.typeParams, n.multParams)}[1] | ^${pathOf(c.path)}${anyArgs(c.typeParams, c.multParams)}(_next = $n)`,
         });
      }
   }
   // most specific next classes first: match takes the first branch the value is an instance of
   branches.sort((a, b) => b.depth - a.depth);
   checkUnambiguous(branches);
   return `// The wrapper for a next object: the legacy class of its most specific next class. Values next and
// legacy share (primitives, enumeration values, instances of user classes) are returned as they are.
function ${WRAP}(x: Any[1]): Any[1]
{
   $x->match([
${branches.map((b) => b.text).join(',\n')},
      a: Any[1] | $a
   ])
}`;
}

const stats = { direct: [], overridden: [], stubbed: [] };
const classes = legacy.filter((c) => !modeOf(c.path).alias).map((c) => emitClass(c, stats));
const header = `// Copyright 2020 Goldman Sachs (legend-pure m3, from which this is generated)
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

// GENERATED by tools/generate-m3.mjs from legend-pure ${LEGACY_VERSION} m3.pure — do not edit.
// Legacy property bodies next models differently go in overrides.pure.
`;
fs.writeFileSync(outFile, `${header}\n${emitWrapper()}\n\n${classes.join('\n\n')}\n\n${emitWrap()}\n`);

console.log(`wrote ${path.relative(process.cwd(), outFile)}: ${classes.length} classes`);
console.log(`  properties: ${stats.direct.length} delegated, ${stats.overridden.length} overridden, ${stats.stubbed.length} not bridged`);
if (process.argv.includes('--verbose')) console.log(`  not bridged: ${stats.stubbed.join(', ')}`);
