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

// The legacy grammar preprocessor: rewrites the syntax legacy Pure has and next's grammar does not into
// next's, before next's parser reads a legacy source (the loader, loader/code/loader.pure, takes it from
// there). It lives in the legacy layer so that next's grammar stays its own.
//
// It reads ###Pure sections as tokens — strings, comments, nesting — so a rewrite only applies where the
// legacy syntax means what it is rewritten for:
//   - a documentation literal, '''…''' at top level before an element, is the element's doc tag
//     (meta::pure::profiles::doc.doc), its text dedented. A '''…''' string inside code is a string;
//   - a Measure, which next has no units for, is set aside, a comment in its place;
//   - a #TDS literal's quoted cells, '…' in legacy, are next's "…".

export const DOC_TAG = 'meta::pure::profiles::doc.doc';

const ELEMENT = /^(native\s+function|function|Class|Enum|Profile|Association|Measure|Primitive)\b/;
// The source with its ###Pure sections rewritten; other sections are left as they are.
export function preprocess(source)
{
   return source.split(/(?=^###\w+[^\n]*$)/m)
      .map((section) => (/^###(?!Pure\b)\w/.test(section) ? section : withoutMeasures(preprocessPure(section))))
      .join('');
}

function preprocessPure(text)
{
   let out = '';
   let depth = 0;
   let i = 0;
   while (i < text.length)
   {
      if (text.startsWith('#TDS', i))
      {
         const island = tdsIsland(text, i);
         out += island.text;
         i = island.end;
      }
      else if (text.startsWith("'''", i))
      {
         const end = text.indexOf("'''", i + 3);
         if (end < 0) throw new Error(`unterminated ''' at ${i}`);
         const after = end + 3;
         const element = depth === 0 ? text.slice(after).match(/^\s*/)[0].length + after : -1;
         if (depth === 0 && ELEMENT.test(text.slice(element)))
         {
            return out + documented(text.slice(element), dedent(text.slice(i + 3, end)));
         }
         out += text.slice(i, after);
         i = after;
      }
      else if (text[i] === "'")
      {
         const end = stringEnd(text, i);
         out += text.slice(i, end);
         i = end;
      }
      else if (text.startsWith('//', i) || text.startsWith('/*', i))
      {
         const end = commentEnd(text, i);
         out += text.slice(i, end);
         i = end;
      }
      else
      {
         if ('{(['.includes(text[i])) depth++;
         if ('})]'.includes(text[i])) depth--;
         out += text[i++];
      }
   }
   return out;
}

// The #TDS literal starting at `start`, its body's quoted cells next's: legacy reads them as Pure strings
// ('…', \ escaping), next as CSV does ("…", "" escaping). Its header, whose quoted names are '…' in both,
// is left as it is. `end` is just after the # closing it (a # in a quoted cell does not).
function tdsIsland(text, start)
{
   // the header is the first line holding something, #TDS's own or the next
   const firstEnd = text.indexOf('\n', start);
   const headerEnd = firstEnd >= 0 && text.slice(start + 4, firstEnd).trim() === '' ? text.indexOf('\n', firstEnd + 1) : firstEnd;
   const closing = text.indexOf('#', start + 4);
   if (headerEnd < 0 || (closing >= 0 && closing < headerEnd)) return { text: text.slice(start, closing + 1), end: closing + 1 };
   let out = text.slice(start, headerEnd);
   let i = headerEnd;
   let cellStart = true;
   while (i < text.length && text[i] !== '#')
   {
      const c = text[i];
      if (cellStart && c === "'")
      {
         let value = '';
         i++;
         while (i < text.length && text[i] !== "'")
         {
            value += text[i] === '\\' ? text[++i] : text[i];
            i++;
         }
         if (i >= text.length) throw new Error(`unterminated TDS cell at ${start}`);
         out += `"${value.replace(/"/g, '""')}"`;
         i++;
         cellStart = false;
         continue;
      }
      if (c === ',' || c === '\n') cellStart = true;
      else if (c !== ' ' && c !== '\t' && c !== '\r') cellStart = false;
      out += c;
      i++;
   }
   if (i >= text.length) throw new Error(`unterminated #TDS at ${start}`);
   return { text: out + '#', end: i + 1 };
}

// The element starting `element` with `doc` as its doc tag: in the tagged values it has, after its
// stereotypes, or in a block of its own there. The rest of the text is preprocessed in turn.
function documented(element, doc)
{
   const keyword = element.match(ELEMENT)[0];
   let i = keyword.length;
   const skipSpace = () => { while (/\s/.test(element[i])) i++; };
   skipSpace();
   if (element.startsWith('<<', i))
   {
      i = element.indexOf('>>', i) + 2;
      skipSpace();
   }
   const tag = `${DOC_TAG} = '${escape(doc)}'`;
   if (element[i] === '{')
   {
      const close = stringAwareClose(element, i);
      return element.slice(0, close) + `, ${tag}}` + preprocessPure(element.slice(close + 1));
   }
   const head = element.slice(0, i).replace(/\s*$/, '');
   return `${head} {${tag}} ` + preprocessPure(element.slice(i));
}

// The section with each top-level Measure replaced by a note that it is set aside.
function withoutMeasures(text)
{
   let out = '';
   let i = 0;
   while (i < text.length)
   {
      if (text[i] === "'") { const e = stringEnd(text, i); out += text.slice(i, e); i = e; continue; }
      if (text.startsWith('//', i) || text.startsWith('/*', i)) { const e = commentEnd(text, i); out += text.slice(i, e); i = e; continue; }
      const measure = (i === 0 || text[i - 1] === '\n') ? text.slice(i).match(/^Measure\s+([\w:]+)\s*\{/) : undefined;
      if (!measure) { out += text[i++]; continue; }
      out += `// legacy Measure ${measure[1]} set aside: next has no units`;
      i = braceClose(text, i + measure[0].length - 1) + 1;
   }
   return out;
}

// The index of the bracket closing the one at `open`, strings and comments skipped.
function braceClose(text, open)
{
   const close = { '{': '}', '(': ')', '[': ']' }[text[open]];
   let depth = 0;
   for (let i = open; i < text.length; i++)
   {
      if (text[i] === "'") { i = stringEnd(text, i) - 1; continue; }
      if (text.startsWith('//', i) || text.startsWith('/*', i)) { i = commentEnd(text, i) - 1; continue; }
      if (text[i] === text[open]) depth++;
      else if (text[i] === close && --depth === 0) return i;
   }
   throw new Error(`unbalanced ${text[open]} at ${open}`);
}

// The index of the `}` closing the tagged values block opening at `open` (its values are strings).
function stringAwareClose(text, open)
{
   let i = open + 1;
   while (text[i] !== '}')
   {
      i = text[i] === "'" ? stringEnd(text, i) : i + 1;
      if (i >= text.length) throw new Error(`unterminated tagged values at ${open}`);
   }
   return i;
}

// The index just after the single-quoted string starting at `start`.
function stringEnd(text, start)
{
   let i = start + 1;
   while (i < text.length && text[i] !== "'") i += text[i] === '\\' ? 2 : 1;
   return i + 1;
}

// The index just after the comment starting at `start`.
function commentEnd(text, start)
{
   const end = text.startsWith('//', start) ? text.indexOf('\n', start) : text.indexOf('*/', start + 2);
   return end < 0 ? text.length : text.startsWith('//', start) ? end : end + 2;
}

// A documentation literal's text as legacy reads it: line breaks as \n whatever the file's, without its
// first and last ones, the indentation its lines share removed; blank lines kept.
function dedent(raw)
{
   const lines = raw.replace(/\r\n?/g, '\n').replace(/^[ \t]*\n/, '').replace(/\n[ \t]*$/, '').split('\n');
   const indent = Math.min(...lines.filter((l) => l.trim()).map((l) => l.match(/^[ \t]*/)[0].length));
   return lines.map((l) => (l.trim() ? l.slice(Number.isFinite(indent) ? indent : 0) : '')).join('\n');
}

const escape = (s) => s.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\n/g, '\\n');
