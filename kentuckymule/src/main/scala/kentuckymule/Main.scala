package kentuckymule

import java.nio.file.{Path, Paths, StandardWatchEventKinds, WatchEvent}

import better.files.File
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import kentuckymule.core.Symbols.{ClassSymbol, Symbol}
import dotty.tools.dotc.util.{NoSource, SourceFile}
import dotty.tools.dotc.{CompilationUnit, parsing}
import kentuckymule.core.{CompletionJob, DependenciesExtraction, Enter, TemplateMemberListCompleter}
import kentuckymule.queue.{JobQueue, QueueJob}
import kentuckymule.queue.JobQueue._

import scala.concurrent.duration
import scala.concurrent.duration.Duration
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
        processSources(ctx, srcsToProcess)
      }
    } finally {
      println(s"It took ${System.currentTimeMillis() - start} ms")
    }
    println("watching ...")
    if (srcsToProcess == "scalalib") {
      val pathToWatch = Paths.get("/Users/grek/scala/kentuckymule/sample-projects/scala/src/library")
      import better.files._
      import _root_.io.methvin.better.files._

      val watcher = new RecursiveFileMonitor(pathToWatch) {
        override def onCreate(file: File, count: Int) = println(s"$file got created")
        override def onModify(file: File, count: Int) = {
          println(s"$file got modified $count times")
          processScalaLibIncrementally(modified = Set(file), added = Set.empty, removed = Set.empty)(ctx)
        }
        override def onDelete(file: File, count: Int) = println(s"$file got deleted")
      }

      import scala.concurrent.ExecutionContext.Implicits.global
      watcher.start()
      while (true) {
        Thread.sleep(1000)
      }
    }
    val totalSize = countSymbols(ctx)
  }

  def timed[T](msg: Long => String)(f: => T): T = {
    val start = System.currentTimeMillis()
    try {
      f
    } finally {
      val duration = System.currentTimeMillis() - start
      println(msg(duration))
    }
  }

  private def processSources(ctx: Context, srcsToProcess: String): Unit = {
    ctx.definitions.rootPackage.clear()
    val jobQueueResult = {
      srcsToProcess match {
        case "scalalib" => processScalaLib(ctx)
        case "scalap" => processScalap(ctx)
      }
    }
    jobQueueResult match {
      case JobDependencyCycle(foundCycles) =>
        println()
        println(s"Found ${foundCycles.size} cycle(s)!")
        foundCycles.zipWithIndex.foreach { case (cycle, j) =>
          println(s"Cycle $j")
          cycle.foreach(job => println(s"\t$job"))
        }
      case CompleterStats(jobsNumber, dependencyMisses) =>
        println(s"Number of jobs processed: $jobsNumber out of which $dependencyMisses finished with dependency miss")
    }
  }

  def processScalap(implicit context: Context): JobQueueResult = {
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
    val jobQueue = new JobQueue(memberListOnly = false)
    val enter = new Enter(jobQueue)
    ScalapHelper.enterStabSymbolsForScalap(jobQueue, enter)(context)
    for (compilationUnit <- compilationUnits)
      enter.enterCompilationUnit(compilationUnit)(context)

    val progressListener = if (context.verbose) NopJobQueueProgressListener else new ProgressBarListener
    val jobQueueResult = jobQueue.processJobQueue(listener = progressListener)(context)
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

    jobQueueResult
  }

  import scala.collection.mutable.{Map => MutableMap}
  val compilationUnits: MutableMap[String, CompilationUnit] = MutableMap.empty

  def parseScalaLib()(implicit context: Context): Unit = {
    val scalaLibDir = Paths.get("./sample-projects/scala/src/library").toAbsolutePath.toString
    for (filePath <- ScalaLibHelper.scalaLibraryFiles(scalaLibDir)) {
      val source = getSource(filePath)(context)
      val unit = new CompilationUnit(source)
      val parser = new parsing.Parsers.Parser(source)(context)
      unit.untpdTree = parser.parse()
      compilationUnits(filePath) = unit
    }
  }

  def processCompilationUnits(compilationUnits: Iterable[CompilationUnit])(implicit context: Context): JobQueueResult = {
    val jobQueue = new JobQueue(memberListOnly = false)
    val enter = new Enter(jobQueue)
    ScalaLibHelper.enterStabSymbolsForScalaLib(jobQueue, enter, context)
    for (compilationUnit <- compilationUnits)
      enter.enterCompilationUnit(compilationUnit)(context)

    val progressListener = if (context.verbose) NopJobQueueProgressListener else new ProgressBarListener
    val jobQueueResult = jobQueue.processJobQueue(listener = progressListener)(context)
    println()
    Timer.sinceInit("Job queue processed")
    jobQueueResult match {
      case JobDependencyCycle(_) =>
        return jobQueueResult
      case _ =>
    }
    val depsExtraction = new DependenciesExtraction(topLevelOnly = true)
    val (classes, deps) = depsExtraction.extractAllDependencies()
    Timer.sinceInit("Deps extracted")
    import scala.collection.JavaConverters._
    val sccResult@TarjanSCC.SCCResult(components, edges) =
      TarjanSCC.collapsedGraph[ClassSymbol](classes.asScala, from => deps.get(from).asScala)
    Timer.sinceInit("Tarjan done")
    println(s"Found ${components.size} dependency groups")
    def printSymbol(cls: ClassSymbol): String = s"${cls.owner.name}.${cls.name}"
    val longestPath = TarjanSCC.longestPath(sccResult)
    Timer.sinceInit("Longest path computed")
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

    jobQueueResult
  }

  def processScalaLib(implicit context: Context): JobQueueResult = {
    Timer.init()
    import java.nio.file.Paths
    println("Calculating outline types for scala library sources...")
    parseScalaLib()
    Timer.sinceInit("Parsing done")
    processCompilationUnits(compilationUnits.values)
  }

  def processScalaLibIncrementally(added: Set[File], modified: Set[File], removed: Set[File])
                                  (implicit context: Context): JobQueueResult = {
    clearScreen()
    Timer.init()
    // TODO
    assert(added.isEmpty)
    assert(removed.isEmpty)
    modified.foreach { filePath =>
      println(s"parsing again $filePath")
      val source = getSource(filePath.pathAsString)(context)
      val unit = new CompilationUnit(source)
      val parser = new parsing.Parsers.Parser(source)(context)
      unit.untpdTree = parser.parse()
      compilationUnits(filePath.pathAsString) = unit
    }
    context.definitions.rootPackage.clear()
    val jobQueueResult = try processCompilationUnits(compilationUnits.values)
    catch {
      case ex: Exception =>
        println(ex.getMessage)
        throw ex
    }
    jobQueueResult match {
      case JobDependencyCycle(foundCycles) =>
        println()
        println(s"Found ${foundCycles.size} cycle(s)!")
        foundCycles.zipWithIndex.foreach { case (cycle, j) =>
          println(s"Cycle $j")
          def prettyPrint(queueJob: QueueJob): String = {
            queueJob match {
              case job: CompletionJob => job.completer match {
                case tc: TemplateMemberListCompleter =>
                  tc.clsSym.toString
              }
              case _ => ""
            }
          }
          cycle.foreach(job => println(s"\t${prettyPrint(job)}"))
        }
      case CompleterStats(jobsNumber, dependencyMisses) =>
        println(s"Number of jobs processed: $jobsNumber out of which $dependencyMisses finished with dependency miss")
    }
    jobQueueResult
  }

  def clearScreen(): Unit = {
    val ANSI_CLS = "\u001b[2J"
    val ANSI_HOME = "\u001b[H"
    println(ANSI_CLS + ANSI_HOME)
    Console.out.flush()
  }

  class ProgressBarListener extends JobQueueProgressListener {
    val progressBar = new ProgressBar(1)
    var lastRerenderAt: Int = 0
    override def thick(queueSize: Int, completed: Int): Unit = {
      val normalizedDelta = (completed - lastRerenderAt).toDouble / (queueSize + completed).toDouble
      val rerender = normalizedDelta > 0.01 || queueSize == 0
      progressBar.total = queueSize + completed
      progressBar.curr = completed
      if (rerender) {
        progressBar.render()
        lastRerenderAt = completed
      }
    }

    override def allComplete(): Unit = println()
  }

}
