package kentuckymule.core

import java.util

import dotty.tools.dotc
import dotc.ast.Trees._
import dotc.core.Contexts.Context
import dotc.core.Names.{Name, TypeName}
import dotc.{CompilationUnit, ast}
import dotc.core.Decorators._
import dotc.core.StdNames._
import Symbols._
import Types._
import dotc.core.TypeOps

/**
  * Creates symbols for declarations and enters them into a symbol table.
  */
class Enter {

  import Enter._
  import ast.untpd._

  val completers: util.Queue[Completer] = new util.ArrayDeque[Completer]()

  class ClassSignatureLookupScope(classSym: ClassSymbol, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (name.isTypeName) {
        val tParamFoundSym = classSym.typeParams.lookup(name)
        if (tParamFoundSym != NoSymbol)
          return LookedupSymbol(tParamFoundSym)
      }
      parentScope.lookup(name)
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      throw new UnsupportedOperationException("There can't be any imports declared withing class signature")
  }

  class LookupClassTemplateScope(classSym: ClassSymbol, imports: ImportsLookupScope, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val classFoundSym = classSym.lookup(name)
      if (classFoundSym != NoSymbol)
        return LookedupSymbol(classFoundSym)
      val impFoundSym = imports.lookup(name)
      impFoundSym match {
        case _: LookedupSymbol | _: IncompleteDependency => impFoundSym
        case _ => parentScope.lookup(name)
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

  class LookupDefDefScope(defSym: DefDefSymbol, imports: ImportsLookupScope, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (name.isTypeName) {
        val tParamFoundSym = defSym.typeParams.lookup(name)
        if (tParamFoundSym != NoSymbol)
          return LookedupSymbol(tParamFoundSym)
      }
      val impFoundSym = imports.lookup(name)
      impFoundSym match {
        case _: LookedupSymbol | _: IncompleteDependency => impFoundSym
        case _ => parentScope.lookup(name)
      }
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new LookupDefDefScope(defSym, imports, parentScope)
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
        val sym = scalaPkg.lookup(name)
        if (sym == NoSymbol)
          NotFound
        else
          return LookedupSymbol(sym)
      }
      val sym = context.definitions.rootPackage.lookup(name)
      if (sym != NoSymbol)
        LookedupSymbol(sym)
      else if (name == context.definitions.rootPackage.name)
        LookedupSymbol(context.definitions.rootPackage)
      else
        NotFound
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      sys.error("unsupported operation")
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
        val sym = predefSymbol.info.lookup(name)
        if (sym != NoSymbol)
          return LookedupSymbol(sym)
      }
      parentScope.lookup(name)
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      sys.error("unsupported operation")
  }

  class LookupCompilationUnitScope(imports: ImportsLookupScope, pkgLookupScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val impFoundSym = imports.lookup(name)
      impFoundSym match {
        case _: LookedupSymbol | _: IncompleteDependency => impFoundSym
        case _ => pkgLookupScope.lookup(name)
      }
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new LookupCompilationUnitScope(imports, pkgLookupScope)
  }

