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

  abstract sealed class LookupAnswer
  case class LookedupSymbol(sym: Symbol) extends LookupAnswer
  case class IncompleteDependency(sym: Symbol) extends LookupAnswer
  case object NotFound extends LookupAnswer

  abstract class LookupScope {
    def lookup(name: Name, imports: ImportsLookupScope)(implicit context: Context): LookupAnswer
  }

  private class ImportCompleter(val importNode: Import, val name: Name) {
    private var sym0: Symbol = _
    def complete(parentLookupScope: LookupScope, previousImports: ImportsLookupScope)(implicit context: Context): LookupAnswer = {
      val Import(expr, List(Ident(name))) = importNode
      val exprAns = resolveSelectors(expr, parentLookupScope, previousImports)
      exprAns match {
        case LookedupSymbol(exprSym) =>
          if (exprSym.isComplete) {
            sym0 = exprSym.lookup(name)
            LookedupSymbol(sym0)
          }
          else IncompleteDependency(exprSym)
        case _ => exprAns
      }
    }
    private def resolveSelectors(t: Tree, parentLookupScope: LookupScope, previousImports: ImportsLookupScope)(implicit context: Context): LookupAnswer =
      t match {
        case Ident(identName) => parentLookupScope.lookup(identName, previousImports)
        case Select(qual, selName) =>
          val ans = resolveSelectors(qual, parentLookupScope, previousImports)
          ans match {
            case LookedupSymbol(qualSym) =>
              if (qualSym.isComplete)
                LookedupSymbol(qualSym.lookup(selName))
              else
                IncompleteDependency(qualSym)
            case _ => ans
          }
      }
    def symbol: Symbol = {
      if (sym0 == null)
        sys.error("this import hasn't been completed " + importNode)
      sym0
    }
  }

  class ImportsCollector(parentLookupScope: LookupScope) {
    private val importCompleters: util.ArrayList[ImportCompleter] = new util.ArrayList[ImportCompleter]()
    def append(imp: Import): Unit = {
//      val Import(expr, List(Ident(name))) = imp
      // TODO: support multiple selectors and renames
      val name: Name = imp match {
        case Import(_, Ident(nme) :: _) => nme
        case Import(_, Pair(_, Ident(nme)) :: _) => nme
      }
      importCompleters.add(new ImportCompleter(imp, name))
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
        val impCompleter = importCompleters.get(i)
        impCompleter.complete(parentLookupScope, importsCompletedSoFar) match {
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
      while (i > 0) {
        val completedImport = importCompleters.get(i)
        if (completedImport.name == name)
          return LookedupSymbol(completedImport.symbol)
        i -= 1
      }
      NotFound
    }
  }

  class LookupCompilationUnitScope(imports: List[Import], pkgLookupScope: PackageLookupScope) {

  }
  class LookupClassTemplateScope(classSym: Symbol, visibleImports: ImportsLookupScope, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name, imports: ImportsLookupScope)(implicit context: Context): LookupAnswer = {
      val foundSym = classSym.lookup(name)
      if (foundSym != NoSymbol)
        LookedupSymbol(foundSym)
      else {
        val ans = imports.lookup(name)
        ans match {
          case _: LookedupSymbol | _: IncompleteDependency => ans
          case _ => parentScope.lookup(name, visibleImports)
        }
      }
    }
  }

  object RootPackageLookupScope extends LookupScope {
    override def lookup(name: Name, imports: ImportsLookupScope)(implicit context: Context): LookupAnswer = {
      val sym = context.definitions.rootPackage.lookup(name)
      if (sym == NoSymbol)
        NotFound
      else
        LookedupSymbol(sym)
    }
  }

  def enterCompilationUnit(unit: CompilationUnit)(implicit context: Context): Unit = {
    val importsInCompilationUnit = new ImportsCollector(RootPackageLookupScope)
    enterTree(unit.untpdTree, context.definitions.rootPackage, importsInCompilationUnit, RootPackageLookupScope)
  }

  class PackageLookupScope(val pkgSym: Symbol, val parent: LookupScope, val parentImports: ImportsLookupScope) extends LookupScope {
    override def lookup(name: Name, imports: ImportsLookupScope)(implicit context: Context): LookupAnswer = {
      val foundSym = pkgSym.lookup(name)
      if (foundSym != NoSymbol)
        LookedupSymbol(foundSym)
      else {
        val ans = imports.lookup(name)
        ans match {
          case _: LookedupSymbol | _: IncompleteDependency => ans
          case _ => parent.lookup(name, parentImports)
        }
      }
    }
  }

  private def enterTree(tree: Tree, owner: Symbol, imports: ImportsCollector, parentScope: LookupScope)(implicit context: Context): Unit = tree match {
    case PackageDef(ident, stats) =>
      val pkgSym = expandQualifiedPackageDeclaration(ident, owner)
      val pkgImports = new ImportsCollector(parentScope)
      val pkgLookupScope = new PackageLookupScope(pkgSym, parentScope, imports.snapshot())
      for (stat <- stats) enterTree(stat, pkgSym, pkgImports, pkgLookupScope)
    case imp: Import =>
      imports.append(imp)
//    case ModuleDef(name, tmpl) =>
//      val modSym = new ModuleSymbol(name)
//      owner.addChild(modSym)
//      enterTree(tmpl, modSym)
    // class or trait
    case t@TypeDef(name, tmpl) if t.isClassDef =>
      val classSym = new ClassSymbol(name)
      owner.addChild(classSym)
      val classLookupScope = new LookupClassTemplateScope(classSym, imports.snapshot(), parentScope)
      val classImports = new ImportsCollector(parentScope)
      enterTree(tmpl, classSym, classImports, classLookupScope)
    case t: Template =>
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
