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

// Generates loader/code/rewrite.pure: rewrite(x, r), a copy of a parsed source (the protocol model next's
// parser builds, shared/specification/protocol/m3_protocol.pure) with every reference passed through r —
// its pointers through r.pointer, the names of the functions it calls through r.functionName; of the
// stereotypes and tagged values applied, only those r.stereotype and r.taggedValue keep. Each object goes
// through r.pre first and r.post once rebuilt, for rewrites that need to see more than a pointer.
//
//   node tools/generate-protocol-rewrite.mjs
//
// Pure cannot set a property by name, so the copy is one branch per concrete protocol class (subclasses
// first), each rebuilding its object with the properties holding protocol objects rewritten in turn.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const specFile = path.resolve(root, '../../../shared/specification/protocol/m3_protocol.pure');
const outFile = path.join(root, 'loader/code/rewrite.pure');

const PROTOCOL = 'platform::pure::protocol::';
const POINTER = 'platform::pure::protocol::grammar::PointerValue';
const ELEMENT = 'platform::pure::protocol::grammar::PackageableElement';
// The calls whose name is a function's (a DotApplication's is a property's).
const CALLS = new Set(['platform::pure::protocol::grammar::valuespecification::FunctionInvocation',
   'platform::pure::protocol::grammar::valuespecification::ArrowInvocation']);

const spec = fs.readFileSync(specFile, 'utf8');
const classes = new Map();
for (const m of spec.matchAll(/^Class\s+(<<([^>]*)>>\s*)?([\w:]+)(?:\s+extends\s+([^\n{]+))?\s*\{([^}]*)\}/gm))
{
   const properties = [...m[5].matchAll(/^\s*(?:<<[^>]*>>\s*)?(\w+)\s*:\s*([\w:]+)\[([^\]]+)\];/gm)]
      .map((p) => ({ name: p[1], type: p[2], multiplicity: p[3] }));
   classes.set(m[3], {
      path: m[3],
      abstract: (m[2] ?? '').includes('typemodifiers.abstract'),
      supers: (m[4] ?? '').split(',').map((s) => s.trim()).filter(Boolean),
      properties,
   });
}

const ancestors = (c) => [c, ...c.supers.filter((s) => classes.has(s)).flatMap((s) => ancestors(classes.get(s)))];
const isA = (c, of) => ancestors(c).some((a) => a.path === of);
// Its properties, its own over those it inherits (a property may be redeclared).
const allProperties = (c) =>
{
   const byName = new Map();
   ancestors(c).reverse().forEach((a) => a.properties.forEach((p) => byName.set(p.name, p)));
   return [...byName.values()];
};

// A property's rewritten value: a protocol object (or any value, for an Any) is rewritten; a call's name
// goes through r.functionName; the rest (an element's own package among them) is left as it is.
function rewritten(c, p, x)
{
   const value = `$${x}.${p.name}`;
   if (p.name === 'functionName' && CALLS.has(c.path)) return `$r.functionName->eval($${x})`;
   const rewritable = p.type.startsWith(PROTOCOL) || p.type === 'platform::pure::protocol::grammar::type::Any';
   // an element's own package is where it is, not a reference
   if (!rewritable || p.name === 'p_sourceInformation' || (p.name === 'package' && isA(c, ELEMENT))) return undefined;
   const cast = `->cast(@${p.type})`;
   // the stereotypes and tagged values applied: those r keeps
   if (p.name === 'stereotypes' || p.name === 'taggedValues')
   {
      const keep = p.name === 'stereotypes' ? 'stereotype' : 'taggedValue';
      return `${value}->filter(v | $r.${keep}->eval($v))->map(v | rewrite($v, $r))${cast}`;
   }
   if (p.multiplicity === '1') return `rewrite(${value}, $r)${cast}`;
   if (p.multiplicity === '0..1') return `if(${value}->isEmpty(), | [], | rewrite(${value}->toOne(), $r)${cast})`;
   return `${value}->map(v | rewrite($v, $r))${cast}`;
}

const depth = (c) => ancestors(c).length;
const concrete = [...classes.values()]
   .filter((c) => !c.abstract && !isA(c, POINTER) && c.path !== POINTER)
   .sort((a, b) => depth(b) - depth(a) || a.path.localeCompare(b.path));

const branches = concrete.map((c, i) =>
{
   const assignments = allProperties(c).map((p) => [p.name, rewritten(c, p, `c${i}`)]).filter(([, v]) => v);
   const body = assignments.length
      ? `^$c${i}(${assignments.map(([n, v]) => `\n            ${n} = ${v}`).join(',')}\n         )`
      : `$c${i}`;
   return `      c${i}: ${c.path}[1] |\n         ${body},`;
});

const header = `// ©2026 JP Morgan Chase & Co. All rights reserved.
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

// GENERATED by tools/generate-protocol-rewrite.mjs from shared/specification/protocol/m3_protocol.pure — do not edit.
`;

fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, `${header}
import meta::pure::bridge::loader::*;

// A copy of a parsed source's object with every reference in it passed through r: its pointers through
// r.pointer, the names of the functions it calls through r.functionName; of the stereotypes and tagged
// values applied, only those r.stereotype and r.taggedValue keep. Each object goes through r.pre first, and
// through r.post once rebuilt.
function meta::pure::bridge::loader::rewrite(x: Any[1], r: meta::pure::bridge::loader::Rewrite[1]): Any[1]
{
   $r.post->eval($r.pre->eval($x)->match([
      p: ${POINTER}[1] | $r.pointer->eval($p),
${branches.join('\n')}
      a: Any[1] | $a
   ]));
}
`);
console.log(`wrote ${path.relative(process.cwd(), outFile)}: ${concrete.length} classes`);
