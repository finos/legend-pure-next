# In-place update analysis

Pure values are immutable, so every platform copies on `add`, `concatenate`, `put`, `^$x(...)` and the like. The
compiler knows when a copy is unnecessary: when the value being "changed" is referenced by nothing else and is never
read again. This analysis records that knowledge in the compiled graph, so a platform may mutate in place instead.

It runs after pass 3, on the resolved graph, in both compilers: `InPlaceUpdateAnalyzer` (Java,
`bootstrap/.../m3/module/InPlaceUpdateAnalyzer.java`) and `annotateUpdatableInPlace` (compiler-pure,
`compiler/code/compiler-pure/compiler/inPlaceUpdateAnalyzer.pure`). The two must agree on every fixture under
`compiler/tests/definition/valueSpecification/updatableInPlace/`.

## What it records

| Property (m3) | Meaning |
|---|---|
| `VariableExpression.updatableInPlace : Boolean[0..1]` | This use holds the only reference to its value: the variable is bound to a fresh value, this is its last use, and no alias of it escaped. The consumer may mutate or keep the value without copying. |
| `FunctionDefinition.resultUpdatableInPlace : Boolean[0..1]` | The function or lambda returns a value nothing else references. |
| `FunctionDefinition.resultUpdatableInPlaceIfParameter : String[0..1]` | The function's result is updatable in place whenever this parameter was passed updatableInPlace: it hands the parameter back, or moves it into a branch whose other branches are fresh (lets `fold` thread its accumulator). |
| `VariableExpression.updatableInPlaceIfParameter : String[0..1]` | This use holds the only reference to its value provided the caller passed that parameter of the enclosing function or lambda updatable in place. |
| `FunctionExpression.updatableInPlace : Boolean[0..1]`, `FunctionExpression.updatableInPlaceIfParameter : String[0..1]` | The same, for the value an expression computes: a key value of a construction or copy that is fresh (a store the object may keep as its own), or a read of a field's collection holding the only reference to it (see Fields). |

**Absent means shared.** All of these are written only when set. A reader that does not know them, or a platform
that ignores them, sees "shared" and copies — which is always correct. The annotation is permission, never
obligation.

## Native contracts

The analysis cannot see inside a native, so natives declare what they do with their arguments in the profile
`platform::pure::profiles::inPlaceUpdate` (`runtime/functions/profile.pure`). Without an annotation the compiler assumes
the worst: every argument may be retained (returned, or kept in hidden state such as `memoize`'s cache), every
lambda argument may run at any time, no result is fresh. Each annotation only adds permission.

| Annotation | Contract every platform must honour |
|---|---|
| `<<inPlaceUpdate.NewInstanceResult>>` | The single-valued result is a newly allocated instance referenced by nothing else (`new`, `copy`). |
| `<<inPlaceUpdate.NewCollectionResult>>` | The to-many result is a new collection referenced by nothing else (`map`, `filter`, `add`), trusted only where the call yields a to-many value: `map` over a single element returns the lambda's value. It may be read-only (a view, an immutable empty singleton), since an in-place operation copies those first; it is never an argument, a cached value, or anything another reference could see change. Its elements are not claimed new. |
| `{inPlaceUpdate.onlyReads='p,q'}` | Nothing of the named arguments comes out of the call: neither returned nor stored. |
| `{inPlaceUpdate.resultMayHoldElementsOf='p'}` | The argument is not kept, but the result may be, or may contain, its elements (`at`, `head`, `filter`, `tail`). |
| `{inPlaceUpdate.callsLambdaWithElementsOf='p'}` | The argument is not kept, but the native calls a lambda argument with its elements (`map`, `fold`). |
| `{inPlaceUpdate.resultMayHoldFieldsOf='p'}` | The object is not kept, but its field values may be held by the result (`copy`). A use is released by these claims only when the parameter is single-valued or the variable is to-many: a single value lifted into a to-many parameter *is* an element. |
| `{inPlaceUpdate.callsLambdaAtMostOnce='f,g'}` | Each named lambda runs at most once, before the call returns, and is not kept; the named lambdas are mutually exclusive alternatives (`if`, `match`). |
| `{inPlaceUpdate.callsLambdaRepeatedly='f'}` | Each named lambda runs any number of times, before the call returns, and is not kept (`map`, `filter`, `fold`, `firstNonEmpty`). |

The compiler checks what it can (`InPlaceUpdateAnnotationValidator` / `validateInPlaceUpdateAnnotations`): annotations are
trusted on native functions only — on a user function they are a compile error, its body being analysed instead; a
tag must name parameters of its function, and the lambda tags only function-typed ones; `NewInstanceResult` needs a single
class instance (or a type parameter) and `NewCollectionResult` a result that is not single-valued; `onlyReads` on a
parameter contradicts any other claim on it that something of the argument comes out.

Adding a native to an annotation is a claim about every platform's implementation. Audit them first: Truffle's
`removeAll` returns its input when there is nothing to remove, so it is not `NewCollectionResult`; `firstNonEmpty` runs its
thunks one after another, so they are not exclusive alternatives.

## Rules

Each root body (a function, a qualified property, a constraint, a property default) is analysed on its own, user
functions first: grouped by recursion (the strongly connected components of the reverse reference index), callees'
groups before their callers'. A call to a user function reads the callee's summary — its result is fresh
(`resultUpdatableInPlace`), or is as updatable in place as the argument for `resultUpdatableInPlaceIfParameter` — except within the group being
analysed, whose summaries are not known yet: such a call gives a shared value. The groups, and so the result, do not
depend on the order functions are visited in. A summary on a result of multiplicity `[m]` speaks for its collection, so a call reads it only when its `m` makes it to-many: `sort` of one object is that object. A callee from a dependency carries its summary in its PDB. Its
arguments still leak: a summary says what a function returns, not what it keeps.

**Bindings.** Parameters and `let`s. Each binding is updatable in place one of three ways — *fresh* (nothing else references
its value), *updatable in place if parameter p* (nothing else does, provided the caller passed p updatable in place), or *shared* — and
is a candidate unless shared:

- a parameter is updatable in place if itself;
- a `let` takes its value's updatability: a `NewInstanceResult` or `NewCollectionResult` call is fresh; a move (`let y = $x` at x's last use) carries
  x's updatability; an `if`/`match` is fresh when every branch ends in a fresh value, updatable in place if p when every branch
  ends in a fresh value or one updatable in place if p, else shared. A collection literal of no value or of several is fresh: every evaluation is a new collection (`is.pure`), while
  `[e]` is e and a constant (an atomic value) is shared;
