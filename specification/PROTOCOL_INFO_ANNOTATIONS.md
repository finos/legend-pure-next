# `ProtocolInfo` annotations

Annotations declared on the `ProtocolInfo` profile (`m3.ttl`) drive how M3
classes and properties are translated into generated artifacts: the FBS
schema, the protocol grammar, the Java metamodel, and the writers / readers
for each. Every annotation is checked at parse time by `M3ModelValidator`;
inconsistencies are surfaced as a single failure that lists every offending
class or property.

This document is the authoritative reference for what each annotation
means and when to use it.

---

## Class-level

### `@pointerSource`

Marks a class as a **foundational definition**: instances carry stable
identity (a fully-qualified path, or owner + name).

Currently applied to:

- `meta::pure::metamodel::PackageableElement`
- `meta::pure::metamodel::function::property::AbstractProperty`
- `meta::pure::metamodel::extension::Stereotype`
- `meta::pure::metamodel::extension::Tag`

Effect:

- A class is **pointer-encodable** iff it is, or transitively extends, a
  `@pointerSource`. The `PointerRef` schema kind is what addresses such
  instances by path.
- A class is forbidden from also carrying `@excluded` or
  `@transientCompilerOnly` — these classes are part of every output by
  definition.
- The closest `@pointerSource` ancestor of a class is its **definition
  taxonomy**. The protocol generator preserves the inheritance edge to
  that ancestor and **strips edges to other (non-pointerSource)
  taxonomy ancestors** when emitting the protocol grammar. Example:
  `UserDefinedPackageableGenericType` extends both `PackageableElement`
  (its definition taxonomy) and `GenericType`; in the protocol grammar
  the `extends GenericType` edge is removed so the only way to reference
  one from a `GenericType`-typed slot is via a `GenericType_Pointer`
  wrapper.
- Validator rule: every class has at most one `@pointerSource` ancestor.
  Multiple inheritances to distinct pointer sources is an error.

### `@transientCompilerOnly`

Marks a class as a **compile-time-only sentinel**: instances exist only
in the in-memory M3 graph and must never be serialized.

Currently applied to:

- `CompilerNotSetGenericType`
- `CompilerNotSetMultiplicity`
- `CompilerGenericTypeAndMultiplicityHolder`

Effect:

- The FBS schema and the protocol grammar do not emit Defs / classes
  for transient types.
- The writer throws if asked to serialize an instance.
- `ModelUtils.reachableConcreteSubtypes` skips transient classes, so
  they do not participate in the bucket math for `nonPointerSubtypes`
  taxonomies.

### `@excluded` (existing)

Marks a class or property as excluded from the protocol layer entirely.
Older mechanism predating this document; used for compile-time-only
helpers that aren't yet migrated to `@transientCompilerOnly`.

---

## Property-level

Every property whose declared type can hold a pointer-encodable value at
runtime must declare exactly one of the three encoding stereotypes
below. The validator computes the property's pointer-eligible subtype
set (reachable concrete subtypes minus the type's `nonPointerSubtypes`
list, intersected with pointer-encodable classes) and refuses to compile
the model if the declared stereotype contradicts the analytic.

### `@pointer`

The slot is **always a path reference**. Use when the property's type
has no `nonPointerSubtypes` list and at least one reachable concrete
subtype is pointer-encodable. The runtime never holds an inline value.

Example: `PackageableElement.package : Package[0..1]` — Package is a
leaf PE, the slot can only hold a path to a Package.

Effect:

- FBS: emits a `PointerRef` (or `[PointerRef]`) field.
- Protocol grammar: type swaps to `T_Pointer`.
- Hand-written code that reads `_xxx()` gets back a `T_Pointer` with a
  `_value()` path string.

### `@maybePointer`

The slot can be **either a path reference OR one of the inline subtypes
listed by `nonPointerSubtypes` on the property's type**. Use when the
type has `nonPointerSubtypes` and at least one reachable concrete
subtype is pointer-encodable.

Example: `AbstractProperty.genericType : GenericType[1]` — GenericType
declares `nonPointerSubtypes = "InferredGenericType, UserDefinedGenericType,
UndefinedGenericType, GenericTypeOperation"`; the slot may hold any of
those inline forms or a `GenericType_Pointer` to a packageable
generic-type singleton.

Effect:

- FBS: emits a property-specific union including `PointerRef` plus the
  listed inline `Def`s.
- Protocol grammar: type swaps to `T_Protocol` (a marker union with
  `T` (inline root) and `T_Pointer` as members).
- Hand-written code dispatches on `instanceof T_Pointer` vs. the inline
  `T` subtypes.

### `@nonPointer`

Explicit **opt-out**: the slot's values are inline despite the type
allowing pointer encoding. Use when a property is the source-of-truth
for its values rather than a reference.

Examples:

- `Section.elements : PackageableElement[*]` — a `Section` is the parse
  block where the elements are originally defined; the elements are
  embedded, not referenced.
- `Profile.p_stereotypes : Stereotype[*]` — a Profile owns its
  stereotypes inline.
- `Class.properties : Property[*]` — properties are part of the class's
  definition.

Effect:

- FBS: same union shape the type would use without `@maybePointer` —
  no `PointerRef` member is emitted (the slot can't carry a pointer).
- Protocol grammar: no type swap; the property keeps its declared type.
- Validator forbids combining `@nonPointer` with `@pointer` or
  `@maybePointer`.

### `@inferred` (existing)

Marks a property as **computed by the compiler**, not declared by the
user. Often combined with one of the encoding stereotypes (e.g.,
`Generalization.specific` is `@inferred, @pointer`).

### `@excluded` (existing)

Skip the property from FBS / protocol generation entirely.

---

## Class-level tagged value

### `nonPointerSubtypes`

A taxonomy declares which of its concrete subtypes are **inline-only**
(the slot can hold them inline; the rest are pointer-encoded).

Currently declared on:

- `Type` — `FunctionType, RelationType, TypeParameter`
- `GenericType` — `InferredGenericType, UserDefinedGenericType,
  UndefinedGenericType, GenericTypeOperation`
- `Multiplicity` — `UserDefinedAdHocMultiplicity, InferredAdHocMultiplicity,
  UserDefinedMultiplicityParameter, InferredMultiplicityParameter,
  UndefinedMultiplicity`
- `FunctionDefinition` — `LambdaFunction`

Validator rules:

- Every entry must be a known **concrete leaf** (non-abstract) class.
  Listing an abstract intermediate is an error; expand to its concrete
  descendants.
- Every entry must be an actual subtype of the declaring class.
- Every concrete descendant of a taxonomy with `nonPointerSubtypes`
  must fall into exactly one bucket: inline (listed), pointer-encodable
  (extends a `@pointerSource`), or `@transientCompilerOnly`. Anything
  unclassified is an error — the author has to choose its serialization
  fate.

---

## Quick reference

| Annotation | Where | What it means |
|---|---|---|
| `@pointerSource` | class | Foundational definition; instances have stable identity |
| `@transientCompilerOnly` | class | Compile-time only; never serialized |
| `@excluded` | class / prop | Skip from generation |
| `nonPointerSubtypes` | class (tag) | Inline-only subtypes of this taxonomy |
| `@pointer` | property | Always a path reference |
| `@maybePointer` | property | Path reference OR a listed inline subtype |
| `@nonPointer` | property | Force inline despite type allowing pointers |
| `@inferred` | property | Computed by the compiler, not user-declared |
