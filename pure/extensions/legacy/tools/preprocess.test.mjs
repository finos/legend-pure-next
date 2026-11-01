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

// The legacy grammar preprocessor: legacy-only syntax rewritten into next's (just extensions::legacy::test-tools).

import test from 'node:test';
import assert from 'node:assert/strict';
import { preprocess } from './preprocess.mjs';

const DOC = 'meta::pure::profiles::doc.doc';

test('a documentation literal before a function is its doc tag', () =>
{
   assert.equal(
      preprocess("'''\nHashes text.\n'''\nfunction a::hash(t: String[1]): String[1]\n{\n  $t\n}\n"),
      `function {${DOC} = 'Hashes text.'} a::hash(t: String[1]): String[1]\n{\n  $t\n}\n`);
});

test('it joins the tagged values an element already has, after its stereotypes', () =>
{
   assert.equal(
      preprocess("'''Doc.'''\nClass <<a::p.s>> {a::p.t = 'x'} a::C\n{\n}\n"),
      `Class <<a::p.s>> {a::p.t = 'x', ${DOC} = 'Doc.'} a::C\n{\n}\n`);
   assert.equal(
      preprocess("'''Doc.'''\nnative function <<a::p.s>> a::f(): String[1];\n"),
      `native function <<a::p.s>> {${DOC} = 'Doc.'} a::f(): String[1];\n`);
});

test('it documents every kind of element', () =>
{
   for (const kind of ['Class', 'Enum', 'Profile', 'Association', 'Measure', 'Primitive'])
   {
      assert.equal(preprocess(`'''D'''\n${kind} a::X\n{\n}\n`), `${kind} {${DOC} = 'D'} a::X\n{\n}\n`);
   }
});

test('its text is dedented, and escaped as a string', () =>
{
   assert.equal(
      preprocess("'''\n    First line.\n\n      Indented, it's \\\\ kept.\n'''\nfunction a::f(): String[1]\n{\n  'x'\n}\n"),
      `function {${DOC} = 'First line.\\n\\n  Indented, it\\'s \\\\\\\\ kept.'} a::f(): String[1]\n{\n  'x'\n}\n`);
});

test('a triple-quoted string in code is a string, left as it is', () =>
{
   const source = "function a::f(): String[1]\n{\n  '''\n  text\n  '''\n}\n";
   assert.equal(preprocess(source), source);
});

test("''' inside a string or a comment is left as it is", () =>
{
   const source = "// '''not a doc'''\n/* '''nor this''' */\nfunction a::f(): String[1]\n{\n  'a \\' ''' b'\n}\n";
   assert.equal(preprocess(source), source);
});

test('only ###Pure sections are rewritten', () =>
{
   const other = "###Mapping\nMapping a::M\n(\n  '''not a doc'''\n)\n";
   assert.equal(preprocess("'''D'''\nfunction a::f(): String[1]\n{\n  'x'\n}\n" + other),
      `function {${DOC} = 'D'} a::f(): String[1]\n{\n  'x'\n}\n` + other);
});

test('a documentation literal in a file with CRLF line endings reads as with LF', () =>
{
   assert.equal(
      preprocess("'''\r\nFirst.\r\n\r\nSecond.\r\n'''\r\nfunction a::f(): String[1]\r\n{\r\n  'x'\r\n}\r\n"),
      `function {${DOC} = 'First.\\n\\nSecond.'} a::f(): String[1]\r\n{\r\n  'x'\r\n}\r\n`);
});

test('a measure, which next has no units for, is set aside with a note', () =>
{
   assert.equal(preprocess("Class a::C\n{\n}\n\nMeasure a::RomanLength\n{\n   *Pes: x -> $x;\n   Cubitum: x -> $x * 1.5;\n}\n\nfunction a::f(): String[1]\n{\n  '}'\n}\n"),
      "Class a::C\n{\n}\n\n// legacy Measure a::RomanLength set aside: next has no units\n\nfunction a::f(): String[1]\n{\n  '}'\n}\n");
});

test("a TDS literal's quoted cells are next's: '…' (\\ escaping) becomes \"…\" (\"\" escaping)", () =>
{
   assert.equal(
      preprocess("function a::f(): Any[1]\n{\n  #TDS\n    id, 'my col'\n    1, '[1,2,3]'\n    2, 'it\\'s \"x\"'\n    3, plain\n  #\n}\n"),
      "function a::f(): Any[1]\n{\n  #TDS\n    id, 'my col'\n    1, \"[1,2,3]\"\n    2, \"it's \"\"x\"\"\"\n    3, plain\n  #\n}\n");
});

test("a TDS literal's cells are not read as strings, nor its # as the end when quoted", () =>
{
   assert.equal(
      preprocess("function a::f(): Any[1]\n{\n  #TDS\n    t\n    it's\n    '#1'\n  #->size()\n}\n"),
      "function a::f(): Any[1]\n{\n  #TDS\n    t\n    it's\n    \"#1\"\n  #->size()\n}\n");
});
