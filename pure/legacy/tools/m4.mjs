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

// Reader for legend-pure's M4 syntax (`^Classifier name @owner { key : value, ... }`), in which the
// legacy m3.pure is written, and extraction of its classes into a plain model:
//   { path, typeParams: [{name, contravariant}], multParams: [name],
//     generalizations: [genericType], properties: [{name, genericType, multiplicity}] }
// where a genericType is { path } | { typeParam } | { functionType: {parameters, returnType, returnMultiplicity} },
// each with optional typeArguments / multiplicityArguments, and a multiplicity is '1', '0..1', '*', ... or a parameter name.

// ---------- tokens and tree ----------

function tokenize(s)
{
   const toks = [];
   let i = 0;
   while (i < s.length)
   {
      const c = s[i];
      if (/\s/.test(c)) { i++; continue; }
      if (s.startsWith('//', i)) { const j = s.indexOf('\n', i); i = j < 0 ? s.length : j; continue; }
      if (s.startsWith('/*', i)) { i = s.indexOf('*/', i) + 2; continue; }
      if (c === "'")
      {
         let j = i + 1, v = '';
         while (s[j] !== "'") { if (s[j] === '\\') { v += s[j + 1]; j += 2; } else { v += s[j++]; } }
         toks.push({ t: 'str', v });
         i = j + 1;
         continue;
      }
      if (s.startsWith('?[', i)) { i = s.indexOf(']?', i) + 2; continue; } // source information
      if ('^{}[],:.@'.includes(c)) { toks.push({ t: c }); i++; continue; }
      const m = /^[A-Za-z0-9_$%+\-]+/.exec(s.slice(i, i + 200));
      if (!m) throw new Error(`M4: unexpected '${c}' at offset ${i}`);
      toks.push({ t: 'id', v: m[0] });
      i += m[0].length;
   }
   return toks;
}

export function parseM4(text)
{
   const T = tokenize(text);
   let p = 0;
   const peek = () => T[p];
   const eat = (t) =>
   {
      if (T[p].t !== t) throw new Error(`M4: expected ${t}, got ${JSON.stringify(T[p])} at token ${p}`);
      return T[p++];
   };
   // name (.key[arrayKey])*
   const pathExpr = () =>
   {
      const segs = [{ k: null, v: eat('id').v }];
      while (peek() && peek().t === '.')
      {
         p++;
         const k = eat('id').v;
         let a = null;
         if (peek().t === '[') { p++; a = eat('id').v; eat(']'); }
         segs.push({ k, v: a });
      }
      return segs;
   };
   const element = () =>
   {
      const k = peek();
      if (k.t === '^') return metaClass();
      if (k.t === 'str') { p++; return { lit: k.v }; }
      return { ref: pathExpr() };
   };
   const rightSide = () =>
   {
      if (peek().t !== '[') return [element()];
      p++;
      const out = [];
      if (peek().t !== ']') { out.push(element()); while (peek().t === ',') { p++; out.push(element()); } }
      eat(']');
      return out;
   };
   const metaClass = () =>
   {
      eat('^');
      const classifier = pathExpr();
      let name = null, owner = null;
      if (peek().t === 'id') name = eat('id').v;
      if (peek().t === '@') { p++; owner = pathExpr(); }
      eat('{');
      const props = [];
      if (peek().t !== '}')
      {
         do
         {
            if (peek().t === ',') p++;
            const key = pathExpr();
            eat(':');
            props.push({ key, val: rightSide() });
         } while (peek().t === ',');
      }
      eat('}');
      return { classifier, name, owner, props };
   };
   const out = [];
   while (p < T.length) out.push(metaClass());
   return out;
}

// ---------- navigation ----------

const last = (segs) => segs[segs.length - 1].v;
const lastKey = (segs) => segs[segs.length - 1].k;
// Root.children[meta].children[pure] -> meta::pure
const childrenPath = (segs) => segs.filter((s) => s.k === 'children' && s.v).map((s) => s.v).join('::');
// the path an element reference designates: Root.children[a].children[B] -> a::B; a root-level name (String) -> itself
const refPath = (segs) => childrenPath(segs) || segs[0].v;

const prop = (mc, name) => mc.props.find((pp) => lastKey(pp.key) === 'properties' && last(pp.key) === name);
const one = (mc, name) => prop(mc, name)?.val[0];
const many = (mc, name) => prop(mc, name)?.val ?? [];

const MULTIPLICITIES = { PureOne: '1', ZeroOne: '0..1', ZeroMany: '*', OneMany: '1..*', PureZero: '0' };

function multiplicity(v)
{
   if (!v) return null;
   if (v.ref) return MULTIPLICITIES[last(v.ref)] ?? last(v.ref);
   const param = one(v, 'multiplicityParameter');
   if (param) return param.lit;
   const bound = (b) => { const mv = one(v, b); return mv ? one(mv, 'value')?.lit : undefined; };
   const lo = bound('lowerBound'), hi = bound('upperBound');
   if (lo === undefined) return '*';
   return lo === hi ? lo : `${lo}..${hi ?? '*'}`;
}

function genericType(v)
{
   if (!v) return null;
   if (v.ref) return { path: refPath(v.ref) }; // a reference to a canonical generic type
   const raw = one(v, 'rawType');
   const tp = one(v, 'typeParameter');
   let gt;
   if (raw && raw.ref) gt = { path: refPath(raw.ref) };
   else if (raw && last(raw.classifier) === 'FunctionType')
   {
      gt = {
         functionType: {
            parameters: many(raw, 'parameters').map((pv) => ({ genericType: genericType(one(pv, 'genericType')), multiplicity: multiplicity(one(pv, 'multiplicity')) })),
            returnType: genericType(one(raw, 'returnType')),
            returnMultiplicity: multiplicity(one(raw, 'returnMultiplicity')),
         },
      };
   }
   else if (tp) gt = { typeParam: one(tp, 'name').lit };
   else gt = { path: '?' };
   const ta = many(v, 'typeArguments').map(genericType);
   const ma = many(v, 'multiplicityArguments').map(multiplicity);
   if (ta.length) gt.typeArguments = ta;
   if (ma.length) gt.multiplicityArguments = ma;
   return gt;
}

/**
 * The classes of a legacy M4 metamodel file.
 */
export function legacyClasses(text)
{
   const result = [];
   for (const mc of parseM4(text))
   {
      if (last(mc.classifier) !== 'Class') continue;
      const pkg = mc.owner ? childrenPath(mc.owner) : '';
      result.push({
         path: pkg ? `${pkg}::${mc.name}` : mc.name,
         name: mc.name,
         typeParams: many(mc, 'typeParameters').map((t) => ({ name: one(t, 'name').lit, contravariant: one(t, 'contravariant')?.ref ? last(one(t, 'contravariant').ref) === 'true' : false })),
         multParams: many(mc, 'multiplicityParameters').map((m) => one(m, 'values')?.lit ?? m.lit ?? '?'),
         generalizations: many(mc, 'generalizations').map((g) => genericType(one(g, 'general'))),
         properties: many(mc, 'properties').map((pm) => ({ name: pm.name, genericType: genericType(one(pm, 'genericType')), multiplicity: multiplicity(one(pm, 'multiplicity')) })),
      });
   }
   return result;
}
