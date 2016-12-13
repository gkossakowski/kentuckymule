package kentuckymule.core

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.Scopes._
import dotty.tools.sharable
import kentuckymule.core.Symbols._

import scala.language.implicitConversions

object Types {

  @sharable private var nextId = 0

  implicit def eqType: Eq[Type, Type] = Eq

  abstract class Type {
    def typeSymbol: Symbol
    def lookup(name: Name)(implicit contexts: Context): Symbol
  }

  abstract class NamedType extends ValueType {

    val prefix: Type
    val name: Name

    type ThisType >: this.type <: NamedType

    def isType: Boolean
  }

  /** A marker trait for types that apply only to type symbols */
  trait TypeType extends Type

  /** A marker trait for types that apply only to term symbols or that
    *  represent higher-kinded types.
    */
  trait TermType extends Type

  /** A marker trait for types that can be types of values or prototypes of value types */
  trait ValueTypeOrProto extends TermType

  /** A marker trait for types that can be types of values or that are higher-kinded  */
  trait ValueType extends ValueTypeOrProto

  final class ClassInfoType(val clsSym: ClassSymbol, val parents: List[Type]) extends TypeType {
    val members: MutableScope = newScope

    override def typeSymbol: Symbol = clsSym

    override def lookup(name: Name)(implicit contexts: Context): Symbol = members.lookup(name)
  }

  final class ModuleInfoType(val modSym: ModuleSymbol, modClassInfoType: ClassInfoType) extends TermType {
    def members: Scope = modClassInfoType.members

    override def typeSymbol: Symbol = modSym
    override def lookup(name: Name)(implicit contexts: Context): Symbol = members.lookup(name)
  }

  final case class MethodInfoType(defDefSymbol: DefDefSymbol, paramTypes: List[List[Type]], resultType: Type) extends Type {
    override def typeSymbol: Symbol = NoSymbol
    override def lookup(name: Name)(implicit contexts: Context): Symbol = NoSymbol
  }

  final case class ValInfoType(vaDefSymbol: ValDefSymbol, resultType: Type) extends Type {
    override def typeSymbol: Symbol = NoSymbol
    override def lookup(name: Name)(implicit contexts: Context): Symbol = resultType.lookup(name)
  }

  final case class SymRef(sym: Symbol) extends Type {
    override def typeSymbol: Symbol = sym
    override def lookup(name: Name)(implicit contexts: Context): Symbol = sym.lookup(name)
  }

  final case class AppliedType(tpe: Type, args: Array[Type]) extends Type {
    override def typeSymbol: Symbol = tpe.typeSymbol
    override def lookup(name: Name)(implicit contexts: Context): Symbol = tpe.lookup(name)
  }

  case object InferredTypeMarker extends Type {
    override def typeSymbol: Symbol = NoSymbol
    override def lookup(name: Name)(implicit contexts: Context): Symbol = NoSymbol
  }

  case object NoType extends Type {
    override def typeSymbol: Symbol = NoSymbol
    override def lookup(name: Name)(implicit contexts: Context): Symbol = NoSymbol
  }

  final class PackageInfoType(pkgSymbol: PackageSymbol) extends Type {
    override def typeSymbol: Symbol = pkgSymbol
    override def lookup(name: Name)(implicit contexts: Context): Symbol = pkgSymbol.lookup(name)
  }

  object WildcardType extends Type {
    override def typeSymbol: Symbol =
      throw new UnsupportedOperationException("wildcard type doesn't have a type symbol associated")

    override def lookup(name: Name)(implicit contexts: Context): Symbol =
      throw new UnsupportedOperationException("wildcard type doesn't have any members")
  }

}
