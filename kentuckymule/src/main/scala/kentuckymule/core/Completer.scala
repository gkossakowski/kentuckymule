package kentuckymule.core

import java.util

import dotty.tools.dotc.ast.untpd.{DefDef, Template, TypeDef, ValDef}
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.TypeOps.AppliedTypeMemberDerivation
import kentuckymule.core.Enter.LookupScope
import kentuckymule.core.Symbols._
import kentuckymule.core.Types._
import CollectionUtils._
import ResolveType.resolveTypeTree

abstract class Completer(val sym: Symbol) {
  def complete()(implicit context: Context): CompletionResult
  def isCompleted: Boolean
  override def toString: String = s"${this.getClass.getName}: $sym"
}

class ModuleCompleter(modSym: ModuleSymbol) extends Completer(modSym) {
  private var cachedInfo: ModuleInfoType = _
  override def complete()(implicit context: Context): CompletionResult = {
    if (cachedInfo != null)
      CompletedType(cachedInfo)
    else if (!modSym.clsSym.isComplete)
      IncompleteDependency(modSym.clsSym)
    else {
      cachedInfo = new ModuleInfoType(modSym, modSym.clsSym.info)
      CompletedType(cachedInfo)
    }
  }
  override def isCompleted: Boolean = cachedInfo != null
}

class TemplateMemberListCompleter(val clsSym: ClassSymbol, tmpl: Template, val lookupScope: LookupScope) extends Completer(clsSym) {
  private var cachedInfo: ClassInfoType = _
  def complete()(implicit context: Context): CompletionResult = {
    val resolvedParents = new util.ArrayList[Type]()
    var remainingParents = tmpl.parents
    while (remainingParents.nonEmpty) {
      val parent = remainingParents.head
      val resolved = resolveTypeTree(parent, lookupScope)
      resolved match {
        case CompletedType(tpe) => resolvedParents.add(tpe)
        case _: IncompleteDependency | NotFound => return resolved
      }
      remainingParents = remainingParents.tail
    }
    val info = new ClassInfoType(clsSym, asScalaList(resolvedParents))
    var i = 0
    while (i < resolvedParents.size()) {
      val parentType = resolvedParents.get(i)
      val parentClassSym = parentType.typeSymbol match {
        case clsSym: ClassSymbol =>
          clsSym
        // TODO: figure out where dealiasing should happen and whether we should preserve any info
        // about the use of a type alias; one possibility is to move dealiasing above to the `resolvedParents`
        // computation
        case typeDefSym: TypeDefSymbol =>
          if (!typeDefSym.isComplete)
            return IncompleteDependency(typeDefSym)
          val aliasedSymbol = typeDefSym.info.asInstanceOf[TypeAliasInfoType].rhsType.typeSymbol
          aliasedSymbol match {
            case clsSym: ClassSymbol => clsSym
            case _ => sys.error(s"The ${parentType} doesn't dealias to a class type. The dealiased type is ${aliasedSymbol}")
          }
      }
      val parentInfo = if (parentClassSym.info != null) parentClassSym.info else
        return IncompleteDependency(parentClassSym)
      parentType match {
        case at: AppliedType =>
          val appliedTypeMemberDerivation = AppliedTypeMemberDerivation.createForDealiasedType(at) match {
            case Left(incompleteDependency) => return incompleteDependency
            case Right(derivation) => derivation
          }
          for (m <- parentInfo.members.iterator) {
            if (!m.isComplete)
              return IncompleteDependency(m)
            val derivedInheritedMember = appliedTypeMemberDerivation.deriveInheritedMemberOfAppliedType(m)
            info.members.enter(derivedInheritedMember)
          }
        case other =>
          info.members.enterAll(parentInfo.members)
      }
      i += 1
    }
    info.members.enterAll(clsSym.decls)
    cachedInfo = info
    CompletedType(info)
  }
  def isCompleted: Boolean = cachedInfo != null
}