  def enterCompilationUnit(unit: CompilationUnit)(implicit context: Context): Unit = {
    val toplevelScope = new PredefLookupScope(RootPackageLookupScope)
    val importsInCompilationUnit = new ImportsCollector(toplevelScope)
    val compilationUnitScope = new LookupCompilationUnitScope(importsInCompilationUnit.snapshot(), toplevelScope)
    val normalizedTrees = unit.untpdTree match {
      case PackageDef(Ident(nme.EMPTY_PACKAGE), stats) =>
        val (pkgs, objOrClass) = stats.partition(_.isInstanceOf[PackageDef])
        pkgs :+ PackageDef(Ident(nme.EMPTY_PACKAGE), objOrClass)
      case other => List(other)
    }
    val lookupScopeContext = new LookupScopeContext(importsInCompilationUnit, compilationUnitScope)
    normalizedTrees foreach { tree =>
      enterTree(tree, context.definitions.rootPackage, lookupScopeContext)
    }

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

  private class LookupScopeContext(imports: ImportsCollector, val parentScope: LookupScope) {
    private var cachedSimpleMemberLookupScope: LookupScope = parentScope
    def addImport(imp: Import): Unit = {
      cachedSimpleMemberLookupScope = null
      imports.append(imp)
    }
    def pushPackageLookupScope(pkgSym: PackageSymbol): LookupScopeContext = {
      val pkgLookupScope = new PackageLookupScope(pkgSym, parentScope, imports.snapshot())
      val pkgImports = new ImportsCollector(pkgLookupScope)
      new LookupScopeContext(pkgImports, pkgLookupScope)
    }
    def pushModuleLookupScope(modSym: ModuleSymbol): LookupScopeContext = {
      val moduleLookupScope = new LookupModuleTemplateScope(modSym, imports.snapshot(), parentScope)
      val moduleImports = new ImportsCollector(moduleLookupScope)
      new LookupScopeContext(moduleImports, moduleLookupScope)
    }
    def pushClassSignatureLookupScope(classSymbol: ClassSymbol): LookupScopeContext = {
      val classSignatureLookupScope = new ClassSignatureLookupScope(classSymbol, parentScope)
      new LookupScopeContext(imports, classSignatureLookupScope)
    }
    def pushClassLookupScope(classSym: ClassSymbol): LookupScopeContext = {
      val classLookupScope = new LookupClassTemplateScope(classSym, imports.snapshot(), parentScope)
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
        cachedSimpleMemberLookupScope = parentScope.replaceImports(imports.snapshot())
        cachedSimpleMemberLookupScope
      }
    }

    def newValDefLookupScope(valDefSymbol: ValDefSymbol): LookupScope = simpleMemberLookupScope()
    def newDefDefLookupScope(defDefSymbol: DefDefSymbol): LookupScope =
      if (defDefSymbol.typeParams.size > 0) {
        new LookupDefDefScope(defDefSymbol, imports.snapshot(), parentScope)
      } else {
        simpleMemberLookupScope()
      }
  }

  private def enterTree(tree: Tree, owner: Symbol, parentLookupScopeContext: LookupScopeContext)(implicit context: Context): Unit = tree match {
    case PackageDef(ident, stats) =>
      val pkgSym = expandQualifiedPackageDeclaration(ident, owner)
      val lookupScopeContext = parentLookupScopeContext.pushPackageLookupScope(pkgSym)
      for (stat <- stats) enterTree(stat, pkgSym, lookupScopeContext)
    case imp: Import =>
      parentLookupScopeContext.addImport(imp)
    case ModuleDef(name, tmpl) =>
      import dotty.tools.dotc.core.NameOps._
      val modClsSym = ClassSymbol(name.moduleClassName, owner)
      val modSym = ModuleSymbol(name, modClsSym, owner)
      owner.addChild(modSym)
      val lookupScopeContext = parentLookupScopeContext.pushModuleLookupScope(modSym)
      locally {
        val completer = new TemplateMemberListCompleter(modClsSym, tmpl, lookupScopeContext.parentScope)
        completers.add(completer)
        modClsSym.completer = completer
      }
      locally {
        val completer = new ModuleCompleter(modSym)
        completers.add(completer)
        modSym.completer = completer
      }
      for (stat <- tmpl.body) enterTree(stat, modClsSym, lookupScopeContext)
    // class or trait
    case t@TypeDef(name, tmpl: Template) if t.isClassDef =>
      val classSym = ClassSymbol(name, owner)
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
      assert(tmpl.constr.vparamss.size <= 1, "Multiple value parameter lists are not supported for class constructor")
      if (tmpl.constr.vparamss.size == 1) {
        tmpl.constr.vparamss.head foreach { vparam =>
          // we're entering constructor parameter as a val declaration in a class
          // TODO: these parameters shouldn't be visible as members outside unless they are declared as vals
          // compare: class Foo(x: Int) vs class Foo(val x: Int)
          enterTree(vparam, classSym, classSignatureLookupScopeContext)
        }
      }
      val lookupScopeContext = classSignatureLookupScopeContext.pushClassLookupScope(classSym)
      val completer = new TemplateMemberListCompleter(classSym, tmpl, lookupScopeContext.parentScope)
      completers.add(completer)
      classSym.completer = completer
      for (stat <- tmpl.body) enterTree(stat, classSym, lookupScopeContext)
    // type alias or type member
    case TypeDef(name, _) =>
      val typeSymbol = TypeDefSymbol(name, owner)
      owner.addChild(typeSymbol)
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
      val lookedUp = owner.lookup(name)
      lookedUp match {
        case pkgSym: PackageSymbol => pkgSym
        case _ =>
          val pkgSym = PackageSymbol(name)
          owner.addChild(pkgSym)
          pkgSym
      }
    case Select(qualifier: RefTree, name: Name) =>
      val qualPkg = expandQualifiedPackageDeclaration(qualifier, owner)
      val lookedUp = qualPkg.lookup(name)
      lookedUp match {
        case pkgSym: PackageSymbol => pkgSym
        case _ =>
          val pkgSym = PackageSymbol(name)
          qualPkg.addChild(pkgSym)
          pkgSym
      }
  }

