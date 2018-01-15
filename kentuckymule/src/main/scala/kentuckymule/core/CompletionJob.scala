package kentuckymule.core

import java.util

import dotty.tools.dotc.core.Contexts.Context
import kentuckymule.core.Symbols._
import kentuckymule.core.Types.{ClassInfoType, NoType, Type}

object CompletionJob {
  val emptySpawnedJobs: util.ArrayList[CompletionJob] = new util.ArrayList[CompletionJob]()
  private val typeAssigner = Symbols.TypeAssigner
  def createOrFetch(completer: Completer): CompletionJob = {
    if (completer.completionJob == null)
      completer.completionJob = new CompletionJob(completer)
    completer.completionJob
  }
}

class CompletionJob private(val completer: Completer, val queueStore: QueueJobStore = new QueueJobStore) {
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
        val completionJob = CompletionJob.createOrFetch(completionResult.sym.completer)
        IncompleteResult(completionJob)
      case NotFound =>
        sys.error(s"The completer for ${completer.sym} finished with a missing dependency")
    }
  }

  def isCompleted: Boolean = completer.isCompleted

  override def toString = s"CompletionJob($completer)"

  private def spawnMembersCompletionJobs(sym: ClassSymbol)(implicit ctx: Context): util.ArrayList[CompletionJob] = {
    val jobs = new util.ArrayList[CompletionJob](sym.decls.size)
    sym.decls.toList foreach {
      case defSym: DefDefSymbol => jobs.add(CompletionJob.createOrFetch(defSym.completer))
      case valSym: ValDefSymbol => jobs.add(CompletionJob.createOrFetch(valSym.completer))
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

/**
  * A stub for a store associated with a single queue job. In the future we can store here
  * a job continuation or a whole stack of jobs that are blocked on a given job.
  */
final class QueueJobStore {
  var pendingJobs: java.util.ArrayList[CompletionJob] = _
}

sealed abstract class JobResult
case class CompleteResult(spawnedJobs: util.ArrayList[CompletionJob]) extends JobResult
case class IncompleteResult(blockingJob: CompletionJob) extends JobResult
