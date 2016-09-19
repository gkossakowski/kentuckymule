package dotty.tools
package dotc
package core

import Names._
import language.implicitConversions

object Types {

  @sharable private var nextId = 0

  implicit def eqType: Eq[Type, Type] = Eq

  abstract class Type

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

}