  def processJobQueue(memberListOnly: Boolean,
                      listener: JobQueueProgressListener = NopJobQueueProgressListener)(implicit ctx: Context): Int = {
    var steps = 0
    while (!completers.isEmpty) {
      steps += 1
      if (ctx.verbose)
        println(s"Step $steps/${steps+completers.size-1}")
      val completer = completers.remove()
      if (!completer.isCompleted) {
        val res = completer.complete()
        res match {
          case CompletedType(tpe: ClassInfoType) =>
            val classSym = tpe.clsSym
            classSym.info = tpe
            if (!memberListOnly)
              scheduleMembersCompletion(classSym)
          case CompletedType(tpe: ModuleInfoType) =>
            val modSym = tpe.modSym
            modSym.info = tpe
          case IncompleteDependency(sym: ClassSymbol) =>
            assert(sym.completer != null, sym.name)
            completers.add(sym.completer)
            completers.add(completer)
          case IncompleteDependency(sym: ModuleSymbol) =>
            assert(sym.completer != null, sym.name)
            completers.add(sym.completer)
            completers.add(completer)
          case IncompleteDependency(sym: ValDefSymbol) =>
            completers.add(sym.completer)
            completers.add(completer)
          case IncompleteDependency(sym: DefDefSymbol) =>
            completers.add(sym.completer)
            completers.add(completer)
          case CompletedType(tpe: MethodInfoType) =>
            val defDefSym = completer.sym.asInstanceOf[DefDefSymbol]
            defDefSym.info = tpe
          case CompletedType(tpe: ValInfoType) =>
            val valDefSym = completer.sym.asInstanceOf[ValDefSymbol]
            valDefSym.info = tpe
          // error cases
          case completed: CompletedType =>
            sys.error(s"Unexpected completed type $completed returned by completer for ${completer.sym}")
          case incomplete@IncompleteDependency(_: TypeDefSymbol) =>
            throw new UnsupportedOperationException("TypeDef support is not implemented yet")
          case incomplete@(IncompleteDependency(_: TypeParameterSymbol) | IncompleteDependency(NoSymbol) |
                           IncompleteDependency(_: PackageSymbol)) =>
            sys.error(s"Unexpected incomplete dependency $incomplete")
          case NotFound =>
            sys.error(s"The completer for ${completer.sym} finished with a missing dependency")
        }
      }
      listener.thick(completers.size, steps)
    }
    listener.allComplete()
    steps
  }

