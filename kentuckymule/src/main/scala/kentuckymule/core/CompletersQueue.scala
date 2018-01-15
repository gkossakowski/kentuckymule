package kentuckymule.core

import java.util

import dotty.tools.dotc.core.Contexts.Context
import kentuckymule.core.Symbols._
import kentuckymule.core.Types.{ClassInfoType, MethodInfoType, ModuleInfoType, NoType, PackageInfoType, TypeAliasInfoType, ValInfoType, Type}
import CompletersQueue._

import scala.collection.JavaConverters._

class CompletersQueue {

  def completers: Seq[Completer] = completionJobs.iterator().asScala.map(_.completer).toSeq

  private val completionJobs: util.Deque[CompletionJob] = new util.ArrayDeque[CompletionJob]()

  def queueCompleter(completer: Completer, pushToTheEnd: Boolean = true): Unit = {
    val completionJob = new CompletionJob(completer)
    if (pushToTheEnd)
      completionJobs.add(completionJob)
    else
      completionJobs.addFirst(completionJob)
  }

  def processJobQueue(memberListOnly: Boolean,
                      listener: JobQueueProgressListener = NopJobQueueProgressListener)(implicit ctx: Context):
  CompleterStats = {
    var steps = 0
    var missedDeps = 0
    try {
      while (!completionJobs.isEmpty) {
        steps += 1
        if (ctx.verbose)
          println(s"Step $steps/${steps + completionJobs.size - 1}")
        val completionJob = completionJobs.remove()
        if (ctx.verbose)
          println(s"Trying to complete $completionJob")
        if (!completionJob.isCompleted) {
          val res = completionJob.complete(memberListOnly)
          if (ctx.verbose)
            println(s"res = $res")
          res match {
            case CompleteResult(spawnedJobs) =>
              var i = 0
              while (i < spawnedJobs.size) {
                completionJobs.addLast(spawnedJobs.get(i))
                i = i + 1
              }
            case IncompleteResult(blockingJob) =>
              missedDeps += 1
              queueIncompleteDependencyJobs(attemptedCompletionJob = completionJob, blockingJob = blockingJob)
          }
        }
        listener.thick(completionJobs.size, steps)
      }
    } catch {
      case ex: Exception =>
        println(s"steps = $steps, missedDeps = $missedDeps")
        throw ex
    }
    listener.allComplete()
    CompleterStats(steps, missedDeps)
  }

  private def queueIncompleteDependencyJobs(attemptedCompletionJob: CompletionJob,
                                            blockingJob: CompletionJob): Unit = {
    completionJobs.add(blockingJob)
    completionJobs.add(attemptedCompletionJob)
  }

}

object CompletersQueue {
  case class CompleterStats(processedJobs: Int, dependencyMisses: Int)

  trait JobQueueProgressListener {
    def thick(queueSize: Int, completed: Int): Unit
    def allComplete(): Unit
  }
  object NopJobQueueProgressListener extends JobQueueProgressListener {
    override def thick(queueSize: Int, completed: Int): Unit = ()
    override def allComplete(): Unit = ()
  }
}

private object CompletionJob {
  val emptySpawnedJobs: util.ArrayList[CompletionJob] = new util.ArrayList[CompletionJob]()
  private val typeAssigner = Symbols.TypeAssigner
}
private class CompletionJob(val completer: Completer) {
  import CompletionJob.{emptySpawnedJobs, typeAssigner}
  assert(completer != null)

  def complete(memberListOnly: Boolean)(implicit ctx: Context): JobResult = {
    val completerResult = completer.complete()
    completerResult match {
      case CompletedType(tpe: ClassInfoType) =>
        val classSym = tpe.clsSym
        classSym.info = tpe
        val spawnedJobs = if (!memberListOnly) {
          spawnMembersCompletionJobs(classSym)
        } else emptySpawnedJobs
        CompleteResult(spawnedJobs)
      // TODO: remove special treatment of StubTypeDefCompleter once poly type aliases are implemented
      case CompletedType(NoType) if completer.isInstanceOf[StubTypeDefCompleter] =>
        val typeDefSym = completer.sym.asInstanceOf[TypeDefSymbol]
        typeDefSym.info = NoType
        CompleteResult(emptySpawnedJobs)
      case CompletedType(tpe: Type) =>
        typeAssigner(completer.sym, tpe)
        CompleteResult(emptySpawnedJobs)
      // error cases
      case incomplete@(IncompleteDependency(_: TypeParameterSymbol) | IncompleteDependency(NoSymbol) |
                       IncompleteDependency(_: PackageSymbol)) =>
        sys.error(s"Unexpected incomplete dependency $incomplete")
      case completionResult: IncompleteDependency =>
        IncompleteResult(new CompletionJob(completionResult.sym.completer))
      case NotFound =>
        sys.error(s"The completer for ${completer.sym} finished with a missing dependency")
    }
  }

  def isCompleted: Boolean = completer.isCompleted

  override def toString = s"CompletionJob($completer)"

  private def spawnMembersCompletionJobs(sym: ClassSymbol)(implicit ctx: Context): util.ArrayList[CompletionJob] = {
    val jobs = new util.ArrayList[CompletionJob](sym.decls.size)
    sym.decls.toList foreach {
      case defSym: DefDefSymbol => jobs.add(new CompletionJob(defSym.completer))
      case valSym: ValDefSymbol => jobs.add(new CompletionJob(valSym.completer))
      case _: ClassSymbol | _: ModuleSymbol =>
      case decl@(_: TypeDefSymbol) =>
        if (ctx.verbose)
          println(s"Ignoring type def $decl in ${sym.name}")
      case decl@(_: TypeParameterSymbol | _: PackageSymbol | NoSymbol) =>
        sys.error(s"Unexpected class declaration: $decl")
    }
    jobs
  }
}

private sealed abstract class JobResult
private case class CompleteResult(spawnedJobs: util.ArrayList[CompletionJob]) extends JobResult
private case class IncompleteResult(blockingJob: CompletionJob) extends JobResult
