package dotty.tools.dotc
package core

import java.util

import dotty.tools.dotc.core.Contexts.Context
import Symbols._
import ast.Trees._
import Names.Name

/**
  * Creates symbols for declarations and enters them into a symbol table.
  */
class Enter {

  import ast.untpd._
  import Enter._

  val templateCompleters: util.Queue[TemplateCompleter] = new util.ArrayDeque[TemplateCompleter]()



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
      val modSym = new ModuleSymbol(name)
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
      templateCompleters.add(new TemplateCompleter(owner, parentScope))
      for (stat <- t.body) enterTree(stat, owner, imports, parentScope)
    // type alias or type member
    case TypeDef(name, _) =>
      val typeSymbol = new TypeDefSymbol(name)
      owner.addChild(typeSymbol)
    case ValDef(name, _, _) =>
      val valSym = new ValDefSymbol(name)
      owner.addChild(valSym)
    case DefDef(name, _, _, _, _) =>
      val valSym = new DefDefSymbol(name)
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

}

object Enter {
  import ast.untpd._

  abstract sealed class LookupAnswer
  case class LookedupSymbol(sym: Symbol) extends LookupAnswer
  case class IncompleteDependency(sym: Symbol) extends LookupAnswer
  case object NotFound extends LookupAnswer

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

  class TemplateCompleter(val sym: Symbol, val lookupScope: LookupScope)

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
}
