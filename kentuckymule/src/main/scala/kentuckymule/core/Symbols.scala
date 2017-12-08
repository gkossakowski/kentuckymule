package kentuckymule.core

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{Name, TermName, TypeName}
import dotty.tools.dotc.core.Scopes.{MutableScope, Scope}
import Types.{ClassInfoType, MethodInfoType, ModuleInfoType, NoType, PackageInfoType, Type, ValInfoType}
import dotty.tools.dotc.core.{Contexts, Scopes}
import dotty.tools.dotc.core.Decorators._
import kentuckymule.core.Enter._

class Symbols { this: Contexts.Context =>
}

object Symbols {
  abstract sealed class Symbol(val name: Name) {

    def info: Type

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

    def isComplete: Boolean
    def decls: Scope = scope
  }
  abstract sealed class TermSymbol(name: Name) extends Symbol(name)
  abstract sealed class TypeSymbol(name: Name) extends Symbol(name)

  final case class PackageSymbol(override val name: Name) extends TermSymbol(name) {
    var info: Type = _
    private var pkgObject: Symbol = NoSymbol
    override def isComplete: Boolean = info != null
    def packageObject_=(pkgObject: ModuleSymbol): Unit = {
      assert(this.pkgObject == NoSymbol, s"illegal overwrite of previously set package object ${this.pkgObject} by $pkgObject")
      this.pkgObject = pkgObject
    }
    def packageObject: Symbol = pkgObject
    var completer: Completer = _
    def completeInfo()(implicit context: Context): CompletionResult = {
      completer.complete()
    }
  }
  sealed case class ClassSymbol(override val name: Name, owner: Symbol) extends TypeSymbol(name) {
    var info: ClassInfoType = _
    var completer: Completer = _
    def completeInfo()(implicit context: Context): CompletionResult = {
      completer.complete()
    }
    val typeParams: MutableScope = Scopes.newScope
    override def isComplete: Boolean = info != null
  }
  final class StubClassSymbol(name: Name, owner: Symbol) extends ClassSymbol(name, owner)
  sealed case class ModuleSymbol(override val name: Name, clsSym: ClassSymbol, owner: Symbol) extends TermSymbol(name) {
    var info: ModuleInfoType = _
    var completer: Completer = _
    def completeInfo()(implicit context: Context): CompletionResult = {
      completer.complete()
    }
    // TODO: this is a messy situation, we probably need to get rid of default scope in Symbol
    override def lookup(name: Name)(implicit ctx: Context): Symbol =
      clsSym.lookup(name)
    override def childrenIterator: Iterator[Symbol] =
      clsSym.childrenIterator
    override def isComplete: Boolean = info != null
  }
  final class StubModuleSymbol(name: Name, clsSym: StubClassSymbol, owner: Symbol) extends
    ModuleSymbol(name, clsSym, owner)
  sealed case class ValDefSymbol(override val name: TermName) extends TermSymbol(name) {
    var info: ValInfoType = _
    var completer: ValDefCompleter = _
    def completeInfo()(implicit context: Context): CompletionResult = {
      completer.complete()
    }

    override def isComplete: Boolean = completer.isCompleted
  }
  final case class TypeDefSymbol(override val name: TypeName, enclosingClass: Symbol) extends TypeSymbol(name) {
    assert(enclosingClass.isInstanceOf[ClassSymbol], enclosingClass)
    def info: Type = NoType
    override def isComplete: Boolean = info != null
  }
  final case class TypeParameterSymbol(override val name: TypeName, index: Int, enclosingClass: Symbol) extends TypeSymbol(name) {
    assert(enclosingClass.isInstanceOf[ClassSymbol], enclosingClass)
    def info: Type = NoType
    override def isComplete: Boolean = info != null
  }
  sealed case class DefDefSymbol(override val name: TermName) extends TermSymbol(name) {
    var info: MethodInfoType = _
    var completer: DefDefCompleter = _
    val typeParams: MutableScope = Scopes.newScope
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

  object NoSymbol extends Symbol("<none>".toTermName) {
    def info: Type = NoType
    def isComplete: Boolean = true
  }
}
