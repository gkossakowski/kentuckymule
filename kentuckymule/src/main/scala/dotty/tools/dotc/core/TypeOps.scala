package dotty.tools.dotc.core

import java.util

import kentuckymule.core.Enter.{CompletionResult, IncompleteDependency}
import kentuckymule.core.Symbols._
import kentuckymule.core.Types._

object TypeOps {

  /**
    * A class that encapsulates calculation of a type of a member of an applied type.
    * It precomputes the data structures supporting fast type substitution and is
    * suited for repeated calls.
    *
    * An example scenario for repeated calls is deriving all inherited members of a given
    * applied type.
    *
    */
  class AppliedTypeMemberDerivation private(private val substitutionMap: SubstitutionMap) {
    def deriveInheritedMemberOfAppliedType(m: Symbol): Symbol = {
      m match {
        case d@DefDefSymbol(name) =>
          assert(d.isComplete, d)
          new InheritedDefDefSymbol(name, substituteTypeArgs(d.info, substitutionMap))
        case v@ValDefSymbol(name) =>
          assert(v.isComplete, v)
          new InheritedValDefSymbol(name, substituteTypeArgs(v.info, substitutionMap))
        case other => other
      }
    }
  }

  object AppliedTypeMemberDerivation {
    /**
      * Performs a recursive dealiasing of an applied type until it reaches an application
      * of a class type and then returns AppliedTypeMemberDerivation. An example:
      *
      *   type Foo[T] = Bar[T]
      *   type Bar[U] = Baz[U]
      *   class Baz[X]
      *
      * If we pass `Foo[SomeType]` as an input, this method will return a derivation for members
      * of `Baz[SomeType]`.
      *
      * If any of the types in the chain of type aliases is not completed, the method returns
      * IncompleteDependency.
      * @param appliedType
      * @return
      */
    // TODO: generalize the CompletionResult to something like CompletionStep[T] and switch from using
    // Either to the specialized type, right now we can't sue CompletionResult because the positive
    // case requires to return `Type` as a value
    def createForDealiasedType(appliedType: AppliedType): Either[IncompleteDependency, AppliedTypeMemberDerivation] = {
      val subMap = appliedType.typeSymbol match {
        case td: TypeDefSymbol =>
          if (!td.isComplete)
            return Left(IncompleteDependency(td))
          td.info.asInstanceOf[TypeAliasInfoType].rhsType match {
            case at: AppliedType =>
              val nestedDerivation = createForDealiasedType(at)
              nestedDerivation.map { deriv =>
                val rhsSubMap = deriv.substitutionMap
                val lhsSubMap = new SubstitutionMap(td.typeParams, appliedType.args.toArray)
                SubstitutionMap.compose(lhsSubMap, rhsSubMap)
              }
          }
        case cls: ClassSymbol =>
          Right(new SubstitutionMap(cls.typeParams, appliedType.args.toArray))
      }
      subMap.map(new AppliedTypeMemberDerivation(_))
    }
  }

  private def substituteTypeArgs(t: ValInfoType, substitutionMap: SubstitutionMap): ValInfoType = {
    val resultType1 = substituteTypeArgs(t.resultType, substitutionMap)
    if (resultType1 ne t.resultType)
      ValInfoType(t.vaDefSymbol, resultType1)
    else
      t
  }

  private def substituteTypeArgs(t: MethodInfoType, substitutionMap: SubstitutionMap): MethodInfoType = {
    val resultType = t.resultType
    val paramTypes = t.paramTypes
    val resultType1 = substituteTypeArgs(resultType, substitutionMap)
    assert(paramTypes.size <= 1, "Only one parameter list is supported for methods")
    val paramTypes1 = if (paramTypes.size == 1) {
      var remainingVParamTypes = paramTypes.head
      var modifiedVParam = false
      val paramTypesBuf = new util.ArrayList[Type]()
      while (remainingVParamTypes.nonEmpty) {
        val vParamType = remainingVParamTypes.head
        val vParamType1 = substituteTypeArgs(vParamType, substitutionMap)
        modifiedVParam = modifiedVParam || (vParamType ne vParamType1)
        paramTypesBuf.add(vParamType1)
        remainingVParamTypes = remainingVParamTypes.tail
      }
      if (modifiedVParam) List(asScalaList(paramTypesBuf)) else paramTypes
    } else Nil
    if ((resultType1 ne resultType) || (paramTypes1 ne paramTypes))
      MethodInfoType(t.defDefSymbol, paramTypes1, resultType1)
    else
      t
  }

