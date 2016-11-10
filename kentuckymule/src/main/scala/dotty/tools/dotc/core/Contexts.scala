package dotty.tools
package dotc
package core

import Decorators._
import Names._
import kentuckymule.core.Types._
import NameOps._
import Flags.ParamAccessor
import util.Positions._
import ast.Trees._
import ast.untpd
import util.{FreshNameCreator, SimpleMap, SourceFile, NoSource}
import reporting._
import collection.mutable
import collection.immutable.BitSet
import config.Settings.SettingsState
import config.ScalaSettings
import config.Settings.SettingGroup
import printing._
import language.implicitConversions
import parsing.Scanners.Comment

object Contexts {

  /** A context is passed basically everywhere in dotc.
   *  This is convenient but carries the risk of captured contexts in
   *  objects that turn into space leaks. To combat this risk, here are some
   *  conventions to follow:
   *
   *    - Never let an implicit context be an argument of a class whose instances
   *      live longer than the context.
   *    - Classes that need contexts for their initialization take an explicit parameter
   *      named `initctx`. They pass initctx to all positions where it is needed
   *      (and these positions should all be part of the intialization sequence of the class).
   *    - Classes that need contexts that survive initialization are instead passed
   *      a "condensed context", typically named `cctx` (or they create one). Condensed contexts
   *      just add some basic information to the context base without the
   *      risk of capturing complete trees.
   *    - To make sure these rules are kept, it would be good to do a sanity
   *      check using bytecode inspection with javap or scalap: Keep track
   *      of all class fields of type context; allow them only in whitelisted
   *      classes (which should be short-lived).
   */
  abstract class Context extends Reporting with Printers with Cloneable /*extends Periods
                            with Substituters
                            with TypeOps
                            with Phases
                            with Printers
                            with Symbols
                            with SymDenotations
                            with Reporting
                            with NamerContextOps
                            */ { thiscontext =>
    implicit def ctx: Context = this

    def scala2Mode: Boolean = true

    /** The context base at the root */
    val base: ContextBase

    /** All outer contexts, ending in `base.initialCtx` and then `NoContext` */
    def outersIterator = new Iterator[Context] {
      var current = thiscontext
      def hasNext = current != NoContext
      def next = { val c = current; current = current.outer; c }
    }

    /** The outer context */
    private[this] var _outer: Context = _
    protected def outer_=(outer: Context) = _outer = outer
    def outer: Context = _outer

    /** The compiler callback implementation, or null if no callback will be called. */

    /** The current context */

    /** The scope nesting level */
    private[this] var _mode: Mode = _
    protected def mode_=(mode: Mode) = _mode = mode
    def mode: Mode = _mode

    /** The current plain printer */
    private[this] var _printerFn: Context => Printer = _
    protected def printerFn_=(printerFn: Context => Printer) = _printerFn = printerFn
    def printerFn: Context => Printer = _printerFn

    /** The current owner symbol */
    private[this] var _owner: Symbol = _
    protected def owner_=(owner: Symbol) = _owner = owner
    def owner: Symbol = _owner

    /** The current settings values */
    private[this] var _sstate: SettingsState = _
    protected def sstate_=(sstate: SettingsState) = _sstate = sstate
    def sstate: SettingsState = _sstate

    /** The current tree */
    private[this] var _compilationUnit: CompilationUnit = _
    protected def compilationUnit_=(compilationUnit: CompilationUnit) = _compilationUnit = compilationUnit
    def compilationUnit: CompilationUnit = _compilationUnit

    /** The current tree */
    private[this] var _tree: Tree[_ >: Untyped] = _
    protected def tree_=(tree: Tree[_ >: Untyped]) = _tree = tree
    def tree: Tree[_ >: Untyped] = _tree

    /** The currently active import info */
//    private[this] var _importInfo: ImportInfo = _
//    protected def importInfo_=(importInfo: ImportInfo) = _importInfo = importInfo
//    def importInfo: ImportInfo = _importInfo

    /** The current compiler-run specific Info */
    private[this] var _runInfo: RunInfo = _
    protected def runInfo_=(runInfo: RunInfo) = _runInfo = runInfo
    def runInfo: RunInfo = _runInfo

    /** An optional diagostics buffer than is used by some checking code
     *  to provide more information in the buffer if it exists.
     */
    private var _diagnostics: Option[StringBuilder] = _
    protected def diagnostics_=(diagnostics: Option[StringBuilder]) = _diagnostics = diagnostics
    def diagnostics: Option[StringBuilder] = _diagnostics

    /**The current fresh name creator */
    private[this] var _freshNames: FreshNameCreator = _
    protected def freshNames_=(freshNames: FreshNameCreator) = _freshNames = freshNames
    def freshNames: FreshNameCreator = _freshNames

    def freshName(prefix: String = ""): String = freshNames.newName(prefix)
    def freshName(prefix: Name): String = freshName(prefix.toString)

    /** A map in which more contextual properties can be stored */
    private var _moreProperties: Map[String, Any] = _
    protected def moreProperties_=(moreProperties: Map[String, Any]) = _moreProperties = moreProperties
    def moreProperties: Map[String, Any] = _moreProperties

    /** Number of findMember calls on stack */
    private[core] var findMemberCount: Int = 0

    /** List of names which have a findMemberCall on stack,
     *  after Config.LogPendingFindMemberThreshold is reached.
     */
    private[core] var pendingMemberSearches: List[Name] = Nil

    /** Those fields are used to cache phases created in withPhase.
      * phasedCtx is first phase with altered phase ever requested.
      * phasedCtxs is array that uses phaseId's as indexes,
      * contexts are created only on request and cached in this array
      */
    private var phasedCtx: Context = _
    private var phasedCtxs: Array[Context] = _

    /** If -Ydebug is on, the top of the stack trace where this context
     *  was created, otherwise `null`.
     */
    private var creationTrace: Array[StackTraceElement] = _

//    private def setCreationTrace() =
//      if (this.settings.YtraceContextCreation.value)
//        creationTrace = (new Throwable).getStackTrace().take(20)

    /** Print all enclosing context's creation stacktraces */
    def printCreationTraces() = {
      println("=== context creation trace =======")
      for (ctx <- outersIterator) {
        println(s">>>>>>>>> $ctx")
        if (ctx.creationTrace != null) println(ctx.creationTrace.mkString("\n"))
      }
      println("=== end context creation trace ===")
    }

    /** The current reporter */
    val reporter: Reporter = new ConsoleReporter()

    /** Leave message in diagnostics buffer if it exists */
    def diagnose(str: => String) =
      for (sb <- diagnostics) {
        sb.setLength(0)
        sb.append(str)
      }

    /** The next outer context whose tree is a template or package definition */
    def enclTemplate: Context = {
      var c = this
      while (c != NoContext && !c.tree.isInstanceOf[Template[_]] && !c.tree.isInstanceOf[PackageDef[_]])
        c = c.outer
      c
    }

    /** The current source file; will be derived from current
     *  compilation unit.
     */
    def source: SourceFile =
      if (compilationUnit == null) NoSource else compilationUnit.source

    /** Is the debug option set? */
    def debug: Boolean = base.settings.debug.value

    /** Is the verbose option set? */
    lazy val verbose: Boolean = base.settings.verbose.value

    /** Should use colors when printing? */
    def useColors: Boolean =
      base.settings.color.value == "always"

    protected def init(outer: Context): this.type = {
      this.outer = outer
      this.phasedCtx = this
      this.phasedCtxs = null
//      setCreationTrace()
      this
    }

    /** A fresh clone of this context. */
    def fresh: FreshContext = clone.asInstanceOf[FreshContext].init(this)

    final def withOwner(owner: Symbol): Context = this
//      if (owner ne this.owner) fresh.setOwner(owner) else this

    override def toString =
      "Context(\n" +
      (outersIterator map ( ctx => s"  owner = ${ctx.owner}") mkString "\n")
  }

