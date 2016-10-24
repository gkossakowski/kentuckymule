package dotty.tools.dotc
package core

import java.util

import dotty.tools.dotc.core.Contexts.Context
import Symbols._
import ast.Trees._
import Names.Name
import Types._

/**
  * Creates symbols for declarations and enters them into a symbol table.
  */
class Enter {

  import ast.untpd._
  import Enter._

  val templateCompleters: util.Queue[TemplateMemberListCompleter] = new util.ArrayDeque[TemplateMemberListCompleter]()

  val defDefCompleters: util.Queue[DefDefCompleter] = new util.ArrayDeque[DefDefCompleter]()

  class LookupCompilationUnitScope(imports: List[Import], pkgLookupScope: PackageLookupScope) {

  }
  class LookupClassTemplateScope(classSym: Symbol, imports: ImportsLookupScope, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val foundSym = classSym.lookup(name)
      if (foundSym != NoSymbol)
        LookedupSymbol(foundSym)
      else {
        val ans = imports.lookup(name)
        ans match {
          case _: LookedupSymbol | _: IncompleteDependency => ans
          case _ => parentScope.lookup(name)
        }
      }
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new LookupClassTemplateScope(classSym, imports, parentScope)
  }

  class LookupModuleTemplateScope(moduleSym: Symbol, imports: ImportsLookupScope, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val foundSym = moduleSym.lookup(name)
      if (foundSym != NoSymbol)
        LookedupSymbol(foundSym)
      else {
        val ans = imports.lookup(name)
        ans match {
          case _: LookedupSymbol | _: IncompleteDependency => ans
          case _ => parentScope.lookup(name)
        }
      }
    }
    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new LookupModuleTemplateScope(moduleSym, imports, parentScope)
  }

  object RootPackageLookupScope extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val sym = context.definitions.rootPackage.lookup(name)
      if (sym == NoSymbol)
        NotFound
      else
        LookedupSymbol(sym)
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      sys.error("unsupported operation")
  }

  def enterCompilationUnit(unit: CompilationUnit)(implicit context: Context): Unit = {
    val importsInCompilationUnit = new ImportsCollector(RootPackageLookupScope)
    enterTree(unit.untpdTree, context.definitions.rootPackage, importsInCompilationUnit, RootPackageLookupScope)
  }

  class PackageLookupScope(val pkgSym: Symbol, val parent: LookupScope, val imports: ImportsLookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val foundSym = pkgSym.lookup(name)
      if (foundSym != NoSymbol)
        LookedupSymbol(foundSym)
      else {
        val ans = imports.lookup(name)
        ans match {
          case _: LookedupSymbol | _: IncompleteDependency => ans
          case _ => parent.lookup(name)
        }
      }
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new PackageLookupScope(pkgSym, parent, imports)
  }

  private def enterTree(tree: Tree, owner: Symbol, imports: ImportsCollector, parentScope: LookupScope)(implicit context: Context): Unit = tree match {
    case PackageDef(ident, stats) =>
      val pkgSym = expandQualifiedPackageDeclaration(ident, owner)
      val pkgImports = new ImportsCollector(parentScope)
      val pkgLookupScope = new PackageLookupScope(pkgSym, parentScope, imports.snapshot())
      for (stat <- stats) enterTree(stat, pkgSym, pkgImports, pkgLookupScope)
    case imp: Import =>
      imports.append(imp)
    case ModuleDef(name, tmpl) =>
      val modClsSym = new ClassSymbol(name)
      val modSym = new ModuleSymbol(name, modClsSym)
      owner.addChild(modSym)
      val moduleLookupScope = new LookupModuleTemplateScope(modSym, imports.snapshot(), parentScope)
      val moduleImports = new ImportsCollector(parentScope)
      enterTree(tmpl, modSym, moduleImports, moduleLookupScope)
    // class or trait
    case t@TypeDef(name, tmpl) if t.isClassDef =>
      val classSym = new ClassSymbol(name)
      owner.addChild(classSym)
      val classLookupScope = new LookupClassTemplateScope(classSym, imports.snapshot(), parentScope)
      val classImports = new ImportsCollector(parentScope)
      enterTree(tmpl, classSym, classImports, classLookupScope)
    case t: Template =>
      // TODO: figure out whether it makes sense to pass modSym.clsSym as an owner in recursive call for ModuleDef case
      val ownerClsSym = owner match {
        case clsSym: ClassSymbol => clsSym
        case modSym: ModuleSymbol => modSym.clsSym
      }
      val completer = new TemplateMemberListCompleter(ownerClsSym, t, parentScope)
      templateCompleters.add(completer)
      ownerClsSym.completer = completer
      for (stat <- t.body) enterTree(stat, ownerClsSym, imports, parentScope)
    // type alias or type member
    case TypeDef(name, _) =>
      val typeSymbol = new TypeDefSymbol(name)
      owner.addChild(typeSymbol)
    case ValDef(name, _, _) =>
      val valSym = new ValDefSymbol(name)
      owner.addChild(valSym)
    case t: DefDef =>
      val valSym = new DefDefSymbol(t.name)
      // TODO: we should create a new lookup scope to take into account accumulated imports
      val completer = new DefDefCompleter(valSym, t, parentScope)
      valSym.completer = completer
      owner.addChild(valSym)
    case _ =>
  }

  private def expandQualifiedPackageDeclaration(pkgDecl: RefTree, owner: Symbol)(implicit ctx: Context): Symbol =
    pkgDecl match {
    case Ident(name: Name) =>
      val lookedUp = owner.lookup(name)
      if (lookedUp != NoSymbol)
        lookedUp
      else {
        val pkgSym = new PackageSymbol(name)
        owner.addChild(pkgSym)
        pkgSym
      }
    case Select(qualifier: RefTree, name: Name) =>
      val qualPkg = expandQualifiedPackageDeclaration(qualifier, owner)
      val lookedUp = owner.lookup(name)
      if (lookedUp != NoSymbol)
        lookedUp
      else {
        val pkgSym = new PackageSymbol(name)
        qualPkg.addChild(pkgSym)
        pkgSym
      }
  }

  def processJobQueue(memberListOnly: Boolean)(implicit ctx: Context): Int = {
    var steps = 0
    while (!templateCompleters.isEmpty) {
      steps += 1
      val completer = templateCompleters.remove()
      if (!completer.isCompleted) {
        val res = completer.complete()
        res match {
          case CompletedType(tpe: ClassInfoType) =>
            val classSym = completer.clsSym
            classSym.info = tpe
            if (!memberListOnly)
              scheduleMembersCompletion(classSym)
          case IncompleteDependency(sym: ClassSymbol) =>
            templateCompleters.add(sym.completer)
            templateCompleters.add(completer)
        }
      }
    }
    if (!memberListOnly) {
      while (!defDefCompleters.isEmpty) {
        steps += 1
        val completer = defDefCompleters.remove()
        if (!completer.isCompleted) {
          val res = completer.complete()
          res match {
            case CompletedType(tpe: MethodInfoType) =>
              val defDefSym = completer.sym.asInstanceOf[DefDefSymbol]
              defDefSym.info = tpe
            case IncompleteDependency(sym: ClassSymbol) =>
              sys.error("Should happen")
          }
        }
      }
    }
    steps
  }

  private def scheduleMembersCompletion(sym: ClassSymbol): Unit = {
    var remainingDecls = sym.decls.toList
    while (remainingDecls.nonEmpty) {
      val decl = remainingDecls.head
      decl match {
        case defSym: DefDefSymbol => defDefCompleters.add(defSym.completer)
      }
      remainingDecls = remainingDecls.tail
    }
  }

}

