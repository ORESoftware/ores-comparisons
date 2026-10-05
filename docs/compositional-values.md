# Compositional value families

Oreslang deliberately supports the useful monadic composition pattern without
requiring a universal source-level `Monad<M<_>>` abstraction or
higher-kinded types.

## Standard families

| Family | Context | Composition |
| --- | --- | --- |
| `Option<T>` | zero or one value | `map`, `flat_map` / `and_then`, `or_else`, `flatten`, `?` |
| `Result<T,E>` | success or error | `map`, `flat_map` / `and_then`, `map_err`, `or_else`, `flatten`, `?` |
| `Future<T>` | one value later | `map`, `flat_map` / `and_then`, `flatten`, `await` |
| `Iterator<T>` | lazy synchronous sequence | `map`, `flat_map` / `and_then`, `flatten`, `next` |
| `Stream<T>` | asynchronous sequence | `map`, `flat_map` / `and_then`, `flatten`, `next` |

`Iterator.from_values(xs)` creates a pull-based iterator.
`Stream.from_values(xs)` creates an asynchronous stream and
`Stream.from_future(f)` creates a one-item stream from any `Awaitable<T>`.

## Question-mark propagation

Postfix `?` is compiler-sealed rather than library-overloadable in v0:

```ores
fnc increment(Option<int> value) => Option<int> {
  val n = value?;
  return Some(n + 1);
}

fnc parse(Result<int, String> value) => Result<int, String> {
  val n = value?;
  return Ok(n + 1);
}
```

`Some(x)?` and `Ok(x)?` evaluate to `x`. `None?` returns `None` from
the enclosing callable. `Err(e)?` returns the same error residual, provided
the enclosing `Result` error type can accept `e`.

The existing ternary expression is preserved:

```ores
val x = condition ? when_true : when_false;
```

A question mark whose expression has a same-depth colon is parsed as ternary;
otherwise it is postfix propagation. Parentheses are the explicit
disambiguator for deliberately nested combinations.

Propagation is rejected inside `defer` expressions. This keeps lexical
cleanup deterministic.

## Mapping and binding

Composition uses one vocabulary across the five families. In the initial
implementation, mapping/binding calls take one explicit output type argument;
this keeps AOT type checking deterministic until return-type inference for
inline lambdas is generalized.

```ores
val doubled = Some(21).map<int>(|n| -> {
  return n * 2;
});

val parsed = Ok(20).flat_map<int>(|n| -> {
  return Ok(n + 1);
});
```

`and_then` is an alias of `flat_map`.

## Future scheduling invariant

Future composition does not weaken Oreslang's scheduler rules. Guest mapper
code passed to `Future.map` or `Future.flat_map` runs only as an
`OresScheduler` turn. A producer, I/O callback, timer callback, or completion
thread may settle runtime state but must not execute guest continuation code
inline.

Likewise, `await` remains a hard scheduler boundary even if the Future has
already completed.

## Iterator and Stream

`Iterator<T>` is stateful and pull-based:

```ores
val xs = Iterator.from_values([1, 2, 3])
  .map<int>(|n| -> { return n * 2; });

val first = xs.next(); // Option<int>
```

`Stream<T>` is the asynchronous counterpart:

```ores
val xs = Stream.from_values([1, 2, 3])
  .map<int>(|n| -> { return n * 2; });

val first = await xs.next(); // Option<int>
```

Iterator and Stream are stateful, move-only/non-shareable capabilities. They
cannot be transported by value across actor boundaries.

## Laws

For lawful pure uses of `Option`, `Result`, and Iterator composition, the
stdlib tests cover the standard laws:

- left identity: `pure(x).flat_map(f) == f(x)`
- right identity: `m.flat_map(pure) == m`
- associativity:
  `m.flat_map(f).flat_map(g) == m.flat_map(|x| -> f(x).flat_map(g))`

Future and Stream preserve equivalent value/error/cancellation results subject
to Oreslang's mandatory scheduler, ordering, cancellation, and safepoint
semantics. They do not promise identical carrier-thread choices or scheduler
traces.
