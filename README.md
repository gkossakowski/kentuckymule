# Kentucky Mule

Kentucky Mule is an exploration of an alternative architecture for Scala
compiler design (specifically typechecker) with focus on speed. Lessons learnt
from Kentucky Mule apply to a wide range of possible compilers and are not
limited to just Scala compiler.

Kentucky Mule's origins are described in my blog post [Can Scala have a highly
parallel
typechecker?](https://medium.com/@gkossakowski/can-scala-have-a-highly-parallel-typechecker-95cd7c146d20)

Since the time I wrote the blog post, I rephrased the original question into a
twofold one with only winning outcomes:

> How to build a highly parallel and high performance typechecker, or does >
Scala have a fundamental language design flaw that prevents such from being
built?

The prototype in this repo computes outline types I described in the blog post.
The outline types enable computation of dependencies between symbols in the
symbol table.

## Name

Kentucky Mule is the name of a bourbon-based cocktail served at Beretta,
a San Francisco restaurant. Other ingredients of Kentucky Mule are ginger beer,
lime juice, a pinch of cane sugar and mint as garnish.

Kentucky Mule is mixed and served in Collins glass at 1199 Valencia St in San
Francisco's Mission district.

## Demo

Let's see Kentucky Mule in action. Below Kentucky Mule is analyzing
[`scalap`](https://github.com/scala/scala/tree/2.12.x/src/scalap/scala/tools/scalap)
sources and printing dependencies *as seen at the API level*, without
typechecking method bodies:

![Kentucky Mule processing scalap sources](kentuckymule_scalap.gif)

Kentucky Mule processes over two thousand lines of code in 600ms on a cold
JVM.

Once the JVM is warmed up the parser becomes bottleneck. If I skip parsing in
benchmarking, Kentucky Mule calculates outline types at speed of processing over
4.4 million lines of Scala code per second.

If I add dependency extraction and analysis (as presented above) of scalap
sources, the performance is at 1.8 million lines of Scala code per second.

## Is Kentucky Mule really fast?

The form of typechecking (or "pre-typechecking") implemented by Kentucky Mule
is a very stripped down version of the real typechecking `scalac` performs when
computing types of declarations. However, there's one benchmark in the repo
where the comparison is fair. The name of the benchmark is `10k.scala`.

In this specific benchmark, the work `scalac` and Kentucky Mule do is very
comparable. And the performance of both is different. The Scala compiler
typechecks the `10k.scala` Scala files at the speed of 7k lines of code per
second. Kentucky Mule typechecks the `10k.scala` at the speed of 15m lines of
code per second. Yes, Kentucky Mule is _that blazingly fast_.

See my notes in `notes.md` for more details about this benchmark.

## Running

Kentucky Mule can be ran with these two commands:

```
$ sbt assembly
$ scala -cp kentuckyMule.jar kentuckymule.Main
```

## Development principles

The goal of Kentucky Mule wasn't a working implementation but trustworthy
numbers. I wanted to get a sense whether ideas for parallelizing Scala
typechecking have a leg to stand on. The idea was to estimate limits of Scala
typechecking performance with as little development cost as possible.

The original goal drove principles of Kentucky Mule development:

  1. Transparent pricing - I added many benchmarks and at each step of
  implementing Kentucky Mule I measured performance cost of supporting Scala
  feature.
  2. Only paranoid survive - many smart people have tried to optimize the Scala
  compiler performance before by following the conventional wisdom to "avoid premature
  optimization" and had very limited results. Kentucky Mule takes a paranoid
  approach and assumes itself to sit at the fringes of the conventional
  software construction practices. In particular, almost everything in Kentucky
  Mule is an exercise in premature optimization.
  3. It's a voyage - my goal was to explore fresh ideas in compiler architecture
  I wanted to see effects on potential performance gains of an implementation
  driven by different architecture compared to what is found in Scala and Dotty,
  javac and many other compilers in the wild.
  4. Loss aversion driven development - performance losses are easier to defend
  against over performance gains to win. This principle is about limiting the
  search space for a performant implementation. Instead of having a feature-rich
  but slow implementation with unknown directions for optimizations, I started
  with a tiny, feature-less implementation and I iterated over implementing
  new features incrementally and carefully watching performance. The advantage
  of latter approach is that at each step the direction is easier to spot:
  I know the feature I want to implement and the change to code is relatively
  small. Small changes to logic are easier to understand from performance-cost
  point of view. This principle is inspired by the _loss aversion_ effect
  described in economics and decision theory.

In particular, the code of Kentucky Mule doesn't use any of functional
programming idioms. Even higher-order functions are almost never used. If there
was an investigation whether I'm a fan of functional programming, this code
base would kill any defense line envisioned even by the best lawyer money
can find.

Check notes.md for details of the architecture explored in Kentucky Mule that
leads to an extremely fast performance.