- *annotatable*: a collection, or a class instance — never a primitive, an enumeration or a function value;
- *not poisoned*: not captured by a lambda that escapes (one not handed straight to a native declaring how it runs
  it — stored in a variable, returned, passed to a user function), which may read the value at any later time.

**Uses.** A use of a candidate is marked — `updatableInPlace` for a fresh binding, `updatableInPlaceIfParameter = p` for one updatable in place
if p — when it is

1. in a consuming position: a direct argument of a call, or a value that moves (a `let`, a branch result flowing
   into one);
2. the last use: computed by backward liveness. Evaluation order is statements in sequence, then each call's
   arguments left to right, then the call. A lambda run once continues into what follows its call, its branches
   being alternatives; one run repeatedly may run again, so a variable it captures is never last inside it.
   A call reads a receiver held by a variable once every argument is evaluated: a later argument that only reads it
   (`$xs->add($xs->size())`, `^$acc(count = $acc.count + 1)`) is done with it by then, so the receiver can still be
   its last use. A variable passed as another argument of the same call is read while the call runs, so it still
   holds the receiver (`$ys->concatenate($ys)`: no mark). A receiver computed by an expression is evaluated first,
   like any argument (`$zs->reverse()->concatenate($zs)`: the reverse does not own `zs`);
3. the only reference: every other use of the binding is released by its callee (not retained), is the receiver of
   a property read, or is itself a last use — two last uses are on exclusive branches.

A property read (`$x.name`, a `Property`, not a `QualifiedProperty`, which runs a body) neither consumes nor keeps
its receiver: the receiver is never marked and never counts as an alias.

## Elements

`$xs->map(x|^$x(v = 1))` may change each object in place only if nothing but `xs` holds it. `elementsUpdatableInPlace` on the
list argument of `map` or `fold` says so: each element is referenced only by this list, once.
`resultElementsUpdatableInPlace` says the same of a user function's result. Absent means shared.

