package dotty.tools
package dotc

import dotty.tools.dotc.core.Contexts.ContextBase
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.{NoSource, SourceFile}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
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
//    val filePath = "sample-files/abc.scala.ignore"
val filePath = "sample-files/Typer.scala.ignore"
    val start = System.currentTimeMillis()
    val context = initCtx.fresh
    val sizes = for (i <- 1 to 5000) yield Future {
      val source = getSource(filePath)(context)
      val parser = new parsing.Parsers.Parser(source)(context)
      val tree = parser.parse()
//      tree.treeSize
      1
    }
    val totalSize = Await.result(Future.sequence(sizes), 1 minute).sum
    println(s"Total size is ${totalSize}")
    println(s"It took ${System.currentTimeMillis() - start}")
  }
}
