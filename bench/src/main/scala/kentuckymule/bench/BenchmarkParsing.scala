package kentuckymule.bench

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.parsing
import dotty.tools.dotc.util.{NoSource, SourceFile}
import org.openjdk.jmh.annotations._

import scala.reflect.io.PlainFile


object BenchmarkParsing {

  @State(Scope.Benchmark)
  class BenchmarkState {
    private val initCtx = (new ContextBase).initialCtx
    val context = initCtx.fresh
  }

  @State(Scope.Thread)
  class ThreadState {
    @Param(Array("sample-files/Typer.scala.ignore"))
    var filePath: String = _
    var source: SourceFile = _
    @Setup(Level.Trial)
    def createSource(bs: BenchmarkState): Unit = {
      source = getSource(filePath)(bs.context)
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

class BenchmarkParsing {
  import BenchmarkParsing._
  @Benchmark
  @Warmup(iterations = 20)
  @Measurement(iterations = 20)
  @Fork(3)
  def parse(bs: BenchmarkState, ts: ThreadState): untpd.Tree = {
    val parser = new parsing.Parsers.Parser(ts.source)(bs.context)
    val tree = parser.parse()
    tree
  }
}
