package dotty.tools

import dotty.tools.dotc.core
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Enter
import dotty.tools.dotc.core.Enter.CompletionResult
import dotty.tools.dotc.core.Symbols.{ClassSymbol, PackageSymbol}

object ScalapHelper {
  import dotty.tools.dotc.core.Decorators._
  def enterStabSymbolsForScalap(implicit ctx: Context): Unit = {
    // enter java.io
    val root = ctx.definitions.rootPackage
    val javaPkg = new PackageSymbol("java".toTermName)
    root.addChild(javaPkg)
    val ioPkg = new PackageSymbol("io".toTermName)
    javaPkg.addChild(ioPkg)
    enterStubClasses(ioPkg, "ByteArrayOutputStream", "OutputStreamWriter", "PrintStream", "IOException", "Writer")

    // enter java.util
    val javaUtilPkg = new PackageSymbol("util".toTermName)
    javaPkg.addChild(javaUtilPkg)

    // enter java.util.regex
    val javaUtilRegexPkg = new PackageSymbol("regex".toTermName)
    javaUtilPkg.addChild(javaUtilRegexPkg)
    enterStubClasses(javaUtilRegexPkg, "Pattern")

    // enter java.lang
    val javaLangPkg = new PackageSymbol("lang".toTermName)
    javaPkg.addChild(javaLangPkg)
    enterStubClasses(javaLangPkg, "String")

    // enter java.beans
    val javaBeansPkg = new PackageSymbol("beans".toTermName)
    javaPkg.addChild(javaBeansPkg)
    enterStubClasses(javaBeansPkg, "Introspector")

    // enter scala
    val scalaPkg = new PackageSymbol("scala".toTermName)
    root.addChild(scalaPkg)

    // enter things visible by default from Predef, scala or java.lang packages into root package
    // normally, you would have separate package for scala and java.lang that are seen from every compilation unit
    // but we don't care about details when just trying to get some performance numbers
    enterStubClasses(root, "Nothing", "RuntimeException", "String", "Array", "Char", "Unit", "Boolean",
      "Option", "List", "Byte", "Int", "Long", "Float", "Double", "Short", "AnyRef", "Any", "Seq", "Class",
      "PartialFunction")
    enterStubClasses(root, Seq.tabulate(23)(i => s"Function$i"): _*)

    // enter scala.reflect
    val reflectPkg = new PackageSymbol("reflect".toTermName)
    scalaPkg.addChild(reflectPkg)
    enterStubClasses(reflectPkg, "NameTransformer")

    // enter scala.reflect.internal
    val reflectInternalPkg = new PackageSymbol("internal".toTermName)
    reflectPkg.addChild(reflectInternalPkg)

    // enter scala.reflect.internal.pickling
    val internalPicklingPkg = new PackageSymbol("pickling".toTermName)
    reflectInternalPkg.addChild(internalPicklingPkg)
    enterStubClasses(internalPicklingPkg, "ByteCodecs")

    // enter scala.reflect.internal.util
    val internalUtilPkg = new PackageSymbol("util".toTermName)
    reflectInternalPkg.addChild(internalUtilPkg)
    enterStubClasses(internalUtilPkg, "ScalaClassLoader")

    // enter scala.tools.nsc
    val toolsPkg = new PackageSymbol("tools".toTermName)
    scalaPkg.addChild(toolsPkg)
    val nscPkg = new PackageSymbol("nsc".toTermName)
    toolsPkg.addChild(nscPkg)
    enterStubClasses(nscPkg, "Settings")

    // enter scala.tools.nsc.classpath
    val classpathPkg = new PackageSymbol("classpath".toTermName)
    nscPkg.addChild(classpathPkg)
    enterStubClasses(classpathPkg, "AggregateClassPath", "ClassPathFactory")

    // enter scala.tools.nsc.util
    val nscUtilPkg = new PackageSymbol("util".toTermName)
    nscPkg.addChild(nscUtilPkg)
    enterStubClasses(nscUtilPkg, "ClassPath")

    // enter scala.tools.util
    val toolsUtilPkg = new PackageSymbol("util".toTermName)
    toolsPkg.addChild(toolsUtilPkg)
    enterStubClasses(toolsUtilPkg, "PathResolver")

    // enter scala.util
    val scalaUtilPkg = new PackageSymbol("util".toTermName)
    scalaPkg.addChild(scalaUtilPkg)
    enterStubClasses(scalaUtilPkg, "PropertiesTrait")

    // enter scala.collection.mutable
    val collectionPkg = new PackageSymbol("collection".toTermName)
    scalaPkg.addChild(collectionPkg)
    val mutablePkg = new PackageSymbol("mutable".toTermName)
    collectionPkg.addChild(mutablePkg)
    enterStubClasses(mutablePkg, "ListBuffer", "Set", "Map")

    // enter scala.language
    val languagePkg = new PackageSymbol("language".toTermName)
    scalaPkg.addChild(languagePkg)
    // we model implicitConversions as a class inside of a language package. In Scala, the "language" is an object
    // and "implicitConversions" is a val
    enterStubClasses(languagePkg, "implicitConversions", "postfixOps")
  }

  private class StubClassCompleter(sym: ClassSymbol) extends Enter.Completer(sym) {
    override def complete()(implicit context: Context): CompletionResult = {
      import core.Types._
      Enter.CompletedType(new ClassInfoType(sym))
    }
    override def isCompleted: Boolean = false
  }

  private def enterStubClasses(pkg: PackageSymbol, classNames: String*)(implicit context: Context): Unit = {
    for (className <- classNames) {
      val cls = ClassSymbol(className.toTypeName)
      cls.completer = new StubClassCompleter(cls)
      pkg.addChild(cls)
    }
  }

  def scalapFiles(kentuckyMuleProjectDir: String) = {
    import java.nio.file.Paths
    val projectDir = Paths.get(kentuckyMuleProjectDir)
    Seq(
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
      "sample-projects/scalap/scala/tools/scalap/scalax/Util/StringUtil.scala") map {
      relativePath => projectDir.resolve(relativePath).toAbsolutePath.toString
    }
  }
}
