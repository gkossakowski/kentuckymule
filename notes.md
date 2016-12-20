# Introduction

This document contains notes, insights and results collected
during Kentucky Mule development.

# Parsing

Everything about parsing using Dotty's parser.

## Importing Dotty's parser

I wanted to use Dotty's parser for parsing Scala files. The natural step was
just to copy the relevant bits of the implementation from dotty's repo here. I
expected this to be straightforward because a parser is supposed to have very
minimal dependencies. In reality, dotty's parser has surprising set of
dependencies. I explain below the process in detail.

First you need to import the following packages:

```
dotty.tools.dotc.ast
dotty.tools.dotc.config
dotty.tools.dotc.core
dotty.tools.dotc.parsing
dotty.tools.dotc.printing
dotty.tools.dotc.reporting
dotty.tools.dotc.rewrite
dotty.tools.dotc.util
dotty.tools.io
```

After the import, I dropped the xml parsing from the `parsing` package because I
don't need to support xml in Kentucky Mule.

The `ast` and `core` packages require heavy pruning. Surprisingly, I couldn't
remove `tpd` from `ast` because parser depends on some unsplicing functionality
that in turn depends on typed trees. However, the `tpd` I left in place is very
minimal and serves just as a mock.

The `core` package went through the biggest pruning. I removed the whole
hierarchy of types except `NamedType` and its parents: `Type`, `ValueType`
`TypeType`, `TermType`. The `NamedType` is referred just in one place in `Trees`
in the definition of the `DefTree` so probably it could be dropped too.

I also removed `Symbols` completely. Almost everything in `Definitions` has been
removed too; except for the mock objects for primitive types (`Int`, `Boolean`,
etc.).

The most challenging was carving out the minimal functionality in `Contexts`.
The `Context` object holds/depends on several objects that are not needed for
parsing:

   CompilerCallback, AnalysisCallback, TyperState, Scope, TypeAssigner, ImportInfo, GADTMap, TypeComparer, ContextualImplicits, SearchHistory, SymbolLoaders and Platform

The `Context` depends on sbt's infrastructure for incremental compilation, on
various state objects that should be internal to typer (typer state, contextual
implicits, search history, etc.). The `SymbolLoaders` is specific to populating
symbol table with symbols. Lastly, `Platform` contains some backend-specific
code. None of these objects are needed for the minimal job of parsing. This
shows that, unfortunately, `Context` became a kitchen sink equivalent of
`Global` in the Scala 2.x compiler.

Also, I ripped out the phase travel functionality that I didn't need because I
have only just one phase: parsing. Lastly, there are some maps and caches
related to types and symbols that I didn't need so I deleted them too.

My suggestion would be to refactor the `Context` to separate better the minimal
context object required by parser, typer and then the rest of the compiler. For
exmaple: `GADTMap` is probably needed just for the typer itself and rest of
phases do not need it in their context object at all.

## Parser's performance

I did a basic testing of parser's performance.

Parsing speed of `basic.ignore.scala`: 990k LoC per second
Parsing speed of `Typer.ignore.scala`: 293k LoC per second

The single-threaded parsing performance is decent. It would take roughly 30s to
parse 10m LoC.

Here are results of testing parser performance in multi-threaded setting.

```
-t 1

[info] Benchmark                                    (filePath)   Mode  Cnt    Score   Error  Units
[info] BenchmarkParsing.parse  sample-files/Typer.scala.ignore  thrpt   40  158.999 ± 4.975  ops/s

-t 2

[info] Benchmark                                    (filePath)   Mode  Cnt    Score   Error  Units
[info] BenchmarkParsing.parse  sample-files/Typer.scala.ignore  thrpt   40  271.202 ± 7.405  ops/s

-t 4

[info] Benchmark                                    (filePath)   Mode  Cnt    Score    Error  Units
[info] BenchmarkParsing.parse  sample-files/Typer.scala.ignore  thrpt   40  314.757 ± 25.697  ops/s

-t 8

[info] Benchmark                                    (filePath)   Mode  Cnt    Score    Error  Units
[info] BenchmarkParsing.parse  sample-files/Typer.scala.ignore  thrpt   40  272.613 ± 14.918  ops/s
```

