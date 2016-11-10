package kentuckymule.bench

import java.util

import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.{CompilationUnit, parsing}
import dotty.tools.dotc.util.{NoSource, SourceFile}
import kentuckymule.ScalapHelper
import kentuckymule.core.Enter
import org.openjdk.jmh.annotations._

import scala.reflect.io.PlainFile


object BenchmarkScalap {

  @State(Scope.Benchmark)
  class BenchmarkState {
    private val initCtx = (new ContextBase).initialCtx
    val context = initCtx.fresh
  }

  import java.nio.file.Paths
  val projectDir = Paths.get("../").toAbsolutePath.toString

  @State(Scope.Thread)
  class ParsedTreeState {
    var compilationUnits: Array[CompilationUnit] = _
    @Setup(Level.Trial)
    def createCompilationUnits(bs: BenchmarkState): Unit = {
      val context = bs.context
      compilationUnits = for (filePath <- ScalapHelper.scalapFiles(projectDir).toArray) yield {
        val source = getSource(filePath)(context)
        val unit = new CompilationUnit(source)
        val parser = new parsing.Parsers.Parser(source)(context)
        unit.untpdTree = parser.parse()
        unit
      }
    }
  }

  @State(Scope.Thread)
  class EnteredSymbolsState {
    var enter: Enter = _
    @Setup(Level.Trial)
    def enterSymbols(bs: BenchmarkState, pts: ParsedTreeState): Unit = {
      enter = new Enter
      val context = bs.context
      context.definitions.rootPackage.clear()
      ScalapHelper.enterStabSymbolsForScalap(context)
      for (compilationUnit <- pts.compilationUnits)
        enter.enterCompilationUnit(compilationUnit)(context)
    }
  }

  def getSource(fileName: String)(ctx: Context): SourceFile = {
    val f = new PlainFile(fileName)
    if (f.exists) new SourceFile(f)
    else {
      ctx.error(s"not found: $fileName")
      NoSource
    }
  }

}

class BenchmarkScalap {
  import BenchmarkScalap._
  @Benchmark
  @Warmup(iterations = 20)
  @Measurement(iterations = 20)
  @Fork(3)
  def enter(bs: BenchmarkState, pts: ParsedTreeState): Unit = {
    val context = bs.context
    context.definitions.rootPackage.clear()
    val enter = new Enter
    var i = 0
    while (i < pts.compilationUnits.length) {
      enter.enterCompilationUnit(pts.compilationUnits(i))(context)
      i += 1
    }
  }

  @Benchmark
  @Warmup(iterations = 20)
  @Measurement(iterations = 20)
  @Fork(3)
  def completeMemberSigs(bs: BenchmarkState, pts: ParsedTreeState): Int = {
    val context = bs.context
    context.definitions.rootPackage.clear()
    val enter = new Enter
    ScalapHelper.enterStabSymbolsForScalap(context)
    var i = 0
    while (i < pts.compilationUnits.length) {
      enter.enterCompilationUnit(pts.compilationUnits(i))(context)
      i += 1
    }
    enter.processJobQueue(memberListOnly = false)(context)
  }

  @Benchmark
  @Warmup(iterations = 20)
  @Measurement(iterations = 20)
  @Fork(3)
  def parse(bs: BenchmarkState): Int = {
    import bs.context
    val sourceFilePaths = ScalapHelper.scalapFiles(projectDir)
    val compilationUnits = new util.ArrayList[CompilationUnit](sourceFilePaths.length)
    var i = 0
    while (i < sourceFilePaths.length) {
      val filePath = sourceFilePaths(i)
      val source = getSource(filePath)(context)
      val unit = new CompilationUnit(source)
      val parser = new parsing.Parsers.Parser(source)(context)
      unit.untpdTree = parser.parse()
      compilationUnits.add(unit)
      i += 1
    }
    compilationUnits.size
  }

  @Benchmark
  @Warmup(iterations = 20)
  @Measurement(iterations = 20)
  @Fork(3)
  def parseAndCompleteMemberSigs(bs: BenchmarkState): Int = {
    import bs.context
    val compilationUnits: Array[CompilationUnit] =
      for (filePath <- ScalapHelper.scalapFiles(projectDir)) yield {
        val source = getSource(filePath)(context)
        val unit = new CompilationUnit(source)
        val parser = new parsing.Parsers.Parser(source)(context)
        unit.untpdTree = parser.parse()
        unit
      }

    context.definitions.rootPackage.clear()
    val enter = new Enter
    ScalapHelper.enterStabSymbolsForScalap(context)
    var i = 0
    while (i < compilationUnits.length) {
      enter.enterCompilationUnit(compilationUnits(i))(context)
      i += 1
    }
    enter.processJobQueue(memberListOnly = false)(context)
  }
}