  private def scheduleMembersCompletion(sym: ClassSymbol)(implicit ctx: Context): Unit = {
    sym.decls.toList foreach {
      case defSym: DefDefSymbol => completers.add(defSym.completer)
      case valSym: ValDefSymbol => completers.add(valSym.completer)
      case _: ClassSymbol | _: ModuleSymbol =>
      case decl@(_: TypeDefSymbol) =>
        if (ctx.verbose)
          println(s"Ignoring type def $decl in ${sym.name}")
      case decl@(_: TypeParameterSymbol | _: PackageSymbol | NoSymbol) =>
        sys.error(s"Unexpected class declaration: $decl")
    }
  }

}

object Enter {
  import ast.untpd._

  sealed trait LookupAnswer
  case class LookedupSymbol(sym: Symbol) extends LookupAnswer
  case object NotFound extends LookupAnswer with CompletionResult

  sealed trait CompletionResult
  case class CompletedType(tpe: Type) extends CompletionResult

  case class IncompleteDependency(sym: Symbol) extends LookupAnswer with CompletionResult

  abstract class LookupScope {
    def lookup(name: Name)(implicit context: Context): LookupAnswer
    def replaceImports(imports: ImportsLookupScope): LookupScope
  }

  private class ImportCompleter(val importNode: Import) {
    private class ImportSelectorResolved(val termSym: Symbol, val typeSym: Symbol, val isWildcard: Boolean)
    private var exprSym0: Symbol = _
    private var resolvedSelectors: util.ArrayList[ImportSelectorResolved] = _
    private var isComplete: Boolean = false
    def complete(parentLookupScope: LookupScope)(implicit context: Context): LookupAnswer = {
      val Import(expr, selectors) = importNode
      val exprAns = resolveSelectors(expr, parentLookupScope)
      val result = mapCompleteAnswer(exprAns) { exprSym =>
        this.exprSym0 = exprSym
        val resolvedSelectors = mapToArrayList(selectors) { selector =>
          val Ident(name) = selector
          if (name != nme.WILDCARD) {
            val termSym = lookupMember(exprSym, name)
            val typeSym = lookupMember(exprSym, name.toTypeName)
            if (termSym == NoSymbol && typeSym == NoSymbol)
              return NotFound
            new ImportSelectorResolved(termSym, typeSym, isWildcard = false)
          } else {
            new ImportSelectorResolved(null, null, isWildcard = true)
          }
        }
        this.resolvedSelectors = resolvedSelectors
        exprSym
      }
      isComplete = true
      result
    }
    def matches(name: Name)(implicit context: Context): Symbol = {
      assert(isComplete, s"the import node hasn't been completed: $importNode")
      var i = 0
      while (i < resolvedSelectors.size) {
        val selector = resolvedSelectors.get(i)
        val sym = if (selector.isWildcard) {
          exprSym0.info.lookup(name)
        } else {
          val termSym = selector.termSym
          val typeSym = selector.typeSym
          if (name.isTermName && termSym != null && termSym.name == name)
            termSym
          else if (typeSym != null && typeSym.name == name)
            typeSym
          else NoSymbol
        }
        // TODO: to support hiding with wildcard renames as in `import foo.bar.{abc => _}`, we would
        // need to continue scanning selectors and check for this shape of a rename
        if (sym != NoSymbol)
          return sym
        i += 1
      }
      NoSymbol
    }
  }

