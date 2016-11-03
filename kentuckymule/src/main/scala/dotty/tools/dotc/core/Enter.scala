package dotty.tools.dotc
package core

import java.util

import dotty.tools.dotc.core.Contexts.Context
import Symbols._
import ast.Trees._
import Names.Name
import Types._
import StdNames.nme

/**
  * Creates symbols for declarations and enters them into a symbol table.
  */
class Enter {

  import ast.untpd._
  import Enter._

  val completers: util.Queue[Completer] = new util.ArrayDeque[Completer]()

  class LookupClassTemplateScope(classSym: ClassSymbol, imports: ImportsLookupScope, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val classFoundSym = classSym.lookup(name)
      if (classFoundSym != NoSymbol)
        return LookedupSymbol(classFoundSym)
      if (name.isTypeName) {
        val tParamFoundSym = classSym.typeParams.lookup(name)
        if (tParamFoundSym != NoSymbol)
          return LookedupSymbol(tParamFoundSym)
      }
      val impFoundSym = imports.lookup(name)
      impFoundSym match {
        case _: LookedupSymbol | _: IncompleteDependency => impFoundSym
        case _ => parentScope.lookup(name)
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

  class LookupCompilationUnitScope(imports: ImportsLookupScope, pkgLookupScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val impFoundSym = imports.lookup(name)
      impFoundSym match {
        case _: LookedupSymbol | _: IncompleteDependency => impFoundSym
        case _ => pkgLookupScope.lookup(name)
      }
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new LookupCompilationUnitScope(imports, pkgLookupScope)
  }

  def enterCompilationUnit(unit: CompilationUnit)(implicit context: Context): Unit = {
    val importsInCompilationUnit = new ImportsCollector(RootPackageLookupScope)
    val compilationUnitScope = new LookupCompilationUnitScope(importsInCompilationUnit.snapshot(), RootPackageLookupScope)
    enterTree(unit.untpdTree, context.definitions.rootPackage, new LookupScopeContext(importsInCompilationUnit, compilationUnitScope))
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

  private class LookupScopeContext(imports: ImportsCollector, val parentScope: LookupScope) {
    private var cachedSimpleMemberLookupScope: LookupScope = parentScope
    def addImport(imp: Import): Unit = {
      cachedSimpleMemberLookupScope = null
      imports.append(imp)
    }
    def pushPackageLookupScope(pkgSym: PackageSymbol): LookupScopeContext = {
      val pkgLookupScope = new PackageLookupScope(pkgSym, parentScope, imports.snapshot())
      val pkgImports = new ImportsCollector(parentScope)
      new LookupScopeContext(pkgImports, pkgLookupScope)
    }
    def pushModuleLookupScope(modSym: ModuleSymbol): LookupScopeContext = {
      val moduleLookupScope = new LookupModuleTemplateScope(modSym, imports.snapshot(), parentScope)
      val moduleImports = new ImportsCollector(parentScope)
      new LookupScopeContext(moduleImports, moduleLookupScope)
    }
    def pushClassLookupScope(classSym: ClassSymbol): LookupScopeContext = {
      val classLookupScope = new LookupClassTemplateScope(classSym, imports.snapshot(), parentScope)
      val classImports = new ImportsCollector(parentScope)
      new LookupScopeContext(classImports, classLookupScope)
    }

    def newSimpleMemberLookupScope: LookupScope = {
      if (cachedSimpleMemberLookupScope != null)
        cachedSimpleMemberLookupScope
      else {
        cachedSimpleMemberLookupScope = parentScope.replaceImports(imports.snapshot())
        cachedSimpleMemberLookupScope
      }
    }
  }

  private def enterTree(tree: Tree, owner: Symbol, parentLookupScopeContext: LookupScopeContext)(implicit context: Context): Unit = tree match {
    case PackageDef(ident, stats) =>
      val pkgSym = expandQualifiedPackageDeclaration(ident, owner)
      val lookupScopeContext = parentLookupScopeContext.pushPackageLookupScope(pkgSym)
      for (stat <- stats) enterTree(stat, pkgSym, lookupScopeContext)
    case imp: Import =>
      parentLookupScopeContext.addImport(imp)
    case ModuleDef(name, tmpl) =>
      val modClsSym = new ClassSymbol(name)
      val modSym = new ModuleSymbol(name, modClsSym)
      owner.addChild(modSym)
      val lookupScopeContext = parentLookupScopeContext.pushModuleLookupScope(modSym)
      val completer = new TemplateMemberListCompleter(modClsSym, tmpl, lookupScopeContext.parentScope)
      completers.add(completer)
      modClsSym.completer = completer
      for (stat <- tmpl.body) enterTree(stat, modClsSym, lookupScopeContext)
    // class or trait
    case t@TypeDef(name, tmpl: Template) if t.isClassDef =>
      val classSym = new ClassSymbol(name)
      // t.tParams is empty for classes, the type parameters are accessible thorugh its primary constructor
      var remainingTparams = tmpl.constr.tparams
      var tParamIndex = 0
      while (remainingTparams.nonEmpty) {
        val tParam = remainingTparams.head
        // TODO: setup completers for TypeDef (resolving bounds, etc.)
        classSym.typeParams.enter(TypeParameterSymbol(tParam.name, tParamIndex))
        remainingTparams = remainingTparams.tail
        tParamIndex += 1
      }
      owner.addChild(classSym)
      val lookupScopeContext = parentLookupScopeContext.pushClassLookupScope(classSym)
      val completer = new TemplateMemberListCompleter(classSym, tmpl, lookupScopeContext.parentScope)
      completers.add(completer)
      classSym.completer = completer
      for (stat <- tmpl.body) enterTree(stat, classSym, lookupScopeContext)
    // type alias or type member
    case TypeDef(name, _) =>
      val typeSymbol = new TypeDefSymbol(name)
      owner.addChild(typeSymbol)
    case t@ValDef(name, _, _) =>
      val valSym = new ValDefSymbol(name)
      val completer = new ValDefCompleter(valSym, t, parentLookupScopeContext.newSimpleMemberLookupScope)
      valSym.completer = completer
      owner.addChild(valSym)
    case t: DefDef =>
      val defSym = new DefDefSymbol(t.name)
      val completer = new DefDefCompleter(defSym, t, parentLookupScopeContext.newSimpleMemberLookupScope)
      defSym.completer = completer
      owner.addChild(defSym)
    case _ =>
  }

  private def expandQualifiedPackageDeclaration(pkgDecl: RefTree, owner: Symbol)(implicit ctx: Context): PackageSymbol =
    pkgDecl match {
    case Ident(name: Name) =>
      val lookedUp = owner.lookup(name)
      lookedUp match {
        case pkgSym: PackageSymbol => pkgSym
        case _ =>
          val pkgSym = new PackageSymbol(name)
          owner.addChild(pkgSym)
          pkgSym
      }
    case Select(qualifier: RefTree, name: Name) =>
      val qualPkg = expandQualifiedPackageDeclaration(qualifier, owner)
      val lookedUp = qualPkg.lookup(name)
      lookedUp match {
        case pkgSym: PackageSymbol => pkgSym
        case _ =>
          val pkgSym = new PackageSymbol(name)
          qualPkg.addChild(pkgSym)
          pkgSym
      }
  }

  def processJobQueue(memberListOnly: Boolean)(implicit ctx: Context): Int = {
    var steps = 0
    while (!completers.isEmpty) {
      steps += 1
      val completer = completers.remove()
      if (!completer.isCompleted) {
        val res = completer.complete()
        res match {
          case CompletedType(tpe: ClassInfoType) =>
            val classSym = tpe.clsSym
            classSym.info = tpe
            if (!memberListOnly)
              scheduleMembersCompletion(classSym)
          case IncompleteDependency(sym: ClassSymbol) =>
            completers.add(sym.completer)
            completers.add(completer)
          case IncompleteDependency(sym: ValDefSymbol) =>
            completers.add(sym.completer)
            completers.add(completer)
          case IncompleteDependency(sym: DefDefSymbol) =>
            completers.add(sym.completer)
            completers.add(completer)
          case CompletedType(tpe: MethodInfoType) =>
            val defDefSym = completer.sym.asInstanceOf[DefDefSymbol]
            defDefSym.info = tpe
          case CompletedType(tpe: ValInfoType) =>
            val valDefSym = completer.sym.asInstanceOf[ValDefSymbol]
            valDefSym.info = tpe
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
        case defSym: DefDefSymbol => completers.add(defSym.completer)
        case valSym: ValDefSymbol => completers.add(valSym.completer)
        case _: ClassSymbol | _: ModuleSymbol =>
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
    private class ImportSelectorResolved(val termSym: Symbol, val typeSym: Symbol, val isWildcard: Boolean)
    private var exprSym0: Symbol = _
    private var resolvedSelectors: util.ArrayList[ImportSelectorResolved] = _
    private var isComplete: Boolean = false
    def complete(parentLookupScope: LookupScope)(implicit context: Context): LookupAnswer = {
      isComplete = true
      val Import(expr, selectors) = importNode
      val exprAns = resolveSelectors(expr, parentLookupScope)
      exprAns match {
        case LookedupSymbol(exprSym) =>
          this.exprSym0 = exprSym
          if (exprSym.isComplete) {
            var remainingSelectors = selectors
            val resolvedSelectors: util.ArrayList[ImportSelectorResolved] = new util.ArrayList[ImportSelectorResolved]
            while (remainingSelectors.nonEmpty) {
              val Ident(name) = remainingSelectors.head
              remainingSelectors = remainingSelectors.tail
              if (name != nme.WILDCARD) {
                val termSym = exprSym.lookup(name)
                val typeSym = exprSym.lookup(name.toTypeName)
                if (termSym == NoSymbol && typeSym == NoSymbol)
                  return NotFound
                resolvedSelectors.add(new ImportSelectorResolved(termSym, typeSym, isWildcard = false))
              } else {
                resolvedSelectors.add(new ImportSelectorResolved(null, null, isWildcard = true))
              }
            }
            this.resolvedSelectors = resolvedSelectors
            LookedupSymbol(exprSym)
          } else IncompleteDependency(exprSym)
        case _ => exprAns
      }
    }
    def matches(name: Name)(implicit context: Context): Symbol = {
      assert(isComplete, s"the import node hasn't been completed: $importNode")
      var i = 0
      while (i < resolvedSelectors.size) {
        val selector = resolvedSelectors.get(i)
        val sym = if (selector.isWildcard) {
          exprSym0.lookup(name)
        } else {
          val termSym = selector.termSym
          val typeSym = selector.typeSym
          if (name.isTermName && termSym != null && termSym.name == name)
            termSym
          else if (typeSym != null && typeSym.name == name)
            typeSym
          else NoSymbol
        }
        // TODO: to support hiding with wildcard renames as in `import foo.bar.{abc => _}`, we would
        // need to continue scanning selectors and check for this shape of a rename
        if (sym != NoSymbol)
          return sym
        i += 1
      }
      NoSymbol
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
          case NotFound => sys.error(s"couldn't resolve import ${impCompleter.importNode}")
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
          return LookedupSymbol(sym)
        i -= 1
      }
      NotFound
    }
  }

  abstract class Completer(val sym: Symbol) {
    def complete()(implicit context: Context): CompletionResult
    def isCompleted: Boolean
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
          case res: IncompleteDependency => return res
        }
        remainingParents = remainingParents.tail
      }
      val info = new ClassInfoType(clsSym)
      var i = 0
      while (i < resolvedParents.size()) {
        val parentType = resolvedParents.get(i)
        val parentSym = parentType.typeSymbol.asInstanceOf[ClassSymbol]
        val parentInfo = if (parentSym.info != null) parentSym.info else
          return IncompleteDependency(parentSym)
        parentType match {
          case at: AppliedType =>
            import TypeOps.{TypeParamMap, deriveMemberOfAppliedType}
            val typeParams = at.typeSymbol.asInstanceOf[ClassSymbol].typeParams
            val typeParamMap = new TypeParamMap(typeParams)
            for (m <- parentInfo.members.iterator) {
              if (!m.isComplete)
                return IncompleteDependency(m)
              val derivedInheritedMember = deriveMemberOfAppliedType(m, at, typeParamMap)
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

  class DefDefCompleter(sym: DefDefSymbol, defDef: DefDef, val lookupScope: LookupScope) extends Completer(sym) {
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
            case NotFound => sys.error(s"Couldn't resolve ${vparam.tpt}")
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

  class ValDefCompleter(sym: ValDefSymbol, valDef: ValDef, val lookupScope: LookupScope) extends Completer(sym) {
    private var cachedInfo: ValInfoType = _
    def complete()(implicit context: Context): CompletionResult = {
      val resultTypeSym = resolveSelectors(valDef.tpt, lookupScope)
      val resultType: Type = resultTypeSym match {
        case LookedupSymbol(rsym) => SymRef(rsym)
        case res: IncompleteDependency => return res
        case NotFound => sys.error("OMG, we don't have error reporting yet")
      }
      val info = ValInfoType(sym, resultType)
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
      case _ => sys.error(s"Unhandled tree $t at ${t.pos}")
    }

  private def resolveTypeTree(t: Tree, parentLookupScope: LookupScope)(implicit context: Context): CompletionResult = t match {
    case AppliedTypeTree(tpt, args) =>
      val resolvedTpt = resolveTypeTree(tpt, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case uncompleted => return uncompleted
      }
      var remainingArgs = args
      val resolvedArgs = new util.ArrayList[Type]()
      while (remainingArgs.nonEmpty) {
        val resolvedArg = resolveTypeTree(remainingArgs.head, parentLookupScope)
        resolvedArg match {
          case CompletedType(argTpe) => resolvedArgs.add (argTpe)
          case _ => return resolvedArg
        }
        remainingArgs = remainingArgs.tail
      }
      CompletedType(AppliedType(resolvedTpt, resolvedArgs.toArray(new Array[Type](resolvedArgs.size))))
    // ParentClass(foo) is encoded as a constructor call with a tree of shape
    // Apply(Select(New(Ident(ParentClass)),<init>),List(Ident(foo)))
    // we want to extract the Ident(ParentClass)
    case Apply(Select(New(tp), nme.CONSTRUCTOR), _) =>
      resolveTypeTree(tp, parentLookupScope)
    // idnet or select?
    case other =>
      val resolvedSel = resolveSelectors(other, parentLookupScope)
      resolvedSel match {
        case LookedupSymbol(sym) => CompletedType(SymRef(sym))
        case NotFound => sys.error(s"Can't resolve selector $other")
        case incomplete: IncompleteDependency => incomplete
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