Parsing doesn't scale well when we add threads. I reported this issue here:
https://github.com/lampepfl/dotty/issues/1527

# Entering Symbols

I created Symbol implementation from scratch to experiment with different ways
of representing symbol table and see which one performs the best.

I started with representing a Symbol as wrapper around a mutable `HashMap[Name,
ArrayBuffer[Symbol]]` that holds stores its children. Benchmarking showed that
symbol table can be populated with symbols from `Typer.scala` the rate of 85k
full symbol table creations per second. Switching from Scala's mutable Map to
Guava's `MultiMap` improved the performance from 85k to 125k per second.

Later work on fixing bugs decreased the performance to 113k ops/s in
`BenchmarkEnter.enter` (using the tree from parsing `Typer.scala`). Switching
from a combo of `MultiMap` and `ArrayBuffer` to `Scope` (borrowed almost
verbatim from dotty) increased performance to 348k ops/s. This is an astonishing
performance gain and shows the power of a specialized, highly optimized data
structure.

The entering of symbols itself is implemented as a single, breadth-first tree
traversal. During the traversal symbols get created, entered into symbol table
and their outline type completers are set up. The type completers receive
`LookupScope` instance that enables completers to resolve identifiers with
respect for Scala's scoping rules. Symbols corresponding to classes and modules
(objects) are added to `completers` job queue (more about it later).

The code in Kentucky Mule responsible for entering symbols into a symbol table
is similar to one found in scalac and dottyc implementation. However, both
scalac and dottyc traverse trees lazily, starting with top-level definitions and
enter symbols for inner classes and definitions only when a completer for out
symbol is forced. For example

```scala
class Foo {
  class Bar {
    def a: Foo
  }
}
```

The symbol for `a` will be entered into `Bar`'s declarations when `Bar` is
looked up in `Foo` and `Bar`'s completer is forced. Kentucky Mule takes a
different approach: it traverses trees in one pass and creates symbols for
`Foo`, `Bar` and `a` eagerly but sets lazy completers.

## Performance of entering symbols

Entering symbols in Kentucky Mule is very fast. For the `10k` benchmark we enter
symbols at speed of 2916 ops/s yields performance of 29 million lines of code
per second (29 * 10k). The `10k` is a very simple Scala file, though:

```scala
class Foo1 {
  def a: Base = null
}

class Foo2 {
  def a: Base = null
}

// repeated until

class Foo2500 {
  def a: Base = null
}

class Base
```

# Completing outline types

Kentucky Mule computes outline types at the speed of processing 4.4 million
lines of code per second on a warmed up JVM. The 4.4 million LoC/s performance
number doesn't account for parsing time. This exact number was based on
benchmarking processing speed of `scalap` sources. The `scalap` project has
over 2100 lines of Scala code and is reasonably representive of the Scala
projects people in the wild.

Outline types are very simplified Scala types that support just two operations:
member listing and lookup by name. Outline type doesn't support subtype checks
so nothing that relies on that check is supported either: e.g. computing least
upper bound (LUBs). LUBs are really important Scala typechecking. They are used
in typechecking expressions, e.g. `if` expression with unifying types of
branches, or for unifying signatures of methods inherited from multiple traits
(as seen in Scala collections library).

The outline types support one important use case: calculation of dependencies
between symbols (e.g. classes). This enables an easy and an efficient
parallelization or even distribution of the proper Scala typechecking because
scheduling and synchronization problem is solved. Check out my blog post for
details
https://medium.com/@gkossakowski/can-scala-have-a-highly-parallel-typechecker-95cd7c146d20#.awe3nr9be

