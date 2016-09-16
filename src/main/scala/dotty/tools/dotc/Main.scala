package dotty.tools
package dotc

import dotty.tools.dotc.core.Contexts.ContextBase
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.{NoSource, SourceFile}

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

  def main(args: Array[String]): Unit = {
    val context = initCtx.fresh
    val source = getSource("abc.scala")(context)
    val parser = new parsing.Parsers.Parser(source)(context)
    val tree = parser.parse()
    println(tree)
  }
}
