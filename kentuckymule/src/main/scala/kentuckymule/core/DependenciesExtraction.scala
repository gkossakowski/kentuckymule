package kentuckymule.core

import dotty.tools.dotc.core.Contexts.Context
import Symbols._
import Types._
import com.google.common.collect.{ImmutableMultimap, ImmutableSet}

/**
  * Extracts class dependencies from Symbols entered into Symbol Table.
  * All Symbols have to be typechecked before being passed for dependency analysis.
  */
class DependenciesExtraction(topLevelOnly: Boolean) {
  private val deps = ImmutableMultimap.builder[ClassSymbol, ClassSymbol]
  private val classes = ImmutableSet.builder[ClassSymbol]

  def extractAllDependencies()(implicit context: Context): (ImmutableSet[ClassSymbol], ImmutableMultimap[ClassSymbol, ClassSymbol]) = {
    walkPackage(context.definitions.rootPackage)
    (classes.build(), deps.build())
  }

  private def walkPackage(pkgSymbol: PackageSymbol)(implicit context: Context): Unit = {
    for (child <- pkgSymbol.childrenIterator) child match {
      case childPackage: PackageSymbol => walkPackage(childPackage)
      case _: StubClassSymbol => // ignore stub class symbols
      case clsChild: ClassSymbol =>
        walkSymbol(clsChild, clsChild)
      case modChild: ModuleSymbol =>
        walkSymbol(modChild, modChild.clsSym)
    }
  }

  /**
    *
    * @param symbol
    * @param ownerClass enclosing class or the class itself for top-level classes or corresponding module class symbol
    *                   for top-level modules (objects)
    * @param context
    */
  private def walkSymbol(symbol: Symbol, ownerClass: ClassSymbol)(implicit context: Context): Unit = {
    assert(symbol.isComplete, symbol)
    symbol match {
      case clsSymbol: ClassSymbol =>
        val newOwnerClass = if (topLevelOnly) ownerClass else clsSymbol
        classes.add(clsSymbol)
        val clsType = clsSymbol.info
        for (p <- clsType.parents) walkType(p, newOwnerClass)
        val members = clsType.members.toArray
        var i = 0
        while (i < members.length) {
          walkSymbol(members(i), newOwnerClass)
          i += 1
        }
      case modSymbol: ModuleSymbol =>
        walkSymbol(modSymbol.clsSym, ownerClass)
      case _: InheritedDefDefSymbol | _: InheritedValDefSymbol =>
        // do nothing, dependency is recorded just for the parent class that declares def or val
      case valSymbol: ValDefSymbol =>
        val valInfo = valSymbol.info
        walkType(valInfo.resultType, ownerClass)
      case defSymbol: DefDefSymbol =>
        val defInfo = defSymbol.info
        var remainingParamGroups = defInfo.paramTypes
        while (remainingParamGroups.nonEmpty) {
          var remainingParams = remainingParamGroups.head
          while (remainingParams.nonEmpty) {
            walkType(remainingParams.head, ownerClass)
            remainingParams = remainingParams.tail
          }
          remainingParamGroups = remainingParamGroups.tail
        }
        walkType(defInfo.resultType, ownerClass)
      case typeDefSymbol: TypeDefSymbol =>
      // TODO: TypeDefSymbol support is not implemented yet: typeDefSymbol.info always returns NoType
        //walkType(typeDefSymbol.info, ownerClass)
    }
  }
  private def walkType(tpe: Type, ownerClass: ClassSymbol): Unit = tpe match {
    case SymRef(sym) =>
      val enclClass = enclosingClass(sym)
      if (!enclClass.isInstanceOf[StubClassSymbol]) {
        val targetClass = if (topLevelOnly) topLevelClass(enclClass.asInstanceOf[ClassSymbol]) else enclClass
        deps.put(ownerClass, targetClass)
      }
    case AppliedType(atpe, args) =>
      walkType(atpe, ownerClass)
      for (arg <- args) walkType(arg, ownerClass)
    case InferredTypeMarker =>
      // nothing to record when a type is declared as inferred
    case WildcardType =>
      // TODO: wildcard types should carry their bounds and they should be walked here
    case _ => sys.error(s"Unhandled type $tpe")
  }
  private def topLevelClass(sym: ClassSymbol): ClassSymbol =
    if (sym.owner.isInstanceOf[PackageSymbol])
      sym
    else
      topLevelClass(sym.owner.asInstanceOf[ClassSymbol])
  private def enclosingClass(sym: Symbol): ClassSymbol = sym match {
    case clsSym: ClassSymbol => clsSym
    case typeDefSymbol: TypeDefSymbol => typeDefSymbol.enclosingClass.asInstanceOf[ClassSymbol]
    case typeParameterSymbol: TypeParameterSymbol => typeParameterSymbol.enclosingClass.asInstanceOf[ClassSymbol]
  }
}
