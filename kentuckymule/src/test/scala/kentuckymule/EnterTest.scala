package kentuckymule

import dotty.tools.dotc
import dotc.core.Contexts.{Context, ContextBase}
import dotc.core.StdNames
import dotc.{CompilationUnit, parsing}
import kentuckymule.core._
import kentuckymule.core.Symbols._
import kentuckymule.core.Types._
import dotc.core.IOUtils
import dotc.core.Decorators._
import dotc.core.Names.{Name, TypeName}
import dotty.tools.dotc.core.TypeOps.AppliedTypeMemberDerivation
import kentuckymule.queue.{JobQueue, QueueJob}
import kentuckymule.queue.JobQueue.{CompleterStats, JobDependencyCycle}
import utest._

//noinspection ComparingLength
object EnterTest extends TestSuite {
  def initCtx = (new ContextBase).initialCtx
  val tests = this {
    implicit val ctx = initCtx.fresh
    'flatPackageDeclaration {
      val src = "package foo.bar; class Abc"
      val jobQueue = new JobQueue
      enterToSymbolTable(ctx, jobQueue)(src)
      val descendants = descendantNames(ctx.definitions.rootPackage)
      assert(descendants ==
        List(
          List(StdNames.nme.ROOTPKG, "foo".toTermName, "bar".toTermName, "Abc".toTypeName)
        )
      )
    }
    'nestedPackageDeclaration {
      val src = "package foo; package bar; class Abc"
      val jobQueue = new JobQueue
      enterToSymbolTable(ctx, jobQueue)(src)
      val descendants = descendantNames(ctx.definitions.rootPackage)
      assert(descendants ==
        List(
          List(StdNames.nme.ROOTPKG, "foo".toTermName, "bar".toTermName, "Abc".toTypeName)
        )
      )
    }
    'duplicatePackageDeclaration {
      val src = "package foo; package bar { class Abc }; package bar { class Xyz }"
      val jobQueue = new JobQueue
      enterToSymbolTable(ctx, jobQueue)(src)
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
    'constructorMultipleParamList {
      val src = "class Foo(val x: Foo)(val y: Foo)"

      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()(ctx)

      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val fooSym = classes("Foo".toTypeName)
      val fooMembers = fooSym.info.membersIterator.toList.map(_.name)
      assert(fooMembers == List("x".toTermName, "y".toTermName))
    }
    import scala.collection.JavaConverters._
    'resolveImport {
      val src = "object A { class B }; class X { import A.B; class Y }"
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      val templateCompleters = jobQueue.completers
      val Some(ycompleter) = templateCompleters collectFirst {
        case cp: TemplateMemberListCompleter if cp.clsSym.name == "Y".toTypeName => cp
      }
      jobQueue.processJobQueue()(ctx)
      val ylookupScope = ycompleter.lookupScope
      val ans = ylookupScope.lookup("B".toTypeName)(ctx)
      assert(ans.isInstanceOf[LookedupSymbol])
    }
    'wildcardImport {
      val src = "object A { class B }; class X { import A._; class Y }"
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      val templateCompleters = jobQueue.completers
      val Some(ycompleter) = templateCompleters collectFirst {
        case cp: TemplateMemberListCompleter if cp.clsSym.name == "Y".toTypeName => cp
      }
      jobQueue.processJobQueue()(ctx)
      val ylookupScope = ycompleter.lookupScope
      val ans = ylookupScope.lookup("B".toTypeName)(ctx)
      assert(ans.isInstanceOf[LookedupSymbol])
    }
    'multipleImports {
      val src = "object A { class B1; class B2; }; class X { import A.{B1, B2}; class Y }"
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      val templateCompleters = jobQueue.completers
      val Some(ycompleter) = templateCompleters collectFirst {
        case cp: TemplateMemberListCompleter if cp.clsSym.name == "Y".toTypeName => cp
      }
      jobQueue.processJobQueue()(ctx)
      val ylookupScope = ycompleter.lookupScope
      locally {
        val ans = ylookupScope.lookup("B1".toTypeName)(ctx)
        assert(ans.isInstanceOf[LookedupSymbol])
      }
      locally {
        val ans = ylookupScope.lookup("B2".toTypeName)(ctx)
        assert(ans.isInstanceOf[LookedupSymbol])
      }
    }
    'importFromVal {
      val src = "class A(b: B) { import b.C; def c: C }; class B { class C }"

      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()(ctx)

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
    'importsInDifferentScopes {
      val src =
        """
          |package foo
          |import A.B
          |
          |object A {
          |  object B {
          |    class C
          |  }
          |  class B
          |}
          |class X {
          |   import B.C
          |   def c: C
          |   def b: B
          |}
        """.stripMargin
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Xsym = classes("X".toTypeName)
      val cDefCompleter = Xsym.lookup("c".toTermName)(ctx).asInstanceOf[DefDefSymbol].completer
      val bDefCompleter = Xsym.lookup("b".toTermName)(ctx).asInstanceOf[DefDefSymbol].completer
      jobQueue.processJobQueue()(ctx)
      val cDeflookupScope = cDefCompleter.lookupScope
      locally {
        val ans = cDeflookupScope.lookup("C".toTypeName)(ctx)
        assert(ans.isInstanceOf[LookedupSymbol])
      }
      val bDeflookupScope = bDefCompleter.lookupScope
      locally {
        val ans = bDeflookupScope.lookup("B".toTypeName)(ctx)
        assert(ans.isInstanceOf[LookedupSymbol])
      }
    }
    'memberPrecedenceOverImport {
      val src =
        """
          |package foo
          |
          |object A {
          |  class B
          |}
          |class X {
          |   import A.B
          |   def b: B
          |   class B
          |}
        """.stripMargin
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Xsym = classes("X".toTypeName)
      val bInXClassSym = Xsym.lookup("B".toTypeName)
      val bDefSym = Xsym.lookup("b".toTermName)(ctx).asInstanceOf[DefDefSymbol]
      jobQueue.processJobQueue()(ctx)
      locally {
        val resultType = bDefSym.info.resultType
        assert(resultType == SymRef(bInXClassSym))
      }
    }
    'importRename {
      /*
       * the import tests two things:
       *   1. whether renames are handled correctly (B is available via B1 name)
       *   2. whether renamed member is excluded from the list of members imported by `_`
       */
      val src =
        """
          |object A {
          |  class B
          |  class C
          |}
          |class X {
          |  import A.{B => B1, _}
          |  class Y
          |}
        """.stripMargin
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      val templateCompleters = jobQueue.completers
      val Some(ycompleter) = templateCompleters collectFirst {
        case cp: TemplateMemberListCompleter if cp.clsSym.name == "Y".toTypeName => cp
      }
      jobQueue.processJobQueue()(ctx)
      val ylookupScope = ycompleter.lookupScope
      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Bsym = classes("B".toTypeName)
      val Csym = classes("C".toTypeName)
      locally {
        val ans = ylookupScope.lookup("B1".toTypeName)(ctx)
        assert(ans == LookedupSymbol(Bsym))
      }
      locally {
        val ans = ylookupScope.lookup("C".toTypeName)(ctx)
        assert(ans == LookedupSymbol(Csym))
      }
      // this checks that B is not available via wildcard import; it was renamed to B1
      locally {
        val ans = ylookupScope.lookup("B".toTypeName)(ctx)
        assert(ans == NotFound)
      }

    }
    'importFromObject {
      val src =
        """
          |package a {
          |  package b {
          |    // this wildcard import is ok: it forces completion of `C` that is not dependent on `A` below
          |    import C._
          |    // import A._ causes a cyclic error also in scalac; only importing a specific name
          |    // avoids the cycle
          |    import A.X
          |    object A extends B {
          |      class X
          |    }
          |    object C
          |  }
          |  class B
          |}
        """.stripMargin
      val jobQueue = new JobQueue
      enterToSymbolTable(ctx, jobQueue)(src)
      val queueResult = jobQueue.processJobQueue()(ctx)
      // no cycles
      assertMatch(queueResult) { case _: CompleterStats => }
    }
    'packageObject {
      val src = "package foo; package object bar { class D }; package bar { class C }"
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      val templateCompleters = jobQueue.completers
      val Some(dCompleter) = templateCompleters collectFirst {
        case cp: TemplateMemberListCompleter if cp.clsSym.name == "D".toTypeName => cp
      }
      val descendants = descendantPaths(ctx.definitions.rootPackage)
      val descendantNames = descendants.map(_.map(_.name))
      assert(descendantNames ==
        List(
          List(StdNames.nme.ROOTPKG, "foo".toTermName, "bar".toTermName, "package".toTermName, "D".toTypeName),
          List(StdNames.nme.ROOTPKG, "foo".toTermName, "bar".toTermName, "C".toTypeName)
        )
      )
      jobQueue.processJobQueue()(ctx)
      val dLookupScope = dCompleter.lookupScope
      val ans = dLookupScope.lookup("C".toTypeName)(ctx)
      assert(ans.isInstanceOf[LookedupSymbol])
    }
    'packageObjectInEmptyPackage {
      val src = "package object bar { class D }; package bar { class C }"
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      val templateCompleters = jobQueue.completers
      val Some(dCompleter) = templateCompleters collectFirst {
        case cp: TemplateMemberListCompleter if cp.clsSym.name == "D".toTypeName => cp
      }
      val descendantNames = this.descendantNames(ctx.definitions.rootPackage)
      assert(descendantNames ==
        List(
          List(StdNames.nme.ROOTPKG, "bar".toTermName, "package".toTermName, "D".toTypeName),
          List(StdNames.nme.ROOTPKG, "bar".toTermName, "C".toTypeName)
        )
      )
      jobQueue.processJobQueue()(ctx)
      val dLookupScope = dCompleter.lookupScope
      val ans = dLookupScope.lookup("C".toTypeName)(ctx)
      assert(ans.isInstanceOf[LookedupSymbol])
    }
    'packageObjectInheritsFromTheSamePackageMember {
      val src =
        """
          |package foo {
          |  package bar {
          |    class A {
          |      class B
          |    }
          |  }
          |}
          |package foo {
          |  package object bar extends A
          |}""".stripMargin
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      val queueResult = jobQueue.processJobQueue()(ctx)
      assert(queueResult.isInstanceOf[CompleterStats])
      val LookedupSymbol(fooPkg) = ctx.definitions.rootPackage.info.lookup("foo".toTermName)
      val LookedupSymbol(barPkg) = fooPkg.info.lookup("bar".toTermName)
      val barPkgMembers = barPkg.info.asInstanceOf[PackageInfoType].members.map(_.name).toSet
      val expectedMembers = Set("A".toTypeName, "B".toTypeName, "package".toTermName)
      assert(barPkgMembers == expectedMembers)
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
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      val templateCompleters = (jobQueue.completers collect {
        case cp: TemplateMemberListCompleter => cp.clsSym.name.toString -> cp
      }).toMap
      jobQueue.processJobQueue()(ctx)
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
        val aResolvedFromC = ClookupScope.lookup("A".toTypeName)
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
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src1, src2)
      val templateCompleters = (jobQueue.completers collect {
        case cp: TemplateMemberListCompleter => cp.clsSym.name.toString -> cp
      }).toMap
      jobQueue.processJobQueue()(ctx)
      val allPaths = descendantPathsFromRoot()
      val classes = allPaths.flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val LookedupSymbol(fooSym) = ctx.definitions.rootPackage.info.lookup("foo".toTermName)
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
      val jobQueue = new JobQueue(memberListOnly = true)
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()(ctx)
      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      locally {
        val Asym = classes("A".toTypeName)
        val Amembers = Asym.info.membersIterator.toList
        assert(Amembers.size == 2)
      }
      locally {
        val Bsym = classes("B".toTypeName)
        val Bmembers = Bsym.info.membersIterator.toList
        assert(Bmembers.size == 1)
      }
    }
    'completeMemberInfo {
      val src = "class A extends B { def a: A }; class B { def b: B }"

      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()(ctx)

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

      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()(ctx)

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
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
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
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
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
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val Bsym = classes("B".toTypeName)
      val Tsym = Asym.typeParams.lookup("T".toTypeName)
      assert(Tsym != NoSymbol)
      locally {
        val LookedupSymbol(aDefSym) = Bsym.info.lookup("a".toTermName)(ctx)
        val CompletedType(aDefTypeInB: ValInfoType) = Bsym.info.asSeenFromThis_slow(aDefSym)
        val SymRef(resultTypeSym) = aDefTypeInB.resultType
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
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val classes = descendantPathsFromRoot().flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val Bsym = classes("B".toTypeName)
      val Csym = classes("C".toTypeName)
      val Xsym = classes("X".toTypeName)
      val Ysym = classes("Y".toTypeName)
      locally {
        val LookedupSymbol(aValSym: ValDefSymbol) = Csym.info.lookup("a".toTermName)
        val CompletedType(aValInfoInC: ValInfoType) = Csym.info.asSeenFromThis_slow(aValSym)
        val SymRef(resultTypeSym) = aValInfoInC.resultType
        assert(resultTypeSym == Ysym)
      }
      locally {
        val LookedupSymbol(bValSym: ValDefSymbol) = Csym.info.lookup("b".toTermName)
        val CompletedType(bValInfoInC: ValInfoType) = Csym.info.asSeenFromThis_slow(bValSym)
        val SymRef(resultTypeSym) = bValInfoInC.resultType
        assert(resultTypeSym == Xsym)
      }
    }
    'referToDefTypeParam {
      val src = "class A { def a[T](x: T): T }"
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
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
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
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
    'objectInheritedMember {
      val src =
        """|class A { class Foo }
           |object B extends A {
           |  def abc: Foo
           |}
        """.stripMargin
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val allPaths = descendantPathsFromRoot()
      val classes = allPaths.flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val objects = allPaths.flatten.collect {
        case modSym: ModuleSymbol => modSym.name -> modSym
      }.toMap
      val Foosym = classes("Foo".toTypeName)
      val Bsym = objects("B".toTermName)
      locally {
        val LookedupSymbol(abcSym: DefDefSymbol) = Bsym.info.lookup("abc".toTermName)
        val abcResultType = abcSym.info.resultType
        assert(abcResultType == SymRef(Foosym))
      }
    }
    'cyclicTypeParents {
      val src =
        """
          |class A extends B
          |class B extends C
          |class C extends A
          |class D extends C""".stripMargin
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      val JobDependencyCycle(foundCycles) = jobQueue.processJobQueue()
      assert(foundCycles.length == 1)
      val foundCycle = foundCycles.head
      val symbolsInCycle = foundCycle collect {
        case cj: CompletionJob => cj.completer.sym
      }
      val classNamesInCycle = symbolsInCycle.map(_.name).toSet
      // note: D is not part of the cycle. It's blocked on a cycle (so it's type can't be completed)
      // but it's not returned in the JobDependencyCycle. This is by design. We want the minimal chain
      // of problematic dependencies to be returned to make the error messages small, focused and easily
      // actionable
      val expectedClassNames = Set("A", "B", "C").map(_.toTypeName)
      assert(classNamesInCycle == expectedClassNames)
    }
    // tests dealiasing, hopefully I'll find a more direct way of testing dealiasing in the future
    'typeAliasClassParent {
      val src =
        """
          |class A extends Foo.B
          |object Foo {
          |  type B = C
          |}
          |class C {
          |  def c: C
          |}
          |""".stripMargin
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val Csym = classes("C".toTypeName)
      locally {
        // if a type alias was resolved correctly, we should find the `c` method that is inherited from the C
        // class
        val LookedupSymbol(cDefInA: DefDefSymbol) = Asym.info.lookup("c".toTermName)
        val cResultType = cDefInA.info.resultType
        assert(cResultType == SymRef(Csym))
      }
    }
    'typeAliasParametricClassParent {
      val src =
        """
          |class A extends Foo.B[D]
          |object Foo {
          |  type B[T] = C[T]
          |}
          |class C[T] {
          |  def d: T
          |}
          |class D
          |""".stripMargin
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val Csym = classes("C".toTypeName)
      val Dsym = classes("D".toTypeName)
      locally {
        // if a type alias was resolved correctly, we should find the `c` method that is inherited from the C
        // class
        val LookedupSymbol(dDefInA) = Asym.info.lookup("d".toTermName)
        val CompletedType(dDefInfoInA: MethodInfoType) = Asym.info.asSeenFromThis_slow(dDefInA)
        assert(dDefInA != NoSymbol)
        val dResultType = dDefInfoInA.resultType
        assert(dResultType == SymRef(Dsym))
      }
    }
    'typeAliasParametric {
      val src =
        """|abstract class Base[A, B] {
           |  val b: B
           |}
           |class Foo
           |class Bar
           |class A {
           |  type Flip[X, Y] = Base[Y, X]
           |  type Result = Flip[Foo, Bar]
           |}
        """.stripMargin
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val baseSym = classes("Base".toTypeName)
      val fooSym = classes("Foo".toTypeName)
      val bSym = baseSym.lookup("b".toTermName)
      locally {
        val LookedupSymbol(resultTypeAlias: TypeDefSymbol) = Asym.info.lookup("Result".toTypeName)
        val LookedupSymbol(flipSym: TypeDefSymbol) = Asym.info.lookup("Flip".toTypeName)
        val resultTypeAliasInfo = resultTypeAlias.info
        val typeAliasRhs = resultTypeAliasInfo.rhsType.asInstanceOf[AppliedType]
        val appliedTypeMemberDerivation = AppliedTypeMemberDerivation.createForDealiasedType(typeAliasRhs).right.get
        val CompletedType(derivedBInfo: ValInfoType) =
          appliedTypeMemberDerivation.deriveInheritedMemberInfoOfAppliedType(bSym)
        val bResultType = derivedBInfo.resultType
        assert(bResultType == SymRef(fooSym))
      }
    }
    'typeAliasInherited {
      val src =
        """|class Foo
           |class Bar {
           |  type T = Foo
           |}
           |class Baz extends Bar {
           |  def abc: T
           |}
           |
        """.stripMargin
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val bazSym = classes("Baz".toTypeName)
      val LookedupSymbol(abcSym) = bazSym.info.lookup("abc".toTermName)
      val LookedupSymbol(tSym) = bazSym.info.lookup("T".toTypeName)
      val abcResultType = abcSym.info.asInstanceOf[MethodInfoType].resultType
      assert(abcResultType == SymRef(tSym))
    }
    'selfType {
      val src =
        """
          |trait Foo[T] {
          |  class Inner
          |}
          |class Bar[T] { self: Foo[T] =>
          |  def a: Inner
          |}
        """.stripMargin
      val jobQueue = new JobQueue
      enterToSymbolTable(ctx, jobQueue)(src)
      val queueResult = jobQueue.processJobQueue()
      assertMatch(queueResult) { case _: CompleterStats => }
    }
    'defTupleReturn {
      val src = "class A { def a[T,U](x: T): (T, U) }"
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      locally {
        val aDefSym = Asym.decls.lookup("a".toTermName)(ctx).asInstanceOf[DefDefSymbol]
        val Tsym = aDefSym.typeParams.lookup("T".toTypeName)
        val Usym = aDefSym.typeParams.lookup("U".toTypeName)
        assert(aDefSym.info != null)
        assert(aDefSym.info.resultType.isInstanceOf[TupleType])
        val TupleType(types) = aDefSym.info.resultType
        assert(types.toList match {
          case SymRef(t) :: SymRef(u) :: Nil => t == Tsym && u == Usym
          case _ => false
        })
      }
    }
    'parameterTuple {
      val src = "class A[T](x: (T, T)) { def y(t: (T, A)): A[T] }"
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val Tsym = Asym.typeParams.lookup("T".toTypeName)
      assert(Tsym != NoSymbol)
      locally {
        val xDefSym = Asym.decls.lookup("x".toTermName)(ctx).asInstanceOf[ValDefSymbol]
        assert(xDefSym.info != null)
        assert(xDefSym.info.resultType.isInstanceOf[TupleType])
        val TupleType(types) = xDefSym.info.resultType
        assert(types.toList match {
          case SymRef(t1) :: SymRef(t2) :: Nil => t1 == Tsym && t2 == Tsym
          case _ => false
        })
      }
      locally {
        val yDefSym = Asym.decls.lookup("y".toTermName)(ctx).asInstanceOf[DefDefSymbol]
        assert(yDefSym.info != null)
        assert(yDefSym.info.paramTypes.head.head.isInstanceOf[TupleType])
        val TupleType(types) = yDefSym.info.paramTypes.head.head
        assert(types.toList match {
          case SymRef(t1) :: SymRef(t2) :: Nil => t1 == Tsym && t2 == Asym
          case _ => false
        })

      }
    }

    'valTuple {
      val src = "class A { val tup: (A, A) }"
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      locally {
        val LookedupSymbol(aDefSym: ValDefSymbol) = Asym.info.lookup("tup".toTermName)(ctx)
        val TupleType(types) = aDefSym.info.resultType
        assert(types.toList match {
          case SymRef(t1) :: SymRef(t2) :: Nil => t1 == Asym && t2 == Asym
          case _ => false
        })
      }
    }
    // checks whether type application buried in a consctuctor call is extracted correctly
    'constructorCall {
      val src = "class A; class B[T] extends A[T]()(x)"
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val Bsym = classes("B".toTypeName)
      val Tsym = Bsym.typeParams.lookup("T".toTypeName)
      locally {
        val Aparents = Bsym.info.parents
        assert(Aparents.head == AppliedType(SymRef(Asym), Array[Type](SymRef(Tsym))))
      }
    }
    'singletonType {
      // `foo.type` is resolved to the type of `foo` and we forget about the singleton type
      val src =
        """class A {
          |  val foo: A
          |  val bar: foo.type
          |}""".stripMargin
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val LookedupSymbol(barSym: ValDefSymbol) = Asym.info.lookup("bar".toTermName)
      assert(barSym.info.resultType == SymRef(Asym))
    }
    'typeMemberWithABound {
      // `foo.type` is resolved to the type of `foo` and we forget about the singleton type
      val src =
        """class A {
          |  type T <: B
          |  val foo: T
          |  val bar: foo.X
          |}
          |class B {
          |  class X
          |}""".stripMargin
      val jobQueue = new JobQueue
      enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val Xsym = classes("X".toTypeName)
      val LookedupSymbol(barSym: ValDefSymbol) = Asym.info.lookup("bar".toTermName)
      assert(barSym.info.resultType == SymRef(Xsym))
    }
    'typeParamWithABound - pending {
      // `foo.type` is resolved to the type of `foo` and we forget about the singleton type
      val src =
        """class A[T <: B] {
          |  val foo: T
          |  val bar: foo.X
          |}
          |class B {
          |  class X
          |}
          |""".stripMargin
      val jobQueue = new JobQueue
      enterToSymbolTable(ctx, jobQueue)(src)
      try jobQueue.processJobQueue()
      catch {
        case ex: Exception => assert(false)
          // turn caught exception into a failed assertion to mark
          // the test as pending
      }
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
      val Asym = classes("A".toTypeName)
      val Xsym = classes("X".toTypeName)
      val LookedupSymbol(barSym: ValDefSymbol) = Asym.info.lookup("bar".toTermName)
      assert(barSym.info.resultType == SymRef(Xsym))
    }
    'superInPath {
      // `foo.type` is resolved to the type of `foo` and we forget about the singleton type
      val src =
        """
          |trait A1 {
          |  class X1
          |}
          |trait A2 {
          |  class X2
          |}
          |trait A3 {
          |  class X2
          |}
          |abstract class B extends A1 with A2 with A3 {
          |  class X1
          |  class X2
          |  val foo: super.X1
          |  val bar: super.X2
          |  val xyz: super[A2].X2
          |}""".stripMargin
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap - "X".toTypeName // remove X there are two classes named `X` and the hashmap will pick one arbitrarily
      val A1sym = classes("A1".toTypeName)
      val A2sym = classes("A2".toTypeName)
      val A3sym = classes("A3".toTypeName)
      val Bsym = classes("B".toTypeName)
      val LookedupSymbol(x1inAsym) = A1sym.info.lookup("X1".toTypeName)
      val LookedupSymbol(x2inA2sym) = A2sym.info.lookup("X2".toTypeName)
      val LookedupSymbol(x2inA3sym) = A3sym.info.lookup("X2".toTypeName)
      val LookedupSymbol(fooSym: ValDefSymbol) = Bsym.info.lookup("foo".toTermName)
      val LookedupSymbol(barSym: ValDefSymbol) = Bsym.info.lookup("bar".toTermName)
      val LookedupSymbol(xyzSym: ValDefSymbol) = Bsym.info.lookup("xyz".toTermName)
      assert(fooSym.info.resultType == SymRef(x1inAsym))
      assert(barSym.info.resultType == SymRef(x2inA3sym))
      assert(xyzSym.info.resultType == SymRef(x2inA2sym))
    }
    'fBoundedPolymorphism {
      // `foo.type` is resolved to the type of `foo` and we forget about the singleton type
      val src =
        """
          |class Foo[T]
          |class Bar[T] extends Foo[Bar[T]]
          |""".stripMargin
      val jobQueue = new JobQueue
      val enter = enterToSymbolTable(ctx, jobQueue)(src)
      jobQueue.processJobQueue()
      val classes = descendantPaths(ctx.definitions.rootPackage).flatten.collect {
        case clsSym: ClassSymbol => clsSym.name -> clsSym
      }.toMap
    }
  }

  final def pending(body: => Unit): Unit = {
    try body
    catch {
      case _: java.lang.AssertionError => return
    }
    asserts.Util.assertError("pending test passed", Seq.empty)
  }

  private def enterToSymbolTable(ctx: Context, jobQueue: JobQueue)(srcs: String*): Enter = {
    val units = srcs.map(src => compilationUnitFromString(src, ctx))
    val enter = new Enter(jobQueue)
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
