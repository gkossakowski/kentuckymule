package dotty.tools.dotc.core

import Names.{Name, TypeName, TermName}

import Decorators._
import Scopes.{MutableScope, Scope}
import Contexts.Context
import Types._
import dotty.tools.dotc.core.Enter._

class Symbols { this: Contexts.Context =>
  import Symbols._
}

object Symbols {
  abstract class Symbol(val name: Name) {

    protected var scope: MutableScope = Scopes.newScope
    def addChild(sym: Symbol)(implicit ctx: Context): Unit = {
      scope.enter(sym)
    }
    def childrenIterator: Iterator[Symbol] = scope.iterator
    def clear(): Unit = {
      // this is wrong if one obtained an iterator for children; to be fixed
      scope = Scopes.newScope
    }
    def lookup(name: Name)(implicit ctx: Context): Symbol =
      scope.lookup(name)
    def lookupAll(name: Name)(implicit ctx: Context): Seq[Symbol] =
      scope.lookupAll(name).toSeq

    def isComplete: Boolean = true
    def decls: Scope = scope
  }
  abstract class TermSymbol(name: Name) extends Symbol(name)
  abstract class TypeSymbol(name: Name) extends Symbol(name)

  final class PackageSymbol(name: Name) extends TermSymbol(name)
  final class ClassSymbol(name: Name) extends TypeSymbol(name) {
    var info: ClassInfoType = _
    var completer: TemplateMemberListCompleter = _
    def completeInfo()(implicit context: Context): CompletionResult = {
      completer.complete()
    }
    val typeParams: MutableScope = Scopes.newScope
  }
  final class ModuleSymbol(name: Name, val clsSym: ClassSymbol) extends TermSymbol(name) {
    var info: ModuleInfoType = _
    var completer: TemplateMemberListCompleter = _
    def completeInfo()(implicit context: Context): CompletionResult = {
      val res = completer.complete()
      res match {
        case CompletedType(modClsInfoType: ClassInfoType) =>
          info = new ModuleInfoType(this, modClsInfoType)
        case _ =>
      }
      res
    }
    // TODO: this is a messy situation, we probably need to get rid of default scope in Symbol
    override def lookup(name: Name)(implicit ctx: Context): Symbol =
      clsSym.lookup(name)
    override def childrenIterator: Iterator[Symbol] =
      clsSym.childrenIterator
  }
  sealed case class ValDefSymbol(override val name: TermName) extends TermSymbol(name) {
    var info: ValInfoType = _
    var completer: ValDefCompleter = _
    def completeInfo()(implicit context: Context): CompletionResult = {
      completer.complete()
    }

    override def isComplete: Boolean = completer.isCompleted
  }
  final case class TypeDefSymbol(override val name: TypeName) extends TypeSymbol(name)
  final case class TypeParameterSymbol(override val name: TypeName, index: Int) extends TypeSymbol(name)
  sealed case class DefDefSymbol(override val name: TermName) extends TermSymbol(name) {
    var info: MethodInfoType = _
    var completer: DefDefCompleter = _
    def completeInfo()(implicit context: Context): CompletionResult = {
      completer.complete()
    }
    override def isComplete: Boolean = completer.isCompleted
  }
  final class InheritedDefDefSymbol(name: TermName, info0: MethodInfoType) extends DefDefSymbol(name) {
    info = info0
    override def completeInfo()(implicit context: Context): CompletionResult = CompletedType(info)
    override def isComplete: Boolean = true
  }
  final class InheritedValDefSymbol(name: TermName, info0: ValInfoType) extends ValDefSymbol(name) {
    info = info0
    override def completeInfo()(implicit context: Context): CompletionResult = CompletedType(info)
    override def isComplete: Boolean = true
  }

  object NoSymbol extends Symbol("<none>".toTermName)
}