Computation of outline types can be thought of as a pre-typechecking phase. For
outline types to be useful for scheduling the real typechecking, they have to be
at least an order of magnitude faster than the real typechecking. Only this way
the distribution or the parallelization would have a chance to actually bring
speed ups.

## Resolving identifiers

One of the most important tasks in typechecking is resolving identifiers. Both
outline and real typechecking need it. Let's see with an example what resolving
identifiers is about:

```scala
class Foo {
  class A
  def f1: A = ...
  import Bar.A
  def f2: A = ...
}

object Bar {
  class A
}
```

When the result type of methods `f1` and `f2` is calculated, the typechecker
needs to understand what identifier `A` refers to. That's called resolving
identifiers. In the example, the result type of method `f1` is the type `Foo#A`
but the result type of method `f2` is the type `Bar#Foo`. For the typechecker to
figure it out, it had to see the following things:

   1. The method `f1` is declared in the class `Foo` so it sees all class
   members, e.g. the class `A`
   2. The method `f2` is preceded by an import
   statement that has to be taken into account.
   3. The import statement refers to an identifier `Bar` and the typechecker needs to realize that the `Bar`
   refers to an object is declared in the same file as `Foo` and it's visible
   to the import statement. Note that the reference `Bar` in the import
   statement points at is an object declared further down the source file.

Resolving identifiers is a fairly complicated task in Scala due to Scala's
scoping rules. For example, the object `Bar` could have inherited class `A` as a
member from some other parent class.

The exact rules are explained in [Chapter 2](http://www.scala-
lang.org/files/archive/spec/2.11/02-identifiers-names-and-scopes.html) of the
Scala Language Specification.

## Scope of import clauses

I'd like to highlight an irregularity of `import` clauses compared to other ways
of introducing bindings (names that identifiers resolve to): declarations,
inheritance, package clauses. Let's look at a modified version of the example
from previous section:

```scala
class Foo {
  def f1: A = ...
  class A
}
```

The method `f1` refers to the class `A` that is defined after the method.
Declarations, members introduced by inheritance and members of packages are all
accessible in random order in Scala. Contrast this with:

```scala
class Foo {
  import Bar.A
  def f2: A = ...
}

object Bar {
  class A
}
```

The method `f2` refers to the class `A` imported by the preceding import
clause. However, the imported class cannot be accessed in a random order from
any declaration in class `Foo`. The imported name `A` is visible only to
declarations appearing after the import clause.

The order-dependence of visibility of names introduced by an import clause has
an impact on how identifier resolution is implemented in Kentucky Mule. If I
could ignore import clauses, the identifier resolution would become a simple
lookup in a chain of nested scopes. However, import clauses require special
care. The fair amount of complexity of implementation of looking up identifiers
comes from supporting import clauses potentially appearing at an arbitrary
point. Making it performant required a careful sharing of `LookupScope`
instances between declarations that are not separated by import classes. In this
example:

```scala
class Foo {
  def f1: A = ...
  def f2: A = ...
}
```

Both method `f1` and `f2` share exactly the same `LookupScope` instance that
resolves identifiers using class `Foo` scope. This helps both CPU and memory
performance. The sharing is of `LookupScope` instances is implemented by
`LookupScopeContext`.

While having imports at arbitrary locations in Scala programs is handy, there's
an implementation cost to this feature. Fortunately enough, with care, one can
support this feature without a big effect on performance.

## Lookup performance

Resolving identifiers boils down to looking up declarations according to scoping
rules and following visible import clauses. The lookup is implemented by
subclasses of `LookupScope`. The creation of `LookupScope` instances is
performed by `LookupScopeContext` which is kept in sync with walked tree during
entering symbols. Each type completer receives a `LookupScope` instance that it
will use to resolve identifiers while performing type completion.

