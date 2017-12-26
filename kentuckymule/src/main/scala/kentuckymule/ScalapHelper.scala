package kentuckymule

import java.nio.file.{FileSystems, Files}

import dotty.tools.dotc.core.Contexts.Context
import kentuckymule.core.{CompletedType, CompletionResult, Enter}
import kentuckymule.core.Symbols.{ClassSymbol, PackageSymbol, StubClassSymbol, Symbol}
import kentuckymule.core.Enter.PackageCompleter

object ScalapHelper {
  import dotty.tools.dotc.core.Decorators._
  def enterStabSymbolsForScalap(enter: Enter)(implicit ctx: Context): Unit = {
    implicit val enterImplicitly = enter
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
    enterStubClasses(javaLangPkg, "String")

    // enter java.beans
    val javaBeansPkg = enterStubPackage("beans", javaPkg)
    enterStubClasses(javaBeansPkg, "Introspector")

    // enter scala
    val scalaPkg = enterStubPackage("scala", root)

    enterStubClasses(scalaPkg, "Any", "AnyRef", "Nothing", "Unit")

    // enter things visible by default from Predef, scala or java.lang packages into root package
    // normally, you would have separate package for scala and java.lang that are seen from every compilation unit
    // but we don't care about details when just trying to get some performance numbers
    enterStubClasses(root, "RuntimeException", "String", "Array", "Char", "Unit", "Boolean",
      "Option", "List", "Byte", "Int", "Long", "Float", "Double", "Short", "Seq", "Class",
      "PartialFunction")
    enterStubClasses(root, Seq.tabulate(23)(i => s"Function$i"): _*)

    // enter scala.reflect
    val reflectPkg = enterStubPackage("reflect", scalaPkg)
    enterStubClasses(reflectPkg, "NameTransformer")

    // enter scala.reflect.internal
    val reflectInternalPkg = enterStubPackage("internal", reflectPkg)

    // enter scala.reflect.internal.pickling
    val internalPicklingPkg = enterStubPackage("pickling", reflectInternalPkg)
    enterStubClasses(internalPicklingPkg, "ByteCodecs")

    // enter scala.reflect.internal.util
    val internalUtilPkg = enterStubPackage("util", reflectInternalPkg)
    enterStubClasses(internalUtilPkg, "ScalaClassLoader")

    // enter scala.tools.nsc
    val toolsPkg = enterStubPackage("tools", scalaPkg)
    val nscPkg = enterStubPackage("nsc", toolsPkg)
    enterStubClasses(nscPkg, "Settings")

    // enter scala.tools.nsc.classpath
    val classpathPkg = enterStubPackage("classpath", nscPkg)
    enterStubClasses(classpathPkg, "AggregateClassPath", "ClassPathFactory")

    // enter scala.tools.nsc.util
    val nscUtilPkg = enterStubPackage("util", nscPkg)
    enterStubClasses(nscUtilPkg, "ClassPath")

    // enter scala.tools.util
    val toolsUtilPkg = enterStubPackage("util", toolsPkg)
    enterStubClasses(toolsUtilPkg, "PathResolver")

    // enter scala.util
    val scalaUtilPkg = enterStubPackage("util", scalaPkg)
    enterStubClasses(scalaUtilPkg, "PropertiesTrait")

    // enter scala.collection
    val collectionPkg = enterStubPackage("collection", scalaPkg)
    // enter scala.collection.mutable
    val mutablePkg = enterStubPackage("mutable", collectionPkg)
    enterStubClasses(mutablePkg, "ListBuffer", "Set", "Map")

    // enter scala.collection.concurrent
    val concurrentPkg = enterStubPackage("concurrent", collectionPkg)
    enterStubClasses(concurrentPkg, "MainNode")

    // enter scala.language
    val languagePkg = enterStubPackage("language", scalaPkg)
    // we model implicitConversions as a class inside of a language package. In Scala, the "language" is an object
    // and "implicitConversions" is a val
    enterStubClasses(languagePkg, "implicitConversions", "postfixOps")
  }

  def enterStubPackage(name: String, owner: PackageSymbol)(implicit enter: Enter, context: Context): PackageSymbol = {
    val pkgSym = PackageSymbol(name.toTermName)
    val pkgCompleter = new PackageCompleter(pkgSym)
    pkgSym.completer = pkgCompleter
    enter.queueCompleter(pkgCompleter)
    owner.addChild(pkgSym)

    pkgSym
  }

  private class StubClassCompleter(sym: ClassSymbol) extends Enter.Completer(sym) {
    override def complete()(implicit context: Context): CompletionResult = {
      import kentuckymule.core.Types._
      CompletedType(new ClassInfoType(sym, Nil))
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

  def scalapFiles(kentuckyMuleProjectDir: String): Array[String] = {
    import java.nio.file.Paths
    val projectDir = Paths.get(kentuckyMuleProjectDir)
    val sourcesFiles = Seq(
      "sample-projects/scalap/scala/tools/scalap/Arguments.scala",
      "sample-projects/scalap/scala/tools/scalap/ByteArrayReader.scala",
      "sample-projects/scalap/scala/tools/scalap/Classfile.scala",
      "sample-projects/scalap/scala/tools/scalap/Classfiles.scala",
      "sample-projects/scalap/scala/tools/scalap/CodeWriter.scala",
      "sample-projects/scalap/scala/tools/scalap/Decode.scala",
      "sample-projects/scalap/scala/tools/scalap/JavaWriter.scala",
      "sample-projects/scalap/scala/tools/scalap/Main.scala",
      "sample-projects/scalap/scala/tools/scalap/MetaParser.scala",
      "sample-projects/scalap/scala/tools/scalap/Properties.scala",
      "sample-projects/scalap/scala/tools/scalap/scalax/rules/Memoisable.scala",
      "sample-projects/scalap/scala/tools/scalap/scalax/rules/Result.scala",
      "sample-projects/scalap/scala/tools/scalap/scalax/rules/Rule.scala",
      "sample-projects/scalap/scala/tools/scalap/scalax/rules/Rules.scala",
      "sample-projects/scalap/scala/tools/scalap/scalax/rules/scalasig/ClassFileParser.scala",
      "sample-projects/scalap/scala/tools/scalap/scalax/rules/scalasig/Flags.scala",
      "sample-projects/scalap/scala/tools/scalap/scalax/rules/scalasig/ScalaSig.scala",
      "sample-projects/scalap/scala/tools/scalap/scalax/rules/scalasig/ScalaSigPrinter.scala",
      "sample-projects/scalap/scala/tools/scalap/scalax/rules/scalasig/SourceFileAttributeParser.scala",
      "sample-projects/scalap/scala/tools/scalap/scalax/rules/scalasig/Symbol.scala",
      "sample-projects/scalap/scala/tools/scalap/scalax/rules/scalasig/Type.scala",
      "sample-projects/scalap/scala/tools/scalap/scalax/rules/SeqRule.scala",
      "sample-projects/scalap/scala/tools/scalap/scalax/util/StringUtil.scala") map {
      relativePath => projectDir.resolve(relativePath).toAbsolutePath.toString
    }
    sourcesFiles.toArray
  }
}
