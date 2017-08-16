package kentuckymule

import java.nio.file.{FileSystems, Files}

import dotty.tools.dotc.core.Contexts.Context
import kentuckymule.core.Enter
import kentuckymule.core.Enter.{CompletedType, CompletionResult, PackageCompleter}
import kentuckymule.core.Symbols.{ClassSymbol, PackageSymbol, StubClassSymbol}

object ScalaLibHelper {
  import dotty.tools.dotc.core.Decorators._
  def enterStabSymbolsForScalaLib(implicit ctx: Context): Unit = {
    // enter java.io
    val root = ctx.definitions.rootPackage
    val javaPkg = enterStubPackage("java", root)
    val ioPkg = enterStubPackage("io", javaPkg)
    enterStubClasses(ioPkg, "ByteArrayOutputStream", "OutputStreamWriter", "PrintStream", "IOException", "Writer")

    // enter java.util
    val javaUtilPkg = enterStubPackage("util", javaPkg)

    // enter java.util.regex
    val javaUtilRegexPkg = enterStubPackage("regex", javaUtilPkg)
    enterStubClasses(javaUtilRegexPkg, "Pattern")

    // enter java.lang
    val javaLangPkg = enterStubPackage("lang", javaPkg)
    enterStubClasses(javaLangPkg, "String", "CharSequence")
    val CompletedType(tpe) = javaLangPkg.completeInfo()
    javaLangPkg.info = tpe

    // enter java.beans
    val javaBeansPkg = enterStubPackage("beans", javaPkg)
    enterStubClasses(javaBeansPkg, "Introspector")

    // enter scala
    val scalaPkg = enterStubPackage("scala", root)

    enterStubClasses(scalaPkg, "Any", "AnyRef", "Nothing", "Unit")
  }

  def enterStubPackage(name: String, owner: PackageSymbol)(implicit context: Context): PackageSymbol = {
    val pkgSym = PackageSymbol(name.toTermName)
    val pkgCompleter = new PackageCompleter(pkgSym)
    pkgSym.completer = pkgCompleter
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

  private def enterStubClasses(pkg: PackageSymbol, classNames: String*)(implicit context: Context): Unit = {
    for (className <- classNames) {
      val cls = new StubClassSymbol(className.toTypeName, pkg)
      cls.completer = new StubClassCompleter(cls)
      pkg.addChild(cls)
    }
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
