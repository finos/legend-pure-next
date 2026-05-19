# Pure Compiler — Pass Semantics

The Pure compiler turns a set of parsed `PureFile`s into a fully resolved
metamodel graph in **three** passes plus a final validation step. Each pass
walks every element in the local module and progressively fills more slots
on the in-memory `PackageableElement` instances created in pass 1.
Cross-element references (e.g. a class generalizing another class, a
function calling another function, a stereotype pointing at a profile) get
resolved through the shared `MetadataAccess` — base PDBs plus the in-flight
local module.

The passes exist because Pure source allows forward references: element `A`
may legally reference element `B` even when `B` is declared after `A` in the
same file. The compiler therefore creates every element's skeleton before
resolving any cross-references that depend on it.

## Why this matters

The order in which slots get populated determines what a cross-reference
lookup sees. A lookup in pass 2 of element `A` that lands on element `B`
gets whatever pass has filled in on `B` so far — nothing more. Validators
and resolvers that need a specific slot on a target element must run in (or
after) the pass that populates that slot.

## Passes

### Pass 1 — element skeletons

For each grammar `PackageableElement` in every parsed file, the compiler
constructs the corresponding metamodel `Impl` and writes the bare minimum
to identify it: its `_name`, eventually its `_package`, and class identity
(`new ClassImpl()`, `new UserDefinedFunctionImpl()`, etc.).

No annotations, no generic types, no properties, no generalizations, no
imports applied — those references all cross element boundaries and would
require lookups against an index that isn't fully populated yet.

Output of pass 1: `elementIndex` contains every locally-declared element
keyed by its fully-qualified path, each entry holding only a name.

### Pass 2 — structural resolution

For each element in `elementIndex`, fill in the slots that depend on other
elements but **not** on expression-level resolution:

- `Class`: type parameters, multiplicity parameters, generalizations,
  classifier generic type, properties (with their generic types and inline
  annotations), qualified property shells, constraints shells, source info.
- `UserDefinedFunction`: parameters, return type, classifier generic type,
  expression sequence (preliminary), constraint shells.
- `NativeFunction`: same as UDF but no expression body.
- `Association`: properties, classifier generic type.
- `Enumeration`: values, classifier generic type, value-level annotations.
- `Profile`: stereotype/tag definitions.
- `PrimitiveType`: generalizations, classifier generic type.

Pass 2 performs cross-element lookups. Because elements are processed in
index order, the target of a lookup may be only partially filled in
(everything pass 1 has done, but possibly nothing pass 2 has done). The
compiler's invariant is that pass 2 only relies on pass-1 outputs of the
target — not on pass-2 outputs.

`updatePackageTree` runs between pass 2 and pass 3: it walks every
compiled element and stitches `_package` slots to point at the resolved
parent `Package` (which itself acquires its `children` set). Pass 2 leaves
`_package` null for local-module elements to avoid circular pointer
cleanup; pass 3 sees a coherent package tree.

### Pass 3 — expression resolution and validation

For each element, walk its `_expressionSequence` (and constraint
expressions, qualified-property bodies, etc.) and resolve every
`FunctionExpression._func`, every `PropertyAccess`, every type reference,
against a now-complete `elementIndex`. By pass 3 every element has
completed pass 2, so cross-references see the fully populated target.

Pass 3 also runs validation that requires consistent metamodel state:
return-type / multiplicity compatibility checks, override compatibility,
qualified-property dispatch validation, etc.

## Reverse reference index

Throughout passes 2 and 3 the compiler builds a **reverse reference index**
— a map from `target_path` → set of `caller_path`s — as a side-effect of
element resolution. The index has two purposes: (1) downstream validators
need to know who references what (e.g. lean-vs-tests enforcement), and
(2) IDE features like find-references and rename-with-callers consume it.

### Where references are recorded

The Java compiler uses a `RecordingMetadataAccess` wrapper around the
`MetadataAccess` it passes to handlers. Every `getElement(path)` lookup
that resolves to a non-noise path is recorded against the *current
caller*. The wrapper provides implicit, comprehensive coverage of every
type/element lookup the compiler does.

Pure mirrors this with **explicit** `recordReference` calls at the key
resolution sites (since Pure's `resolveElement` returns just the element,
not a context):

- `genericTypeCompiler` — every resolved type ref
- `annotationCompiler` — sub-element refs for stereotypes/tags
  (`Profile.stereotypeName` / `Profile.tagName`)
- `functionApplicationResolver` — winner function at validation success
- `dotApplicationResolver` — property/QP refs at `_func` setter
- `newResolver` — class refs from `^Foo(...)` construction

### Caller scoping

The "current caller" starts as the element being compiled (set by the
pass-2 / pass-3 dispatch via `setCurrentElement`). When the compile
descends into a sub-element (property, qualified property, constraint,
enum value), the caller is refined via `withCallerSubElement(subName)` so
references made inside attribute to `Class.propName` rather than
`Class`. Sub-element granularity exists on both sides — target paths can
also carry the dot suffix (e.g. `Profile.stereotypeName`,
`MyEnum.VALUE`, `Foo.bar`).

### Noise filter

`recordReference` filters out anything matching `meta::pure::metamodel::*`
— the compiler internally looks up dozens of metamodel types per
compile, and including them would drown the signal. Self-references
(target == caller) are also filtered.

## Lean-references validator

After pass 3, the **`LeanReferencesValidator`** scans the reverse index
to enforce a hard rule: **non-test elements may not reference test-only
elements**. The validator iterates every recorded `(target, caller)`
pair, classifies each via `TestElementFilter.isTestElement` (anything
stereotyped under `meta::pure::profiles::test` or with `<<PCT.test>>` /
`<<PCT.adapter>>`), and emits one `CompilationError` per violation. Sub-
element paths resolve to their parent for classification; the caller
element's `_sourceInformation` provides the `(at sourceId:line c col)`
suffix.

Violations are sorted by `(callerPath, targetPath)` so the output is
deterministic across Java and Pure compilers, regardless of recording
order. Java's `LeanReferencesValidator.validate` and Pure's
`meta::pure::compiler::validateLeanReferences` produce byte-identical
error lists for the same input — a critical parity property for the
test suite (e.g. `leanReferencesTest_E_nonTestReferencesTestElement`)
which runs on both compilers.

## Implementation pointers

- `org.finos.legend.pure.m3.module.localModule.topLevel.TopLevelCompiler` —
  driver that runs the three passes per local module.
- `org.finos.legend.pure.m3.module.localModule.LocalModule.compile` —
  wires the validator into the post-pass-3 hook so violations surface in
  `CompilationResult.errors()`.
- `org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.elements.*Handler` —
  per-element-kind logic for each pass (Class, UDF, Native, Association,
  Enumeration, Profile, PrimitiveType).
- `org.finos.legend.pure.m3.module.RecordingMetadataAccess` —
  the lookup wrapper that builds the reverse index in Java.
- `org.finos.legend.pure.m3.module.LeanReferencesValidator` —
  the post-compile validator.
- `pure/specification/compiler/compiler-pure/compiler/leanReferencesValidator.pure` —
  the Pure mirror of the validator, wired into both `compile` and
  `compileDir` at the end of pass 3.