  private def substituteTypeArgs(t: Type, substitutionMap: SubstitutionMap): Type = t match {
    case vt: ValInfoType =>
      substituteTypeArgs(vt, substitutionMap)
    case mt: MethodInfoType =>
      substituteTypeArgs(mt, substitutionMap)
    case SymRef(sym: TypeParameterSymbol) =>
      val substituted = substitutionMap(sym)
      if (substituted == null) t else substituted
    case _ => t
  }

  /**
    * Implements a simple substitution map for positional arguments. Given an array of
    * symbols that represent type parameters and an array of type arguments (type-level
    * values for paremeters), it substitutes type parameter symbol with a corresponding
    * type argument. It uses an index-wise correspondence.
   */
  private class SubstitutionMap(private val typeParams: Scopes.Scope,
                        private val typeArgs: Array[Type]) extends (TypeParameterSymbol => Type) {
    private val typeParamsArray: Array[Symbol] = typeParams.toArray
    // TODO: bring back this assert, right now it's failing because Function1 class has a wrong arity
    // figure out why
//    assert(typeParamsArray.length == typeArgs.length, s"typeParams = ${typeParamsArray.toSeq}, typeArgs = ${typeArgs.toSeq}")
    private def indexOf(typeParam: TypeParameterSymbol): Int = {
      var index = 0
      while (index < typeParamsArray.length) {
        if (typeParamsArray(index) == typeParam)
          return index
        index += 1
      }
      -1
    }
    def apply(typeParam: TypeParameterSymbol): Type = {
      val typeParamIndex = indexOf(typeParam)
      if (typeParamIndex == -1)
        return null
      assert(typeParamIndex < typeArgs.length, s"$typeParamIndex out of range for ${typeArgs: Seq[Type]}")
      typeArgs(typeParamIndex)
    }
  }
  private object SubstitutionMap {
    /*
     * Composes two substitutions. The logic is a bit tricky, so let's illustrate
     * with an example:
     *
     * m1: SubstitutionMap
     * (T1, T2, T3) // type params
     * (SomeType1, SomeType2, SomeType3) // type arguments (type-level values)
     *
     * m2: SubstitutionMap
     * (U1, U2) // type params
     * (T2, SomeType4) // type arguments, T2 is a SymRef
     *
     * result: SubstitutionMap
     * (U1, U2)
     * (SomeType2, SomeType4)
     *
     * The composition is computed in O(n2*n1) where n2 is the number of type args
     * for m2 and n2 is number of type params for m1. The actual computation is
     * two iterations over arrays so it's pretty fast especially for short seqeunces.
     * It's not clear to me how the composition can be implemented faster than in
     * quadratic time.
     */
    def compose(m1: SubstitutionMap, m2: SubstitutionMap): SubstitutionMap = {
      var i = 0
      val composedArgs = new Array[Type](m2.typeArgs.length)
      while (i < m2.typeArgs.length) {
        val typeArg = m2.typeArgs(i)
        composedArgs(i) = typeArg match {
          case SymRef(tps: TypeParameterSymbol) =>
            m1(tps)
          case _ => typeArg
        }
        i += 1
      }
      new SubstitutionMap(m2.typeParams, composedArgs)
    }
  }

  private def asScalaList[T](javaList: util.ArrayList[T]): List[T] = {
    var i = javaList.size() - 1
    var res: List[T] = Nil
    while (i >= 0) {
      res = javaList.get(i) :: res
      i -= 1
    }
    res
  }

}