  private def lookupMember(sym: Symbol, name: Name)(implicit context: Context): Symbol = {
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
        val sym = completedImport.matches(name)
        if (sym != NoSymbol)
          return LookedupSymbol(sym)
        i -= 1
      }
      NotFound
    }
  }

  abstract class Completer(val sym: Symbol) {
    def complete()(implicit context: Context): CompletionResult
    def isCompleted: Boolean
  }

  class ModuleCompleter(modSym: ModuleSymbol) extends Completer(modSym) {
    private var cachedInfo: ModuleInfoType = _
    override def complete()(implicit context: Context): CompletionResult = {
      if (cachedInfo != null)
        CompletedType(cachedInfo)
      else if (!modSym.clsSym.isComplete)
        IncompleteDependency(modSym.clsSym)
      else {
        cachedInfo = new ModuleInfoType(modSym, modSym.clsSym.info)
        CompletedType(cachedInfo)
      }
    }
    override def isCompleted: Boolean = cachedInfo != null
  }

  class TemplateMemberListCompleter(val clsSym: ClassSymbol, tmpl: Template, val lookupScope: LookupScope) extends Completer(clsSym) {
    private var cachedInfo: ClassInfoType = _
    def complete()(implicit context: Context): CompletionResult = {
      val resolvedParents = new util.ArrayList[Type]()
      var remainingParents = tmpl.parents
      while (remainingParents.nonEmpty) {
        val parent = remainingParents.head
        val resolved = resolveTypeTree(parent, lookupScope)
        resolved match {
          case CompletedType(tpe) => resolvedParents.add(tpe)
          case _: IncompleteDependency | NotFound => return resolved
        }
        remainingParents = remainingParents.tail
      }
      val info = new ClassInfoType(clsSym, asScalaList(resolvedParents))
      var i = 0
      while (i < resolvedParents.size()) {
        val parentType = resolvedParents.get(i)
        val parentSym = parentType.typeSymbol.asInstanceOf[ClassSymbol]
        val parentInfo = if (parentSym.info != null) parentSym.info else
          return IncompleteDependency(parentSym)
        parentType match {
          case at: AppliedType =>
            import TypeOps.{TypeParamMap, deriveMemberOfAppliedType}
            val typeParams = at.typeSymbol.asInstanceOf[ClassSymbol].typeParams
            val typeParamMap = new TypeParamMap(typeParams)
            for (m <- parentInfo.members.iterator) {
              if (!m.isComplete)
                return IncompleteDependency(m)
              val derivedInheritedMember = deriveMemberOfAppliedType(m, at, typeParamMap)
              info.members.enter(derivedInheritedMember)
            }
          case other =>
            info.members.enterAll(parentInfo.members)
        }
        i += 1
      }
      info.members.enterAll(clsSym.decls)
      cachedInfo = info
      CompletedType(info)
    }
    def isCompleted: Boolean = cachedInfo != null
  }

  class DefDefCompleter(sym: DefDefSymbol, defDef: DefDef, val lookupScope: LookupScope) extends Completer(sym) {
    private var cachedInfo: MethodInfoType = _
    def complete()(implicit context: Context): CompletionResult = {
      val paramTypes = {
        // TODO: write interruptible map2, def interMap2[T](xss: List[List[T])(f: T => CompletionResult): List[List[Type]]
        defDef.vparamss map {  vParams =>
          vParams map { vparam =>
            val resolvedType = resolveTypeTree(vparam.tpt, lookupScope)
            resolvedType match {
              case CompletedType(tpe) => tpe
              case res: IncompleteDependency => return res
              case NotFound => sys.error(s"Couldn't resolve ${vparam.tpt}")
            }
          }
        }
      }
      val resultType: Type = if (defDef.tpt.isEmpty) InferredTypeMarker else {
        val resolvedType = resolveTypeTree(defDef.tpt, lookupScope)
        resolvedType match {
          case CompletedType(tpe) => tpe
          case res: IncompleteDependency => return res
          case NotFound => sys.error(s"Couldn't resolve ${defDef.tpt}")
        }
      }
      val info = MethodInfoType(sym, paramTypes, resultType)
      cachedInfo = info
      CompletedType(info)
    }
    def isCompleted: Boolean = cachedInfo != null
  }

  class ValDefCompleter(sym: ValDefSymbol, valDef: ValDef, val lookupScope: LookupScope) extends Completer(sym) {
    private var cachedInfo: ValInfoType = _
    def complete()(implicit context: Context): CompletionResult = try {
      val resultType: Type = if (valDef.tpt.isEmpty) InferredTypeMarker else {
        val resolvedType = resolveTypeTree(valDef.tpt, lookupScope)
        resolvedType match {
          case CompletedType(tpe) => tpe
          case res: IncompleteDependency => return res
          case NotFound => sys.error(s"Couldn't resolve ${valDef.tpt}")
        }
      }
      val info = ValInfoType(sym, resultType)
      cachedInfo = info
      CompletedType(info)
    } catch {
      case ex: Exception =>
        throw new RuntimeException(s"Error while completing $valDef", ex)
    }
    def isCompleted: Boolean = cachedInfo != null
  }

  private def resolveSelectors(t: Tree, parentLookupScope: LookupScope)(implicit context: Context): LookupAnswer =
    t match {
      case Ident(identName) => parentLookupScope.lookup(identName)
      case Select(qual, selName) =>
        val ans = resolveSelectors(qual, parentLookupScope)
        ans match {
          case LookedupSymbol(qualSym) =>
            if (qualSym.isComplete) {
              val selSym = qualSym.info.lookup(selName)
              if (selSym != NoSymbol)
                LookedupSymbol(selSym)
              else
                NotFound
            } else IncompleteDependency(qualSym)
          case _ => ans
        }
      case _ => sys.error(s"Unhandled tree $t at ${t.pos}")
    }

  private def resolveTypeTree(t: Tree, parentLookupScope: LookupScope)(implicit context: Context): CompletionResult = t match {
    case AppliedTypeTree(tpt, args) =>
      val resolvedTpt = resolveTypeTree(tpt, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case uncompleted => return uncompleted
      }
      var remainingArgs = args
      val resolvedArgs = new util.ArrayList[Type]()
      while (remainingArgs.nonEmpty) {
        val resolvedArg = resolveTypeTree(remainingArgs.head, parentLookupScope)
        resolvedArg match {
          case CompletedType(argTpe) => resolvedArgs.add (argTpe)
          case _ => return resolvedArg
        }
        remainingArgs = remainingArgs.tail
      }
      CompletedType(AppliedType(resolvedTpt, resolvedArgs.toArray(new Array[Type](resolvedArgs.size))))
    // ParentClass(foo) is encoded as a constructor call with a tree of shape
    // Apply(Select(New(Ident(ParentClass)),<init>),List(Ident(foo)))
    // we want to extract the Ident(ParentClass)
    case Apply(Select(New(tp), nme.CONSTRUCTOR), _) =>
      resolveTypeTree(tp, parentLookupScope)
    case Parens(t2) => resolveTypeTree(t2, parentLookupScope)
    case Function(args, res) =>
      val resolvedFunTypeArgs = new util.ArrayList[Type]()
      var remainingArgs = args
      while (remainingArgs.nonEmpty) {
        val resolvedArg = resolveTypeTree(remainingArgs.head, parentLookupScope)
        resolvedArg match {
          case CompletedType(argTpe) => resolvedFunTypeArgs.add(argTpe)
          case _ => return resolvedArg
        }
        remainingArgs = remainingArgs.tail
      }
      val resolvedRes = resolveTypeTree(res, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case other => return other
      }
      val funName = functionNamesByArity(resolvedFunTypeArgs.size)
      resolvedFunTypeArgs.add(resolvedRes)
      val functionSym = parentLookupScope.lookup(funName) match {
        case LookedupSymbol(sym) => sym
        case NotFound => sys.error(s"Can't resolve $funName")
        case x: IncompleteDependency => return x
      }
      CompletedType(AppliedType(SymRef(functionSym), resolvedFunTypeArgs.toArray(new Array(resolvedFunTypeArgs.size))))
    // TODO: I ignore a star indicator of a repeated parameter as it's not essential and fairly trivial to deal with
    case PostfixOp(ident, nme.raw.STAR) =>
      resolveTypeTree(ident, parentLookupScope)
    // TODO: we horribly ignore tuples for now
    case Tuple(trees) =>
      resolveTypeTree(trees.head, parentLookupScope)
    // TODO: we ignore by name argument `=> T` and resolve it as `T`
    case ByNameTypeTree(res) =>
      resolveTypeTree(res, parentLookupScope)
    // TODO: I ignore AndTypeTree and pick just the left side, for example the `T with U` is resolved to `T`
    case t@AndTypeTree(left, right) =>
      if (context.verbose)
        println(s"Ignoring $t (printed because this hacky shortcut is non-trivial)")
      resolveTypeTree(left, parentLookupScope)
    case TypeBoundsTree(EmptyTree, EmptyTree) =>
      CompletedType(WildcardType)
    case InfixOp(left, op, right) =>
      val resolvedLeftType = resolveTypeTree(left, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case other => return other
      }
      val resolvedRightType = resolveTypeTree(left, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case other => return other
      }
      val resolvedOp = parentLookupScope.lookup(op) match {
        case LookedupSymbol(sym) => SymRef(sym)
        case NotFound =>
          sys.error(s"Can't resolve $op")
        case incomplete: IncompleteDependency => return incomplete
      }
      CompletedType(AppliedType(resolvedOp, Array[Type](resolvedLeftType, resolvedRightType)))
    case SelectFromTypeTree(qualifier, name) =>
      val resolvedQualifier = resolveTypeTree(qualifier, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case other => return other
      }
      val resolvedSelect = if (resolvedQualifier.typeSymbol.isComplete)
        resolvedQualifier.typeSymbol.info.lookup(name)
      else
        return IncompleteDependency(resolvedQualifier.typeSymbol)
      if (resolvedSelect != NoSymbol)
        CompletedType(SymRef(resolvedSelect))
      else
        NotFound
    // idnet or select?
    case other =>
      val resolvedSel = resolveSelectors(other, parentLookupScope)
      resolvedSel match {
        case LookedupSymbol(sym) => CompletedType(SymRef(sym))
        case NotFound =>
          sys.error(s"Can't resolve selector $other")
        case incomplete: IncompleteDependency => incomplete
      }
  }

  trait JobQueueProgressListener {
    def thick(queueSize: Int, completed: Int): Unit
    def allComplete(): Unit
  }
  object NopJobQueueProgressListener extends JobQueueProgressListener {
    override def thick(queueSize: Int, completed: Int): Unit = ()
    override def allComplete(): Unit = ()
  }

  private def asScalaList[T](javaList: util.ArrayList[T]): List[T] = {
    var i = javaList.size() - 1
    var res: List[T] = Nil
    while (i >= 0) {
      res = javaList.get(i) :: res
      i -= 1
    }
    res
  }

  private def asScalaList2[T](javaList: util.ArrayList[util.ArrayList[T]]): List[List[T]] = {
    var i = javaList.size() - 1
    var res: List[List[T]] = Nil
    while (i >= 0) {
      val innerList = asScalaList(javaList.get(i))
      res = innerList :: res
      i -= 1
    }
    res
  }

  @inline final private def foreachWithIndex[T](xs: List[T])(f: (T, Int) => Unit): Unit = {
    var index = 0
    var remaining = xs
    while (remaining.nonEmpty) {
      f(remaining.head, index)
      remaining = remaining.tail
      index += 1
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

  @inline final private def mapToArrayList[T, U](xs: List[T])(f: T => U): util.ArrayList[U] = {
    val result = new util.ArrayList[U]()
    var remaining = xs
    while (remaining.nonEmpty) {
      val elem = f(remaining.head)
      result.add(elem)
      remaining = remaining.tail
    }
    result
  }

  private val maxFunctionArity = 22
  val functionNamesByArity: Array[TypeName] = Array.tabulate(maxFunctionArity+1)(i => s"Function$i".toTypeName)
}
