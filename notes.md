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
related to types and symbols that I didnd't need so I deleted them too.

My suggestion would be to refactor the `Context` to separate better the minimal
context object required by parser, typer and then the rest of the compiler. For
exmaple: `GADTMap` is probably needed just for the typer itself and rest of
phases do not need it in their context object at all.

## Parser's performance

I did a basic testing of parser's performance.

Parsing speed of `basic.ignore.scala`: 990k LoC per second
Parsing speed of `Typer.ignore.scala`: 293k LoC per second

The single-threaeed parsing performance is decent. It would take roughly 30s to
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

Later work on fixing bugs decraesed the performance to 113k ops/s in
`BenchmarkEnter.enter` (using the tree from parsing `Typer.scala`). Switching
from a combo of `MultiMap` and `ArrayBuffer` to `Scope` (borrowed almost
verbatim from dotty) increased performance to 348k ops/s. This is an astonishing
performance gain and shows the power of a specialized, highly optimized data
structure.

The entering of symbols itself is implemented as a single, breadth-first tree
traversal. During the traversal symbols get created, entered into symbol table
and their outline type completers are set up. The type completers receive
`LookupScope` instance that enables completers to resolve identifiers with
respect for Scala's scaping rules. Symbols corresponding to classes and modules
(objects) are added to `completers` job queue (more about it later).

The code in Kentucky Mule responsible for entering symbols into a symbol table
is similar to one found in scalac and dottyc implementation. However, both
scalac and dottyc traverse trees lazily, starting with top-level definitions and
enter symbols for inner classes and definitions only when a completer for out
symbol is forced. For exmaple

```
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

```
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



