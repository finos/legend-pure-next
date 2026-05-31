[![FINOS - Incubating](https://cdn.jsdelivr.net/gh/finos/contrib-toolbox@master/images/badge-incubating.svg)](https://community.finos.org/docs/governance/Software-Projects/stages/incubating)

```
    __                              __   ____                     _   __          __
   / /   ___  ____  ___  ____  ____/ /  / __ \__  __________     / | / /__  _  __/ /_
  / /   / _ \/ __ `/ _ \/ __ \/ __  /  / /_/ / / / / ___/ _ \   /  |/ / _ \| |/_/ __/
 / /___/  __/ /_/ /  __/ / / / /_/ /  / ____/ /_/ / /  /  __/  / /|  /  __/>  </ /_
/_____/\___/\__, /\___/_/ /_/\__,_/  /_/    \__,_/_/   \___/  /_/ |_/\___/_/|_|\__/
           /____/             :: the next-generation Pure compiler ::
```


A fresh implementation of **Pure** — the data modeling and query language behind the FINOS [Legend](https://legend.finos.org/) platform — rebuilt around three ideas: **simplicity**, **nimbleness**, and **self-description**.

> [!TIP]
> **New to Pure?** Pure lets you write your organization's data model **once** — a clear, shared dictionary of every concept, attribute, and relationship, mapped to the systems that actually hold the data. A flexible type system and class-level constraints describe each concept *precisely*; modeling and meta-modeling live on the same platform, since Pure itself is described in Pure.
>
> The dictionary then compiles and runs against real backends (SQL, services, in-memory), so it never drifts from reality — it *is* the code that queries it.

```scala
Class my::events::Person
{
  name: String[1];
}

Class my::events::Event
[
  reasonableCapacity: $this.venueCapacity > 0 && $this.venueCapacity <= 100000,
  nameNotBlank:       $this.name->length() > 0
]
{
  name:          String[1];
  venueCapacity: Integer[1];
}

Class my::events::Concert extends Event
[
  sensibleDuration: $this.durationMinutes >= 30 && $this.durationMinutes <= 360,
  knownArtist:      $this.artist.name->length() > 0
]
{
  artist:          Person[1];
  durationMinutes: Integer[1];
}

// All concerts whose artist's name starts with the given prefix
// and whose venue holds at least minCapacity people.
function my::events::concertsByArtistPrefix(prefix: String[1], minCapacity: Integer[1]): Concert[*]
{
  Concert.all()->filter(c | $c.artist.name->startsWith($prefix) && $c.venueCapacity >= $minCapacity)
}
```

## Why Legend Pure Next?

- **Simpler.** A focused codebase — parser, compiler, runtime — without years of accumulated layers.
- **Nimbler.** One specification, multiple backends (java-direct, Truffle/GraalVM, TypeScript). A model written once runs on the JVM or in the browser.
- **Self-describing.** The metamodel is RDF, the compiler's helper layer is written in Pure itself, and the same description drives code generation for every backend.

## Architecture at a glance

The project is organized around a single **specification** (the m3 metamodel + grammar) and several **platforms** that build on top of it:

```
pure/specification        →  m3.ttl metamodel + ANTLR grammar (the source of truth)
pure/modules              →  Pure language stdlib + translation modules (Pure → target lang)
bootstrap/                →  Java-direct backend (parser, compiler, runtime, Web IDE)
platforms/truffle/        →  GraalVM Truffle backend for optimized execution
platforms/typescript/     →  TypeScript backend (run Pure in browsers / Node)
platforms/java/           →  Generated-Java backend (to come)
```

---

### Specification — [`pure/specification/`](pure/specification/)

The single normative description of Pure. Every backend reads from this tree; only the executable bits (native bodies, codegen targets) vary per platform. It splits into four layers, each a sibling directory:

```
pure/specification/
├── m3.ttl + m3_*.ttl/.fbs         →  Metamodel  (what Pure is)
├── grammar/                       →  Syntax     (how Pure is written)
├── compiler/                      →  Lowering   (how source becomes metamodel)
└── runtime/                       →  Semantics  (how compiled code behaves)
```

**Metamodel** — RDF/TTL files at the root of the spec define every Pure concept:
- [`m3.ttl`](pure/specification/m3.ttl) — the core M3 metamodel (`Class`, `Property`, `Function`, `GenericType`, `Multiplicity`, …).
- [`m3_protocol_addition.ttl`](pure/specification/m3_protocol_addition.ttl) — protocol-level extensions (`Section`, imports, …).
- [`m3_fbs_addition.fbs`](pure/specification/m3_fbs_addition.fbs) + [`m3-fbs-field-ids.txt`](pure/specification/m3-fbs-field-ids.txt) — supplemental FlatBuffer tables and stable field-ID pinning for the on-disk `.pdb` format.
- [`PROTOCOL_INFO_ANNOTATIONS.md`](pure/specification/PROTOCOL_INFO_ANNOTATIONS.md) — authoritative reference for the `ProtocolInfo` annotations that drive every code generator.

**Grammar** ([`grammar/`](pure/specification/grammar/)) — surface syntax:
- [`antlr/`](pure/specification/grammar/antlr/) — top-level `.g4` files (`TopLexer.g4`, `TopParser.g4`) and the M3 grammar (`m3/M3Lexer.g4`, `m3/M3Parser.g4`).
- [`mapping/`](pure/specification/grammar/mapping/) — DSL rules that map grammar productions back to metamodel classes.
- [`textMate/syntaxes/`](pure/specification/grammar/textMate/syntaxes/) — editor highlighting grammar.
- [`tests/`](pure/specification/grammar/tests/) — parser fixtures organized per construct (`class/`, `function/`, `association/`, `enumeration/`, `relation/`, `valueSpecification/`, …).

**Compiler** ([`compiler/`](pure/specification/compiler/)) — lowering from AST to resolved metamodel:
- [`compiler.md`](pure/specification/compiler/compiler.md) — the normative three-pass model (skeleton → structural resolution → expression resolution + validation) and reverse-reference-index semantics.
- [`tests/`](pure/specification/compiler/tests/) — parameterized `###CompiledGraph` and `###Error` fixtures, mirrored per construct.
- [`compiler-pure/`](pure/specification/compiler/compiler-pure/) — **the slice of the compiler written in Pure itself**. Its own `compiler/`, `printer/`, `test/` trees; this is what makes Legend Pure Next self-describing.

**Runtime** ([`runtime/`](pure/specification/runtime/)) — the executable surface area:
- [`functions/`](pure/specification/runtime/functions/) — the `meta::pure::functions::*` standard library, grouped by domain: [`collection/`](pure/specification/runtime/functions/collection/), [`string/`](pure/specification/runtime/functions/string/), [`relation/`](pure/specification/runtime/functions/relation/), [`date/`](pure/specification/runtime/functions/date/), [`math/`](pure/specification/runtime/functions/math/), [`boolean/`](pure/specification/runtime/functions/boolean/), [`lang/`](pure/specification/runtime/functions/lang/), [`meta/`](pure/specification/runtime/functions/meta/), [`io/`](pure/specification/runtime/functions/io/), [`asserts/`](pure/specification/runtime/functions/asserts/).
- [`featureTests/`](pure/specification/runtime/featureTests/) — backend-agnostic tests for cross-cutting features (dispatch, open variables, pointer access, …).
- [`test/`](pure/specification/runtime/test/) — the PCT (Pure Compatibility Test) harness shared across backends.
- [`printer/`](pure/specification/runtime/printer/) — compiled-form and protocol-form printers.

---

### Modules — [`pure/modules/`](pure/modules/)

Pure code that ships with the platform. Two siblings, each fanning out per backend:

```
pure/modules/
├── language/      →  Native function bodies for the stdlib, per backend
│   ├── java/
│   └── typescript/
└── translation/   →  Pure-source → target-language translators
    ├── shared/      backend-agnostic helpers
    ├── java/
    └── typescript/
```

**Language** ([`language/`](pure/modules/language/)) — backs the stdlib declared under `pure/specification/runtime/functions/` with executable implementations. Each backend directory has the same shape:
- [`language/java/code/`](pure/modules/language/java/code/) and [`language/typescript/code/`](pure/modules/language/typescript/code/) — `native.pure` (maps each Pure native to its target-language implementation), plus `factories/`, `metamodel/`, `serialization/` for backend-specific scaffolding. TypeScript also ships a small smoke `demo.pure`.

**Translation** ([`translation/`](pure/modules/translation/)) — translators that lower a Pure program into idiomatic target-language source. Each translator has the same skeleton (`translation.pure` entry point, `runtime.pure` helpers, `gallery.pure` driver, `tests/`):
- [`translation/shared/code/`](pure/modules/translation/shared/code/) — backend-agnostic infrastructure: `canonical.pure`, `decompile.pure`, `dependencies.pure`, `gallery_shell.pure` (the HTML harness both galleries instantiate).
- [`translation/typescript/code/`](pure/modules/translation/typescript/code/) — Pure → TypeScript, paired with `platforms/typescript/`. Adds `pdb.pure` + `reflect.pure` (PDB-to-TS bulk translation, Pure-graph reflection) and ships a `runtime-lib.ts` to consumers.
- [`translation/java/code/`](pure/modules/translation/java/code/) — Pure → Java. The translator exists today and powers the Java gallery; **the matching platform is in flight** (see [Platforms](#platforms--platforms) below).

---

### Bootstrap — [`bootstrap/`](bootstrap/)

The Java-direct backend. This is where new language features land first; the Truffle and (soon) Java-generated platforms are built on top.

```
bootstrap/
├── bin/                            →  CLI entry scripts (just ide, …)
└── legend-pure-next-bootstrap/     →  Maven multi-module project
    ├── …-bootstrap-core/             Library core, four siblings:
    │   ├── …-parser/                   ANTLR4 → AST
    │   ├── …-compiler/                 .pure → .pdb (the 3-pass model)
    │   ├── …-execution/                Tree-walking interpreter
    │   └── …-generators/               Codegen: RDF → Java + FlatBuffers
    ├── …-bootstrap-cli/              Command-line driver
    └── …-bootstrap-ide/              Web IDE: Monaco editor + LSP
                                      (ports 9090/9091, `just ide`)
```

---

### Platforms — [`platforms/`](platforms/)

Execution targets that reuse the bootstrap front-end. Each ships a **gallery** rendering every PCT test in `meta::pure::functions` side-by-side as Pure source vs. the translated target — a live coverage snapshot per backend. [Browse all galleries →](https://finos.github.io/legend-pure-next/)

<pre>
platforms/
├── truffle/      →  GraalVM Truffle backend (optimized execution)
│   └─ Gallery: <a href="https://finos.github.io/legend-pure-next/truffle.html">Live ↗</a>
├── typescript/   →  TypeScript runtime (browser / Node)
│   └─ Gallery: <a href="https://finos.github.io/legend-pure-next/typescript.html">Live ↗</a>
└── java/         →  Generated-Java backend (to come)
    └─ Gallery: <a href="https://finos.github.io/legend-pure-next/java.html">Live ↗</a>
</pre>

**Truffle** ([`truffle/`](platforms/truffle/)) — partial-evaluating execution + native-image build for fast one-shot runs. Internally: `truffle-platform/` (PDB writer + runtime), `truffle-extension/` (TS + Java translator bridges), `truffle-ide/`.

**TypeScript** ([`typescript/`](platforms/typescript/)) — Pure models + queries run in the browser or Node. Paired with [`pure/modules/translation/typescript/`](pure/modules/translation/typescript/).

**Java** ([`java/`](platforms/java/)) _(to come)_ — AOT translation from Pure to idiomatic Java source. The translator already lives at [`pure/modules/translation/java/`](pure/modules/translation/java/); the platform will wrap it into a first-class build/run target.

## Getting started

### Prerequisites

- Java 21 (GraalVM recommended if you want the Truffle backend)
- [flatc 25.2.10](https://github.com/google/flatbuffers/releases/tag/v25.2.10) on your `PATH`
- [`just`](https://github.com/casey/just) as the top-level build runner
- Node.js + [pnpm](https://pnpm.io/) for the TypeScript platform

### Build & test

```sh
just              # build + run the default test suite (alias for `just test`)
just build        # build, no tests
just test         # run each subproject's core test suite (mvn surefire + spec)
just test-all     # everything in `test` plus self-host parity + runtime suites
just ide          # launch the Web IDE
just clean        # delete build artifacts
```

Per-platform recipes are addressable as `just bootstrap::<recipe>`, `just truffle::<recipe>`, `just typescript::<recipe>`. Run `just --list bootstrap` (etc.) to see what each offers.

## Usage example

> TODO: a minimal end-to-end snippet — define a class in Pure, compile it, run a query.

## Roadmap

> TODO: link to the public roadmap / milestones once published.

## Contributing

For questions, bugs, or feature requests please open an [issue](https://github.com/finos/legend-pure-next/issues).
For anything else please send an email to {project mailing list}. <!-- TODO: replace with the actual address -->

To submit a contribution:
1. Fork it (<https://github.com/finos/legend-pure-next/fork>)
2. Create your feature branch (`git checkout -b feature/fooBar`)
3. Read our [contribution guidelines](.github/CONTRIBUTING.md) and [Community Code of Conduct](https://www.finos.org/code-of-conduct)
4. Commit your changes (`git commit -am 'Add some fooBar'`)
5. Push to the branch (`git push origin feature/fooBar`)
6. Open a pull request

_NOTE:_ Commits and pull requests to FINOS repositories will only be accepted from those contributors with an active, executed Individual Contributor License Agreement (ICLA) with FINOS OR who are covered under an existing and active Corporate Contribution License Agreement (CCLA) executed with FINOS. Commits from individuals not covered under an ICLA or CCLA will be flagged and blocked by the FINOS Clabot tool (or [EasyCLA](https://community.finos.org/docs/governance/Software-Projects/easycla)). Please note that some CCLAs require individuals/employees to be explicitly named on the CCLA.

*Need an ICLA? Unsure if you are covered under an existing CCLA? Email [help@finos.org](mailto:help@finos.org).*

## License

Copyright 2026 FINOS

Distributed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).

SPDX-License-Identifier: [Apache-2.0](https://spdx.org/licenses/Apache-2.0)
