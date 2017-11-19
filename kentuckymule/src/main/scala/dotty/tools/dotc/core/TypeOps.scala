package dotty.tools.dotc.core

import java.util

import kentuckymule.core.Symbols._
import kentuckymule.core.Types._

object TypeOps {

  def deriveMemberOfAppliedType(m: Symbol, appliedType: AppliedType, typeParamsMap: TypeParamMap): Symbol = {
    val typeArgs = appliedType.args
    m match {
      case d@DefDefSymbol(name) =>
        assert(d.isComplete, d)
        new InheritedDefDefSymbol(name, substituteTypeArgs(d.info, typeParamsMap, typeArgs.toArray))
      case v@ValDefSymbol(name) =>
        assert(v.isComplete, v)
        new InheritedValDefSymbol(name, substituteTypeArgs(v.info, typeParamsMap, typeArgs.toArray))
      case other => other
    }
  }

  private def substituteTypeArgs(t: ValInfoType, paramsMap: TypeParamMap, args: Array[Type]): ValInfoType = {
    val resultType1 = substituteTypeArgs(t.resultType, paramsMap, args)
    if (resultType1 ne t.resultType)
      ValInfoType(t.vaDefSymbol, resultType1)
    else
      t
  }

  private def substituteTypeArgs(t: MethodInfoType, paramsMap: TypeParamMap, args: Array[Type]): MethodInfoType = {
    val resultType = t.resultType
    val paramTypes = t.paramTypes
    val resultType1 = substituteTypeArgs(resultType, paramsMap, args)
    assert(paramTypes.size <= 1, "Only one parameter list is supported for methods")
    val paramTypes1 = if (paramTypes.size == 1) {
      var remainingVParamTypes = paramTypes.head
      var modifiedVParam = false
      val paramTypesBuf = new util.ArrayList[Type]()
      while (remainingVParamTypes.nonEmpty) {
        val vParamType = remainingVParamTypes.head
        val vParamType1 = substituteTypeArgs(vParamType, paramsMap, args)
        modifiedVParam = modifiedVParam || (vParamType ne vParamType1)
        paramTypesBuf.add(vParamType1)
        remainingVParamTypes = remainingVParamTypes.tail
      }
      if (modifiedVParam) List(asScalaList(paramTypesBuf)) else paramTypes
    } else Nil
    if ((resultType1 ne resultType) || (paramTypes1 ne paramTypes))
      t
    else
      MethodInfoType(t.defDefSymbol, paramTypes1, resultType1)
  }

  private def substituteTypeArgs(t: Type, paramsMap: TypeParamMap, args: Array[Type]): Type = t match {
    case vt: ValInfoType =>
      substituteTypeArgs(vt, paramsMap, args)
    case mt: MethodInfoType =>
      substituteTypeArgs(mt, paramsMap, args)
    case SymRef(sym: TypeParameterSymbol) =>
      val index = paramsMap.indexOf(sym)
      if (index == -1) t else args(index)
    case _ => t
  }

  class TypeParamMap(typeParams: Scopes.Scope) {
    private val typeParamsArray: Array[Symbol] = typeParams.toArray
    def indexOf(typeParam: TypeParameterSymbol): Int = {
      var index = 0
      while (index < typeParamsArray.length) {
        if (typeParamsArray(index) == typeParam)
          return index
        index += 1
      }
      -1
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