  /** A condensed context provides only a small memory footprint over
   *  a Context base, and therefore can be stored without problems in
   *  long-lived objects.
  abstract class CondensedContext extends Context {
    override def condensed = this
  }
  */

  /** A fresh context allows selective modification
   *  of its attributes using the with... methods.
   */
  abstract class FreshContext extends Context {
    def setMode(mode: Mode): this.type = { this.mode = mode; this }
    def setPrinterFn(printer: Context => Printer): this.type = { this.printerFn = printer; this }
    def setSettings(sstate: SettingsState): this.type = { this.sstate = sstate; this }
    def setCompilationUnit(compilationUnit: CompilationUnit): this.type = { this.compilationUnit = compilationUnit; this }
    def setTree(tree: Tree[_ >: Untyped]): this.type = { this.tree = tree; this }
//    def setImportInfo(importInfo: ImportInfo): this.type = { this.importInfo = importInfo; this }
    def setRunInfo(runInfo: RunInfo): this.type = { this.runInfo = runInfo; this }
    def setDiagnostics(diagnostics: Option[StringBuilder]): this.type = { this.diagnostics = diagnostics; this }
    def setFreshNames(freshNames: FreshNameCreator): this.type = { this.freshNames = freshNames; this }
    def setMoreProperties(moreProperties: Map[String, Any]): this.type = { this.moreProperties = moreProperties; this }

    def setProperty(prop: (String, Any)): this.type = setMoreProperties(moreProperties + prop)

  }

  implicit class ModeChanges(val c: Context) extends AnyVal {
    final def withModeBits(mode: Mode): Context =
      if (mode != c.mode) c.fresh.setMode(mode) else c

    final def addMode(mode: Mode): Context = withModeBits(c.mode | mode)
    final def maskMode(mode: Mode): Context = withModeBits(c.mode & mode)
    final def retractMode(mode: Mode): Context = withModeBits(c.mode &~ mode)
  }

