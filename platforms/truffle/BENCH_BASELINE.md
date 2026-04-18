# Phase 0–3 Baseline — Full-Truffle Rewrite

Captured **2026-04-16** on macOS arm64, GraalVM 23.1.10 (JDK 21).

This baseline is what the full-Truffle rewrite must eventually surpass (peak throughput) and never regress on (correctness). All measurements from a cold JVM start.

## Test-suite status

| Suite | Oracle | Java (`PureExecution`) | Truffle JVM | Native image |
|-------|--------|------------------------|----------------------------|--------------|
| `runCompiledGraphTests` (compilation fixtures) | `specification/compiler/*.pure` — 207 | **207 / 207** in ~12 s | **207 / 207** in ~15 s | **207 / 207** in ~11.5 s (pre-Phase-7.3; not rebuilt) |
| `runFunctionTests` (zero-arg `<<test.Test>>` stdlib) | `specification/functions/**/*.pure` — 172 discovered | **172 / 172** in **158 ms** | **172 / 172** in **158 ms** | TBD |
| `runPCTTests` (parametrized PCT) | 418 discovered | **418 / 418** in **139 ms** | **418 / 418** in **140 ms** | TBD |

## Specialized native coverage

| Category | Signatures specialized | Still bridged |
|----------|-----------------------|---------------|
| Math (int) | `plus`, `minus`, `times` on `Integer_1` | `abs`, `mod`, `pow`, single-arg `minus` |
| Math (float) | `plus`, `times` on `Float_1` | `minus`, `abs` |
| Math (number) | `lessThan`, `greaterThan`, `≤`, `≥`, `divide` | `compare` |
| Boolean | `not` on `Boolean_1` | `equal` (needs type-aware eq) |
| Collection (no-lambda) | `size`, `isEmpty`, `toOne`, `at`, `head`, `last`, `concatenate`, `init`, `tail`, `take`, `drop`, `slice`, `reverse`, `range` | `sort`, `zip`, `add` |
| Collection (lambda-driven, Phase 7.3 DirectCallNode) | `map` (MANY + m), `filter`, `fold` (MANY + 1), `exists`, `forAll`, `find` | `removeDuplicates`, `sort`, lambda-less 0..1 map variant |
| String (Phase 5) | `plus`, `length`, `substring` (2+3-arg), `startsWith`, `endsWith`, `contains`, `split`, `toUpper`, `toLower`, `trim`, `indexOf` (2+3-arg), `replace` | `toString` (complex formatting), `joinStrings`, `format` |

Total: ~47 specialized signatures / ~170 total natives. Lambda-driven collection nodes (Phase 7.3) dispatch via `DirectCallNode` + monomorphic inline cache — lambda bodies run in frames and are Graal-inlineable at hot call sites.

## Phase 3 architecture

Frame-eligible `FunctionDefinition`s (no lambdas in their expression tree, not themselves LambdaFunctions) execute against a Truffle `VirtualFrame`:

- `FrameDescriptorBuilder` pre-allocates one Object slot per parameter and per `letFunction` target.
- `FrameVariableReadNode` / `FrameLetFunctionNode` use `frame.getObject` / `frame.setObject` directly — no HashMap hop per variable access.
- `TruffleEvaluator.evaluateFunctionDefinition` routes frame-eligible FDs through `executeWithFrame`; non-eligible FDs (any containing a lambda in the body) fall back to the HashMap path in `ValueSpecificationEvaluator` and explicitly suspend the caller's layout/frame so their sub-expressions lower in their own scope.
- Bridged natives re-entering the evaluator via `eval.evaluate(subVS)` see the currently-executing frame through a reentrant context on `TruffleEvaluator`, so sub-expressions of frame-eligible FDs still read slot values correctly.

## Phase 7.2 architecture — lambda bodies in frames

`LambdaFunction`s now also get their own `FrameLayout` via `FrameDescriptorBuilder.analyzeLambda`:

