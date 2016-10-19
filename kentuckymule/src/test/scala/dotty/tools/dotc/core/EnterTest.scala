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
    'duplicatePackageDeclaration {
      val src = "package foo; package bar { class Abc }; package bar { class Xyz }"
      enterToSymbolTable(src, ctx)
      val descendants = descendantPaths(ctx.definitions.rootPackage)
      val descendantNames = descendants.map(_.map(_.name))
      val barName = "bar".toTermName
      val allBarPkgs = descendants.flatMap(_.filter(_.name == barName)).toSet
      assert(allBarPkgs.size == 1)
      assert(descendantNames ==
        List(
          List(StdNames.nme.ROOTPKG, "foo".toTermName, "bar".toTermName, "Abc".toTypeName),
          List(StdNames.nme.ROOTPKG, "foo".toTermName, "bar".toTermName, "Xyz".toTypeName)
        )
      )
    }
    'resolveImport {
      val src = "object A { class B }; class X { import A.B; class Y }"
      val templateCompleters = enterToSymbolTable(src, ctx)
      val Some(ycompleter) = templateCompleters.find(_.sym.name == "Y".toTypeName)
      val ylookupScope = ycompleter.lookupScope
      val ans = ylookupScope.lookup("B".toTypeName)(ctx)
      assert(ans.isInstanceOf[Enter.LookedupSymbol])
    }
  }

  private def enterToSymbolTable(src: String, ctx: Context): collection.Iterable[Enter.TemplateCompleter] = {
    import scala.collection.JavaConverters._
    val unit = compilationUnitFromString(src, ctx)
    val enter = new Enter
    enter.enterCompilationUnit(unit)(ctx)
    enter.templateCompleters.asScala
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