object Enter {
  import ast.untpd._

  sealed trait LookupAnswer
  case class LookedupSymbol(sym: Symbol) extends LookupAnswer
  case object NotFound extends LookupAnswer

  sealed trait CompletionResult
  case class CompletedType(tpe: Type) extends CompletionResult

  case class IncompleteDependency(sym: Symbol) extends LookupAnswer with CompletionResult

  abstract class LookupScope {
    def lookup(name: Name)(implicit context: Context): LookupAnswer
    def replaceImports(imports: ImportsLookupScope): LookupScope
  }

  private class ImportCompleter(val importNode: Import) {
    private var termSym0: Symbol = _
    private var typeSym0: Symbol = _
    private var isComplete: Boolean = false
    def complete(parentLookupScope: LookupScope)(implicit context: Context): LookupAnswer = {
      isComplete = true
      val Import(expr, List(Ident(name))) = importNode
      val exprAns = resolveSelectors(expr, parentLookupScope)
      exprAns match {
        case LookedupSymbol(exprSym) =>
          if (exprSym.isComplete) {
            termSym0 = exprSym.lookup(name)
            typeSym0 = exprSym.lookup(name.toTypeName)
            if (termSym0 != NoSymbol)
              LookedupSymbol(termSym0)
            else if (typeSym0 != NoSymbol)
              LookedupSymbol(typeSym0)
            else
              NotFound
          }
          else IncompleteDependency(exprSym)
        case _ => exprAns
      }
    }
    def termSymbol: Symbol = {
      if (!isComplete)
        sys.error("this import hasn't been completed " + importNode)
      if (termSym0 != null)
        termSym0
      else
        NoSymbol
    }
    def typeSymbol: Symbol = {
      if (!isComplete)
        sys.error("this import hasn't been completed " + importNode)
      if (typeSym0 != null)
        typeSym0
      else
        NoSymbol
    }
    def matches(name: Name): Symbol = {
      if (name.isTermName)
        termSymbol
      else
        typeSymbol
    }
  }

  class ImportsCollector(parentLookupScope: LookupScope) {
    private val importCompleters: util.ArrayList[ImportCompleter] = new util.ArrayList[ImportCompleter]()
    def append(imp: Import): Unit = {
      importCompleters.add(new ImportCompleter(imp))
    }
    def snapshot(): ImportsLookupScope = {
      new ImportsLookupScope(importCompleters, parentLookupScope)()
    }
    def isEmpty: Boolean = importCompleters.isEmpty
  }