Resolving identifiers is a very common operation so it must be fast. The
implementation strategy I picked in Kentucky Mule is to have a very thin layer
of logic behind the code that needs to perform a name lookup and calls to the
`Scope` instances that actually perform the lookup. The `Scope` is a hash table
implementation specialized for `Name -> Symbol` lookup. I borrowed the `Scope`
implementation from dotty, originally due to its fast insertion performance (see
the section on performance of entering symbols).

To illustrate why having a thin layer of lookup logic is important for
performance, I'd like to show one example from Kentucky Mule implementation:

```scala
// surprisingly enough, this conditional lets us save over 100 ops/s for the completeMemberSigs benchmark
// we get 1415 ops/s if we unconditionally push class signature lookup scope vs 1524 ops/s with the condition
// below
val classSignatureLookupScopeContext =
if (tParamIndex > 0)
  parentLookupScopeContext.pushClassSignatureLookupScope(classSym)
else
  parentLookupScopeContext
```

This logic is responsible for checking if a class we're processing at the moment
is generic (has type parameters) and sets up lookup scope instance accordingly.
The idea is that if there are type parameters, we need a special lookup scope
instance that will make those parameters available to declarations inside a
class. For example:

```scala
class Foo[T, U] {
  val x: T // when typechecking val x, we'll need to resolve T
}
```

However, if a class doesn't introduce any new type parameters, we don't need a
lookup scope dedicated to class's signature because there no new identifiers
that are introduced. This distinction between generic and non-generic classes
contributes to over 6% better performance of typechecking `scalap` sources. I
found such a big difference surprising. I didn't expect an unconditional
creation of an empty lookup scope dedicated to class signature to introduce
such a slow down.

### Imports lookup implementation

Imports have a pretty big impact on overall implementation of identifier
lookups. Import clauses are handled by `ImportsLookupScope` that is effectively
a view over an array of import clauses. Let's consider this example:

```scala
class Foo {
  import Bar.a
  import a.b
  def x: b.type = ...
  import Bar.c
  import c.D
  def y: D = ...
}
```

In Kentucky Mule, I collect all four import clauses into one array. The lookup
scope instance for method `x` receives an instance of `ImportsLookupScope` that
points at the the index of the last visible import to the method `x`. In our
example, it's the `import a.b` clause (which has index 1). Similarly, the method
`y` will get `ImportsLookupScope` instance that points at `import c.D` clause
(with index 3) in the array of all import clauses. The array itself is shared
between all lookup scope instances. I picked that strategy for two reasons:

  1. Performance - I wanted to have a very fast way of searching all import
  clauses and iterating over an array is the fastest for short sequences
  2. Correctness - previous imports need to be visible while completing
  subsequent imports

To illustrate the second point, the `import a.b` refers to just imported `Bar.a`
so when I complete `a.b`, I need to take into account `Bar.a`. I found it's the
easiest to do by having all imports in one array and then complete them in the
top-down order (so previous imports can be taken into account) but perform
lookups in bottom-up order (which is respecting shadowing Scala rules). Having a
datastructure that lets me traverse efficiently in both directions was a reason
I picked an Array.

# Completers design

Completer is a piece of logic that computes ("completes") the type of a symbol
by resolving identifiers and by looking up members of other symbols. Let me show
you with a simple example what completers do:

```scala
class Foo(x: Bar.X)

object Bar extends Abc
class Abc {
  class X
}
```

When typechecking the constructor of `Foo` the following steps are taken:

  1. Compute the type of the constructor parameter `x`, which requires
  resolving the `Bar` identifier and then selecting member `X` from `Bar`'s
  type
  2. To select member `X` from the type of `Bar`, we need to compute it: force
  completer for the object `Bar`
  3. The completer for the object `Bar` sees `Abc` as a parent. The completer
  resolves `Abc` to the class declared below. In order to compute the full type
  of the object `Bar` (e.g. list of all members), the type of `Abc` is required
  4. The completer for the class `Abc` is forced. During the computation of
  the type of the class `Abc`, the class `X` is entered as `Abc`'s member.
  5. The completer for the object `Bar` can recognize the class `X` as an
  inherited member of the object `Bar` and save that information in `Bar`'s
  type
  6. Finally, the completer for the class `Foo` can finish its work by
  selecting the member `X` from the type of the object `Bar`