- Slots for parameters (bound from the invocation's args) **and** open variables (bound from `Closure.capturedScope()`, with fallback to the current HashMap scope so dynamic bindings like QP type variables still reach the body).
- Body expressions lower under the lambda's layout, so every variable read — including captured ones — resolves via a `FrameVariableReadNode` indexed access.

## Phase 7.3 architecture — DirectCallNode inline caches

Each lambda-driven collection node (`MapNode`, `FilterNode`, `FoldNode`, `ExistsNode`, `ForAllNode`, `FindNode`) holds a `@Child LambdaCallNode`. The iteration loop is no longer inside a `@TruffleBoundary` — `LambdaCallNode.call` invokes the lambda via a `DirectCallNode`, which Graal's partial evaluator can inline into the enclosing loop.

- `LambdaRootNode` wraps each lambda's compiled body in a Truffle `RootNode`. Its `execute` reads `[closure, arg0, arg1, ...]` from `frame.getArguments()`, binds params + open-vars to slots, and delegates to `TruffleEvaluator.runLambdaBody` to install the layout/scope context before running the body.
- `TruffleEvaluator.callTargetForLambda` caches a single `RootCallTarget` per `LambdaFunction`, shared across every call site that dispatches to it.
- `LambdaCallNode` holds a monomorphic inline cache: on first call it records the `LambdaFunction` identity and creates a `DirectCallNode` for its `CallTarget`. Same-lambda calls dispatch directly; different-lambda calls fall back to an `IndirectCallNode` (megamorphic).
- If the lambda's body fails to lower (e.g. a `DotApplication` whose `_func()` throws), `callTargetForLambda` returns `null` and `LambdaCallNode` takes the legacy `@TruffleBoundary` path through `eval.executeFunction` — correct but unoptimized.

## Observations

- **Java evaluator is the ground truth.** 172 zero-arg `<<test.Test>>` stdlib tests pass cleanly in 154 ms — <1 ms per test, trivially fast. It's a good fast oracle to run on every PR.
- **Truffle bridge-first breaks on the 27th test.** The failure (`match_Any_MANY__Function_$1_MANY$__P_o__T_m_` calling through a lambda whose body references an un-bound param) is a latent bug in the current Truffle `VariableReadNode` / scope handling. It's not caught by `runCompiledGraphTests` because that suite doesn't exercise the pattern. **This validates the whole point of Phase 0 — the function-tests suite is a strictly stronger parity oracle.**
- **Discovery count mismatch.** We counted 227 `<<test.Test>>` annotations across `specification/functions/` but `runFunctionTests` discovers 172. The 55-test gap is tests that take parameters (`Function<>[1]`, `Type[1]`, etc.) — typically helpers referenced from PCT. Those are picked up by `runPCTTests` (418 discovered).
- **PCT adapter is the stdlib `testAdapterForInMemoryExecution<X|o>`.** Passed by reference (mangled name) as the first argument to each PCT test. The Pure body of `runPCTTests` had to pin the `map` body's return type to `Boolean[1]` with a trailing `true;` — otherwise the generic `eval()` return propagates into `map`'s overload resolution and picks the many→many form. (Java interpreter quirk; the Truffle rewrite removes this ambiguity.)
- **Truffle-JVM PCT passes 418/418.** Unlike `runFunctionTests`, the PCT suite doesn't trip the `VariableReadNode` scope bug (that specific test is tagged `<<test.Test>>`, not `<<PCT.test>>`). So for now PCT is the broadest green-across-all-evaluators oracle we have.
- **Phase 3 skips lambda-containing FDs.** `FrameDescriptorBuilder.analyze` returns `null` when the FD is a `LambdaFunction` or contains one anywhere in its expression tree; those FDs stay on the HashMap path. Lambda bodies themselves execute in frames (Phase 7.2) and dispatch via DirectCallNode (Phase 7.3).
- **QualifiedProperty dispatch is handled in `TruffleEvaluator`.** Since QP implements both `FunctionDefinition` and `AbstractProperty`, our switch matches FD first (emitting `UserFunctionCallNode`), bypassing Java's `evaluatePropertyFunc`. To preserve parity, `evaluateFunctionDefinition` detects a `QualifiedProperty` and (a) re-resolves the overload by name + arity via C3 linearization (mirrors `resolveQualifiedPropertyDispatch`), and (b) binds class-level type variables from the target's `classifierGenericType` into scope so bodies that read `$x` on a `Class(x:...)` see the right value.

## Running the baselines

```
just test-pure              # 207 compilation tests via Java
just test-functions-pure    # 172 runtime tests via Java (PARITY ORACLE #1)
just test-pct-pure          # 418 PCT tests via Java (PARITY ORACLE #2)
just test-pure-truffle      # 207 compilation tests via Truffle JVM
just test-functions-truffle # 172 runtime tests via Truffle JVM  ← currently 26/172
just test-pct-truffle       # 418 PCT tests via Truffle JVM
just test-pure-native       # 207 compilation tests via native binary
just test-functions-native  # 172 runtime tests via native binary
just test-pct-native        # 418 PCT tests via native binary
```

## Target at end of full-Truffle rewrite

| Suite | Java | Truffle native + specialized |
|-------|------|------------------------------|
| `runCompiledGraphTests` | ~13 s | **< 3 s** |
| `runFunctionTests` | 154 ms | **< 50 ms** |
| `runPCTTests` (418) | 137 ms | **< 50 ms** |

Hot compute micro (`fib(30)`): JVM tree-walking ~50 ms → native with unboxed primitives + specialized natives **< 2 ms**.

**Invariant:** Every phase keeps all three suites green and improves at least one benchmark. No regression >5 % on any previously-migrated bench.
