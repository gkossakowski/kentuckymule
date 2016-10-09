package kentuckymule.bench

import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Enter
import dotty.tools.dotc.{CompilationUnit, parsing}
import dotty.tools.dotc.util.{NoSource, SourceFile}
import org.openjdk.jmh.annotations._

import scala.reflect.io.PlainFile


object BenchmarkEnter {

  @State(Scope.Benchmark)
  class BenchmarkState {
    private val initCtx = (new ContextBase).initialCtx
    val context = initCtx.fresh
  }

  @State(Scope.Thread)
  class ThreadState {
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
  def enter(bs: BenchmarkState, ts: ThreadState): Unit = {
    val context = bs.context
    context.definitions.rootPackage.clear()
    val enter = new Enter
    enter.enterCompilationUnit(ts.compilationUnit)(context)
  }
}
