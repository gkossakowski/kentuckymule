package kentuckymule.bench

import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.{CompilationUnit, parsing}
import dotty.tools.dotc.util.{NoSource, SourceFile}
import kentuckymule.core.Enter
import kentuckymule.queue.JobQueue
import org.openjdk.jmh.annotations._

import scala.reflect.io.PlainFile


object BenchmarkEnter {

  @State(Scope.Benchmark)
  class BenchmarkState {
    private val initCtx = (new ContextBase).initialCtx
    val context: Context = initCtx.fresh
  }

  @State(Scope.Thread)
  class ParsedTreeState {
    @Param(Array("sample-files/Typer.scala.ignore", "sample-files/10k.scala.ignore"))
    var filePath: String = _
    var source: SourceFile = _
    var compilationUnit: CompilationUnit = _
    @Setup(Level.Trial)
    def createSource(bs: BenchmarkState): Unit = {
      source = getSource(filePath)(bs.context)
      compilationUnit = new CompilationUnit(source)
      val parser = new parsing.Parsers.Parser(source)(bs.context)
      compilationUnit.untpdTree = parser.parse()
    }
  }

  @State(Scope.Thread)
  class EnteredSymbolsState {
    var enter: Enter = _
    @Setup(Level.Trial)
    def enterSymbols(bs: BenchmarkState, pts: ParsedTreeState): Unit = {
      val jobQueue = new JobQueue
      enter = new Enter(jobQueue)
      val context = bs.context
      context.definitions.rootPackage.clear()
      enter.enterCompilationUnit(pts.compilationUnit)(context)
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

class BenchmarkEnter {
  import BenchmarkEnter._
  @Benchmark
  @Warmup(iterations = 20)
  @Measurement(iterations = 20)
  @Fork(3)
  def enter(bs: BenchmarkState, pts: ParsedTreeState): Unit = {
    val context = bs.context
    context.definitions.rootPackage.clear()
    val jobQueue = new JobQueue
    val enter = new Enter(jobQueue)
    enter.enterCompilationUnit(pts.compilationUnit)(context)
  }

  @Benchmark
  @Warmup(iterations = 20)
  @Measurement(iterations = 20)
  @Fork(3)
  def completeMemberList(bs: BenchmarkState, pts: ParsedTreeState): Int = {
    val context = bs.context
    context.definitions.rootPackage.clear()
    val jobQueue = new JobQueue
    val enter = new Enter(jobQueue)
    enter.enterCompilationUnit(pts.compilationUnit)(context)
    jobQueue.processJobQueue(memberListOnly = true)(context).processedJobs
  }

  @Benchmark
  @Warmup(iterations = 20)
  @Measurement(iterations = 20)
  @Fork(3)
  def completeMemberSigs(bs: BenchmarkState, pts: ParsedTreeState): Int = {
    val context = bs.context
    context.definitions.rootPackage.clear()
    val jobQueue = new JobQueue
    val enter = new Enter(jobQueue)
    enter.enterCompilationUnit(pts.compilationUnit)(context)
    jobQueue.processJobQueue(memberListOnly = false)(context).processedJobs
  }
}