Both scalac and dottyc (and many other compilers) treat completers as simple
lazy computations that are forced on the first access.

I explore a different design of completers in Kentucky Mule compared to the ones
found in both scalac and dottyc. There are a few principles I wanted to explore
in Kentucky Mule:

  1. Unknown (uncompleted *yet*) type information of other symbols is
  a fundamental problem of typechecking and should be handled explicitly
  2. Completers should be as eager in evaluation as possible. Unnecessary
  laziness is both expensive to orchestrate and makes reasoning about the code
  hard.
  3. Completers should be designed for observability: I want to understand what
  are they doing, how much work if left to do and how much time is spent on
  each task
  4. Completers design should encourage tight loops and shallow stack traces
  that are both JVM performance-friendly and make profiling easier

These principles led me to designing an asynchronous-like interface to
completers. In my design, I treat missing (not computed yet) types of
dependencies the same way as you would treat data that haven't arrived yet in an
I/O setting. I want my completers to not block on missing information.

To achieve non-blocking, my completers are written in a style resembling
cooperative multitasking. When a completer misses the type of one of its
dependencies, it yields the control back to the completers' scheduler with an
information what's missing (what caused an interruption). The scheduler is free
to schedule a completion of the missing dependency first and retry the
interrupted completer afterwards.

In this design, the completer becomes a function that takes symbol table as an
argument and returns either a completed type if all dependencies can be looked
up in the symbol table and their types are already complete. Otherwise,
completer returns an information about its missing dependencies.

The non-blocking, cooperative multitasking style design of completers brings a
few more benefits in addition to adhering to principles I started off with:

  1. The scheduler has a global view of pending completers and is free to
  reshuffle the work queue. This is enables batching completers that depend on
  I/O: reading class files from disk. This should minimize the cost of context
  switching
  2. It's easy to attribute the cost of typechecking to different kind
  of completers. For example, I can distinguish between completers that
  calculate types for source files and completers that read types from class
  files (for dependencies on libraries).
  3. Tracking progress of typechecking is easy: I just check the size of the
  work queue.

# Benchmmarks for enter

Below are some sample performance numbers I collected and saved for
just having a ballpark figures to see if I'm not losing performance
as I continue working on the code.

## 0c0661b38f0c9f00869c509c3be43735a6ac845d

```
[info] Benchmark                                  (filePath)   Mode  Cnt       Score      Error  Units
[info] BenchmarkEnter.enter  sample-files/Typer.scala.ignore  thrpt   60  272211.967 ± 2541.271  ops/s
[info] BenchmarkEnter.enter    sample-files/10k.scala.ignore  thrpt   60    3110.109 ±   21.727  ops/s
```

## 85602c95ff3a68b9c6187acd9aeb2ca4381d7daa: Update 10k.scala.ignore benchmark file

```
[info] Benchmark                                  (filePath)   Mode  Cnt       Score      Error  Units
[info] BenchmarkEnter.enter  sample-files/Typer.scala.ignore  thrpt   60  275007.736 ± 1212.551  ops/s
[info] BenchmarkEnter.enter    sample-files/10k.scala.ignore  thrpt   60    3036.487 ±   18.061  ops/s
```

## 102388171f0e615e8987e83554698f7f297d2732: Implement DefDefCompleter

```
info] Benchmark                                  (filePath)   Mode  Cnt       Score      Error  Units
[info] BenchmarkEnter.enter  sample-files/Typer.scala.ignore  thrpt   60  242336.865 ± 1837.541  ops/s
[info] BenchmarkEnter.enter    sample-files/10k.scala.ignore  thrpt   60    2916.501 ±   32.409  ops/s
```
