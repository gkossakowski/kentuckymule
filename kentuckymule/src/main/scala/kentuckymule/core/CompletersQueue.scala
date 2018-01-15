package kentuckymule.core

import java.util

import dotty.tools.dotc.core.Contexts.Context
import kentuckymule.core.Symbols._
import kentuckymule.core.Types.{ClassInfoType, MethodInfoType, ModuleInfoType, NoType, PackageInfoType, TypeAliasInfoType, ValInfoType, Type}
import CompletersQueue._

import scala.collection.JavaConverters._

class CompletersQueue {

  def completers: Seq[Completer] = completionJobs.iterator().asScala.map(_.completer).toSeq

  val completionJobs: util.Deque[CompletionJob] = new util.ArrayDeque[CompletionJob]()

  class CompletionJob(val completer: Completer) {
    assert(completer != null)
  }

  def queueCompleter(completer: Completer, pushToTheEnd: Boolean = true): Unit = {
    val completionJob = new CompletionJob(completer)
    if (pushToTheEnd)
      completionJobs.add(completionJob)
    else
      completionJobs.addFirst(completionJob)
  }

  def queueIncompleteDependencyJobs(attemptedCompletionJob: CompletionJob,
                                    completionResult: IncompleteDependency): Unit = {
    val incompleteSymbol = completionResult.sym
    val completionJob = new CompletionJob(completer = incompleteSymbol.completer)
    completionJobs.add(completionJob)
    completionJobs.add(attemptedCompletionJob)
  }

  private val typeAssigner = Symbols.TypeAssigner

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
        val completer = completionJob.completer
        if (ctx.verbose)
          println(s"Trying to complete $completer")
        if (!completer.isCompleted) {
          val res = completer.complete()
          if (ctx.verbose)
            println(s"res = $res")
          res match {
            case CompletedType(tpe: ClassInfoType) =>
              val classSym = tpe.clsSym
              classSym.info = tpe
              if (!memberListOnly) {
                val spawnedJobs = spawnMembersCompletionJobs(classSym)
                var i = 0
                while (i < spawnedJobs.size) {
                  completionJobs.addLast(spawnedJobs.get(i))
                  i = i + 1
                }
              }
            // TODO: remove special treatment of StubTypeDefCompleter once poly type aliases are implemented
            case CompletedType(NoType) if completer.isInstanceOf[StubTypeDefCompleter] =>
              val typeDefSym = completer.sym.asInstanceOf[TypeDefSymbol]
              typeDefSym.info = NoType
            case CompletedType(tpe: Type) =>
              typeAssigner(completer.sym, tpe)
            // error cases
            case incomplete@(IncompleteDependency(_: TypeParameterSymbol) | IncompleteDependency(NoSymbol) |
                             IncompleteDependency(_: PackageSymbol)) =>
              sys.error(s"Unexpected incomplete dependency $incomplete")
            case completionResult: IncompleteDependency =>
              missedDeps += 1
              queueIncompleteDependencyJobs(completionJob, completionResult)
            case NotFound =>
              sys.error(s"The completer for ${completer.sym} finished with a missing dependency")
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
