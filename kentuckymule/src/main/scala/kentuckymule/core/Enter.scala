package kentuckymule.core

import java.util

import dotty.tools.dotc
import dotc.ast.Trees._
import dotc.core.Contexts.Context
import dotc.core.Names.{Name, TermName, TypeName}
import dotc.{CompilationUnit, ast}
import dotc.core.Decorators._
import dotc.core.StdNames._
import Symbols._
import dotc.core.Flags
import CollectionUtils._
import kentuckymule.queue.JobQueue

/**
  * Creates symbols for declarations and enters them into a symbol table.
  */
class Enter(jobQueue: JobQueue) {

  import Enter._
  import ast.untpd._

  class ClassSignatureLookupScope(classSym: ClassSymbol, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (name.isTypeName) {
        val tParamFoundSym = classSym.typeParams.lookup(name)
        if (tParamFoundSym != NoSymbol)
          return LookedupSymbol(tParamFoundSym)
      }
      parentScope.lookup(name)
    }

    override def addImports(imports: ImportsLookup): LookupScope =
      throw new UnsupportedOperationException("There can't be any imports declared withing class signature")

    override def enclosingClass: LookupAnswer = parentScope.enclosingClass
  }

  class LookupClassTemplateScope private(classSym: ClassSymbol,
                                         imports: ImportsLookup,
                                         parentScope: LookupScope) extends LookupScope {
    def this(classSym: ClassSymbol, parentScope: LookupScope) = this(classSym, null, parentScope)
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (!classSym.isComplete)
        return IncompleteDependency(classSym)
      val selfLookupAns =
        if (classSym.info.selfInfo != null)
          classSym.info.selfInfo.lookup(name)
        else NotFound
      if (selfLookupAns != NotFound)
        return selfLookupAns
      val classLookupAns = classSym.info.lookup(name)
      if (classLookupAns != NotFound)
        return classLookupAns
      if (imports != null) {
        val impFoundSym = imports.lookup(name)
        impFoundSym match {
          case _: LookedupSymbol | _: IncompleteDependency => impFoundSym
          case _ => parentScope.lookup(name)
        }
      } else parentScope.lookup(name)
    }

    override def addImports(imports: ImportsLookup): LookupScope =
      new LookupClassTemplateScope(classSym, imports, parentScope)
    override def enclosingClass: LookupAnswer = LookedupSymbol(classSym)
  }

  class LookupModuleTemplateScope private(moduleSym: ModuleSymbol,
                                          imports: ImportsLookup,
                                          parentScope: LookupScope) extends LookupScope {
    def this(moduleSym: ModuleSymbol, parentScope: LookupScope) = this(moduleSym, null, parentScope)
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (!moduleSym.isComplete)
        return IncompleteDependency(moduleSym)
      val foundSym = moduleSym.info.lookup(name)
      if (foundSym != NotFound)
        foundSym
      else {
        if (imports != null) {
          val ans = imports.lookup(name)
          ans match {
            case _: LookedupSymbol | _: IncompleteDependency => ans
            case _ => parentScope.lookup(name)
          }
        } else parentScope.lookup(name)
      }
    }
    override def addImports(imports: ImportsLookup): LookupScope =
      new LookupModuleTemplateScope(moduleSym, imports, parentScope)

    override def enclosingClass: LookupAnswer = LookedupSymbol(moduleSym.clsSym)
  }

  class LookupDefDefScope(defSym: DefDefSymbol, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (name.isTypeName) {
        val tParamFoundSym = defSym.typeParams.lookup(name)
        if (tParamFoundSym != NoSymbol)
          return LookedupSymbol(tParamFoundSym)
      }
      parentScope.lookup(name)
    }

    override def addImports(imports: ImportsLookup): LookupScope =
      sys.error("unsupported operation")

    override def enclosingClass: LookupAnswer = parentScope.enclosingClass
  }

  class LookupTypeDefScope(typeDefSym: TypeDefSymbol, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (name.isTypeName) {
        val tParamFoundSym = typeDefSym.typeParams.lookup(name)
        if (tParamFoundSym != NoSymbol)
          return LookedupSymbol(tParamFoundSym)
      }
      parentScope.lookup(name)
    }

    override def addImports(imports: ImportsLookup): LookupScope =
      sys.error("unsupported operation")

    override def enclosingClass: LookupAnswer = parentScope.enclosingClass
  }

  object RootPackageLookupScope extends LookupScope {
    private var scalaPkg: Symbol = _
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      // TODO: lookup in the scala package performed in the root package
      // lookup scope is a hack. We should either introduce as a separate scope
      // or add an implicit import as it's specified in the spec
      if (scalaPkg == null) {
        // fun fact: if we don't cache the scala pkg symbol but perform the lookup
        // below every time in this method, we loose 100 ops/s in the
        // BenchmarkScalap.completeMemberSigs
        scalaPkg = context.definitions.rootPackage.lookup(nme.scala_)
      }
      if (scalaPkg != NoSymbol) {
        if (!scalaPkg.isComplete)
          return IncompleteDependency(scalaPkg)
        val answer = scalaPkg.info.lookup(name)
        answer match {
          case NotFound =>
          case other => return other
        }
      }
      locally {
        val javaPkg = context.definitions.rootPackage.lookup(nme.java)
        if (javaPkg != NoSymbol) {
          if (!javaPkg.isComplete)
            return IncompleteDependency(javaPkg)
          val javaLangPkg = javaPkg.lookup(nme.lang)
          if (!javaLangPkg.isComplete)
            return IncompleteDependency(javaLangPkg)
          val sym = javaLangPkg.lookup(name)
          if (sym == NoSymbol)
            NotFound
          else
            return LookedupSymbol(sym)
        }
      }
      val sym = context.definitions.rootPackage.lookup(name)
      if (sym != NoSymbol)
        LookedupSymbol(sym)
      else if (name == context.definitions.rootPackage.name)
        LookedupSymbol(context.definitions.rootPackage)
      else
        NotFound
    }

    override def addImports(imports: ImportsLookup): LookupScope =
      sys.error("unsupported operation")

    override def enclosingClass: LookupAnswer = NotFound
  }

  class PredefLookupScope(parentScope: LookupScope) extends LookupScope {
    var predefSymbol: Symbol = _
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (predefSymbol == null) {
        val scalaPkg = context.definitions.rootPackage.lookup(nme.scala_)
        if (scalaPkg != NoSymbol) {
          if (!scalaPkg.isComplete)
            return IncompleteDependency(scalaPkg)
          val sym = scalaPkg.lookup(nme.Predef)
          predefSymbol = sym
        } else predefSymbol = NoSymbol
      }

      if (predefSymbol != NoSymbol) {
        if (!predefSymbol.isComplete)
          return IncompleteDependency(predefSymbol)
        val lookupAnswer = predefSymbol.info.lookup(name)
        if (lookupAnswer != NotFound)
          return lookupAnswer
      }
      parentScope.lookup(name)
    }

    override def addImports(imports: ImportsLookup): LookupScope =
      sys.error("unsupported operation")

    override def enclosingClass: LookupAnswer = parentScope.enclosingClass
  }

  class LookupCompilationUnitScope private(imports: ImportsLookup, pkgLookupScope: LookupScope) extends LookupScope {
    def this(pkgLookupScope: LookupScope) = this(null, pkgLookupScope)
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (imports != null) {
        val impFoundSym = imports.lookup(name)
        impFoundSym match {
          case _: LookedupSymbol | _: IncompleteDependency => impFoundSym
          case _ => pkgLookupScope.lookup(name)
        }
      } else pkgLookupScope.lookup(name)
    }

    override def addImports(imports: ImportsLookup): LookupScope =
      new LookupCompilationUnitScope(imports, pkgLookupScope)

    override def enclosingClass: LookupAnswer = NotFound
  }

  def enterCompilationUnit(unit: CompilationUnit)(implicit context: Context): Unit = {
    val toplevelScope = {
      // TODO: hack, check for declaration of Predef object
      if (unit.source.file.name != "Predef.scala")
        new PredefLookupScope(RootPackageLookupScope)
      else
        RootPackageLookupScope
    }
    val importsInCompilationUnit = new ImportsCollector(toplevelScope)
    val compilationUnitScope = new LookupCompilationUnitScope(toplevelScope)
    val lookupScopeContext = new LookupScopeContext(importsInCompilationUnit, compilationUnitScope)
    try {
      enterTree(unit.untpdTree, context.definitions.rootPackage, lookupScopeContext)
    } catch {
      case ex: Exception => throw new RuntimeException(s"Error while entering symbols from ${unit.source}", ex)
    }

  }

  // the `isForPackageObject` exists solely to support package objects that
  // inherit from classes in the same package:
  // package foo {
  //   class A
  // }
  // package object foo extends A
  // this structure is extremely weird and, honestly, ill-defined but it's
  // supported in Scala (sadly)
  // the ill-defined part comes from the fact that `package object foo` both
  // contributes members to `foo` and consumes them at the same time
  // the `isForPackageObject` disabled lookup via info (which would lead to
  // a cycle) and performs lookup via `decls` which escapes the cycle because
  // it only includes members that are syntactically entered into a package
  class PackageLookupScope private(val pkgSym: Symbol,
                                   val parent: LookupScope,
                                   val imports: ImportsLookup,
                                  isForPackageObject: Boolean) extends LookupScope {
    import kentuckymule.core.LookupAnswer.symToLookupAnswer
    def this(pkgSym: Symbol, parent: LookupScope, isForPackageObject: Boolean) =
      this(pkgSym, parent, null, isForPackageObject)
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val pkgMemberLookupAnswer = if (isForPackageObject) {
        symToLookupAnswer(pkgSym.lookup(name))
      } else if (!pkgSym.isComplete) {
        return IncompleteDependency(pkgSym)
      } else {
        pkgSym.info.lookup(name)
      }
      if (pkgMemberLookupAnswer != NotFound)
        pkgMemberLookupAnswer
      else {
        if (imports != null) {
          val ans = imports.lookup(name)
          ans match {
            case _: LookedupSymbol | _: IncompleteDependency => ans
            case _ => parent.lookup(name)
          }
        } else parent.lookup(name)
      }
    }

    override def addImports(imports: ImportsLookup): LookupScope =
      new PackageLookupScope(pkgSym, parent, imports, isForPackageObject)

    override def enclosingClass: LookupAnswer = NotFound
  }

  private class LookupScopeContext(imports: ImportsCollector, parentScope: LookupScope) {
    private var cachedSimpleMemberLookupScope: LookupScope = parentScope
    def addImport(imp: Import): Unit = {
      cachedSimpleMemberLookupScope = null
      imports.append(imp)
    }
    private def parentScopeWithImports(): LookupScope = {
      if (!imports.isEmpty)
        parentScope.addImports(imports.snapshot())
      else
        parentScope
    }
    def pushPackageLookupScope(pkgSym: PackageSymbol, isForPackageObjectLookup: Boolean): LookupScopeContext = {
      val pkgLookupScope = new PackageLookupScope(pkgSym, parentScopeWithImports(), isForPackageObjectLookup)
      val pkgImports = new ImportsCollector(pkgLookupScope)
      new LookupScopeContext(pkgImports, pkgLookupScope)
    }
    def pushModuleLookupScope(modSym: ModuleSymbol): LookupScopeContext = {
      val moduleLookupScope = new LookupModuleTemplateScope(modSym, parentScopeWithImports())
      val moduleImports = new ImportsCollector(moduleLookupScope)
      new LookupScopeContext(moduleImports, moduleLookupScope)
    }
    def pushClassSignatureLookupScope(classSymbol: ClassSymbol): LookupScopeContext = {
      val classSignatureLookupScope = new ClassSignatureLookupScope(classSymbol, parentScopeWithImports())
      val dummyImports = new ImportsCollector(classSignatureLookupScope)
      new LookupScopeContext(dummyImports, classSignatureLookupScope)
    }
    def pushClassLookupScope(classSym: ClassSymbol): LookupScopeContext = {
      val classLookupScope = new LookupClassTemplateScope(classSym, parentScopeWithImports())
      // imports collector receives class lookup scope so the following construct is supported
      // class Bar { val y: String = "abc" }
      // class Foo { import x.y; val x: Bar = new Bar }
      // YES! Imports can have forward references in Scala (which is a little bit strange)
      val classImports = new ImportsCollector(classLookupScope)
      new LookupScopeContext(classImports, classLookupScope)
    }

    private def simpleMemberLookupScope(): LookupScope = {
      if (cachedSimpleMemberLookupScope != null)
        cachedSimpleMemberLookupScope
      else {
        cachedSimpleMemberLookupScope = parentScopeWithImports()
        cachedSimpleMemberLookupScope
      }
    }

    def newValDefLookupScope(valDefSymbol: ValDefSymbol): LookupScope = simpleMemberLookupScope()
    def newTypeAliasLookupScope(typeDefSymbol: TypeDefSymbol): LookupScope = {
      if (typeDefSymbol.typeParams.size > 0) {
        new LookupTypeDefScope(typeDefSymbol, parentScopeWithImports())
      } else {
        simpleMemberLookupScope()
      }
    }
    def newDefDefLookupScope(defDefSymbol: DefDefSymbol): LookupScope =
      if (defDefSymbol.typeParams.size > 0) {
        new LookupDefDefScope(defDefSymbol, parentScopeWithImports())
      } else {
        simpleMemberLookupScope()
      }

    def newMemberLookupScope(): LookupScope = {
      simpleMemberLookupScope()
    }
  }

  private def enterTree(tree: Tree, owner: Symbol, parentLookupScopeContext: LookupScopeContext)(implicit context: Context): Unit = tree match {
    case PackageDef(ident, stats) =>
      val pkgSym = expandQualifiedPackageDeclaration(ident, owner)
      val lookupScopeContext = parentLookupScopeContext.pushPackageLookupScope(pkgSym, isForPackageObjectLookup = false)
      for (stat <- stats) enterTree(stat, pkgSym, lookupScopeContext)
    case imp: Import =>
      parentLookupScopeContext.addImport(imp)
    case md@ModuleDef(name, tmpl) =>
      import dotty.tools.dotc.core.NameOps._
      val isPackageObject = md.mods is Flags.Package
      val modName = if (isPackageObject) nme.PACKAGE else name
      val modOwner = if (isPackageObject) lookupOrCreatePackage(name, owner) else owner
      val modClsSym = ClassSymbol(modName.moduleClassName, owner, null)
      val modSym = ModuleSymbol(modName, modClsSym, owner)
      if (isPackageObject) {
        assert(modOwner.isInstanceOf[PackageSymbol], "package object has to be declared inside of a package (enforced by syntax)")
        modOwner.asInstanceOf[PackageSymbol].packageObject = modSym
      }
      modOwner.addChild(modSym)
      locally {
        val moduleSignatureLookupScopeContext = if (isPackageObject) {
          parentLookupScopeContext.
            pushPackageLookupScope(modOwner.asInstanceOf[PackageSymbol], isForPackageObjectLookup = true)
        } else {
          parentLookupScopeContext
        }
        val completer = new TemplateMemberListCompleter(modClsSym, tmpl, moduleSignatureLookupScopeContext.newMemberLookupScope())
        jobQueue.queueCompleter(completer, pushToTheEnd = !isPackageObject)
        modClsSym.completer = completer
      }
      locally {
        val completer = new ModuleCompleter(modSym)
        jobQueue.queueCompleter(completer, pushToTheEnd = !isPackageObject)
        modSym.completer = completer
      }
      val moduleSignatureLookupScopeContext = if (isPackageObject) {
        parentLookupScopeContext.
          pushPackageLookupScope(modOwner.asInstanceOf[PackageSymbol], isForPackageObjectLookup = false)
      } else {
        parentLookupScopeContext
      }
      val templateMemberListLookupScopeContext = moduleSignatureLookupScopeContext.pushModuleLookupScope(modSym)
      for (stat <- tmpl.body) enterTree(stat, modClsSym, templateMemberListLookupScopeContext)
    // class or trait
    case t@TypeDef(name, tmpl: Template) if t.isClassDef =>
      val selfValSym = tmpl.self match {
        case EmptyValDef => null
        case ValDef(selfDefName, _, _) =>
          ValDefSymbol(selfDefName)
      }
      val classSym = ClassSymbol(name, owner, selfValSym)
      // t.tParams is empty for classes, the type parameters are accessible thorugh its primary constructor
      foreachWithIndex(tmpl.constr.tparams) { (tParam, tParamIndex) =>
        // TODO: setup completers for TypeDef (resolving bounds, etc.)
        classSym.typeParams.enter(TypeParameterSymbol(tParam.name, tParamIndex, classSym))
      }
      owner.addChild(classSym)
      // surprisingly enough, this conditional lets us save over 100 ops/s for the completeMemberSigs benchmark
      // we get 1415 ops/s if we unconditionally push class signature lookup scope vs 1524 ops/s with the condition
      // below
      val classSignatureLookupScopeContext =
        if (tmpl.constr.tparams.nonEmpty)
          parentLookupScopeContext.pushClassSignatureLookupScope(classSym)
        else
          parentLookupScopeContext

      tmpl.constr.vparamss foreach { vparams =>
        vparams foreach { vparam =>
          // we're entering constructor parameter as a val declaration in a class
          // TODO: these parameters shouldn't be visible as members outside unless they are declared as vals
          // compare: class Foo(x: Int) vs class Foo(val x: Int)
          enterTree(vparam, classSym, classSignatureLookupScopeContext)
        }
      }

      val completer = new TemplateMemberListCompleter(classSym, tmpl, classSignatureLookupScopeContext.newMemberLookupScope())
      jobQueue.queueCompleter(completer)
      if (selfValSym != null) {
        val selfCompleter =
          new ValDefCompleter(selfValSym, tmpl.self, classSignatureLookupScopeContext.newValDefLookupScope(selfValSym))
        selfValSym.completer = selfCompleter
        jobQueue.queueCompleter(selfCompleter)
      }
      classSym.completer = completer
      val lookupScopeContext = classSignatureLookupScopeContext.pushClassLookupScope(classSym)
      for (stat <- tmpl.body) enterTree(stat, classSym, lookupScopeContext)
    case td@TypeDef(name, rhs) if !rhs.isEmpty =>
      val typeDefSymbol = TypeDefSymbol(name, owner)
      foreachWithIndex(td.tparams) { (tParam, tParamIndex) =>
        // TODO: setup completers for TypeDef (resolving bounds, etc.)
        typeDefSymbol.typeParams.enter(TypeParameterSymbol(tParam.name, tParamIndex, owner))
      }
      val rhsLookupScope = parentLookupScopeContext.newTypeAliasLookupScope(typeDefSymbol)
      val completer =
        new TypeDefCompleter(typeDefSymbol, td, rhsLookupScope)
      typeDefSymbol.completer = completer
      jobQueue.queueCompleter(completer)
      owner.addChild(typeDefSymbol)
    case t@ValDef(name, _, _) =>
      val valSym = ValDefSymbol(name)
      val completer = new ValDefCompleter(valSym, t, parentLookupScopeContext.newValDefLookupScope(valSym))
      valSym.completer = completer
      owner.addChild(valSym)
    case t: DefDef =>
      val defSym = DefDefSymbol(t.name)
      var remainingTparams = t.tparams
      var tParamIndex = 0
      foreachWithIndex(t.tparams) { (tParam, tParamIndex) =>
        // TODO: setup completers for TypeDef (resolving bounds, etc.)
        defSym.typeParams.enter(TypeParameterSymbol(tParam.name, tParamIndex, owner))
      }
      val completer = new DefDefCompleter(defSym, t, parentLookupScopeContext.newDefDefLookupScope(defSym))
      defSym.completer = completer
      owner.addChild(defSym)
    case _ =>
  }

  private def expandQualifiedPackageDeclaration(pkgDecl: RefTree, owner: Symbol)(implicit ctx: Context): PackageSymbol =
    pkgDecl match {
    case Ident(name: Name) =>
      lookupOrCreatePackage(name, owner)
    case Select(qualifier: RefTree, name: Name) =>
      val qualPkg = expandQualifiedPackageDeclaration(qualifier, owner)
      lookupOrCreatePackage(name, qualPkg)
  }

  private def lookupOrCreatePackage(name: Name, owner: Symbol)(implicit ctx: Context): PackageSymbol = {
    /**
      * The line below for `resolvedOwner` is a hack borrowed from scalac and dottyc. It is hard
      * to understand so it receives a special, long comment. The long comment is cheaper to implement
      * than a more principled handling of empty packages hence the choice.
      *
      * In ac8cdb7e6f3040ba826da4e5479b3d75a7a6fa9e I tried to fix the interaction of
      * an empty package with other packages. In the commit message, I said:
      *
      *    Scala's parser has a weird corner case when both an empty package and
      *    regular one are involved in the same compilation unit:
      *
      *    // A.scala
      *    class A
      *    package foo {
      *      class B
      *    }
      *
      *    is expanded during parsing into:
      *    // A.scala
      *    package <empty> {
      *      class A
      *      package foo {
      *        class B
      *      }
      *    }
      *
      *    However, one would expect the actual ast representation to be:
      *
      *    package <empty> {
      *      class A
      *    }
      *    package foo {
      *      class B
      *    }
      *
      *    I believe `foo` is put into the empty package to preserve the property
      *    that a compilation unit has just one root ast node. Surprisingly, both
      *    the scalac and dottyc handle the scoping properly when the asts are
      *    nested in this weird fashion. For example, in scalac `B` won't see the
      *    `A` class even if it seems like it should when one looks at the nesting
      *    structure. I couldn't track down where the special logic that is
      *    responsible for special casing the empty package and ignoring the nesting
      *    structure.
      *
      * In the comment above, I was wrong. `B` class actually sees the `A` class when both are
      * declared in the same compilation unit. The `A` class becomes inaccessible to any other
      * members declared in a `foo` package (or any other package except for the empty package)
      * when these members are declared in a separate compilation unit. This is expected:
      * members declared in the same compilation unit are accessible to each other through
      * reference by a simple identifier. My fix in ac8cdb7e6f3040ba826da4e5479b3d75a7a6fa9e
      * was wrong based on this wrong analysis.
      *
      * The correct analysis is that scoping rules for the compilation unit should be preserved
      * so simply moving `package foo` declaration out of the empty package declaration is not
      * a good way to fix the problem that the `package foo` should not have the empty package
      * as an owner. The best fix would be at parsing time: the parser should create a top-level
      * ast not (called e.g. CompilationUnit) that would hold `class A` and `package foo`
      * verbatim as they're declared in source code. And only during entering symbols, the
      * empty package would be introduced for the `class A` with correct owners.
      *
      * Making this a reality would require changing parser's implementation which is hard so
      * we're borrowing a trick from scalac: whenever we're creating a package, we're checking
      * whether the owner is an empty package. If so, we're teleporting ourselves to the root
      * package. This, in effect, undoes the nesting into an empty package done by parser.
      * But it undoes it only for the owner chain hierarchy. The scoping rules are kept intact
      * so members in the same compilation unit are visible to each other.
      *
      * The same logic in scalac lives in the `createPackageSymbol` method at:
      *
      * https://github.com/scala/scala/blob/2.12.x/src/compiler/scala/tools/nsc/typechecker/Namers.scala#L341
      */
    val resolvedOwner = if (owner == ctx.definitions.emptyPackage) ctx.definitions.rootPackage else owner
    val lookedUp = resolvedOwner.lookup(name)
    lookedUp match {
      case pkgSym: PackageSymbol => pkgSym
      case _ =>
        val pkgSym = PackageSymbol(name)
        val pkgCompleter = new PackageCompleter(pkgSym)
        pkgSym.completer = pkgCompleter
        jobQueue.queueCompleter(pkgCompleter, pushToTheEnd = false)
        resolvedOwner.addChild(pkgSym)
        pkgSym
    }
  }

}

