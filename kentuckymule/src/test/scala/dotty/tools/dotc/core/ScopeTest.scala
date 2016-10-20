package dotty.tools.dotc.core

import dotty.tools.dotc.{CompilationUnit, parsing}
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import Symbols._
import Decorators._
import Names._
import utest._

object ScopeTest extends TestSuite {
  def initCtx = (new ContextBase).initialCtx
  val tests = this {
    implicit val ctx = initCtx.fresh
    'nestedScope {
      val sym1 :: sym2 :: sym3 :: Nil = List("Outer1", "Outer2", "Inner").map(_.toTypeName).map(new ClassSymbol(_))
      val outerScope = Scopes.newScope
      outerScope.enter(sym1)
      assert(outerScope.size == 1)
      assert(outerScope.lookup("Outer1".toTypeName) != NoSymbol)
      outerScope.enter(sym2)
      assert(outerScope.size == 2)
      assert(outerScope.lookup("Outer2".toTypeName) != NoSymbol)

      val nestedScope = Scopes.newNestedScope(outerScope)
      assert(nestedScope.size == 2)
      assert(nestedScope.lookup("Outer1".toTypeName) != NoSymbol)
      assert(nestedScope.lookup("Outer2".toTypeName) != NoSymbol)

      nestedScope.enter(sym3)
      assert(nestedScope.size == 3)
      assert(nestedScope.lookup("Inner".toTypeName) != NoSymbol)
      assert(nestedScope.lookup("Outer1".toTypeName) != NoSymbol)
      assert(nestedScope.lookup("Outer2".toTypeName) != NoSymbol)
    }
    'enterAll {
      val sym1 :: sym2 :: sym3 :: Nil = List("C1", "C2", "C3").map(_.toTypeName).map(new ClassSymbol(_))
      val scope1 = Scopes.newScope
      scope1.enter(sym1)
      scope1.enter(sym2)
      assert(scope1.size == 2)
      val scope2 = Scopes.newScope
      scope2.enter(sym3)
      assert(scope2.size == 1)
      scope2.enterAll(scope1)
      assert(scope2.size == 3)
      assert(scope2.lookup("C1".toTypeName) != NoSymbol)
      assert(scope2.lookup("C2".toTypeName) != NoSymbol)
      assert(scope2.lookup("C3".toTypeName) != NoSymbol)
    }
  }

}
