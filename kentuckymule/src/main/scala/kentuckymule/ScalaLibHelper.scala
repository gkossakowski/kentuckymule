package kentuckymule

import java.nio.file.{FileSystems, Files}

import dotty.tools.dotc.core.Contexts.Context
import kentuckymule.core.Enter
import kentuckymule.core.Enter.{CompletedType, CompletionResult, IncompleteDependency, PackageCompleter}
import kentuckymule.core.Symbols.{ClassSymbol, ModuleSymbol, PackageSymbol, StubClassSymbol, StubModuleSymbol, Symbol}

object ScalaLibHelper {
  import dotty.tools.dotc.core.Decorators._
  import dotty.tools.dotc.core.NameOps._
  def enterStabSymbolsForScalaLib(implicit enter: Enter, ctx: Context): Unit = {
    // enter java.io
    val root = ctx.definitions.rootPackage
    val javaPkg = enterStubPackage("java", root)
    val ioPkg = enterStubPackage("io", javaPkg)
    enterStubClasses(ioPkg, "ByteArrayOutputStream", "OutputStreamWriter", "PrintStream", "IOException", "Writer")

    // enter java.util
    val javaUtilPkg = enterStubPackage("util", javaPkg)
    enterStubClasses(javaUtilPkg, "Collection")

    // enter java.util.regex
    val javaUtilRegexPkg = enterStubPackage("regex", javaUtilPkg)
    enterStubClasses(javaUtilRegexPkg, "Pattern")

    // enter java.util.concurrent
    val javaUtilConcurrentPkg = enterStubPackage("concurrent", javaUtilPkg)
    enterStubClasses(javaUtilConcurrentPkg, "ForkJoinWorkerThread", "ForkJoinTask", "Callable",
      "Executor", "ExecutorService", "ThreadFactory", "TimeUnit", "CountDownLatch")

    // enter java.util.concurrent.ForkJoinPool and .ForkJoinWorkerThreadFactory
    {
      val forkJoinPoolCls = enterStubObject(javaUtilConcurrentPkg, "ForkJoinPool")
      enterStubClass(forkJoinPoolCls, "ForkJoinWorkerThreadFactory")
    }

    // enter java.util.concurrent.atomic
    val javaUtilConcurrentAtomicPkg = enterStubPackage("atomic", javaUtilConcurrentPkg)
    enterStubClasses(javaUtilConcurrentAtomicPkg, "AtomicInteger")

    // enter java.lang
    val javaLangPkg = enterStubPackage("lang", javaPkg)
    enterStubClasses(javaLangPkg, "String", "CharSequence", "Class", "Throwable", "Runnable")

    // enter java.lang.ref
    val javaLangRefPkg = enterStubPackage("ref", javaLangPkg)
    enterStubClasses(javaLangRefPkg, "PhantomReference")

    // enter java.beans
    val javaBeansPkg = enterStubPackage("beans", javaPkg)
    enterStubClasses(javaBeansPkg, "Introspector")

    // enter scala
    val scalaPkg = enterStubPackage("scala", root)

    enterStubClasses(scalaPkg, "Any", "AnyRef", "Nothing", "Unit")
  }

  def enterStubPackage(name: String, owner: PackageSymbol)(implicit enter: Enter, context: Context): PackageSymbol = {
    val pkgSym = PackageSymbol(name.toTermName)
    val pkgCompleter = new PackageCompleter(pkgSym)
    pkgSym.completer = pkgCompleter
    enter.queueCompleter(pkgCompleter, pushToTheEnd = false)
    owner.addChild(pkgSym)
    pkgSym
  }

  private class StubClassCompleter(sym: ClassSymbol) extends Enter.Completer(sym) {
    override def complete()(implicit context: Context): CompletionResult = {
      import kentuckymule.core.Types._
      Enter.CompletedType(new ClassInfoType(sym, Nil))
    }
    override def isCompleted: Boolean = false
  }

  private class StubModuleCompleter(sym: ModuleSymbol) extends Enter.Completer(sym) {
    override def complete()(implicit context: Context): CompletionResult = {
      import kentuckymule.core.Types._
      val clsInfo = if (sym.clsSym.isComplete)
          sym.clsSym.info
        else
          return IncompleteDependency(sym)
      Enter.CompletedType(new ModuleInfoType(sym, clsInfo))
    }
    override def isCompleted: Boolean = false
  }

  private def enterStubClasses(pkg: PackageSymbol, classNames: String*)(implicit context: Context): Unit = {
    for (className <- classNames) {
      enterStubClass(pkg, className)
    }
  }

  private def enterStubClass(parent: Symbol, className: String)(implicit context: Context): StubClassSymbol = {
    val cls = new StubClassSymbol(className.toTypeName, parent)
    cls.completer = new StubClassCompleter(cls)
    parent.addChild(cls)
    cls
  }

  private def enterStubObject(parent: Symbol, objectName: String)(implicit context: Context): StubModuleSymbol = {
    val modClsSym = new StubClassSymbol(objectName.toTypeName.moduleClassName, parent)
    modClsSym.completer = new StubClassCompleter(modClsSym)
    val modSym = new StubModuleSymbol(objectName.toTermName, modClsSym, parent)
    modSym.completer = new StubModuleCompleter(modSym)
    parent.addChild(modSym)
    modSym
  }

  def scalaLibraryFiles(scalaLibDir: String): Array[String] = {
    import java.nio.file.Paths
    val projectDir = Paths.get(scalaLibDir)
    val matcher = FileSystems.getDefault.getPathMatcher("glob:**/*.scala")
    val fileStream = Files.find(projectDir, 255, (path, attrs) => matcher.matches(path))
    import scala.collection.JavaConverters._
    fileStream.map[String](_.toAbsolutePath.toString).iterator().asScala.toArray
  }
}