object Enter {
  import ast.untpd._

  abstract class LookupScope {
    def lookup(name: Name)(implicit context: Context): LookupAnswer
    def enclosingClass: LookupAnswer
    def addImports(imports: ImportsLookup): LookupScope
  }

  private class ImportCompleter(val importNode: Import) {

    private class ImportSelectorResolved(val termSym: Symbol, val typeSym: Symbol, renamedTo: Name) {
      val typeNameRenamed: TypeName = if (renamedTo != null) renamedTo.toTypeName else null
      val termNameRenamed: TermName = if (renamedTo != null) renamedTo.toTermName else null
    }
    private var exprSym0: Symbol = _
    private var resolvedSelectors: util.ArrayList[ImportSelectorResolved] = _
    private var hasFinalWildcard: Boolean = false
    private var isComplete: Boolean = false
    def complete(parentLookupScope: LookupScope)(implicit context: Context): LookupAnswer = {
      val Import(expr, selectors) = importNode
      val exprAns = ResolveType.resolveSelectors(expr, parentLookupScope)
      val result = mapCompleteAnswer(exprAns) { exprSym =>
        this.exprSym0 = exprSym
        val resolvedSelectors = mapToArrayList(selectors) { selector =>
          val (name, renamedTo) = selector match {
            case Ident(selName) => (selName, selName)
            case Pair(Ident(selName), Ident(selRenamedTo)) => (selName, selRenamedTo)
          }
          if (name != nme.WILDCARD) {
            val termSym: Symbol = {
              val lookupAnswer = lookupMember(exprSym, name)
              lookupAnswer match {
                case LookedupSymbol(sym) => sym
                case NotFound => NoSymbol
                case id: IncompleteDependency => return id
              }
            }
            val typeSym: Symbol = {
              val lookupAnswer = lookupMember(exprSym, name.toTypeName)
              lookupAnswer match {
                case LookedupSymbol(sym) => sym
                case NotFound => NoSymbol
                case id: IncompleteDependency => return id
              }
            }
            if (termSym == NoSymbol && typeSym == NoSymbol)
              return NotFound
            new ImportSelectorResolved(termSym, typeSym, renamedTo)
          } else {
            // parser guarantees that the wildcard name can only appear at the end of
            // the selector list and we check for possible null value below
            // signalling the wildcard selector via a null value is really wonky but
            // I'm doing it for one reason: I'm paranoid about performance and don't
            // want to scan the selectors list twice
            null
          }
        }
        val selectorsSize = resolvedSelectors.size()
        if (selectorsSize > 0 && resolvedSelectors.get(selectorsSize-1) == null) {
          resolvedSelectors.remove(selectorsSize-1)
          hasFinalWildcard = true
        }
        this.resolvedSelectors = resolvedSelectors
        exprSym
      }
      isComplete = true
      result
    }
    def matches(name: Name)(implicit context: Context): LookupAnswer = {
      assert(isComplete, s"the import node hasn't been completed: $importNode")
      var i = 0
      var seenNameInSelectors = false
      while (i < resolvedSelectors.size) {
        val selector = resolvedSelectors.get(i)
        val sym = {
          val termSym = selector.termSym
          val typeSym = selector.typeSym
          // all comparisons below are pointer equality comparisons; the || operator
          // has short-circuit optimization so this check, while verbose, is actually
          // really efficient
          if ((typeSym != null && (typeSym.name eq name)) || (termSym.name eq name)) {
            seenNameInSelectors = true
          }
          if (name.isTermName && termSym != null && (selector.termNameRenamed == name)) {
            seenNameInSelectors = true
            termSym
        } else if (typeSym != null && (selector.typeNameRenamed == name)) {
            seenNameInSelectors = true
            typeSym
          } else NoSymbol
        }
        if (sym != NoSymbol)
          return LookedupSymbol(sym)
        i += 1
      }
      // if not seen before, consider the final wildcard import
      // seenNameInSelector check is required by the spec (4.7):
      //   If a final wildcard is present, all importable members z
      //   z of p other than x1,…,xn,y1,…,yn
      //   x1, …, xn, y1,…,yn are also made available under their own unqualified names.
      if (!seenNameInSelectors && hasFinalWildcard) {
        exprSym0.info.lookup(name)
      } else NotFound
    }
  }

