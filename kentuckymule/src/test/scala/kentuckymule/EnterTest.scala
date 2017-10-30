package kentuckymule

import dotty.tools.dotc
import dotc.core.Contexts.{Context, ContextBase}
import dotc.core.StdNames
import dotc.{CompilationUnit, parsing}
import kentuckymule.core.Enter
import kentuckymule.core.Enter.{LookedupSymbol, NotFound, TemplateMemberListCompleter}
import kentuckymule.core.Symbols._
import kentuckymule.core.Types.SymRef
import dotc.core.IOUtils
import dotc.core.Decorators._
import dotc.core.Names.Name
import utest._

object EnterTest extends TestSuite {
  def initCtx = (new ContextBase).initialCtx
  val tests = this {
    implicit val ctx = initCtx.fresh
    'flatPackageDeclaration {
      val src = "package foo.bar; class Abc"
      enterToSymbolTable(ctx, src)
      val descendants = descendantNames(ctx.definitions.rootPackage)
      assert(descendants ==
        List(
          List(StdNames.nme.ROOTPKG, "foo".toTermName, "bar".toTermName, "Abc".toTypeName)
        )
      )
    }
    'nestedPackageDeclaration {
      val src = "package foo; package bar; class Abc"
      enterToSymbolTable(ctx, src)
      val descendants = descendantNames(ctx.definitions.rootPackage)
      assert(descendants ==
        List(
          List(StdNames.nme.ROOTPKG, "foo".toTermName, "bar".toTermName, "Abc".toTypeName)
        )
      )
    }
    'duplicatePackageDeclaration {
      val src = "package foo; package bar { class Abc }; package bar { class Xyz }"
      enterToSymbolTable(ctx, src)
      val descendants = descendantPathsFromRoot()
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
    import scala.collection.JavaConverters._
    'resolveImport {
      val src = "object A { class B }; class X { import A.B; class Y }"
      val enter = enterToSymbolTable(ctx, src)
      val templateCompleters = enter.completers.asScala
      val Some(ycompleter) = templateCompleters collectFirst {
        case cp: TemplateMemberListCompleter if cp.clsSym.name == "Y".toTypeName => cp
      }
      enter.processJobQueue(memberListOnly = false)(ctx)
      val ylookupScope = ycompleter.lookupScope
      val ans = ylookupScope.lookup("B".toTypeName)(ctx)
      assert(ans.isInstanceOf[Enter.LookedupSymbol])
    }
    'wildcardImport {
      val src = "object A { class B }; class X { import A._; class Y }"
      val enter = enterToSymbolTable(ctx, src)
      val templateCompleters = enter.completers.asScala
      val Some(ycompleter) = templateCompleters collectFirst {
        case cp: TemplateMemberListCompleter if cp.clsSym.name == "Y".toTypeName => cp
      }
      enter.processJobQueue(memberListOnly = false)(ctx)
      val ylookupScope = ycompleter.lookupScope
      val ans = ylookupScope.lookup("B".toTypeName)(ctx)
      assert(ans.isInstanceOf[Enter.LookedupSymbol])
    }
    'multipleImports {
      val src = "object A { class B1; class B2; }; class X { import A.{B1, B2}; class Y }"
      val enter = enterToSymbolTable(ctx, src)
      val templateCompleters = enter.completers.asScala
      val Some(ycompleter) = templateCompleters collectFirst {
        case cp: TemplateMemberListCompleter if cp.clsSym.name == "Y".toTypeName => cp
      }
      enter.processJobQueue(memberListOnly = false)(ctx)
      val ylookupScope = ycompleter.lookupScope
      locally {
        val ans = ylookupScope.lookup("B1".toTypeName)(ctx)
        assert(ans.isInstanceOf[Enter.LookedupSymbol])
      }
      locally {
        val ans = ylookupScope.lookup("B2".toTypeName)(ctx)
        assert(ans.isInstanceOf[Enter.LookedupSymbol])
      }
    }
    'importFromVal {
      val src = "class A(b: B) { import b.C; def c: C }; class B { class C }"
      val enter = enterToSymbolTable(ctx, src)
      enter.processJobQueue(memberListOnly = false)(ctx)
      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      locally {
        val Asym = classes("A".toTypeName)
        val Csym = classes("C".toTypeName)
        val cDefSym = Asym.decls.lookup("c".toTermName)(ctx).asInstanceOf[DefDefSymbol]
        assert(cDefSym.info != null)
        assert(cDefSym.info.paramTypes.isEmpty)
        assert(cDefSym.info.resultType.isInstanceOf[SymRef])
        val SymRef(resultTypeSym) = cDefSym.info.resultType
        assert(resultTypeSym == Csym)
      }
    }
    'predefAndScalaPackagePrecedence {
      val src =
        """|package scala {
           |  class A
           |  class B
           |  object Predef {
           |    class A
           |  }
           |}
           |class C
           |
        """.stripMargin
      val enter = enterToSymbolTable(ctx, src)
      val templateCompleters = (enter.completers.asScala collect {
        case cp: TemplateMemberListCompleter => cp.clsSym.name.toString -> cp
      }).toMap
      enter.processJobQueue(memberListOnly = false)(ctx)
      val allPaths = descendantPathsFromRoot()
      val classes = allPaths.flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val objects = allPaths.flatten.collect {
        case modSym: ModuleSymbol => modSym.name -> modSym
      }.toMap
      val Csym = classes("C".toTypeName)
      locally {
        val ClookupScope = templateCompleters("C").lookupScope
        val predef = objects("Predef".toTermName)
        val aInPredef = predef.info.lookup("A".toTypeName)
        val LookedupSymbol(aResolvedFromC) = ClookupScope.lookup("A".toTypeName)
        assert(aResolvedFromC == aInPredef)
      }
      locally {
        val predef = objects("Predef".toTermName)
        val predefLookupScope = templateCompleters("Predef$").lookupScope
        assert(predefLookupScope.lookup("C".toTypeName) == LookedupSymbol(Csym))
      }
    }
    'emptyPackageScope {
      val src1 =
        """|class A
           |class B
           |package foo {
           |  class A1
           |  class B1
           |}
        """.stripMargin
      val src2 =
        """
          |package foo
          |class C
        """.stripMargin
      val enter = enterToSymbolTable(ctx, src1, src2)
      val templateCompleters = (enter.completers.asScala collect {
        case cp: TemplateMemberListCompleter => cp.clsSym.name.toString -> cp
      }).toMap
      enter.processJobQueue(memberListOnly = false)(ctx)
      val allPaths = descendantPathsFromRoot()
      val classes = allPaths.flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val fooSym = ctx.definitions.rootPackage.info.lookup("foo".toTermName)
      assert(fooSym.isInstanceOf[PackageSymbol])
      val Asym = classes("A".toTypeName)
      val Bsym = classes("B".toTypeName)
      val A1sym = classes("A1".toTypeName)
      val B1sym = classes("B1".toTypeName)
      locally {
        val AlookupScope = templateCompleters("A").lookupScope
        val BlookupScope = templateCompleters("B").lookupScope
        assert(AlookupScope.lookup("B".toTypeName) == LookedupSymbol(Bsym))
        assert(BlookupScope.lookup("A".toTypeName) == LookedupSymbol(Asym))
        assert(AlookupScope.lookup("A1".toTypeName) == NotFound)
        assert(BlookupScope.lookup("A1".toTypeName) == NotFound)
        assert(AlookupScope.lookup("foo".toTermName) == LookedupSymbol(fooSym))
      }
      locally {
        val A1lookupScope = templateCompleters("A1").lookupScope
        val B1lookupScope = templateCompleters("B1").lookupScope
        assert(A1lookupScope.lookup("B1".toTypeName) == LookedupSymbol(B1sym))
        assert(B1lookupScope.lookup("A1".toTypeName) == LookedupSymbol(A1sym))
        assert(A1lookupScope.lookup("A".toTypeName) == LookedupSymbol(Asym))
        assert(B1lookupScope.lookup("A".toTypeName) == LookedupSymbol(Asym))
        assert(A1lookupScope.lookup("foo".toTermName) == LookedupSymbol(fooSym))
      }
      locally {
        val Csym = classes("C".toTypeName)
        val ClookupScope = templateCompleters("C").lookupScope
        assert(ClookupScope.lookup("A1".toTypeName) == LookedupSymbol(A1sym))
        assert(ClookupScope.lookup("B1".toTypeName) == LookedupSymbol(B1sym))
        assert(ClookupScope.lookup("A".toTypeName) == NotFound)
        assert(ClookupScope.lookup("B".toTypeName) == NotFound)
      }
    }
    'resolveMembers {
      val src = "class A extends B { def a: A }; class B { def b: B }"
      val enter = enterToSymbolTable(ctx, src)
      enter.processJobQueue(memberListOnly = true)(ctx)
      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      locally {
        val Asym = classes("A".toTypeName)
        val Amembers = Asym.info.members
        assert(Amembers.size == 2)
      }
      locally {
        val Bsym = classes("B".toTypeName)
        val Bmembers = Bsym.info.members
        assert(Bmembers.size == 1)
      }
    }
    'completeMemberInfo {
      val src = "class A extends B { def a: A }; class B { def b: B }"
      val enter = enterToSymbolTable(ctx, src)
      enter.processJobQueue(memberListOnly = false)(ctx)
      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      locally {
        val Asym = classes("A".toTypeName)
        val aDefSym = Asym.decls.lookup("a".toTermName)(ctx).asInstanceOf[DefDefSymbol]
        assert(aDefSym.info != null)
        assert(aDefSym.info.paramTypes.isEmpty)
        assert(aDefSym.info.resultType.isInstanceOf[SymRef])
        val SymRef(resultTypeSym) = aDefSym.info.resultType
        assert(resultTypeSym == Asym)
      }
      locally {
        val Bsym = classes("B".toTypeName)
        val bDefSym = Bsym.decls.lookup("b".toTermName)(ctx).asInstanceOf[DefDefSymbol]
        assert(bDefSym.info != null)
        assert(bDefSym.info.paramTypes.isEmpty)
        assert(bDefSym.info.resultType.isInstanceOf[SymRef])
        val SymRef(resultTypeSym) = bDefSym.info.resultType
        assert(resultTypeSym == Bsym)
      }
    }
    'memberInfoRefersToImport {
      val src = "class A { def a: A; import B.BB; def b: BB }; object B { class BB }"
      val enter = enterToSymbolTable(ctx, src)
      enter.processJobQueue(memberListOnly = false)(ctx)
      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      locally {
        val aDefSym = Asym.decls.lookup("a".toTermName)(ctx).asInstanceOf[DefDefSymbol]
        assert(aDefSym.info != null)
        assert(aDefSym.info.paramTypes.isEmpty)
        assert(aDefSym.info.resultType.isInstanceOf[SymRef])
        val SymRef(resultTypeSym) = aDefSym.info.resultType
        assert(resultTypeSym == Asym)
      }
      locally {
        val BBsym = classes("BB".toTypeName)
        val bDefSym = Asym.decls.lookup("b".toTermName)(ctx).asInstanceOf[DefDefSymbol]
        assert(bDefSym.info != null)
        assert(bDefSym.info.paramTypes.isEmpty)
        assert(bDefSym.info.resultType.isInstanceOf[SymRef])
        val SymRef(resultTypeSym) = bDefSym.info.resultType
        assert(resultTypeSym == BBsym)
      }
    }
    'referToClassTypeParam {
      val src = "class A[T, U] { def a: U; def b: T; class AA { def c: T } }"
      val enter = enterToSymbolTable(ctx, src)
      enter.processJobQueue(memberListOnly = false)
      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val Tsym = Asym.typeParams.lookup("T".toTypeName)
      val Usym = Asym.typeParams.lookup("U".toTypeName)
      assert(Tsym != NoSymbol)
      assert(Usym != NoSymbol)
      locally {
        val aDefSym = Asym.decls.lookup("a".toTermName)(ctx).asInstanceOf[DefDefSymbol]
        assert(aDefSym.info != null)
        assert(aDefSym.info.resultType.isInstanceOf[SymRef])
        val SymRef(resultTypeSym) = aDefSym.info.resultType
        assert(resultTypeSym == Usym)
      }
      locally {
        val bDefSym = Asym.decls.lookup("b".toTermName)(ctx).asInstanceOf[DefDefSymbol]
        assert(bDefSym.info != null)
        assert(bDefSym.info.resultType.isInstanceOf[SymRef])
        val SymRef(resultTypeSym) = bDefSym.info.resultType
        assert(resultTypeSym == Tsym)
      }
      val AAsym = classes("AA".toTypeName)
      locally {
        val cDefSym = AAsym.decls.lookup("c".toTermName)(ctx).asInstanceOf[DefDefSymbol]
        assert(cDefSym.info != null)
        assert(cDefSym.info.resultType.isInstanceOf[SymRef])
        val SymRef(resultTypeSym) = cDefSym.info.resultType
        assert(resultTypeSym == Tsym)
      }
    }
    'referToClassTypeParamInConstructor {
      val src = "class A[T](x: T)"
      val enter = enterToSymbolTable(ctx, src)
      enter.processJobQueue(memberListOnly = false)
      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val Tsym = Asym.typeParams.lookup("T".toTypeName)
      assert(Tsym != NoSymbol)
      locally {
        val xDefSym = Asym.decls.lookup("x".toTermName)(ctx).asInstanceOf[ValDefSymbol]
        assert(xDefSym.info != null)
        assert(xDefSym.info.resultType.isInstanceOf[SymRef])
        val SymRef(resultTypeSym) = xDefSym.info.resultType
        assert(resultTypeSym == Tsym)
      }
    }
    'inheritedReferringToTypeMember {
      val src = "class B extends A[C]; class A[T] { val a: T }; class C"
      val enter = enterToSymbolTable(ctx, src)
      enter.processJobQueue(memberListOnly = false)
      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val Bsym = classes("B".toTypeName)
      val Tsym = Asym.typeParams.lookup("T".toTypeName)
      assert(Tsym != NoSymbol)
      locally {
        val aDefSym = Bsym.info.members.lookup("a".toTermName)(ctx).asInstanceOf[ValDefSymbol]
        val SymRef(resultTypeSym) = aDefSym.info.resultType
        val Csym = classes("C".toTypeName)
        assert(resultTypeSym == Csym)
      }
    }
    'inheritedReferringToTypeMemberTransitive {
      val src =
        """class B[T] extends A[T,X]
          |class A[T,U] { val a: T; val b: U }
          |class C extends B[Y]
          |class X
          |class Y""".stripMargin
      val enter = enterToSymbolTable(ctx, src)
      enter.processJobQueue(memberListOnly = false)
      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val Bsym = classes("B".toTypeName)
      val Csym = classes("C".toTypeName)
      val Xsym = classes("X".toTypeName)
      val Ysym = classes("Y".toTypeName)
      locally {
        val aValSym = Csym.info.members.lookup("a".toTermName).asInstanceOf[ValDefSymbol]
        val SymRef(resultTypeSym) = aValSym.info.resultType
        assert(resultTypeSym == Ysym)
      }
      locally {
        val bValSym = Csym.info.members.lookup("b".toTermName).asInstanceOf[ValDefSymbol]
        val SymRef(resultTypeSym) = bValSym.info.resultType
        assert(resultTypeSym == Xsym)
      }
    }
    'referToDefTypeParam {
      val src = "class A { def a[T](x: T): T }"
      val enter = enterToSymbolTable(ctx, src)
      enter.processJobQueue(memberListOnly = false)
      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      locally {
        val aDefSym = Asym.decls.lookup("a".toTermName)(ctx).asInstanceOf[DefDefSymbol]
        val Tsym = aDefSym.typeParams.lookup("T".toTypeName)
        assert(aDefSym.info != null)
        assert(aDefSym.info.resultType.isInstanceOf[SymRef])
        val SymRef(resultTypeSym) = aDefSym.info.resultType
        assert(resultTypeSym == Tsym)
      }
    }
    'classParent {
      val src = "class A extends B; class B"
      val enter = enterToSymbolTable(ctx, src)
      enter.processJobQueue(memberListOnly = false)
      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val Bsym = classes("B".toTypeName)
      locally {
        val Aparents = Asym.info.parents
        assert(Aparents.size == 1)
        assert(Aparents.head == SymRef(Bsym))
      }
    }
  }

  private def enterToSymbolTable(ctx: Context, srcs: String*) = {
    val units = srcs.map(src => compilationUnitFromString(src, ctx))
    val enter = new Enter
    units.foreach(unit => enter.enterCompilationUnit(unit)(ctx))
    enter
  }

  private def descendantPathsFromRoot()(implicit context: Context): List[List[Symbol]] = {
    descendantPaths(context.definitions.rootPackage)
  }

  private def descendantPaths(s: Symbol)(implicit context: Context): List[List[Symbol]] = {
    val children = s.childrenIterator.toList
    if (children.isEmpty)
      List(List(s))
    else {
      for {
        child <- children
        path <- descendantPaths(child)
          // filter out the `rootPackage :: emptyPackage :: Nil` that is created due to
          // emptyPackage symbol created in Definitions and testing for presence of that
          // path brings no value
          // if there're members in the empty package declared, their paths won't be
          // filtered out
          if path != List(context.definitions.emptyPackage)
      } yield s :: path
    }
  }

  private def descendantNames(s: Symbol)(implicit context: Context): List[List[Name]] =
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