A list qualifies when it is at its last use and came from: `map` whose lambda is `resultUpdatableInPlace`, or returns its
element from a qualifying list; a literal of fresh values; a `resultMayHoldElementsOf` native over qualifying lists,
any other instance it is given (`add`'s value) being fresh; a user function's summary; an `if` whose branches all
qualify. A use before the last one that may let an element out (`at`, `filter`, storing the list, a lambda not proved
only to read its element) loses it for good; `onlyReads` uses and property reads do not.

`callsLambdaWithElementsOf` alone (`map`, `fold`) hands each element over once and gives it up, so the lambda may take
it over. Next to `resultMayHoldElementsOf` (`filter`, `sort`) the native keeps the element: the lambda only reads it.

## Fields

`^$acc(items = $acc.items->add($e))` grows `items` in place only if nothing but `acc` holds that list. The compiler
proves the part it can see:

- the read `$acc.items` is marked as the copy's source is (`updatableInPlace`, or `updatableInPlaceIf p`) when the copy consumes `acc`
  (its source is marked), the read is in the value of the key replacing `items` (a plain `=` key, not `+=`, not a
  property of a property), outside any lambda, it is the only use of `acc` reading `items`, and every other use of
  `acc` reads another property (no other use at all: a call given `acc` might read `items`);
- a key value that is a call is marked as updatable in place as it is (`[1, 2]->map(...)`, `$acc.items->add($e)`: fresh); a key
  value held by a variable carries that variable's use mark; a literal of no value or several is fresh.

The rest is the platform's, at run time, as an invariant: **a field holding a collection as its own is the only
reference to it.** A platform that keeps a field's collection as its own

- stores it so only when the stored value is proved updatable in place (a marked key value);
- turns it into a read-only collection on any other read, so a read kept anywhere (`let before = $acc.items`) makes
  any later marked read get a read-only one, which is copied;
- does the same, in both objects, when a copy duplicates the object holding it (`^$acc(count = 1)` with `acc` still
  read): two objects then hold it;
- hands it out as its own only to a marked read.

A platform that does none of this ignores the marks: a field's collection is always read-only, and copied. Java:
`LazyObject.updatableInPlace`/`updatableInPlaceIf` store, `LazyObject.updatableInPlaceValues` reads, any other read (`__values`) and `copyInto` make
it a `StoredList`. JavaScript: `__updatableInPlace`/`__updatableInPlaceIf` store, `__updatableInPlaceRead` reads, anything else stores it as held by
another too (`__stored`, `__copy`). Truffle: `PureDynamicObject.writeOwnSlot` stores, `DirectPropertyAccessNode` reads
given the read's mark, any other read or a copy freezes the `GrowableSequence`. The bootstrap interpreter:
`DynamicInstance.putUpdatableInPlace` stores, `updatableInPlaceCollection` reads, any other access makes it the instance's raw value.

## Platform obligations

- Mutate in place only an argument whose `VariableExpression.updatableInPlace` is set, or whose `updatableInPlaceIfParameter` names
  a parameter the platform knows was passed updatable in place, and only a representation the runtime itself allocated (Java:
  a `FastList`, `PureCollections.isGrowable`; JavaScript: an array that is not frozen, `__growable` — a metadata read
  is frozen; Truffle: a `GrowableSequence`, into which anything else is copied first). Copy anything else. Not knowing
  is always allowed: it means copying.
- `fold` may tell its step whether it holds the only reference to its accumulator: for the first step, whether the
  start value was passed updatable in place; for each next one, whether the previous step's result is updatable in place
  (`resultUpdatableInPlace`, or `resultUpdatableInPlaceIfParameter` naming the accumulator and the accumulator was). The element is
  never updatable in place (it belongs to the folded collection). Java: `PureCollections.fold(..., FoldStep)`; JavaScript:
  `__foldUpdatingInPlace(..., step)`; Truffle: `InlineFoldNode` sets the accumulator's flag slot.
- A call to a user function or a qualified property may tell it which arguments it was passed updatableInPlace: an argument
  marked `updatableInPlace`, or marked `updatableInPlaceIfParameter` with that flag known. Which parameters a body would use such a
  fact for is read off its own marks, not recorded. Java: one `long` mask, bit i for parameter i —
  `executeUpdatingInPlace(..., long)` for a user function whose body reads a flag, `<name>UpdatingInPlace(..., long)` for every
  qualified property with parameters (so every override has the same method); the plain method forwards with no bit
  set, which is what reflective and dynamic calls reach, so they copy. JavaScript: a trailing `pureUpdatableInPlace = 0`
  parameter (defaulted, so `.length`, which qualified-property dispatch matches on, is unchanged), and `__qpUpdatingInPlace` for
  a qualified-property call. Truffle: an `UpdatableInPlaceMask` after the arguments, decoded by the callee's root. A
  qualified property's result is never trusted: the override that runs may differ from the one the caller sees.
- A copy (`^$x(k = v)`) whose source holds the only reference to its object may change the source itself instead of
  copying it, as long as nothing but its keys is set: a copy whose class has association properties (reverse ends to
  link), whose key refers to the object being built (`~`), or which sets a property of a property, is still a copy.
  A copy computes every key value before it sets any (a value may read a property another key sets, as in
  `^$s(left = $s.right, right = $s.left)`), and a reused object first drops the defaults it kept (a copy computes its
  own from its own values). Java: `LazyObject.reusedForCopy`, and `LazyObject.reuse(src, set, values...)` with several
  keys; JavaScript: `__copyInPlace` (an instance the program built, not a metadata element nor a frozen object);
  Truffle: `CopyWithKeysNode` (a `PureDynamicObject` whose class has no association property); the bootstrap
  interpreter: the copy native (a `DynamicInstance`).
- A constraint never solely holds what it checks: its parameters are shared, and no flag of the code around it applies inside
  it.
- Keep the evaluation order above; never re-evaluate or reorder an argument updatable in place.
- A native annotated `NewInstanceResult` or `NewCollectionResult` must not return an argument, a cached value, or anything another reference could see change. A read-only view is allowed: an in-place operation copies it first.