  private def lookupMember(sym: Symbol, name: Name)(implicit context: Context): LookupAnswer = {
    assert(sym.isComplete, s"Can't look up a member $name in a symbol that is not completed yet: $sym")
    sym match {
      case clsSym: ClassSymbol => clsSym.info.lookup(name)
      case modSym: ModuleSymbol => modSym.info.lookup(name)
      case pkgSym: PackageSymbol => pkgSym.info.lookup(name)
      case valSym: ValDefSymbol => valSym.info.lookup(name)
      case _: TypeDefSymbol =>
        throw new UnsupportedOperationException("Support for type defs is not implemented yet")
      case _: DefDefSymbol =>
        sys.error("Selecting from unapplied defs is not legal in Scala")
      case _: TypeParameterSymbol =>
        // TODO: actually, not true, it makes sense to select members of a type parameter by looking at its upper bound
        // but this is not implemented yet
        // for example this is legal: class Foo[T <: Bar] { val x: T; val y: T.boo }; class Bar { val boo: ... }
        sys.error("Unexpected selection from type parameter symbol, it should have been substituted by type argument")
      case NoSymbol =>
        sys.error("Ooops. Trying to select a member of NoSymbol (this is a bug)")
    }
  }

  class ImportsCollector(parentLookupScope: LookupScope) {
    private val importCompleters: util.ArrayList[ImportCompleter] = new util.ArrayList[ImportCompleter]()
    def append(imp: Import): Unit = {
      importCompleters.add(new ImportCompleter(imp))
    }
    def snapshot(): ImportsLookup = {
      new ImportsLookup(importCompleters, parentLookupScope)()
    }
    def isEmpty: Boolean = importCompleters.isEmpty
  }