  class ImportsLookupScope(importCompleters: util.ArrayList[ImportCompleter], parentLookupScope: LookupScope)
                          (lastCompleterIndex: Int = importCompleters.size - 1) {
    private var allComplete: Boolean = false


    private def resolveImports()(implicit context: Context): Symbol = {
      var i: Int = 0
      while (i <= lastCompleterIndex) {
        val importsCompletedSoFar = new ImportsLookupScope(importCompleters, parentLookupScope)(lastCompleterIndex = i-1)
        importsCompletedSoFar.allComplete = true
        val parentLookupWithImports = parentLookupScope.replaceImports(importsCompletedSoFar)
        val impCompleter = importCompleters.get(i)
        impCompleter.complete(parentLookupWithImports) match {
          case _: LookedupSymbol =>
          case IncompleteDependency(sym) => return sym
          case NotFound => sys.error("couldn't resolve import")
        }
        i += 1
      }
      allComplete = true
      null
    }
    def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (!allComplete) {
        val sym = resolveImports()
        if (sym != null)
          return IncompleteDependency(sym)
      }
      var i = lastCompleterIndex
      while (i >= 0) {
        val completedImport = importCompleters.get(i)
        val sym = completedImport.matches(name)
        if (sym != NoSymbol)
          return LookedupSymbol(completedImport.termSymbol)
        i -= 1
      }
      NotFound
    }
  }

  class TemplateMemberListCompleter(val clsSym: ClassSymbol, tmpl: Template, val lookupScope: LookupScope) {
    private var cachedInfo: ClassInfoType = _
    def complete()(implicit context: Context): CompletionResult = {
      val resolvedParents = new util.ArrayList[ClassSymbol]()
      var remainingParents = tmpl.parents
      while (remainingParents.nonEmpty) {
        val parent = remainingParents.head
        val resolved = resolveSelectors(parent, lookupScope)
        resolved match {
          case LookedupSymbol(rsym: ClassSymbol) => resolvedParents.add(rsym)
          case res: IncompleteDependency => return res
          case NotFound => sys.error("OMG, we don't have error reporting yet")
        }
        remainingParents = remainingParents.tail
      }
      val info = new ClassInfoType(clsSym)
      var i = 0
      while (i < resolvedParents.size()) {
        val parent = resolvedParents.get(i)
        val parentInfo = if (parent.info != null) parent.info else
          return IncompleteDependency(parent)
        info.members.enterAll(parentInfo.members)
        i += 1
      }
      info.members.enterAll(clsSym.decls)
      cachedInfo = info
      CompletedType(info)
    }
    def isCompleted: Boolean = cachedInfo != null
  }

  class DefDefCompleter(val sym: DefDefSymbol, defDef: DefDef, val lookupScope: LookupScope) {
    private var cachedInfo: MethodInfoType = _
    def complete()(implicit context: Context): CompletionResult = {
      // TODO support multiple parameter lists
      assert(defDef.vparamss.size <= 1)
      val paramTypes = if (defDef.vparamss.nonEmpty) {
        var remainingVparams = defDef.vparamss.head
        val resolvedParamTypes = new util.ArrayList[Type]()
        while (remainingVparams.nonEmpty) {
          val vparam = remainingVparams.head
          val resolvedTypeSym = resolveSelectors(vparam.tpt, lookupScope)
          resolvedTypeSym match {
            case LookedupSymbol(rsym) => resolvedParamTypes.add(SymRef(rsym))
            case res: IncompleteDependency => return res
            case NotFound => sys.error("OMG, we don't have error reporting yet")
          }
          remainingVparams = remainingVparams.tail
        }
        List(asScalaList(resolvedParamTypes))
      } else Nil
      val resultTypeSym = resolveSelectors(defDef.tpt, lookupScope)
      val resultType: Type = resultTypeSym match {
        case LookedupSymbol(rsym) => SymRef(rsym)
        case res: IncompleteDependency => return res
        case NotFound => sys.error("OMG, we don't have error reporting yet")
      }
      val info = MethodInfoType(sym, paramTypes, resultType)
      cachedInfo = info
      CompletedType(info)
    }
    def isCompleted: Boolean = cachedInfo != null
  }

  private def resolveSelectors(t: Tree, parentLookupScope: LookupScope)(implicit context: Context): LookupAnswer =
    t match {
      case Ident(identName) => parentLookupScope.lookup(identName)
      case Select(qual, selName) =>
        val ans = resolveSelectors(qual, parentLookupScope)
        ans match {
          case LookedupSymbol(qualSym) =>
            if (qualSym.isComplete)
              LookedupSymbol(qualSym.lookup(selName))
            else
              IncompleteDependency(qualSym)
          case _ => ans
        }
    }

  private def asScalaList[T](javaList: util.ArrayList[T]): List[T] = {
    var i = javaList.size() - 1
    var res: List[T] = Nil
    while (i >= 0) {
      res = javaList.get(i) :: res
      i += 1
    }
    res
  }
}
