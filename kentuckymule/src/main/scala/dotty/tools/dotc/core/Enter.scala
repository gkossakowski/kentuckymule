package dotty.tools.dotc
package core

import dotty.tools.dotc.core.Contexts.Context
import Symbols._
import ast.Trees.{RefTree => _, Template => _, Tree => _, _}
import Names.Name
import ast.untpd._

/**
  * Creates symbols for declarations and enters them into a symbol table.
  */
class Enter {

  def enterCompilationUnit(unit: CompilationUnit)(implicit context: Context): Unit = {
    enterTree(unit.untpdTree, context.definitions.rootPackage)
  }

  private def enterTree(tree: Tree, owner: Symbol)(implicit context: Context): Unit = tree match {
    case PackageDef(ident, stats) =>
      val pkgSym = expandQualifiedPackageDeclaration(ident, owner)
      for (stat <- stats) enterTree(stat, pkgSym)
    case ModuleDef(name, tmpl) =>
      val modSym = new ModuleSymbol(name)
      owner.addChild(modSym)
      enterTree(tmpl, modSym)
    // class or trait
    case t@TypeDef(name, tmpl) if t.isClassDef =>
      val classSym = new ClassSymbol(name)
      owner.addChild(classSym)
      enterTree(tmpl, classSym)
    case t: Template =>
      for (stat <- t.body) enterTree(stat, owner)
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

  private def expandQualifiedPackageDeclaration(pkgDecl: RefTree, owner: Symbol): Symbol = pkgDecl match {
    case Ident(name: Name) =>
      val pkgSym = new PackageSymbol(name)
      owner.addChild(pkgSym)
      pkgSym
    case Select(qualifier: RefTree, name: Name) =>
      val qualPkg = expandQualifiedPackageDeclaration(qualifier, owner)
      val pkgSym = new PackageSymbol(name)
      qualPkg.addChild(pkgSym)
      pkgSym
  }

}