  class ImportsLookup(importCompleters: util.ArrayList[ImportCompleter], parentLookupScope: LookupScope)
                     (lastCompleterIndex: Int = importCompleters.size - 1) {
    private var allComplete: Boolean = false


    private def resolveImports()(implicit context: Context): Symbol = {
      var i: Int = 0
      while (i <= lastCompleterIndex) {
        val importsCompletedSoFar = new ImportsLookup(importCompleters, parentLookupScope)(lastCompleterIndex = i-1)
        importsCompletedSoFar.allComplete = true
        val parentLookupWithImports = parentLookupScope.addImports(importsCompletedSoFar)
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
        val lookupAnswer = completedImport.matches(name)
        if (lookupAnswer != NotFound)
          return lookupAnswer
        i -= 1
      }
      NotFound
    }
  }

  @inline final private def mapCompleteAnswer(ans: LookupAnswer)(f: Symbol => Symbol): LookupAnswer = {
    ans match {
      case LookedupSymbol(sym) =>
        if (sym.isComplete)
           LookedupSymbol(f(sym))
        else
          IncompleteDependency(sym)
      case other => other
    }
  }

  private val maxFunctionArity = 22
  val functionNamesByArity: Array[TypeName] = Array.tabulate(maxFunctionArity+1)(i => s"Function$i".toTypeName)
}