class PackageCompleter(pkgSym: PackageSymbol) extends Completer(pkgSym) {
  private var cachedInfo: PackageInfoType = _
  override def complete()(implicit context: Context): CompletionResult = {
    if (cachedInfo != null)
      CompletedType(cachedInfo)
    else if ((pkgSym.packageObject != NoSymbol) && (!pkgSym.packageObject.isComplete))
      IncompleteDependency(pkgSym.packageObject)
    else {
      val info = new PackageInfoType(pkgSym)
      // TODO: check for conflicting definitions in the package and package object, e.g.:
      // package foo { class Abc }; package object foo { class Abc }
      if (pkgSym.packageObject != NoSymbol)
        info.members.enterAll(pkgSym.packageObject.asInstanceOf[ModuleSymbol].info.members)
      info.members.enterAll(pkgSym.decls)

      cachedInfo = info
      CompletedType(cachedInfo)
    }
  }
  override def isCompleted: Boolean = cachedInfo != null
}

class DefDefCompleter(sym: DefDefSymbol, defDef: DefDef, val lookupScope: LookupScope) extends Completer(sym) {
  private var cachedInfo: MethodInfoType = _
  def complete()(implicit context: Context): CompletionResult = {
    val paramTypes = {
      // TODO: write interruptible map2, def interMap2[T](xss: List[List[T])(f: T => CompletionResult): List[List[Type]]
      defDef.vparamss map {  vParams =>
        vParams map { vparam =>
          val resolvedType = resolveTypeTree(vparam.tpt, lookupScope)
          resolvedType match {
            case CompletedType(tpe) => tpe
            case res: IncompleteDependency => return res
            case NotFound => sys.error(s"Couldn't resolve ${vparam.tpt}")
          }
        }
      }
    }
    val resultType: Type = if (defDef.tpt.isEmpty) InferredTypeMarker else {
      val resolvedType = resolveTypeTree(defDef.tpt, lookupScope)
      resolvedType match {
        case CompletedType(tpe) => tpe
        case res: IncompleteDependency => return res
        case NotFound => sys.error(s"Couldn't resolve ${defDef.tpt}")
      }
    }
    val info = MethodInfoType(sym, paramTypes, resultType)
    cachedInfo = info
    CompletedType(info)
  }
  def isCompleted: Boolean = cachedInfo != null
}

class ValDefCompleter(sym: ValDefSymbol, valDef: ValDef, val lookupScope: LookupScope) extends Completer(sym) {
  private var cachedInfo: ValInfoType = _
  def complete()(implicit context: Context): CompletionResult = try {
    val resultType: Type = if (valDef.tpt.isEmpty) InferredTypeMarker else {
      val resolvedType = resolveTypeTree(valDef.tpt, lookupScope)
      resolvedType match {
        case CompletedType(tpe) => tpe
        case res: IncompleteDependency => return res
        case NotFound => sys.error(s"Couldn't resolve ${valDef.tpt}")
      }
    }
    val info = ValInfoType(sym, resultType)
    cachedInfo = info
    CompletedType(info)
  } catch {
    case ex: Exception =>
      throw new RuntimeException(s"Error while completing $valDef", ex)
  }
  def isCompleted: Boolean = cachedInfo != null
}

abstract class TypeDefCompleter(sym: TypeDefSymbol) extends Completer(sym)

// TODO: remove this once type members with bounds are implemented
class StubTypeDefCompleter(sym: TypeDefSymbol) extends TypeDefCompleter(sym) {
  private var cachedInfo: Type = _
  override def isCompleted: Boolean = cachedInfo != null
  override def complete()(implicit context: Context): CompletionResult = {
    cachedInfo = NoType
    CompletedType(cachedInfo)
  }
}

class TypeAliasCompleter(sym: TypeDefSymbol, typeDef: TypeDef, val lookupScope: LookupScope) extends TypeDefCompleter(sym) {
  private var cachedInfo: TypeAliasInfoType = _
  def complete()(implicit context: Context): CompletionResult = try {
    val rhsType: Type = {
      val resolvedType = resolveTypeTree(typeDef.rhs, lookupScope)
      resolvedType match {
        case CompletedType(tpe) => tpe
        case res: IncompleteDependency => return res
        case NotFound => sys.error(s"Couldn't resolve ${typeDef.rhs}")
      }
    }
    val info = TypeAliasInfoType(sym, rhsType)
    cachedInfo = info
    CompletedType(info)
  } catch {
    case ex: Exception =>
      throw new RuntimeException(s"Error while completing $typeDef", ex)
  }
  def isCompleted: Boolean = cachedInfo != null
}
