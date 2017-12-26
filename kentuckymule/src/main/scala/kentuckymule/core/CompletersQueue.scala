package kentuckymule.core

import java.util

import dotty.tools.dotc.core.Contexts.Context
import kentuckymule.core.Symbols._
import kentuckymule.core.Types.{ClassInfoType, MethodInfoType, ModuleInfoType, NoType, PackageInfoType, TypeAliasInfoType, ValInfoType}
import CompletersQueue._

class CompletersQueue {

  val completers: util.Deque[Completer] = new util.ArrayDeque[Completer]()

  def queueCompleter(completer: Completer, pushToTheEnd: Boolean = true): Unit = {
    if (pushToTheEnd)
      completers.add(completer)
    else
      completers.addFirst(completer)
  }

  def processJobQueue(memberListOnly: Boolean,
                      listener: JobQueueProgressListener = NopJobQueueProgressListener)(implicit ctx: Context):
  CompleterStats = {
    var steps = 0
    var missedDeps = 0
    try {
      while (!completers.isEmpty) {
        steps += 1
        if (ctx.verbose)
          println(s"Step $steps/${steps + completers.size - 1}")
        val completer = completers.remove()
        if (ctx.verbose)
          println(s"Trying to complete $completer")
        if (!completer.isCompleted) {
          val res = completer.complete()
          if (ctx.verbose)
            println(s"res = $res")
          if (res.isInstanceOf[IncompleteDependency]) {
            missedDeps += 1
          }
          res match {
            case CompletedType(tpe: ClassInfoType) =>
              val classSym = tpe.clsSym
              classSym.info = tpe
              if (!memberListOnly)
                scheduleMembersCompletion(classSym)
            case CompletedType(tpe: ModuleInfoType) =>
              val modSym = tpe.modSym
              modSym.info = tpe
            case IncompleteDependency(sym: ClassSymbol) =>
              assert(sym.completer != null, sym.name)
              queueCompleter(sym.completer)
              queueCompleter(completer)
            case IncompleteDependency(sym: ModuleSymbol) =>
              assert(sym.completer != null, sym.name)
              queueCompleter(sym.completer)
              queueCompleter(completer)
            case IncompleteDependency(sym: ValDefSymbol) =>
              queueCompleter(sym.completer)
              queueCompleter(completer)
            case IncompleteDependency(sym: DefDefSymbol) =>
              queueCompleter(sym.completer)
              queueCompleter(completer)
            case IncompleteDependency(sym: PackageSymbol) =>
              queueCompleter(sym.completer)
              queueCompleter(completer)
            case IncompleteDependency(sym: TypeDefSymbol) =>
              queueCompleter(sym.completer)
              queueCompleter(completer)
            case CompletedType(tpe: MethodInfoType) =>
              val defDefSym = completer.sym.asInstanceOf[DefDefSymbol]
              defDefSym.info = tpe
            case CompletedType(tpe: ValInfoType) =>
              val valDefSym = completer.sym.asInstanceOf[ValDefSymbol]
              valDefSym.info = tpe
            case CompletedType(tpe: PackageInfoType) =>
              val pkgSym = completer.sym.asInstanceOf[PackageSymbol]
              pkgSym.info = tpe
            case CompletedType(tpe: TypeAliasInfoType) =>
              val typeDefSym = completer.sym.asInstanceOf[TypeDefSymbol]
              typeDefSym.info = tpe
            // TODO: remove special treatment of StubTypeDefCompleter once poly type aliases are implemented
            case CompletedType(NoType) if completer.isInstanceOf[StubTypeDefCompleter] =>
              val typeDefSym = completer.sym.asInstanceOf[TypeDefSymbol]
              typeDefSym.info = NoType
            // error cases
            case completed: CompletedType =>
              sys.error(s"Unexpected completed type $completed returned by completer for ${completer.sym}")
            case incomplete@(IncompleteDependency(_: TypeParameterSymbol) | IncompleteDependency(NoSymbol) |
                             IncompleteDependency(_: PackageSymbol)) =>
              sys.error(s"Unexpected incomplete dependency $incomplete")
            case NotFound =>
              sys.error(s"The completer for ${completer.sym} finished with a missing dependency")
          }
        }
        listener.thick(completers.size, steps)
      }
    } catch {
      case ex: Exception =>
        println(s"steps = $steps, missedDeps = $missedDeps")
        throw ex
    }
    listener.allComplete()
    CompleterStats(steps, missedDeps)
  }

  private def scheduleMembersCompletion(sym: ClassSymbol)(implicit ctx: Context): Unit = {
    sym.decls.toList foreach {
      case defSym: DefDefSymbol => queueCompleter(defSym.completer)
      case valSym: ValDefSymbol => queueCompleter(valSym.completer)
      case _: ClassSymbol | _: ModuleSymbol =>
      case decl@(_: TypeDefSymbol) =>
        if (ctx.verbose)
          println(s"Ignoring type def $decl in ${sym.name}")
      case decl@(_: TypeParameterSymbol | _: PackageSymbol | NoSymbol) =>
        sys.error(s"Unexpected class declaration: $decl")
    }
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
