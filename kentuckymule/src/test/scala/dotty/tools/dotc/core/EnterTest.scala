package dotty.tools.dotc.core

import dotty.tools.dotc.{CompilationUnit, parsing}
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import Symbols._
import Decorators._
import Names._
import utest._

object EnterTest extends TestSuite {
  def initCtx = (new ContextBase).initialCtx
  val tests = this {
    val ctx = initCtx.fresh
    'flatPackageDeclaration {
      val src = "package foo.bar; class Abc"
      enterToSymbolTable(src, ctx)
      val descendants = descendantNames(ctx.definitions.rootPackage)
      assert(descendants ==
        List(
          List(StdNames.nme.ROOTPKG, "foo".toTermName, "bar".toTermName, "Abc".toTypeName)
        )
      )
    }
    'nestedPackageDeclaration {
      val src = "package foo; package bar; class Abc"
      enterToSymbolTable(src, ctx)
      val descendants = descendantNames(ctx.definitions.rootPackage)
      assert(descendants ==
        List(
          List(StdNames.nme.ROOTPKG, "foo".toTermName, "bar".toTermName, "Abc".toTypeName)
        )
      )
    }
  }

  private def enterToSymbolTable(src: String, ctx: Context): Unit = {
    val unit = compilationUnitFromString(src, ctx)
    val enter = new Enter
    enter.enterCompilationUnit(unit)(ctx)
  }

  private def descendantPaths(s: Symbol): List[List[Symbol]] = {
    val children = s.childrenIterator.toList
    if (children.isEmpty)
      List(List(s))
    else {
      for {
        child <- children
        path <- descendantPaths(child)
      } yield s :: path
    }
  }

  private def descendantNames(s: Symbol): List[List[Name]] =
    descendantPaths(s).map(_.map(_.name))

  private def compilationUnitFromString(contents: String, ctx: Context): CompilationUnit = {
    IOUtils.withTemporarySourceFile(contents, ctx) { source =>
      val unit = new CompilationUnit(source)
      val parser = new parsing.Parsers.Parser(source)(ctx)
      unit.untpdTree = parser.parse()
      unit
    }
  }

}
