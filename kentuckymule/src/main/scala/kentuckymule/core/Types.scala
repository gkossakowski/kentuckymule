package kentuckymule.core

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.Scopes._
import dotty.tools.dotc.core.TypeOps.AppliedTypeMemberDerivation
import dotty.tools.sharable
import kentuckymule.core.Symbols._
import kentuckymule.core.LookupAnswer.symToLookupAnswer

import scala.collection.mutable
import scala.language.implicitConversions

object Types {

  @sharable private var nextId = 0

  implicit def eqType: Eq[Type, Type] = Eq

  abstract class Type {
    def typeSymbol: Symbol
    def lookup(name: Name)(implicit contexts: Context): LookupAnswer
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

  final class ClassInfoType(val clsSym: ClassSymbol,
                            val parents: List[Type],
                            val selfInfo: Type) extends TypeType {

    var parentMemberDerivation: List[Option[AppliedTypeMemberDerivation]] = _

    private val reversedParents = parents.reverse

    def membersIterator: Iterator[Symbol] = (reversedParents.iterator flatMap { tpe =>
      tpe.typeSymbol.info.asInstanceOf[ClassInfoType].membersIterator
    }) ++ clsSym.decls.iterator

    override def typeSymbol: Symbol = clsSym

    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = {
      val clsDecl = clsSym.decls.lookup(name)
      if (clsDecl != NoSymbol)
        return LookedupSymbol(clsDecl)
      var remainingReversedParents = reversedParents
      while (remainingReversedParents.nonEmpty) {
        val parent = remainingReversedParents.head
        val lookupParent = parent.lookup(name)
        lookupParent match {
          case result: LookedupSymbol => return result
          case result: IncompleteDependency => return result
          case NotFound => // continue looking
        }
        remainingReversedParents = remainingReversedParents.tail
      }
      NotFound
    }


    def asSeenFromThis_slow(s: Symbol)(implicit context: Context): CompletionResult = {
      if (clsSym.decls.lookupAll(s.name).contains(s)) {
        assert(s.isComplete, s)
        CompletedType(s.info)
      } else {
        (parents zip parentMemberDerivation) foreach {
          case (at: AppliedType, Some(appliedTypeMemberDerivation)) =>
            if (at.lookup(s.name) == LookedupSymbol(s)) {
              val parentDerivedType = at.typeSymbol match {
                case parentCls: ClassSymbol =>
                  val parentDerived = parentCls.info.asSeenFromThis_slow(s)
                  parentDerived match {
                    case CompletedType(parentClassDerivedType) =>
                      parentClassDerivedType
                    case _ => return parentDerived
                  }
                case parentTypeDef: TypeDefSymbol =>
                  s.info
              }
              return appliedTypeMemberDerivation.deriveInheritedMemberInfoOfAppliedType(parentDerivedType)
            }
        }
        NotFound
      }
    }
  }

  final class ModuleInfoType(val modSym: ModuleSymbol, modClassInfoType: ClassInfoType) extends TermType {
    def membersIterator: Iterator[Symbol] = modClassInfoType.membersIterator

    override def typeSymbol: Symbol = modSym
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer =
      modClassInfoType.lookup(name)
  }

  final case class MethodInfoType(defDefSymbol: DefDefSymbol, paramTypes: List[List[Type]], resultType: Type) extends Type {
    override def typeSymbol: Symbol = NoSymbol
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = NotFound
  }

  final case class ValInfoType(vaDefSymbol: ValDefSymbol, resultType: Type) extends Type {
    override def typeSymbol: Symbol = NoSymbol
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = resultType.lookup(name)
  }

  final case class TypeAliasInfoType(typeDefSymbol: TypeDefSymbol, rhsType: Type) extends Type {
    override def typeSymbol: Symbol = typeDefSymbol
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = rhsType.lookup(name)
  }

  final case class SymRef(sym: Symbol) extends Type {
    override def typeSymbol: Symbol = sym
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = {
      if (!sym.isComplete)
        IncompleteDependency(sym)
      else
        sym.info.lookup(name)
    }
  }

  final case class AppliedType(tpe: Type, args: mutable.WrappedArray[Type]) extends Type {
    override def typeSymbol: Symbol = tpe.typeSymbol
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = tpe.lookup(name)
  }

  final case class TupleType(types: Array[Type]) extends TypeType {
    override def typeSymbol: Symbol = NoSymbol
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = NotFound
  }

  final case class TypeBounds(lo: Type, hi: Type) extends TypeType {
    override def typeSymbol: Symbol = hi.typeSymbol
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = hi.lookup(name)
  }

  case object InferredTypeMarker extends Type {
    override def typeSymbol: Symbol = NoSymbol
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = NotFound
  }

  case object NoType extends Type {
    override def typeSymbol: Symbol = NoSymbol
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = NotFound
  }

  final class PackageInfoType(pkgSymbol: PackageSymbol) extends Type {
    val members: MutableScope = newScope
    override def typeSymbol: Symbol = pkgSymbol
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = {
      symToLookupAnswer(members.lookup(name))
    }
  }

  final class RootPackageInfoType(pkgSymbol: PackageSymbol) extends Type {
    override def typeSymbol: Symbol = pkgSymbol
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = {
      symToLookupAnswer(pkgSymbol.lookup(name))
    }
  }

  final class EmptyPackageInfoType(pkgSymbol: PackageSymbol) extends Type {
    override def typeSymbol: Symbol = pkgSymbol
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = {
      symToLookupAnswer(pkgSymbol.lookup(name))
    }
  }

  object WildcardType extends Type {
    override def typeSymbol: Symbol =
      throw new UnsupportedOperationException("wildcard type doesn't have a type symbol associated")

    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer =
      throw NoMembersException

    object NoMembersException extends UnsupportedOperationException("wildcard type doesn't have any members")
  }

}
