package dotty.tools.dotc.core

import dotty.tools.dotc.{CompilationUnit, parsing}
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import Symbols._
import Decorators._
import utest._

object EnterTest extends TestSuite {
  def initCtx = (new ContextBase).initialCtx
  val tests = this {
    val ctx = initCtx.fresh
    'flatPackageDecl {
      val src = "package foo.bar; class Abc"
      enterToSymbolTable(src, ctx)
      val rootPkg = ctx.definitions.rootPackage
      // these are commented out because of a bug in Enter's implementation
      // of package handling
      val rootChildren = childrenNames(rootPkg)
      assert(rootChildren == Seq("foo"))
      val fooPkg = rootPkg.lookup("foo".toTermName)
      val fooChildren = childrenNames(fooPkg)
      assert(fooChildren == Seq("bar"))
      val barPkg = fooPkg.lookup("bar".toTermName)
      val barChildren = childrenNames(barPkg)
      assert(barChildren == Seq("Abc"))
    }
    'nestedPackageDecl {
      val src = "package foo; package bar; class Abc"
      enterToSymbolTable(src, ctx)
      val rootPkg = ctx.definitions.rootPackage
      val rootChildren = childrenNames(rootPkg)
      assert(rootChildren == Seq("foo"))
      val fooPkg = rootPkg.lookup("foo".toTermName)
      val fooChildren = childrenNames(fooPkg)
      assert(fooChildren == Seq("bar"))
      val barPkg = fooPkg.lookup("bar".toTermName)
      val barChildren = childrenNames(barPkg)
      assert(barChildren == Seq("Abc"))
    }
  }

  private def enterToSymbolTable(src: String, ctx: Context): Unit = {
    val unit = compilationUnitFromString(src, ctx)
    val enter = new Enter
    enter.enterCompilationUnit(unit)(ctx)
  }

  private def childrenNames(s: Symbol): List[String] =
    s.childrenIterator.toList.map(_.name.toString)

  private def compilationUnitFromString(contents: String, ctx: Context): CompilationUnit = {
    IOUtils.withTemporarySourceFile(contents, ctx) { source =>
      val unit = new CompilationUnit(source)
      val parser = new parsing.Parsers.Parser(source)(ctx)
      unit.untpdTree = parser.parse()
      unit
    }
  }

}
