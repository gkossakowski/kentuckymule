package kentuckymule

import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import kentuckymule.core.Symbols.{ClassSymbol, Symbol}
import dotty.tools.dotc.util.{NoSource, SourceFile}
import dotty.tools.dotc.{CompilationUnit, parsing}
import kentuckymule.core.Enter.CompleterStats
import kentuckymule.core.{DependenciesExtraction, Enter}

import scala.reflect.io.PlainFile

object Main {

  protected def initCtx: Context = (new ContextBase).initialCtx

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

    val srcsToProcess: String = args match {
      case Array("scalalib") => "scalalib"
      case _ => "scalap"
    }

    val ctx = initCtx.fresh
    ctx.settings.verbose.update(false)(ctx)
    ctx.settings.language.update(dotty.tools.dotc.core.StdNames.nme.Scala2.toString :: Nil)(ctx)
    try {
      for (i <- 1 to 1) {
        ctx.definitions.rootPackage.clear()
        val CompleterStats(jobsNumber, dependencyMisses) = {
          srcsToProcess match {
            case "scalalib" => processScalaLib(ctx)
            case "scalap" => processScalap(ctx)
          }
        }
        println(s"Number of jobs processed: $jobsNumber out of which $dependencyMisses finished with dependency miss")
      }
    } finally {
      println(s"It took ${System.currentTimeMillis() - start} ms")
    }
    val totalSize = countSymbols(ctx)
  }

  def processScalap(implicit context: Context): Enter.CompleterStats = {
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
    val enter = new Enter
    ScalapHelper.enterStabSymbolsForScalap(enter)(context)
    for (compilationUnit <- compilationUnits)
      enter.enterCompilationUnit(compilationUnit)(context)

    val progressListener = if (context.verbose) Enter.NopJobQueueProgressListener else new ProgressBarListener
    val jobsNumber = enter.processJobQueue(memberListOnly = false, progressListener)(context)
    val depsExtraction = new DependenciesExtraction(topLevelOnly = true)
    val (classes, deps) = depsExtraction.extractAllDependencies()
    import scala.collection.JavaConverters._
    val sccResult@TarjanSCC.SCCResult(components, edges) =
      TarjanSCC.collapsedGraph[ClassSymbol](classes.asScala, from => deps.get(from).asScala)
    println(s"Found ${components.size} dependency groups")
    def printSymbol(cls: ClassSymbol): String = s"${cls.owner.name}.${cls.name}"
    val longestPath = TarjanSCC.longestPath(sccResult)
    println(s"Printing the longest (${longestPath.size}) dependency path ")
    for (component <- longestPath) {
      val symbols = component.vertices
      print(s"Component.size = ${symbols.size}: ")
      print((symbols.asScala take 4).map(printSymbol).mkString(", "))
      if (symbols.size > 4)
        println(", ...")
      else
        println()
    }

    jobsNumber
  }

  def processScalaLib(implicit context: Context): Enter.CompleterStats = {
    import java.nio.file.Paths
    println("Calculating outline types for scala library sources...")
    val scalaLibDir = Paths.get("./sample-projects/scala/src/library").toAbsolutePath.toString
    val compilationUnits = for (filePath <- ScalaLibHelper.scalaLibraryFiles(scalaLibDir)) yield {
      val source = getSource(filePath)(context)
      val unit = new CompilationUnit(source)
      val parser = new parsing.Parsers.Parser(source)(context)
      unit.untpdTree = parser.parse()
      unit
    }
    val enter = new Enter
    ScalaLibHelper.enterStabSymbolsForScalaLib(enter, context)
    for (compilationUnit <- compilationUnits)
      enter.enterCompilationUnit(compilationUnit)(context)

    val progressListener = if (context.verbose) Enter.NopJobQueueProgressListener else new ProgressBarListener
    val jobsNumber = enter.processJobQueue(memberListOnly = false, progressListener)(context)
    val depsExtraction = new DependenciesExtraction(topLevelOnly = true)
    val (classes, deps) = depsExtraction.extractAllDependencies()
    import scala.collection.JavaConverters._
    val sccResult@TarjanSCC.SCCResult(components, edges) =
      TarjanSCC.collapsedGraph[ClassSymbol](classes.asScala, from => deps.get(from).asScala)
    println(s"Found ${components.size} dependency groups")
    def printSymbol(cls: ClassSymbol): String = s"${cls.owner.name}.${cls.name}"
    val longestPath = TarjanSCC.longestPath(sccResult)
    println(s"Printing the longest (${longestPath.size}) dependency path ")
    for (component <- longestPath) {
      val symbols = component.vertices
      print(s"Component.size = ${symbols.size}: ")
      print((symbols.asScala take 4).map(printSymbol).mkString(", "))
      if (symbols.size > 4)
        println(", ...")
      else
        println()
    }

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
