package kentuckymule.core

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{Name, TermName, TypeName}
import dotty.tools.dotc.core.Scopes.{MutableScope, Scope}
import Types.{ClassInfoType, MethodInfoType, ModuleInfoType, NoType, PackageInfoType, Type, TypeAliasInfoType, ValInfoType}
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

    def completer: Completer
  }

  // TODO: the global pattern match on symbols + casts is very ugly but it's done for performance reasons
  // I tried to make TypeAssigner a Type => Unit function returned by Symbol
  // each subclass of symbol would return its own assigner and thanks to virtual dispatch +
  // path-dependent types, we can get rid of casts. However, this cost me 45ops/s (2.5% performance slow down)
  object TypeAssigner extends ((Symbol, Type) => Unit) {
    override def apply(symbol: Symbol, tpe: Type): Unit = {
      symbol match {
        case sym: PackageSymbol =>
          sym.info = tpe
        case sym: ClassSymbol =>
          sym.info = tpe.asInstanceOf[ClassInfoType]
        case sym: ModuleSymbol =>
          sym.info = tpe.asInstanceOf[ModuleInfoType]
        case sym: ValDefSymbol =>
          sym.info = tpe.asInstanceOf[ValInfoType]
        case sym: TypeDefSymbol =>
          sym.info = tpe
        case sym: DefDefSymbol =>
          sym.info = tpe.asInstanceOf[MethodInfoType]
        case sym: ImportSymbol =>
          sym.info = tpe
        case sym =>
          sys.error(s"Invalid symbol $sym")
      }
    }
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
  sealed case class ClassSymbol(override val name: Name, owner: Symbol, selfValDef: ValDefSymbol) extends TypeSymbol(name) {
    var info: ClassInfoType = _
    var completer: Completer = _
    def completeInfo()(implicit context: Context): CompletionResult = {
      completer.complete()
    }
    val typeParams: MutableScope = Scopes.newScope
    override def isComplete: Boolean = info != null
  }
  final class StubClassSymbol(name: Name, owner: Symbol) extends ClassSymbol(name, owner, null)
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
  final case class ValDefSymbol(override val name: TermName) extends TermSymbol(name) {
    var info: ValInfoType = _
    var completer: ValDefCompleter = _
    def completeInfo()(implicit context: Context): CompletionResult = {
      completer.complete()
    }

    override def isComplete: Boolean = completer.isCompleted
  }
  final case class TypeDefSymbol(override val name: TypeName, enclosingClass: Symbol) extends TypeSymbol(name) {
    assert(enclosingClass.isInstanceOf[ClassSymbol], enclosingClass)
    // TODO: turn it back to TypeAliasInfoType once StubTypeDefCompleter is out
    // right now, info has type `Type` because StubTypeDefCompleter returns NoType as
    // a result
    var info: Type = _
    var completer: TypeDefCompleter = _
    val typeParams: MutableScope = Scopes.newScope
    override def isComplete: Boolean = info != null
    def completeInfo()(implicit context: Context): CompletionResult = {
      completer.complete()
    }
  }
  final case class TypeParameterSymbol(override val name: TypeName, index: Int, enclosingClass: Symbol) extends TypeSymbol(name) {
    assert(enclosingClass.isInstanceOf[ClassSymbol], enclosingClass)
    def info: Type = NoType
    override def isComplete: Boolean = info != null
    override def completer: Completer = throw new UnsupportedOperationException("type parameters do not have type completers")
  }
  final case class DefDefSymbol(override val name: TermName, owner: Symbol) extends TermSymbol(name) {
    var info: MethodInfoType = _
    var completer: DefDefCompleter = _
    val typeParams: MutableScope = Scopes.newScope
    def completeInfo()(implicit context: Context): CompletionResult = {
      completer.complete()
    }
    override def isComplete: Boolean = completer.isCompleted
  }

  object NoSymbol extends Symbol("<none>".toTermName) {
    def info: Type = NoType
    def isComplete: Boolean = true
    override def completer: Completer = throw new UnsupportedOperationException("NoSymbol doesn't have type completer")
  }

  case class ImportSymbol() extends Symbol("<none>".toTermName) {
    var info: Type = _
    var completer: ImportCompleter = _
    override def isComplete: Boolean = info != null
  }
}
