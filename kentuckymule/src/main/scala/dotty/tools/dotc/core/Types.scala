package dotty.tools
package dotc
package core

import Names._
import language.implicitConversions
import Scopes._
import Symbols._

object Types {

  @sharable private var nextId = 0

  implicit def eqType: Eq[Type, Type] = Eq

  abstract class Type {
    def typeSymbol: Symbol
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

  class ClassInfoType(val clsSym: ClassSymbol) extends TypeType {
    val members: MutableScope = newScope

    override def typeSymbol: Symbol = clsSym
  }

  class ModuleInfoType(val modSym: ModuleSymbol, modClassInfoType: ClassInfoType) extends TermType {
    def members: Scope = modClassInfoType.members

    override def typeSymbol: Symbol = modSym
  }

  case class MethodInfoType(defDefSymbol: DefDefSymbol, paramTypes: List[List[Type]], resultType: Type) extends Type {
    override def typeSymbol: Symbol = NoSymbol
  }

  case class ValInfoType(vaDefSymbol: ValDefSymbol, resultType: Type) extends Type {
    override def typeSymbol: Symbol = NoSymbol
  }

  case class SymRef(sym: Symbol) extends Type {
    override def typeSymbol: Symbol = sym
  }

  case class AppliedType(tpe: Type, args: List[Type]) extends Type {
    override def typeSymbol: Symbol = tpe.typeSymbol
  }

}