  implicit class FreshModeChanges(val c: FreshContext) extends AnyVal {
    final def addMode(mode: Mode): c.type = c.setMode(c.mode | mode)
    final def maskMode(mode: Mode): c.type = c.setMode(c.mode & mode)
    final def retractMode(mode: Mode): c.type = c.setMode(c.mode &~ mode)
  }

  /** A class defining the initial context with given context base
   *  and set of possible settings.
   */
  private class InitialContext(val base: ContextBase, settings: SettingGroup) extends FreshContext {
    outer = NoContext
    mode = Mode.None
    printerFn = _ => ??? //new RefinedPrinter(_)
    sstate = settings.defaultState
    tree = untpd.EmptyTree
    runInfo = new RunInfo(this)
    diagnostics = None
    freshNames = new FreshNameCreator.Default
    moreProperties = Map.empty
  }

  @sharable object NoContext extends Context {
    val base = null
  }

  /** A context base defines state and associated methods that exist once per
   *  compiler run.
   */
  class ContextBase extends ContextState {

    /** The applicable settings */
    val settings = new ScalaSettings

    /** The initial context */
    val initialCtx: Context = new InitialContext(this, settings)

    /** Documentation base */
    val docbase = new DocBase

    /** The standard definitions */
    val definitions = new Definitions

    /** Initializes the `ContextBase` with a starting context.
     *  This initializes the `platform` and the `definitions`.
     */
    def initialize()(implicit ctx: Context): Unit = {
      definitions.init()
    }
  }

  class DocBase {
    private[this] val _docstrings: mutable.Map[Symbol, Comment] =
      mutable.Map.empty

    def docstring(sym: Symbol): Option[Comment] = _docstrings.get(sym)

    def addDocstring(sym: Symbol, doc: Option[Comment]): Unit =
      doc.map(d => _docstrings += (sym -> d))

    /*
     * Dottydoc places instances of `Package` in this map - but we do not want
     * to depend on `dottydoc` for the compiler, as such this is defined as a
     * map of `String -> AnyRef`
     */
    private[this] val _packages: mutable.Map[String, AnyRef] = mutable.Map.empty
    def packages[A]: mutable.Map[String, A] = _packages.asInstanceOf[mutable.Map[String, A]]

    /** Should perhaps factorize this into caches that get flushed */
    private var _defs: Map[Symbol, Set[Symbol]] = Map.empty
    def defs(sym: Symbol): Set[Symbol] = _defs.get(sym).getOrElse(Set.empty)

    def addDef(s: Symbol, d: Symbol): Unit = _defs = (_defs + {
      s -> _defs.get(s).map(xs => xs + d).getOrElse(Set(d))
    })
  }

  /** The essential mutable state of a context base, collected into a common class */
  class ContextState {
    // Symbols state

    /** A counter for unique ids */
    private[core] var _nextId = 0

    def nextId = { _nextId += 1; _nextId }

    /** The last allocated superclass id */
    private[core] var lastSuperId = -1

    /** Allocate and return next free superclass id */
    private[core] def nextSuperId: Int = {
      lastSuperId += 1
//      if (lastSuperId >= classOfId.length) {
//        val tmp = new Array[ClassSymbol](classOfId.length * 2)
//        classOfId.copyToArray(tmp)
//        classOfId = tmp
//      }
      lastSuperId
    }

    /** The number of recursive invocation of underlying on a NamedType
     *  during a controlled operation.
     */
    private[core] var underlyingRecursions: Int = 0

    /** The set of named types on which a currently active invocation
     *  of underlying during a controlled operation exists. */
    private[core] val pendingUnderlying = new mutable.HashSet[Type]

    /** Next denotation transformer id */
    private[core] var nextDenotTransformerId: Array[Int] = _

    // Printers state
    /** Number of recursive invocations of a show method on current stack */
    private[dotc] var toTextRecursions = 0

    // Reporters state
    private[dotc] var indent = 0

    protected[dotc] val indentTab = "  "

    def reset() = {
      lastSuperId = -1
    }

    // Test that access is single threaded

    /** The thread on which `checkSingleThreaded was invoked last */
    @sharable private var thread: Thread = null

    /** Check that we are on the same thread as before */
    def checkSingleThreaded() =
      if (thread == null) thread = Thread.currentThread()
      else assert(thread == Thread.currentThread(), "illegal multithreaded access to ContextBase")
  }

  object Context {

    /** Implicit conversion that injects all printer operations into a context */
    implicit def toPrinter(ctx: Context): Printer = ctx.printer

    /** implicit conversion that injects all ContextBase members into a context */
    implicit def toBase(ctx: Context): ContextBase = ctx.base

    // @sharable val theBase = new ContextBase // !!! DEBUG, so that we can use a minimal context for reporting even in code that normally cannot access a context
  }

  /** Info that changes on each compiler run */
  class RunInfo(initctx: Context) {
    implicit val ctx: Context = initctx
  }
}
