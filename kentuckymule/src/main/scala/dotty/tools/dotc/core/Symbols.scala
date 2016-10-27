package dotty.tools.dotc.core

import Names.{Name, TypeName, TermName}

import scala.collection.mutable.{ListBuffer, Map}
import com.google.common.collect.{ArrayListMultimap, ListMultimap}

import scala.collection.JavaConverters._
import Decorators._
import Scopes.{MutableScope, Scope}
import Contexts.Context
import Types._
import dotty.tools.dotc.core.Enter.{CompletedType, CompletionResult, DefDefCompleter, TemplateMemberListCompleter}

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
  final class ValDefSymbol(name: Name) extends TermSymbol(name)
  final class TypeDefSymbol(name: TypeName) extends TypeSymbol(name)
  final class DefDefSymbol(name: Name) extends TermSymbol(name) {
    var info: MethodInfoType = _
    var completer: DefDefCompleter = _
    def completeInfo()(implicit context: Context): CompletionResult = {
      completer.complete()
    }
  }

  object NoSymbol extends Symbol("<none>".toTermName)
}
