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
    val start = System.currentTimeMillis()

    val ctx = initCtx.fresh
    ctx.settings.verbose.update(true)(ctx)
    try {
      for (i <- 1 to 1) {
        ctx.definitions.rootPackage.clear()
        val jobsNumber = processScalap(ctx)
        println("Number of jobs processed: " + jobsNumber)
      }
    } finally {
      println(s"It took ${System.currentTimeMillis() - start} ms")
    }
    val totalSize = countSymbols(ctx)
  }

  def processScalap(implicit context: Context): Int = {
    import java.nio.file.Paths
    println("Calculating outline types for scalap sources...")
    val projectDir = Paths.get(".").toAbsolutePath.toString
    val compilationUnits = for (filePath <- ScalapHelper.scalapFiles(projectDir)) yield {
      val source = getSource(filePath)(context)
      val unit = new CompilationUnit(source)
      val parser = new parsing.Parsers.Parser(source)(context)
      unit.untpdTree = parser.parse()
      unit
    }
    ScalapHelper.enterStabSymbolsForScalap(context)
    val enter = new Enter
    for (compilationUnit <- compilationUnits)
      enter.enterCompilationUnit(compilationUnit)(context)

    val progressListener = if (context.verbose) Enter.NopJobQueueProgressListener else new ProgressBarListener
    val jobsNumber = enter.processJobQueue(memberListOnly = false, progressListener)(context)
    jobsNumber
  }

  class ProgressBarListener extends Enter.JobQueueProgressListener {
    val progressBar = new ProgressBar(1)
    override def thick(queueSize: Int, completed: Int): Unit = {
      progressBar.total = queueSize + completed
      progressBar.curr = completed
      progressBar.render()
    }

    override def allComplete(): Unit = println()
  }

}
