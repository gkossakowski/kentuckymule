package dotty.tools
package dotc

import dotty.tools.dotc.core.Contexts.ContextBase
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Enter
import dotty.tools.dotc.util.{NoSource, SourceFile}
import dotc.core.Symbols._

import scala.reflect.io.PlainFile

object Main {

  protected def initCtx = (new ContextBase).initialCtx

  def getSource(fileName: String)(ctx: Context): SourceFile = {
    val f = new PlainFile(fileName)
    if (f.exists) new SourceFile(f)
    else {
      ctx.error(s"not found: $fileName")
      NoSource
    }
  }

  def countSymbols(ctx: Context): Int = {
    def countAllChildren(s: Symbol): Int = 1 + s.childrenIterator.map(countAllChildren).sum
    countAllChildren(ctx.definitions.rootPackage)
  }

  def main(args: Array[String]): Unit = {
    val filePath = "bench/sample-files/Typer.scala.ignore"
    val ctx = initCtx.fresh
    val source = getSource(filePath)(ctx)

    val start = System.currentTimeMillis()

    val compilationUnit = {
      val unit = new CompilationUnit(source)
      val parser = new parsing.Parsers.Parser(source)(ctx)
      unit.untpdTree = parser.parse()
      unit
    }

    for (i <- 1 to 50000) {
      ctx.definitions.rootPackage.clear()

      val enter = new Enter
      enter.enterCompilationUnit(compilationUnit)(ctx)

    }

    println(s"It took ${System.currentTimeMillis() - start}")

    val totalSize = countSymbols(ctx)

    println(s"Total size is ${totalSize}")
  }
}
